package com.example.livefudai

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

class FudaiAccessibilityService : AccessibilityService() {

    private var isMonitoring = false
    private var lastClickTime = 0L
    private val CLICK_COOLDOWN = 5000L // 5秒冷却，防止重复点击

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isMonitoring) return
        
        event ?: return
        
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
    }

    /**
     * 检测并点击福袋
     */
    private fun checkAndClickFudai() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < CLICK_COOLDOWN) return
        
        val rootNode = rootInActiveWindow ?: return
        
        try {
            // 方法1：查找包含"福袋"的文字节点
            val fudaiNodes = findNodesByText(rootNode, "福袋")
            if (fudaiNodes.isNotEmpty()) {
                Timber.d("找到福袋节点，数量: ${fudaiNodes.size}")
                
                // 点击第一个可点击的福袋节点
                for (node in fudaiNodes) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        lastClickTime = currentTime
                        Timber.d("点击了福袋")
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
     * 根据文字查找节点
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

    companion object {
        var instance: FudaiAccessibilityService? = null
            private set
    }

    init {
        instance = this
    }
}
