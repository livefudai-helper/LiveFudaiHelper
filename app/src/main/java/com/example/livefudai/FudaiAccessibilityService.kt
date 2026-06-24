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
    private val CLICK_COOLDOWN = 5000L // 5秒冷却，防止重复点击
    private var eventCount = 0
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "fudai_channel"
    private lateinit var clickSimulator: ClickSimulator

    // 福袋关键词列表
    private val fudaiKeywords = listOf("福袋", "超级福袋", "限时福袋", "全民福袋")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isMonitoring) return
        
        event ?: return
        
        eventCount++
        
        // 每100个事件打一次日志
        if (eventCount % 100 == 0) {
            Timber.d("已处理 $eventCount 个事件")
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
        
        // 启动前台服务
        startForegroundService()
        
        Toast.makeText(this, "✅ 福袋助手已启动，正在监听...", Toast.LENGTH_LONG).show()
        
        Timber.d("前台服务已启动，通知栏可见")
    }

    /**
     * 启动前台服务
     */
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
     * 检测并点击福袋
     */
    private fun checkAndClickFudai() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < CLICK_COOLDOWN) return
        
        val rootNode = rootInActiveWindow ?: return
        
        try {
            // 方法1：通过福袋关键词查找
            for (keyword in fudaiKeywords) {
                val fudaiNodes = findNodesByText(rootNode, keyword)
                if (fudaiNodes.isNotEmpty()) {
                    Timber.d("方法1找到福袋节点（关键词：$keyword），数量: ${fudaiNodes.size}")
                    
                    // 找第一个能获取到坐标的节点
                    for (node in fudaiNodes) {
                        val rect = getNodeRect(node)
                        if (rect != null && rect.width() > 0 && rect.height() > 0) {
                            val x = rect.centerX()
                            val y = rect.centerY()
                            
                            Toast.makeText(this, "🎯 检测到福袋！点击坐标($x, $y)", Toast.LENGTH_SHORT).show()
                            vibrate(100)
                            
                            clickSimulator.click(x, y)
                            lastClickTime = currentTime
                            Timber.d("点击福袋成功，坐标: ($x, $y)")
                            return
                        }
                    }
                }
            }
            
            // 方法2：通过倒计时查找（格式：09:33）
            val countdownNodes = findNodesByRegex(rootNode, "\\d{2}:\\d{2}".toRegex())
            if (countdownNodes.isNotEmpty()) {
                Timber.d("方法2找到倒计时节点，数量: ${countdownNodes.size}")
                
                // 找第一个能获取到坐标的节点，点击它的父区域
                for (node in countdownNodes) {
                    val rect = getNodeRect(node)
                    if (rect != null && rect.width() > 0 && rect.height() > 0) {
                        // 点击倒计时上方一点的位置（福袋图标位置）
                        val x = rect.centerX()
                        val y = rect.top - rect.height() // 往上偏移一个节点高度
                        
                        if (y > 0) {
                            Toast.makeText(this, "⏰ 检测到倒计时！点击福袋($x, $y)", Toast.LENGTH_SHORT).show()
                            vibrate(100)
                            
                            clickSimulator.click(x, y)
                            lastClickTime = currentTime
                            Timber.d("通过倒计时点击福袋成功，坐标: ($x, $y)")
                            return
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "检测福袋时出错")
            Toast.makeText(this, "❌ 检测出错: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 获取节点在屏幕上的位置
     */
    private fun getNodeRect(node: AccessibilityNodeInfo): Rect? {
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect
        } catch (e: Exception) {
            Timber.e(e, "获取节点坐标失败")
            null
        }
    }

    /**
     * 根据文字查找节点（同时检查 text 和 contentDescription）
     */
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

    /**
     * 根据正则表达式查找节点
     */
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

    /**
     * 震动反馈
     */
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
