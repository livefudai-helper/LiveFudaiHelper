package com.example.livefudai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 点击模拟器 - 负责模拟用户点击、滑动等操作
 * 支持Android 7.0+的Gesture API和传统的Accessibility点击
 */
class ClickSimulator(private val service: AccessibilityService) {
    
    companion object {
        private const val TAG = "ClickSimulator"
        private const val GESTURE_DURATION = 50L // 手势持续时间(ms)
    }
    
    /**
     * 点击指定坐标（使用Gesture API，Android 7.0+）
     */
    fun clickAt(x: Int, y: Int, callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 使用Gesture API模拟点击
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        GESTURE_DURATION
                    )
                )
                .build()
            
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "点击成功: ($x, $y)")
                    callback?.invoke(true)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "点击被取消: ($x, $y)")
                    callback?.invoke(false)
                }
            }, null)
            
        } else {
            // Android 7.0以下使用传统方法
            Log.w(TAG, "Android版本过低，无法使用Gesture API")
            callback?.invoke(false)
        }
    }
    
    /**
     * 点击AccessibilityNodeInfo（推荐使用）
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            Log.e(TAG, "点击节点失败", e)
            false
        }
    }
    
    /**
     * 点击节点中心位置（结合Gesture和Accessibility）
     */
    fun clickNodeCenter(node: AccessibilityNodeInfo) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        
        Log.d(TAG, "点击节点中心: ($centerX, $centerY)")
        clickAt(centerX, centerY)
    }
    
    /**
     * 输入文本到EditText
     */
    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val bundle = android.os.Bundle()
            bundle.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        } catch (e: Exception) {
            Log.e(TAG, "输入文本失败", e)
            false
        }
    }
    
    /**
     * 滑动屏幕
     */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        duration
                    )
                )
                .build()
            
            service.dispatchGesture(gesture, null, null)
            Log.d(TAG, "滑动: ($startX,$startY) -> ($endX,$endY)")
        }
    }
    
    /**
     * 向上滑动（加载更多内容）
     */
    fun scrollUp() {
        val displayMetrics = service.resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2
        val startY = displayMetrics.heightPixels * 3 / 4
        val endY = displayMetrics.heightPixels / 4
        
        swipe(centerX, startY, centerX, endY, 500)
    }
    
    /**
     * 向下滑动
     */
    fun scrollDown() {
        val displayMetrics = service.resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2
        val startY = displayMetrics.heightPixels / 4
        val endY = displayMetrics.heightPixels * 3 / 4
        
        swipe(centerX, startY, centerX, endY, 500)
    }
    
    /**
     * 长按某个位置
     */
    fun longClickAt(x: Int, y: Int, duration: Long = 1000) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        duration
                    )
                )
                .build()
            
            service.dispatchGesture(gesture, null, null)
            Log.d(TAG, "长按: ($x, $y), 持续${duration}ms")
        }
    }
    
    /**
     * 查找并点击（通过文本）
     */
    fun findAndClickByText(text: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        
        try {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            
            if (nodes.isEmpty()) {
                Log.d(TAG, "未找到文本: $text")
                return false
            }
            
            nodes.firstOrNull()?.let { node ->
                val result = clickNode(node)
                Log.d(TAG, "点击文本'$text': $result")
                return result
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "查找并点击失败", e)
        } finally {
            rootNode.recycle()
        }
        
        return false
    }
    
    /**
     * 查找并点击（通过View ID）
     */
    fun findAndClickById(viewId: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        
        try {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            
            if (nodes.isEmpty()) {
                Log.d(TAG, "未找到View ID: $viewId")
                return false
            }
            
            nodes.firstOrNull()?.let { node ->
                val result = clickNode(node)
                Log.d(TAG, "点击View ID'$viewId': $result")
                return result
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "查找并点击失败", e)
        } finally {
            rootNode.recycle()
        }
        
        return false
    }
}
