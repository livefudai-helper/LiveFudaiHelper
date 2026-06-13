package com.example.livefudaihelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.example.livefudaihelper.OCRManager.recognizeText
import kotlinx.coroutines.*
import java.util.regex.Pattern

class FudaiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FudaiService"
        private const val SCAN_INTERVAL_MS = 10_000L  // 10秒扫描一次
        private const val MAX_WAIT_TIME_MS = 30_000L   // 30秒没找到就切换
        private const val CLICK_DELAY_MS = 2_000L      // 点击后等待2秒
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var currentRoomStartTime = 0L
    private var ocrManager: OCRManager? = null

    // 倒计时正则（匹配 00:19、01:45、1:23 等格式）
    private val countdownPattern = Pattern.compile("""\d{1,2}[:：]\d{2}""")

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "无障碍服务已连接")
        ocrManager = OCRManager.getInstance(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning) return
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val eventType = event.eventType

        // 只处理直播APP的事件
        if (!isLiveApp(packageName)) return

        Log.d(TAG, "收到事件: $eventType, 包名: $packageName")

        // 当窗口内容变化时，触发扫描
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scheduleScan()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "服务被中断")
    }

    fun startScanning() {
        Log.d(TAG, "开始扫描")
        isRunning = true
        currentRoomStartTime = System.currentTimeMillis()
        updateNotification("正在扫描直播间...")
        scheduleScan()
    }

    fun stopScanning() {
        Log.d(TAG, "停止扫描")
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
    }

    private fun scheduleScan() {
        if (!isRunning) return

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (isRunning) {
                scanForFudai()
            }
        }, SCAN_INTERVAL_MS)
    }

    private fun scanForFudai() {
        if (!isRunning) return

        Log.d(TAG, "开始扫描福袋...")
        updateNotification("正在识别福袋...")

        // 检查是否超时（30秒）
        val elapsed = System.currentTimeMillis() - currentRoomStartTime
        if (elapsed > MAX_WAIT_TIME_MS) {
            Log.d(TAG, "超时，切换到下一个直播间")
            updateNotification("超时，切换直播间...")
            switchToNextLiveRoom()
            return
        }

        serviceScope.launch {
            try {
                // 截图并用OCR识别
                val rootNode = rootInActiveWindow ?: return@launch
                val screenshot = takeScreenshot() ?: return@launch

                // 用OCR识别文字
                val ocrResult = withContext(Dispatchers.IO) {
                    ocrManager?.recognizeText(screenshot)
                }

                if (ocrResult.isNullOrEmpty()) {
                    Log.d(TAG, "OCR识别结果为空")
                    scheduleScan()
                    return@launch
                }

                Log.d(TAG, "OCR识别结果: $ocrResult")

                // 查找倒计时文本（如 00:19）
                val found = findCountdownAndClick(ocrResult)

                if (found) {
                    Log.d(TAG, "找到福袋！已点击")
                    updateNotification("找到福袋，已点击！")
                    // 点击后等待一段时间再继续扫描
                    handler.postDelayed({ scheduleScan() }, CLICK_DELAY_MS)
                } else {
                    Log.d(TAG, "未找到福袋，继续扫描")
                    updateNotification("未找到福袋，继续扫描...")
                    scheduleScan()
                }

            } catch (e: Exception) {
                Log.e(TAG, "扫描出错", e)
                scheduleScan()
            }
        }
    }

    /**
     * 在OCR结果中查找倒计时，并点击对应位置
     */
    private suspend fun findCountdownAndClick(ocrResult: String): Boolean {
        // 查找倒计时格式（如 00:19、01:45）
        val matcher = countdownPattern.matcher(ocrResult)
        if (matcher.find()) {
            val countdown = matcher.group()
            Log.d(TAG, "找到倒计时: $countdown")

            // 获取屏幕尺寸
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // 福袋在左上角，倒计时附近的位置
            // 估算点击位置：左上角区域
            val clickX = (screenWidth * 0.10).toInt()  // 左上角 x=10%
            val clickY = (screenHeight * 0.10).toInt()  // 左上角 y=10%

            Log.d(TAG, "点击位置: ($clickX, $clickY)")
            clickAt(clickX, clickY)
            return true
        }

        return false
    }

    /**
     * 在节点树中查找包含倒计时的节点并点击
     */
    private fun findCountdownInNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = node.text?.toString() ?: ""
        if (countdownPattern.matcher(text).find()) {
            Log.d(TAG, "在节点树中找到倒计时: $text")
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        // 递归搜索子节点
        for (i in 0 until node.childCount) {
            if (findCountdownInNode(node.getChild(i))) {
                return true
            }
        }

        return false
    }

    /**
     * 模拟点击指定坐标（需要 API >= 24）
     */
    private fun clickAt(x: Int, y: Int) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            Log.w(TAG, "系统版本过低，无法使用 Gesture API")
            return
        }

        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "点击完成: ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "点击被取消: ($x, $y)")
            }
        }

        dispatchGesture(gesture, callback, handler)
    }

    /**
     * 切换到下一个直播间（下滑手势）
     */
    private fun switchToNextLiveRoom() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            Log.w(TAG, "系统版本过低，无法切换直播间")
            scheduleScan()
            return
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // 上滑手势（切换下一个直播）
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.7f
        val endX = screenWidth / 2f
        val endY = screenHeight * 0.3f

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "已切换到下一个直播间")
                currentRoomStartTime = System.currentTimeMillis()
                handler.postDelayed({ scheduleScan() }, 3_000L)  // 等待3秒加载
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "切换直播间被取消")
                scheduleScan()
            }
        }

        dispatchGesture(gesture, callback, handler)
    }

    /**
     * 更新前台通知
     */
    private fun updateNotification(status: String) {
        val service = FudaiForegroundService.getInstance()
        service?.updateNotification(status)
    }

    /**
     * 判断是否为直播APP
     */
    private fun isLiveApp(packageName: String): Boolean {
        val liveApps = listOf(
            "com.ss.android.ugc.aweme",  // 抖音
            "com.tencent.mobileqq",        // 腾讯直播
            "com.kuaishou.nebula",         // 快手直播
            "com.ss.android.article.video" // 抖音火山版
        )
        return liveApps.any { packageName.contains(it, ignoreCase = true) }
    }

    /**
     * 截图（需要 API >= 30）
     */
    private suspend fun takeScreenshot(): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        // 注意：AccessibilityService 的截图API需要 API >= 30
        // 这里返回一个null，实际使用OCR时会通过其他方式获取屏幕图像
        // 或者通过 MediaProjection 实现
        null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
    }
}
