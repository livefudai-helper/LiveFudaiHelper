package com.example.livefudai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.*

/**
 * 福袋自动参与服务 - 核心无障碍服务
 * 监听直播间界面，识别福袋元素并自动参与
 * 
 * 重要：本服务受 PreferencesManager 控制
 * 只有用户点击"开始运行"后，才会真正执行自动点击逻辑
 */
class FudaiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FudaiAccessibility"
        private const val TRIGGER_TIME_REMAINING = 3 * 60 // 3分钟 = 180秒
        private const val CLICK_COOLDOWN_MS = 5000L // 点击冷却时间：5秒
        private const val COUNTDOWN_CHECK_INTERVAL = 30_000L // 倒计时检查间隔：30秒
    }

    private lateinit var ocrManager: OCRManager
    private lateinit var clickSimulator: ClickSimulator
    private var monitoringJob: Job? = null
    private var lastClickTime = 0L // 上次点击时间（防止重复点击）
    private var isProcessing = false // 是否正在处理福袋（防止并发）

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "福袋服务创建")
        
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
        Log.d(TAG, "无障碍服务已连接")
        
        // 启动前台服务（保持后台运行）
        val intent = Intent(this, FudaiForegroundService::class.java).apply {
            action = "ACTION_START"
        }
        startService(intent)
        
        // 提示用户服务已连接（但不一定在运行）
        val isRunning = PreferencesManager.isServiceRunning(this)
        if (isRunning) {
            showToast("福袋助手已启动，进入直播间即可自动检测")
        } else {
            showToast("福袋助手已连接，请点击"开始运行"")
        }
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
        
        // ⚠️ 关键检查：如果服务未运行，直接返回，不执行任何操作
        if (!PreferencesManager.isServiceRunning(this)) {
            return
        }
        
        // 防止并发处理
        if (isProcessing) {
            Log.d(TAG, "正在处理中，跳过本次事件")
            return
        }
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: ""
                if (pkg.contains("aweme") || pkg.contains("kuaishou") || pkg.contains("qq")) {
                    Log.d(TAG, "界面切换: ${event.packageName}")
                    showToast("检测到直播APP，正在扫描福袋...")
                    checkForFudai()
                }
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
     * 核心逻辑：福袋入口是纯图标（无文字），固定在左上角
     * 所以主要依赖坐标点击，文字查找仅用于弹窗按钮
     */
    private fun checkForFudai() {
        // ⚠️ 关键检查：如果服务未运行，直接返回
        if (!PreferencesManager.isServiceRunning(this)) {
            Log.d(TAG, "服务已暂停，不执行检查")
            return
        }
        
        // 检查冷却时间
        val now = System.currentTimeMillis()
        if (now - lastClickTime < CLICK_COOLDOWN_MS) {
            Log.d(TAG, "冷却中，跳过本次检查")
            return
        }
        
        isProcessing = true
        
        try {
            // 第1步：尝试坐标点击左上角福袋图标（主要方式）
            // 因为福袋是纯图标，无障碍节点无法通过文字找到
            clickFudaiByGesture()
            
            // 第2步：查找弹窗内的按钮（"去发表评论"/"一键发表评论"等）
            val rootNode = rootInActiveWindow ?: return
            try {
                val fudaiNodes = findFudaiNodes(rootNode)
                if (fudaiNodes.isNotEmpty()) {
                    Log.d(TAG, "检测到 ${fudaiNodes.size} 个福袋相关节点")
                    showToast("检测到福袋，正在自动处理...")
                    fudaiNodes.forEach { node ->
                        processFudaiNode(node)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查福袋失败", e)
            } finally {
                rootNode.recycle()
            }
        } finally {
            isProcessing = false
        }
    }

    /**
     * 查找福袋节点（直播间入口 + 弹窗内容）
     * 入口可能是图标（无文字），需要同时查找 contentDescription
     */
    private fun findFudaiNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        
        // 方法1：通过文字查找（弹窗按钮等）
        val textKeywords = listOf("去发表评论", "一键发表评论", "立即参与", "参与", "抢福袋", "福袋", "抽奖")
        for (keyword in textKeywords) {
            val matches = root.findAccessibilityNodeInfosByText(keyword)
            results.addAll(matches)
        }
        
        // 方法2：通过 contentDescription 查找福袋图标（入口通常是图标，无文字）
        val iconNodes = findNodesByDesc(root, listOf("福", "福袋", "lucky", "bag"))
        results.addAll(iconNodes)
        
        // 方法3：查找可点击且包含"福"相关描述的节点
        val clickableFudai = findClickableNodeWithFudaiDesc(root)
        if (clickableFudai != null) {
            results.add(clickableFudai)
        }
        
        return results.distinctBy { it.windowId }
    }

    /**
     * 递归查找 contentDescription 包含指定关键词的节点
     */
    private fun findNodesByDesc(node: AccessibilityNodeInfo, keywords: List<String>): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val desc = node.contentDescription?.toString() ?: ""
        for (keyword in keywords) {
            if (desc.contains(keyword)) {
                results.add(node)
                break
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                results.addAll(findNodesByDesc(child, keywords))
                child.recycle()
            }
        }
        return results
    }

    /**
     * 查找可点击且 contentDescription/viewIdResourceName 包含福袋相关信息的节点
     */
    private fun findClickableNodeWithFudaiDesc(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        if (node.isClickable && (desc.contains("福") || viewId.contains("fudai") || viewId.contains("lucky") || viewId.contains("bag"))) {
            return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val result = findClickableNodeWithFudaiDesc(child)
                if (result != null) return result
                child.recycle()
            }
        }
        return null
    }

    /**
     * 处理单个福袋节点
     *
     * 抖音福袋流程：
     * 1. 直播间主界面点击福袋入口（"价值10钻"/"参与人数"区域）
     * 2. 弹出福袋详情弹窗
     * 3. 弹窗显示参与条件（如"发送评论：小叔家的合金车真好！"）
     * 4. 点击"去发表评论"按钮
     * 5. 自动输入评论并发送
     */
    private fun processFudaiNode(node: AccessibilityNodeInfo) {
        // ⚠️ 关键检查：如果服务未运行，停止处理
        if (!PreferencesManager.isServiceRunning(this)) {
            Log.d(TAG, "服务已暂停，停止处理")
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodeText = node.text?.toString() ?: ""
                Log.d(TAG, "处理节点: $nodeText")
                
                // 情况1：检测到福袋弹窗内的按钮（"去发表评论"/"立即参与"）
                if (nodeText.contains("去发表评论") || nodeText.contains("立即参与")
                    || nodeText.contains("参与") || nodeText.contains("抢福袋")) {
                    Log.d(TAG, "[弹窗按钮] 点击: $nodeText")
                    
                    // 更新最后点击时间
                    lastClickTime = System.currentTimeMillis()
                    
                    // 先提取评论内容（从弹窗中的"发送评论：xxx"）
                    val comment = extractCommentFromScreen()
                    if (comment.isNotEmpty()) {
                        Log.d(TAG, "提取到评论内容: $comment")
                        // 点击按钮后通常会弹出输入框或自动填充
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        delay(1500)
                        // 尝试输入提取到的评论
                        inputComment(comment)
                        delay(500)
                        clickSendButton()
                    } else {
                        // 没有特定评论要求，直接点击
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    return@launch
                }
                
            // 情况2：检测到福袋入口（直播间主界面，可能是图标）
                // 尝试点击节点本身或其可点击的父节点
                var clickableNode: AccessibilityNodeInfo? = null
                if (node.isClickable) {
                    clickableNode = node
                } else {
                    clickableNode = findClickableParent(node)
                }
                
                if (clickableNode != null) {
                    // 更新最后点击时间
                    lastClickTime = System.currentTimeMillis()
                    
                    Log.d(TAG, "[福袋入口] 点击福袋入口（${node.text ?: node.contentDescription ?: "图标"}）")
                    clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(2000) // 等待弹窗打开
                    
                    // ⚠️ 检查服务状态
                    if (!PreferencesManager.isServiceRunning(this@FudaiAccessibilityService)) {
                        Log.d(TAG, "服务已暂停，停止处理")
                        return@launch
                    }
                    
                    // 弹窗打开后，查找"去发表评论"/"一键发表评论"按钮并点击
                    val popupRoot = rootInActiveWindow
                    if (popupRoot != null) {
                        val commentBtn = findNodeByTexts(popupRoot,
                            listOf("去发表评论", "一键发表评论", "立即参与", "参与", "抢福袋"))
                        if (commentBtn != null) {
                            Log.d(TAG, "[弹窗] 找到按钮: ${commentBtn.text ?: commentBtn.contentDescription}")
                            val comment = extractCommentFromScreen()
                            commentBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            delay(1500)
                            if (comment.isNotEmpty()) {
                                inputComment(comment)
                                delay(500)
                                clickSendButton()
                            }
                        } else {
                            Log.w(TAG, "[弹窗] 未找到评论按钮，尝试提取评论并输入")
                            // 弹窗可能已经显示了评论输入框
                            val comment = extractCommentFromScreen()
                            if (comment.isNotEmpty()) {
                                inputComment(comment)
                                delay(500)
                                clickSendButton()
                            }
                        }
                        popupRoot.recycle()
                    }
                } else {
                    Log.w(TAG, "[福袋入口] 未找到可点击的节点")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "处理福袋失败", e)
            }
        }
    }

    /**
     * 向上查找可点击的父节点
     */
    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current
            }
            current = current.parent
        }
        return null
    }

    /**
     * 通过多个关键词查找节点，返回第一个匹配的
     */
    private fun findNodeByTexts(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) {
                return nodes[0]
            }
        }
        return null
    }

    /**
     * 从当前屏幕提取评论内容（匹配"发送评论：xxx"格式）
     */
    private fun extractCommentFromScreen(): String {
        val rootNode = rootInActiveWindow ?: return ""
        try {
            val allTexts = mutableListOf<String>()
            collectAllText(rootNode, allTexts)
            
            for (text in allTexts) {
                // 匹配 "发送评论：xxx" 或 "评论内容：xxx"
                val pattern = Regex("""(?:发送评论|评论内容|评论)[：:]\s*(.+?)(?:\s|$)""")
                pattern.find(text)?.let { match ->
                    val comment = match.groupValues[1].trim()
                    if (comment.isNotEmpty()) {
                        Log.d(TAG, "从弹窗提取评论: $comment")
                        return comment
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取评论内容失败", e)
        } finally {
            rootNode.recycle()
        }
        return ""
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
     * @param customComment 从弹窗提取的评论内容，为空则使用默认评论
     */
    private fun inputComment(customComment: String = "") {
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
                val hints = listOf("说点什么", "发条评论", "输入评论", "说点什么...")
                for (hint in hints) {
                    val nodes = rootNode.findAccessibilityNodeInfosByText(hint)
                    if (nodes.isNotEmpty()) {
                        inputField = nodes[0]
                        Log.d(TAG, "找到输入框（提示文字）: $hint")
                        break
                    }
                }
            }
            
            // 方法3：查找所有可编辑的节点
            if (inputField == null) {
                inputField = findEditableNode(rootNode)
                if (inputField != null) {
                    Log.d(TAG, "找到输入框（可编辑节点）")
                }
            }
            
            // 输入评论内容
            val commentText = if (customComment.isNotEmpty()) customComment else "参与福袋，好运连连！"
            if (inputField != null) {
                val bundle = android.os.Bundle()
                bundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    commentText
                )
                inputField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                Log.d(TAG, "✅ 已输入评论: $commentText")
                showToast("已自动输入评论")
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
     * 递归查找可编辑的输入框节点
     */
    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) {
            return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val result = findEditableNode(child)
                if (result != null) return result
                child.recycle()
            }
        }
        return null
    }

    /**
     * 点击"发送"按钮
     */
    private fun clickSendButton() {
        val rootNode = rootInActiveWindow ?: return
        
        try {
            // 查找"发送"按钮（抖音/快手/通用）
            val sendTexts = listOf("发送", "发送评论", "发布", "提交", "确定")
            
            for (sendText in sendTexts) {
                val buttons = rootNode.findAccessibilityNodeInfosByText(sendText)
                if (buttons.isNotEmpty()) {
                    val btn = buttons[0]
                    // 优先点击可点击的按钮
                    val clickableBtn = if (btn.isClickable) btn else findClickableParent(btn)
                    (clickableBtn ?: btn).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "✅ 已点击'$sendText'按钮")
                    showToast("评论已发送")
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
     * 点击参与按钮（备用方法）
     */
    private fun clickParticipateButton() {
        val rootNode = rootInActiveWindow ?: return
        
        try {
            // 查找"去发表评论"/"立即参与"/"参与"按钮
            val participateTexts = listOf("去发表评论", "立即参与", "参与", "抢福袋")
            for (text in participateTexts) {
                val buttons = rootNode.findAccessibilityNodeInfosByText(text)
                if (buttons.isNotEmpty()) {
                    val btn = buttons[0]
                    val clickableBtn = if (btn.isClickable) btn else findClickableParent(btn)
                    (clickableBtn ?: btn).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "✅ 已点击'$text'按钮")
                    showToast("已点击参与按钮")
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
            action = "ACTION_STOP"
        }
        startService(intent)
        
        showToast("福袋助手已停止")
        Log.d(TAG, "福袋服务销毁")
    }

    /**
     * 范围扫描点击左上角区域（福袋图标和宝石图标在同一排）
     * 根据用户截图（HONOR V30 PRO 2400x1080），福袋在左上角一排
     * 扫描一排多个点，提高命中率
     */
    private fun clickFudaiByGesture() {
        // ⚠️ 关键检查：如果服务未运行，不执行点击
        if (!PreferencesManager.isServiceRunning(this)) {
            Log.d(TAG, "服务已暂停，不执行坐标点击")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "API < 24，不支持 dispatchGesture")
            return
        }

        // 检查冷却时间
        val now = System.currentTimeMillis()
        if (now - lastClickTime < CLICK_COOLDOWN_MS) {
            Log.d(TAG, "冷却中，跳过坐标点击")
            return
        }

        try {
            // 获取屏幕尺寸
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            display.getMetrics(metrics)
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            // 根据用户截图分析（HONOR V30 PRO 2400x1080）：
            // 福袋和宝石图标在左上角同一排
            // x范围：屏幕左侧 3% ~ 22%（约 30px ~ 240px）
            // y范围：屏幕顶部 9% ~ 15%（约 215px ~ 360px）
            val startX = screenWidth * 0.03f
            val endX = screenWidth * 0.22f
            val centerY = screenHeight * 0.12f  // 一排图标的中间位置

            // 生成一排点击点（从左到右，每隔一段距离点一次）
            val clickPoints = listOf(
                Pair(startX, centerY),                        // 最左边
                Pair(startX + (endX - startX) * 0.25f, centerY), // 左1
                Pair(startX + (endX - startX) * 0.50f, centerY), // 中间
                Pair(startX + (endX - startX) * 0.75f, centerY), // 右1
                Pair(endX, centerY)                           // 最右边
            )

            Log.d(TAG, "开始范围扫描点击，屏幕: ${screenWidth}x${screenHeight}, " +
                       "区域: [${startX.toInt()},${centerY.toInt()}] ~ [${endX.toInt()},${centerY.toInt()}]")

            // 依次点击一排位置
            CoroutineScope(Dispatchers.IO).launch {
                for ((index, point) in clickPoints.withIndex()) {
                    // 检查服务状态
                    if (!PreferencesManager.isServiceRunning(this@FudaiAccessibilityService)) {
                        Log.d(TAG, "服务已暂停，停止扫描")
                        return@launch
                    }

                    val (x, y) = point
                    Log.d(TAG, "[扫描点击 ${index + 1}/${clickPoints.size}] 坐标: ($x, $y)")

                    // 创建点击手势
                    val path = Path()
                    path.moveTo(x, y)
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                        .build()

                    dispatchGesture(gesture, null, null)

                    // 更新最后点击时间
                    lastClickTime = System.currentTimeMillis()

                    // 每次点击后等待弹窗响应
                    delay(1200)

                    // 检查是否弹出了福袋窗口（有评论按钮或倒计时）
                    val popupRoot = rootInActiveWindow
                    if (popupRoot != null) {
                        val commentBtn = findNodeByTexts(popupRoot,
                            listOf("去发表评论", "一键发表评论", "立即参与", "参与", "抢福袋"))
                        
                        // 提取倒计时
                        val countdown = extractCountdownFromScreen()
                        
                        if (countdown > 0) {
                            Log.d(TAG, "[扫描点击 ${index + 1}] 检测到倒计时: ${countdown}秒")
                            
                            if (countdown > TRIGGER_TIME_REMAINING) {
                                // 倒计时 > 3分钟，等待
                                Log.d(TAG, "倒计时 ${countdown}秒 > ${TRIGGER_TIME_REMAINING}秒，等待...")
                                showToast("福袋倒计时 ${countdown}秒，等待至3分钟内...")
                                
                                // 关闭弹窗（点击返回或空白区域）
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                delay(1000)
                                
                                // 等待后重新扫描
                                delay(COUNTDOWN_CHECK_INTERVAL)
                                continue // 继续扫描下一个点
                            } else {
                                // 倒计时 ≤ 3分钟，立即参与
                                Log.d(TAG, "倒计时 ${countdown}秒 ≤ ${TRIGGER_TIME_REMAINING}秒，立即参与！")
                                showToast("倒计时 ${countdown}秒，立即参与福袋！")
                                
                                if (commentBtn != null) {
                                    Log.d(TAG, "[扫描点击 ${index + 1}] 命中福袋弹窗！")
                                    commentBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    delay(1500)
                                    val comment = extractCommentFromScreen()
                                    if (comment.isNotEmpty()) {
                                        inputComment(comment)
                                        delay(500)
                                        clickSendButton()
                                    }
                                } else {
                                    // 没有评论按钮，可能直接可以参与
                                    Log.d(TAG, "未找到评论按钮，尝试直接参与")
                                }
                                
                                popupRoot.recycle()
                                return@launch // 命中后退出扫描
                            }
                        } else if (commentBtn != null) {
                            // 没有检测到倒计时，但有评论按钮，直接参与
                            Log.d(TAG, "[扫描点击 ${index + 1}] 命中福袋弹窗！(无倒计时)")
                            commentBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            delay(1500)
                            val comment = extractCommentFromScreen()
                            if (comment.isNotEmpty()) {
                                inputComment(comment)
                                delay(500)
                                clickSendButton()
                            }
                            popupRoot.recycle()
                            return@launch // 命中后退出扫描
                        }
                        
                        popupRoot.recycle()
                    }
                }

                Log.d(TAG, "范围扫描完成，未命中福袋")
                showToast("扫描完成，未发现福袋")
            }

        } catch (e: Exception) {
            Log.e(TAG, "范围扫描点击失败", e)
        }
    }
}
