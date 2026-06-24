package com.example.livefudai

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
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

    // 福袋关键词列表
    private val fudaiKeywords = listOf("福袋", "超级福袋", "限时福袋", "全民福袋")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isMonitoring) return
        
        event ?: return
        
        eventCount++
        
        // 每100个事件弹一次Toast，说明服务在正常工作
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
        
        // 启动前台服务（通知栏显示"正在运行"）
        startForegroundService()
        
        // 弹Toast提示
        Toast.makeText(this, "✅ 福袋助手已启动，正在监听...", Toast.LENGTH_LONG).show()
        
        Timber.d("前台服务已启动，通知栏可见")
    }

    /**
     * 启动前台服务，在通知栏显示常驻通知
     */
    private fun startForegroundService() {
        // 创建通知渠道（Android 8.0+ 需要）
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
        
        // 构建通知
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("福袋助手")
            .setContentText("正在监听抖音福袋...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // 常驻通知，不能被滑动删除
            .build()
        
        // 启动前台服务
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
            // 方法1：查找包含福袋关键词的文字节点
            for (keyword in fudaiKeywords) {
                val fudaiNodes = findNodesByText(rootNode, keyword)
                if (fudaiNodes.isNotEmpty()) {
                    Timber.d("找到福袋节点（关键词：$keyword），数量: ${fudaiNodes.size}")
                    
                    // 弹Toast提示
                    Toast.makeText(this, "🎯 检测到福袋！正在点击...", Toast.LENGTH_SHORT).show()
                    
                    // 震动一下
                    vibrate(100)
                    
                    // 点击第一个可点击的福袋节点
                    for (node in fudaiNodes) {
                        if (node.isClickable) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            lastClickTime = currentTime
                            Timber.d("点击了福袋节点")
                            return
                        }
                        // 如果节点本身不可点击，找它的可点击父节点
                        var parent = node.parent
                        while (parent != null) {
                            if (parent.isClickable) {
                                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                lastClickTime = currentTime
                                Timber.d("点击了福袋父节点")
                                return
                            }
                            parent = parent.parent
                        }
                    }
                }
            }
            
            // 方法2：查找倒计时节点（格式：09:33）
            val countdownNodes = findNodesByRegex(rootNode, "\\d{2}:\\d{2}".toRegex())
            if (countdownNodes.isNotEmpty()) {
                Timber.d("找到倒计时节点，数量: ${countdownNodes.size}")
            }
            
        } catch (e: Exception) {
            Timber.e(e, "检测福袋时出错")
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
        // 检查 text
        val nodeText = node.text
        if (nodeText != null && nodeText.contains(text)) {
            result.add(node)
        }
        
        // 检查 contentDescription
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
