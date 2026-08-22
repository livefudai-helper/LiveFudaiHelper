package com.example.livefudai

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.livefudai.util.TemplateMatcher
import timber.log.Timber

/**
 * 福袋助手无障碍服务（v27 + 福多多动作层）
 *
 * 完整流程（移植自「福多多」.e0 主流程）：
 *   直播间(LIVE_ROOM) → 检测到福袋图标 → 点击进入(DETAIL_PENDING)
 *   → 解析任务需求 → 自动关注/评论/点赞/粉丝团 → 点击参与(PARTICIPATED)
 *   → 检测「参与成功」→ 返回直播间(LIVE_ROOM)
 */
class FudaiAccessibilityService : AccessibilityService() {

    enum class State { LIVE_ROOM, DETAIL_PENDING, PARTICIPATED }

    private var isMonitoring = false
    private var lastClickTime = 0L
    private val CLICK_COOLDOWN = 5000L
    // 盲点击兜底：独立冷却 + 连点计数，避免无限刷同一个固定点
    private var lastBlindClickTime = 0L
    private val BLIND_CLICK_COOLDOWN = 10000L
    private var blindClickCount = 0
    private val BLIND_CLICK_MAX = 30
    private var eventCount = 0
    private var lastDebugTime = 0L
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "fudai_channel"
    private lateinit var clickSimulator: ClickSimulator
    private val colorMatcher = ColorMatcher()
    private lateinit var templateMatcher: TemplateMatcher
    private lateinit var autoFollowComment: AutoFollowComment
    private var isImageChecking = false
    private var lastImageCheckTime = 0L
    private val IMAGE_CHECK_INTERVAL = 2000L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val fudaiKeywords = listOf("福袋", "超级福袋", "限时福袋", "全民福袋", "锦鲤", "福")

    // ===== 状态机 =====
    private var state: State = State.LIVE_ROOM
    private var detailEnterTime = 0L
    private var lastBagSignature = ""
    private var currentIsSuper = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isMonitoring) return
        event ?: return
        eventCount++

        val now = System.currentTimeMillis()
        if (now - lastDebugTime > 5000) {
            lastDebugTime = now
            // dumpNodeInfo()
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (state == State.LIVE_ROOM) {
                    val clicked = checkAndClickFudai()
                    if (clicked) {
                        state = State.DETAIL_PENDING
                        detailEnterTime = now
                        Toast.makeText(this, "🎁 进入福袋详情，开始处理任务...", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    handleDetailFlow()
                }
            }
        }
    }

    override fun onInterrupt() {
        Timber.w("服务中断")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("无障碍服务已连接")
        isMonitoring = true
        eventCount = 0
        clickSimulator = ClickSimulator(this)
        autoFollowComment = AutoFollowComment(this)

        // v27 新增：初始化 TemplateMatcher（多模板方案，来自福多多反编译）
        templateMatcher = TemplateMatcher(this)
        templateMatcher.loadTemplates()

        // 初始化配置（来自福多多的设置项）
        FudaiConfig.init(this)

        startForegroundService()

        // 未开启截图识别时主动提示：图像找福袋（福多多方案）是最可靠的检测路径
        if (screenshotManager == null) {
            Toast.makeText(this, "💡 未开启截图识别：请在 App 内点「开启截图识别」授权，以启用图像找福袋", Toast.LENGTH_LONG).show()
        }

        Toast.makeText(this, "✅ 福袋助手已启动（含自动关注/评论/点赞）", Toast.LENGTH_LONG).show()
        Timber.d("前台服务已启动")
    }

    // ===================== 详情页状态机 =====================

    private fun handleDetailFlow() {
        val root = rootInActiveWindow ?: return
        val tasks = FudaiTaskParser.parse(root)
        val now = System.currentTimeMillis()

        // 超时保护：进入详情 15 秒内没完成，强制返回
        if (now - detailEnterTime > 15000) {
            Timber.w("详情页处理超时，强制返回")
            autoFollowComment.clickBack(root)
            resetToLiveRoom()
            return
        }

        // 不在详情页（还没加载出参与按钮，或已回直播间）
        if (!tasks.hasParticipateButton) return

        when (state) {
            State.DETAIL_PENDING -> {
                val sig = tasks.allTexts.joinToString("|").hashCode().toString()
                if (sig == lastBagSignature) {
                    // 同一福袋已处理过，返回直播间
                    autoFollowComment.clickBack(root)
                    resetToLiveRoom()
                    return
                }
                currentIsSuper = tasks.allTexts.any { it.contains("超级福袋") }

                // 每日上限判断（移植自福多多 .e0 的 C/e 上限校验）
                if (currentIsSuper && autoFollowComment.superFudaiCountToday >= FudaiConfig.maxDailySuperFudai) {
                    Timber.w("超级福袋已达每日上限(${FudaiConfig.maxDailySuperFudai})，放弃")
                    autoFollowComment.clickBack(root)
                    resetToLiveRoom(lastBagSignature = sig)
                    return
                }
                if (!currentIsSuper && autoFollowComment.coinFudaiCountToday >= FudaiConfig.maxDailyCoinFudai) {
                    Timber.w("抖币福袋已达每日上限(${FudaiConfig.maxDailyCoinFudai})，放弃")
                    autoFollowComment.clickBack(root)
                    resetToLiveRoom(lastBagSignature = sig)
                    return
                }

                // 执行关注/评论/点赞/粉丝团
                autoFollowComment.completeTasks(root, tasks)
                val ok = autoFollowComment.clickParticipate(root)
                if (ok) {
                    lastBagSignature = sig
                    state = State.PARTICIPATED
                    Toast.makeText(this, "✅ 已参与，等待结果...", Toast.LENGTH_SHORT).show()
                } else {
                    autoFollowComment.clickBack(root)
                    resetToLiveRoom(lastBagSignature = sig)
                }
            }

            State.PARTICIPATED -> {
                val success = tasks.allTexts.any { it.contains("参与成功") || it.contains("已参与") || it.contains("已报名") }
                if (success) {
                    if (currentIsSuper) autoFollowComment.superFudaiCountToday++
                    else autoFollowComment.coinFudaiCountToday++
                    Timber.d("参与成功（超级=${currentIsSuper}），返回直播间。今日超级=${autoFollowComment.superFudaiCountToday} 抖币=${autoFollowComment.coinFudaiCountToday} 关注=${autoFollowComment.followCountToday}")
                    autoFollowComment.clickBack(root)
                    resetToLiveRoom()
                } else {
                    // 还没成功，再尝试点一次参与
                    autoFollowComment.clickParticipate(root)
                }
            }

            else -> { /* LIVE_ROOM 不会进这里 */ }
        }
    }

    private fun resetToLiveRoom(lastBagSignature: String = "") {
        state = State.LIVE_ROOM
        this.lastBagSignature = lastBagSignature
        detailEnterTime = 0L
    }

    // ===================== 直播间福袋图标检测 =====================

    /** @return 是否点击了福袋图标（用于切换状态） */
    private fun checkAndClickFudai(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < CLICK_COOLDOWN) return false

        val rootNode = rootInActiveWindow ?: return false

        try {
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels

            // 搜索区域：覆盖直播间上半屏全宽（福袋横幅常驻顶部区域，抖音改版也不易失效）
            val searchLeft = 0
            val searchTop = 0
            val searchRight = screenWidth
            val searchBottom = (screenHeight * 0.45).toInt()

            // 方法1：找福袋关键词
            val fudaiCandidates = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
            for (keyword in fudaiKeywords) {
                val nodes = findNodesByText(rootNode, keyword)
                for (node in nodes) {
                    val rect = getNodeRect(node)
                    if (rect != null && rect.width() > 0 && rect.height() > 0 &&
                        rect.centerX() in searchLeft..searchRight && rect.centerY() in searchTop..searchBottom
                    ) {
                        fudaiCandidates.add(Pair(node, keyword))
                    }
                }
            }
            if (fudaiCandidates.isNotEmpty()) {
                fudaiCandidates.sortBy { getNodeRect(it.first)?.top ?: Int.MAX_VALUE }
                val best = fudaiCandidates.first()
                val rect = getNodeRect(best.first)!!
                val x = (rect.left - rect.width() * 1.5f).toInt().coerceAtLeast(10)
                val y = (rect.centerY() - rect.height() * 0.5f).toInt().coerceAtLeast(10)
                Toast.makeText(this, "🎯 文字命中[${best.second}] 点击($x, $y)", Toast.LENGTH_SHORT).show()
                vibrate(100)
                clickSimulator.click(x, y)
                lastClickTime = currentTime
                blindClickCount = 0
                Timber.d("文字命中点击，关键词: ${best.second}, 坐标: ($x, $y)")
                return true
            }

            // 方法2：找倒计时
            val countdownPatterns = listOf("\\d{1,2}:\\d{2}".toRegex(), "\\d{2,3}秒".toRegex(), "\\d{2,3}".toRegex())
            val countdownCandidates = mutableListOf<AccessibilityNodeInfo>()
            for (pattern in countdownPatterns) {
                val nodes = findNodesByRegex(rootNode, pattern)
                for (node in nodes) {
                    val rect = getNodeRect(node)
                    if (rect != null && rect.width() > 0 && rect.height() > 0 &&
                        rect.centerX() in searchLeft..searchRight && rect.centerY() in searchTop..searchBottom
                    ) {
                        countdownCandidates.add(node)
                    }
                }
            }
            if (countdownCandidates.isNotEmpty()) {
                countdownCandidates.sortBy { getNodeRect(it)?.top ?: Int.MAX_VALUE }
                val best = countdownCandidates.first()
                val rect = getNodeRect(best)!!
                val x = rect.centerX()
                val y = (rect.top - rect.height() * 2).toInt().coerceAtLeast(10)
                Toast.makeText(this, "⏰ 倒计时命中 点击($x, $y)", Toast.LENGTH_SHORT).show()
                vibrate(100)
                clickSimulator.click(x, y)
                lastClickTime = currentTime
                blindClickCount = 0
                Timber.d("倒计时命中点击，坐标: ($x, $y)")
                return true
            }

            // 方法3：图像识别（TemplateMatcher 优先，ColorMatcher 兜底）
            val sm = screenshotManager
            if (sm != null) {
                val now = System.currentTimeMillis()
                if (!isImageChecking && now - lastImageCheckTime > IMAGE_CHECK_INTERVAL) {
                    isImageChecking = true
                    lastImageCheckTime = now
                    Thread {
                        try {
                            val regionWidth = screenWidth
                            val regionHeight = (screenHeight * 0.45f).toInt()
                            val bitmap = sm.takeScreenshotRegion(0, 0, regionWidth, regionHeight)
                            if (bitmap != null) {
                                val searchRect = Rect(0, 0, regionWidth, regionHeight)
                                val tplResult = templateMatcher.findFudai(bitmap, searchRect, minScore = FudaiConfig.fudaiMinScore)
                                val colorResult = if (tplResult == null) colorMatcher.findFudai(bitmap, searchRect) else null
                                val finalX: Int
                                val finalY: Int
                                val methodName: String
                                val confidence: Float
                                if (tplResult != null) {
                                    finalX = tplResult.x; finalY = tplResult.y
                                    methodName = "模板[${tplResult.templateName}] 比例${tplResult.scale}x"
                                    confidence = tplResult.score
                                } else if (colorResult != null) {
                                    finalX = colorResult.first; finalY = colorResult.second
                                    methodName = "颜色兜底"; confidence = 0f
                                } else {
                                    bitmap.recycle()
                                    return@Thread
                                }
                                mainHandler.post {
                                    Toast.makeText(this, "🖼️ $methodName ($finalX,$finalY) 置信${"%.0f".format(confidence * 100)}%", Toast.LENGTH_SHORT).show()
                                    vibrate(100)
                                    clickSimulator.click(finalX, finalY)
                                    lastClickTime = now
                                    Timber.d("图像识别点击成功，方法=$methodName, 坐标=($finalX, $finalY), 置信度=$confidence")
                                }
                                bitmap.recycle()
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "图像检测出错")
                        } finally {
                            isImageChecking = false
                        }
                    }.start()
                    // 图像检测是异步的，本次不返回 true，下一轮事件再判定
                    return false
                }
            }

            // 方法4：盲点击（手动兜底，仅当开关打开；禁止无限刷同一个固定点）
            if (enableBlindClick) {
                // 盲点击有独立冷却，避免每个无障碍事件都触发
                if (currentTime - lastBlindClickTime < BLIND_CLICK_COOLDOWN) return false
                lastBlindClickTime = currentTime
                blindClickCount++
                val blindX = (screenWidth * 0.08f).toInt()
                val blindY = (screenHeight * 0.12f).toInt()
                // 连续盲点击过多仍未命中福袋，自动关闭以免误触
                if (blindClickCount > BLIND_CLICK_MAX) {
                    enableBlindClick = false
                    Toast.makeText(this, "⚠️ 盲点击已自动关闭（连续 $blindClickCount 次未检测到福袋，请检查截图/文字识别）", Toast.LENGTH_LONG).show()
                    Timber.w("盲点击自动关闭，count=$blindClickCount")
                    return false
                }
                // 仅每 5 次提示一次，减少刷屏
                if (blindClickCount % 5 == 1) {
                    Toast.makeText(this, "👆 盲点击兜底 ($blindX,$blindY) 第${blindClickCount}次", Toast.LENGTH_SHORT).show()
                }
                vibrate(100)
                clickSimulator.click(blindX, blindY)
                lastClickTime = currentTime
                Timber.d("盲点击，坐标: ($blindX, $blindY) count=$blindClickCount")
                return true
            }
        } catch (e: Exception) {
            Timber.e(e, "检测福袋时出错")
        }
        return false
    }

    // ===================== 工具方法 =====================

    private fun getNodeRect(node: AccessibilityNodeInfo): Rect? {
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect
        } catch (e: Exception) {
            null
        }
    }

    private fun findNodesByText(rootNode: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(rootNode, text, result)
        return result
    }

    private fun findNodesByTextRecursive(node: AccessibilityNodeInfo, text: String, result: MutableList<AccessibilityNodeInfo>) {
        node.text?.let { if (it.contains(text)) result.add(node) }
        node.contentDescription?.let { if (it.contains(text)) result.add(node) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findNodesByTextRecursive(it, text, result) }
        }
    }

    private fun findNodesByRegex(rootNode: AccessibilityNodeInfo, regex: Regex): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        findNodesByRegexRecursive(rootNode, regex, result)
        return result
    }

    private fun findNodesByRegexRecursive(node: AccessibilityNodeInfo, regex: Regex, result: MutableList<AccessibilityNodeInfo>) {
        node.text?.let { if (regex.matches(it)) result.add(node) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findNodesByRegexRecursive(it, regex, result) }
        }
    }

    private fun vibrate(milliseconds: Long) {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(milliseconds)
            }
        } catch (e: Exception) {
            Timber.e(e, "震动失败")
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "福袋助手服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "福袋助手后台运行服务"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("福袋助手")
            .setContentText("正在监听抖音福袋...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        var instance: FudaiAccessibilityService? = null
            private set
        var screenshotManager: ScreenshotManager? = null
        var enableBlindClick = false
    }

    init {
        instance = this
    }
}
