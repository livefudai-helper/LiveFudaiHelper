package com.example.livefudai

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Rect
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import timber.log.Timber

class FudaiAccessibilityService : AccessibilityService() {

    private var isMonitoring = false
    private var lastClickTime = 0L
    private val CLICK_COOLDOWN = 5000L
    private var eventCount = 0
    private var lastDebugTime = 0L
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "fudai_channel"
    private lateinit var clickSimulator: ClickSimulator

    private val fudaiKeywords = listOf("福袋", "超级福袋", "限时福袋", "全民福袋", "锦鲤", "福")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isMonitoring) return
        event ?: return
        eventCount++

        val now = System.currentTimeMillis()
        if (now - lastDebugTime > 5000) {
            lastDebugTime = now
            dumpNodeInfo()
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                checkAndClickFudai()
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

        startForegroundService()

        Toast.makeText(this, "✅ 福袋助手已启动，正在监听...", Toast.LENGTH_LONG).show()
        Timber.d("前台服务已启动")
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "福袋助手服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "福袋助手后台运行服务"
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
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

    private fun dumpNodeInfo() {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Timber.d("调试：rootNode 为空")
            return
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val allTexts = mutableListOf<Pair<String, Rect>>() // 文字+坐标
        var totalNodes = 0

        fun traverse(node: AccessibilityNodeInfo) {
            totalNodes++
            val rect = Rect()
            try {
                node.getBoundsInScreen(rect)
            } catch (e: Exception) {
                // 忽略
            }

            node.text?.let {
                if (it.isNotBlank()) allTexts.add(Pair(it.toString(), rect))
            }
            node.contentDescription?.let {
                if (it.isNotBlank()) allTexts.add(Pair(it.toString(), rect))
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { traverse(it) }
            }
        }

        traverse(rootNode)

        // 找出左上角区域（左30%，上25%）的所有文字
        val leftTopArea = allTexts.filter { (_, rect) ->
            rect.centerX() < screenWidth * 0.3 &&
            rect.centerY() < screenHeight * 0.25 &&
            rect.width() > 0 && rect.height() > 0
        }

        // 找出其中包含福袋关键词的
        val fudaiInLeftTop = leftTopArea.filter { (text, _) ->
            fudaiKeywords.any { text.contains(it) }
        }

        // 找出倒计时格式的
        val countdownInLeftTop = leftTopArea.filter { (text, _) ->
            Regex("\\d{1,2}:\\d{2}").matches(text) ||
            Regex("\\d{2,3}秒").matches(text) ||
            (text.length <= 3 && text.all { c -> c.isDigit() })
        }

        Timber.d("调试：总节点=$totalNodes, 左上角文字=${leftTopArea.size}")
        Timber.d("调试：左上角福袋相关=$fudaiInLeftTop")
        Timber.d("调试：左上角倒计时=$countdownInLeftTop")

        val msg = "左上文字:${leftTopArea.size} | 福袋:${fudaiInLeftTop.size} | 倒计时:${countdownInLeftTop.size}"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun checkAndClickFudai() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < CLICK_COOLDOWN) return

        val rootNode = rootInActiveWindow ?: return

        try {
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels

            // 只在左上角区域搜索（左30%，上25%）
            val searchLeft = 0
            val searchTop = 0
            val searchRight = (screenWidth * 0.3).toInt()
            val searchBottom = (screenHeight * 0.25).toInt()

            // 方法1：找福袋关键词
            val fudaiCandidates = mutableListOf<Pair<AccessibilityNodeInfo, String>>()

            for (keyword in fudaiKeywords) {
                val nodes = findNodesByText(rootNode, keyword)
                for (node in nodes) {
                    val rect = getNodeRect(node)
                    if (rect != null && rect.width() > 0 && rect.height() > 0) {
                        // 只考虑左上角区域
                        if (rect.centerX() in searchLeft..searchRight &&
                            rect.centerY() in searchTop..searchBottom) {
                            fudaiCandidates.add(Pair(node, keyword))
                        }
                    }
                }
            }

            if (fudaiCandidates.isNotEmpty()) {
                // 选最靠上的
                fudaiCandidates.sortBy { getNodeRect(it.first)?.top ?: Int.MAX_VALUE }
                val best = fudaiCandidates.first()
                val rect = getNodeRect(best.first)!!

                // 点击文字的左上方（福袋图标在文字左上方）
                val x = rect.left - rect.width() * 1.5f
                val y = rect.centerY() - rect.height() * 0.5f

                val clickX = x.toInt().coerceAtLeast(10)
                val clickY = y.toInt().coerceAtLeast(10)

                Toast.makeText(this, "🎯 文字命中[${best.second}] 点击($clickX, $clickY)", Toast.LENGTH_SHORT).show()
                vibrate(100)

                clickSimulator.click(clickX, clickY)
                lastClickTime = currentTime
                Timber.d("文字命中点击，关键词: ${best.second}, 坐标: ($clickX, $clickY)")
                return
            }

            // 方法2：找倒计时
            val countdownPatterns = listOf(
                "\\d{1,2}:\\d{2}".toRegex(),
                "\\d{2,3}秒".toRegex(),
                "\\d{2,3}".toRegex()
            )

            val countdownCandidates = mutableListOf<AccessibilityNodeInfo>()

            for (pattern in countdownPatterns) {
                val nodes = findNodesByRegex(rootNode, pattern)
                for (node in nodes) {
                    val rect = getNodeRect(node)
                    if (rect != null && rect.width() > 0 && rect.height() > 0) {
                        if (rect.centerX() in searchLeft..searchRight &&
                            rect.centerY() in searchTop..searchBottom) {
                            countdownCandidates.add(node)
                        }
                    }
                }
            }

            if (countdownCandidates.isNotEmpty()) {
                countdownCandidates.sortBy { getNodeRect(it)?.top ?: Int.MAX_VALUE }
                val best = countdownCandidates.first()
                val rect = getNodeRect(best)!!

                // 点击倒计时正上方（福袋图标位置）
                val x = rect.centerX()
                val y = rect.top - rect.height() * 2

                val clickY = y.toInt().coerceAtLeast(10)

                Toast.makeText(this, "⏰ 倒计时命中 点击($x, $clickY)", Toast.LENGTH_SHORT).show()
                vibrate(100)

                clickSimulator.click(x, clickY)
                lastClickTime = currentTime
                Timber.d("倒计时命中点击，坐标: ($x, $clickY)")
                return
            }

            // 方法3：盲点击——直接点左上角固定位置（相对坐标）
            // 福袋大约在屏幕宽度8%，高度12%的位置
            val blindX = (screenWidth * 0.08f).toInt()
            val blindY = (screenHeight * 0.12f).toInt()

            // 这个方法不自动点击，只在调试时显示
            // 如果前两个方法都不行，我们再启用盲点击

        } catch (e: Exception) {
            Timber.e(e, "检测福袋时出错")
            Toast.makeText(this, "❌ 错误: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

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

    private fun findNodesByTextRecursive(
        node: AccessibilityNodeInfo,
        text: String,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        val nodeText = node.text
        if (nodeText != null && nodeText.contains(text)) {
            result.add(node)
        }

        val nodeDesc = node.contentDescription
        if (nodeDesc != null && nodeDesc.contains(text)) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            child?.let {
                findNodesByTextRecursive(it, text, result)
            }
        }
    }

    private fun findNodesByRegex(rootNode: AccessibilityNodeInfo, regex: Regex): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        findNodesByRegexRecursive(rootNode, regex, result)
        return result
    }

    private fun findNodesByRegexRecursive(
        node: AccessibilityNodeInfo,
        regex: Regex,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        val nodeText = node.text
        if (nodeText != null && regex.matches(nodeText)) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            child?.let {
                findNodesByRegexRecursive(it, regex, result)
            }
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

    companion object {
        var instance: FudaiAccessibilityService? = null
            private set
    }

    init {
        instance = this
    }
}
