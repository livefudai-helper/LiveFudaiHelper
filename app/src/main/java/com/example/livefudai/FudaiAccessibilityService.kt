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

    private val fudaiKeywords = listOf("福袋", "超级福袋", "限时福袋", "全民福袋", "锦鲤", "免费抽", "福")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isMonitoring) return
        event ?: return
        eventCount++

        // 每5秒弹一次调试信息
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

    /**
     * 调试：dump节点信息
     */
    private fun dumpNodeInfo() {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Timber.d("调试：rootNode 为空")
            return
        }

        val allTexts = mutableListOf<String>()
        val allDescs = mutableListOf<String>()
        var totalNodes = 0

        fun traverse(node: AccessibilityNodeInfo) {
            totalNodes++
            node.text?.let { if (it.isNotBlank()) allTexts.add(it.toString()) }
            node.contentDescription?.let { if (it.isNotBlank()) allDescs.add(it.toString()) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { traverse(it) }
            }
        }

        traverse(rootNode)

        // 找包含"袋"或"福"或倒计时格式的文字
        val fudaiRelated = allTexts.filter {
            it.contains("袋") || it.contains("福") || it.contains("锦鲤") ||
            Regex("\\d{1,2}:\\d{2}").matches(it) ||
            Regex("\\d{2,3}秒").matches(it)
        }

        val descRelated = allDescs.filter {
            it.contains("袋") || it.contains("福") || it.contains("锦鲤")
        }

        val countdownLike = allTexts.filter {
            Regex("\\d{1,2}:\\d{2}").matches(it) ||
            Regex("\\d{2,3}秒").matches(it) ||
            (it.length <= 3 && it.all { c -> c.isDigit() })
        }

        Timber.d("调试：总节点数=$totalNodes, 文字节点=${allTexts.size}, contentDesc=${allDescs.size}")
        Timber.d("调试：福袋相关文字=$fudaiRelated")
        Timber.d("调试：福袋相关描述=$descRelated")
        Timber.d("调试：疑似倒计时=$countdownLike")

        // 弹Toast显示关键信息
        val debugMsg = "节点:$totalNodes | 福袋相关:${fudaiRelated.size + descRelated.size} | 倒计时:${countdownLike.size}"
        Toast.makeText(this, debugMsg, Toast.LENGTH_SHORT).show()
    }

    private fun checkAndClickFudai() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < CLICK_COOLDOWN) return

        val rootNode = rootInActiveWindow ?: return

        try {
            // 方法1：通过福袋关键词查找
            for (keyword in fudaiKeywords) {
                val fudaiNodes = findNodesByText(rootNode, keyword)
                if (fudaiNodes.isNotEmpty()) {
                    Timber.d("方法1找到节点（关键词：$keyword），数量: ${fudaiNodes.size}")

                    for (node in fudaiNodes) {
                        val rect = getNodeRect(node)
                        if (rect != null && rect.width() > 0 && rect.height() > 0) {
                            val x = rect.centerX()
                            val y = rect.centerY()

                            Toast.makeText(this, "🎯 方法1命中！点击($x, $y)", Toast.LENGTH_SHORT).show()
                            vibrate(100)

                            clickSimulator.click(x, y)
                            lastClickTime = currentTime
                            Timber.d("点击成功，坐标: ($x, $y)")
                            return
                        }
                    }
                }
            }

            // 方法2：通过倒计时查找（多种格式）
            val countdownPatterns = listOf(
                "\\d{1,2}:\\d{2}".toRegex(),  // 9:33 或 09:33
                "\\d{2,3}秒".toRegex(),       // 599秒
                "\\d{2,3}".toRegex()          // 纯数字秒数
            )

            for (pattern in countdownPatterns) {
                val countdownNodes = findNodesByRegex(rootNode, pattern)
                if (countdownNodes.isNotEmpty()) {
                    Timber.d("方法2找到倒计时节点（模式：$pattern），数量: ${countdownNodes.size}")

                    for (node in countdownNodes) {
                        val rect = getNodeRect(node)
                        if (rect != null && rect.width() > 0 && rect.height() > 0) {
                            val x = rect.centerX()
                            val y = rect.top - rect.height()

                            if (y > 0) {
                                Toast.makeText(this, "⏰ 方法2命中！点击($x, $y)", Toast.LENGTH_SHORT).show()
                                vibrate(100)

                                clickSimulator.click(x, y)
                                lastClickTime = currentTime
                                Timber.d("通过倒计时点击成功，坐标: ($x, $y)")
                                return
                            }
                        }
                    }
                }
            }

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
