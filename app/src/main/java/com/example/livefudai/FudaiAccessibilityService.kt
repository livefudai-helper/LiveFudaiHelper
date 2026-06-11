package com.example.livefudai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.*

/**
 * 福袋自动参与服务 - 核心无障碍服务
 * 监听直播间界面，识别福袋元素并自动参与
 */
class FudaiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FudaiAccessibility"
        private const val TRIGGER_TIME_REMAINING = 3 * 60 // 3分钟 = 180秒
    }

    private lateinit var ocrManager: OCRManager
    private lateinit var clickSimulator: ClickSimulator
    private var monitoringJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "福袋服务启动")

        // 初始化OCR管理器（使用HTTP API，无需本地jar）
        ocrManager = OCRManager(this)

        // 初始化点击模拟器
        clickSimulator = ClickSimulator(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // 配置无障碍服务
        serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = arrayOf(
                "com.ss.android.ugc.aweme", // 抖音
                "com.tencent.mobileqq",      // QQ直播
                "com.kuaishou.nebula"        // 快手
            )
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }

        this.serviceInfo = serviceInfo
        Log.d(TAG, "无障碍服务已连接，开始监听直播间")

        // 启动前台服务（保持后台运行）
        val intent = Intent(this, FudaiForegroundService::class.java).apply {
            action = FudaiForegroundService.ACTION_START
        }
        startService(intent)

        // 提示用户服务已启动
        showToast("福袋助手已启动，进入直播间即可自动检测")
    }

    /**
     * 在主线程显示 Toast
     */
    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "界面切换: ${event.packageName}")
                // 只在抖音/快手/QQ直播切换时提示
                val pkg = event.packageName?.toString() ?: ""
                if (pkg.contains("aweme") || pkg.contains("kuaishou") || pkg.contains("qq")) {
                    showToast("检测到直播APP，正在扫描福袋...")
                }
                checkForFudai()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 定期检查福袋（防抖处理）
                scheduleFudaiCheck()
            }
        }
    }

    /**
     * 定时检查福袋（带防抖）
     */
    private fun scheduleFudaiCheck() {
        monitoringJob?.cancel()
        monitoringJob = CoroutineScope(Dispatchers.Main).launch {
            delay(2000) // 2秒防抖
            checkForFudai()
        }
    }

    /**
     * 检查福袋并参与
     */
    private fun checkForFudai() {
        val rootNode = rootInActiveWindow ?: return

        try {
            // 1. 查找福袋入口（通常为悬浮按钮或列表项）
            val fudaiNodes = findFudaiNodes(rootNode)

            if (fudaiNodes.isEmpty()) {
                // Log.d(TAG, "未检测到福袋")
                return
            }

            Log.d(TAG, "检测到 ${fudaiNodes.size} 个福袋")

            // 2. 遍历所有福袋
            fudaiNodes.forEach { node ->
                processFudaiNode(node)
            }

        } catch (e: Exception) {
            Log.e(TAG, "检查福袋失败", e)
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * 查找福袋节点
     */
    private fun findFudaiNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val keywords = listOf("福袋", "抽奖", "幸运福袋", "超级福袋", "抢福袋", "参与福袋")

        for (keyword in keywords) {
            val matches = root.findAccessibilityNodeInfosByText(keyword)
            results.addAll(matches)
        }

        return results.distinctBy { it.windowId }
    }

    /**
     * 处理单个福袋节点（两步参与流程）
     *
     * 流程：
     * 1. 点击福袋入口
     * 2. 等待弹窗打开
     * 3. 从节点树提取倒计时
     * 4. 判断是否≤3分钟
     * 5. 输入评论内容
     * 6. 点击"发送"按钮
     * 7. 点击"参与"按钮
     */
    private fun processFudaiNode(node: AccessibilityNodeInfo) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "处理福袋: ${node.text}")

                // ========== 第1步：点击福袋入口 ==========
                Log.d(TAG, "[第1步] 点击福袋入口")
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(2000) // 等待弹窗打开

                // ========== 第2步：从节点树提取倒计时 ==========
                Log.d(TAG, "[第2步] 提取倒计时")
                val countdown = extractCountdownFromScreen()

                if (countdown <= 0) {
                    Log.w(TAG, "无法识别倒计时，跳过")
                    return@launch
                }

                Log.d(TAG, "福袋倒计时: ${countdown}秒")

                // ========== 第3步：判断是否达到触发条件 ==========
                if (countdown <= TRIGGER_TIME_REMAINING) {
                    Log.d(TAG, "倒计时≤3分钟，开始参与")

                    // ========== 第4步：输入评论内容 ==========
                    Log.d(TAG, "[第3步] 输入评论内容")
                    inputComment()
                    delay(1000)

                    // ========== 第5步：点击"发送"按钮 ==========
                    Log.d(TAG, "[第4步] 点击发送按钮")
                    clickSendButton()
                    delay(1500)

                    // ========== 第6步：点击"参与"按钮 ==========
                    Log.d(TAG, "[第5步] 点击参与按钮")
                    clickParticipateButton()
                    delay(1000)

                    Log.d(TAG, "✅ 成功参与福袋！")

                } else {
                    Log.d(TAG, "倒计时未到，等待...（剩余${countdown}秒）")
                }

            } catch (e: Exception) {
                Log.e(TAG, "处理福袋失败", e)
            }
        }
    }

    /**
     * 从当前屏幕的AccessibilityNode树中提取倒计时（秒）
     * 无需截屏，直接遍历节点文字，比OCR更快更准
     */
    private fun extractCountdownFromScreen(): Long {
        val rootNode = rootInActiveWindow ?: return 0L

        try {
            val allTexts = mutableListOf<String>()
            collectAllText(rootNode, allTexts)

            for (text in allTexts) {
                // 匹配 "03:25" 或 "3:25" 格式
                val pattern1 = Regex("""(\d{1,2}):(\d{2})""")
                pattern1.find(text)?.let { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0
                    // 只关注倒计时（通常≤5分钟）
                    val total = minutes * 60 + seconds
                    if (total in 1..300) {
                        Log.d(TAG, "识别到倒计时: $minutes:$seconds")
                        return total
                    }
                }

                // 匹配 "3分25秒" 格式
                val pattern2 = Regex("""(\d+)分(\d+)秒""")
                pattern2.find(text)?.let { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0
                    val total = minutes * 60 + seconds
                    if (total in 1..300) {
                        Log.d(TAG, "识别到倒计时: ${minutes}分${seconds}秒")
                        return total
                    }
                }

                // 匹配 "剩余180秒" 格式
                val pattern3 = Regex("""剩余\s*(\d+)\s*秒""")
                pattern3.find(text)?.let { match ->
                    val sec = match.groupValues[1].toLongOrNull() ?: 0
                    if (sec in 1..300) {
                        Log.d(TAG, "识别到倒计时: ${sec}秒")
                        return sec
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "提取倒计时失败", e)
        } finally {
            rootNode.recycle()
        }

        return 0L
    }

    /**
     * 递归收集所有节点的文字
     */
    private fun collectAllText(node: AccessibilityNodeInfo, results: MutableList<String>) {
        node.text?.toString()?.let { if (it.isNotBlank()) results.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) results.add(it) }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectAllText(child, results)
                child.recycle()
            }
        }
    }

    /**
     * 输入评论内容
     */
    private fun inputComment() {
        val rootNode = rootInActiveWindow ?: return

        try {
            // 查找评论输入框（不同平台ID不同）
            val possibleIds = listOf(
                "com.ss.android.ugc.aweme:id/et_content",      // 抖音
                "com.kuaishou.nebula:id/et_comment",           // 快手
                "com.tencent.mobileqq:id/et_input"              // QQ直播
            )

            var inputField: AccessibilityNodeInfo? = null

            // 方法1：通过View ID查找
            for (id in possibleIds) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
                    inputField = nodes[0]
                    Log.d(TAG, "找到输入框（ID）: $id")
                    break
                }
            }

            // 方法2：通过提示文字查找EditText
            if (inputField == null) {
                val editTexts = rootNode.findAccessibilityNodeInfosByText("说点什么")
                if (editTexts.isEmpty()) {
                    // 尝试其他提示文字
                    val hints = listOf("说点什么", "发条评论", "输入评论")
                    for (hint in hints) {
                        val nodes = rootNode.findAccessibilityNodeInfosByText(hint)
                        if (nodes.isNotEmpty()) {
                            // 找到输入框（通常是前一个或父节点）
                            inputField = nodes[0]
                            Log.d(TAG, "找到输入框（提示文字）: $hint")
                            break
                        }
                    }
                } else {
                    inputField = editTexts[0]
                    Log.d(TAG, "找到输入框（文本）")
                }
            }

            // 输入评论内容
            if (inputField != null) {
                val bundle = android.os.Bundle()
                bundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "参与福袋，好运连连！"
                )
                inputField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                Log.d(TAG, "✅ 已输入评论")
            } else {
                Log.w(TAG, "⚠️ 未找到输入框")
            }

        } catch (e: Exception) {
            Log.e(TAG, "输入评论失败", e)
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * 点击"发送"按钮
     */
    private fun clickSendButton() {
        val rootNode = rootInActiveWindow ?: return

        try {
            // 查找"发送"按钮
            val sendTexts = listOf("发送", "发送评论", "发布")

            for (sendText in sendTexts) {
                val buttons = rootNode.findAccessibilityNodeInfosByText(sendText)
                if (buttons.isNotEmpty()) {
                    buttons[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "✅ 已点击'$sendText'按钮")
                    return
                }
            }

            Log.w(TAG, "⚠️ 未找到发送按钮")

        } catch (e: Exception) {
            Log.e(TAG, "点击发送按钮失败", e)
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * 点击参与按钮
     */
    private fun clickParticipateButton() {
        val rootNode = rootInActiveWindow ?: return

        try {
            // 查找"参与"或"立即参与"按钮
            val participateTexts = listOf("参与", "立即参与", "抢福袋")

            for (text in participateTexts) {
                val buttons = rootNode.findAccessibilityNodeInfosByText(text)
                if (buttons.isNotEmpty()) {
                    buttons[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "✅ 已点击'$text'按钮")
                    return
                }
            }

            Log.w(TAG, "⚠️ 未找到参与按钮")

        } catch (e: Exception) {
            Log.e(TAG, "点击参与按钮失败", e)
        } finally {
            rootNode.recycle()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        monitoringJob?.cancel()
        ocrManager.release()

        // 停止前台服务
        val intent = Intent(this, FudaiForegroundService::class.java).apply {
            action = FudaiForegroundService.ACTION_STOP
        }
        startService(intent)

        showToast("福袋助手已停止")
        Log.d(TAG, "福袋服务销毁")
    }
}
