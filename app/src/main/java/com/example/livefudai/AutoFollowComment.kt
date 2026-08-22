package com.example.livefudai

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import timber.log.Timber
import kotlin.random.Random

/**
 * 自动关注 / 评论 / 点赞 / 粉丝团 执行器
 *
 * 移植自「福多多」核心动作逻辑：
 *   - LB0/d;.e0()  福袋参与主流程（关注 / 评论 / 点赞 / 粉丝团 / 参与）
 *   - LB0/d;.g()   直播间点赞 / 评论概率触发（养号）
 *   - Lh1/h;.c0()  节点点击；Lh1/h;.b0() 坐标点击兜底
 *   - node.setText() 无障碍填字（ACTION_SET_TEXT），对应福多多 d0/j.setText
 *
 * 设计要点：
 *   1. 优先 node.performAction(ACTION_CLICK)，失败再用坐标手势兜底（与福多多一致）。
 *   2. 评论用 ACTION_SET_TEXT 直接填字，再点「发送」，避免调起输入法。
 *   3. 概率触发仅用于「直播间养号随机动作」，福袋任务要求则强制完成（不概率门控）。
 *   4. 每日上限（关注/超级/抖币）由本类计数，进程内有效。
 */
class AutoFollowComment(private val service: FudaiAccessibilityService) {

    // 每日计数（进程内存级，重启清零；如需跨重启持久化可后续用 SharedPreferences 记录日期+次数）
    var followCountToday = 0
    var superFudaiCountToday = 0
    var coinFudaiCountToday = 0

    private val clickSimulator = ClickSimulator(service)

    /**
     * 执行福袋任务集（移植自 .e0 的任务分发段）
     * @param root    详情页根节点
     * @param tasks   已解析的任务需求
     * @return true 表示已尝试处理（无论单项是否成功）
     */
    fun completeTasks(root: AccessibilityNodeInfo?, tasks: FudaiTaskSet): Boolean {
        if (root == null) return false

        // 1) 关注（任务要求则强制；受每日上限约束）
        if (tasks.needFollow) {
            if (!FudaiConfig.enableFollow) {
                Timber.d("任务要求关注，但关注开关关闭，可能无法参与")
            } else if (followCountToday >= FudaiConfig.maxDailyFollow) {
                Timber.w("关注已达每日上限(${FudaiConfig.maxDailyFollow})，跳过")
            } else if (doFollow(root)) {
                followCountToday++
                sleep(FudaiConfig.dwellBeforeLikeFollowSec)
            }
        }

        // 2) 评论
        if (tasks.needComment && FudaiConfig.enableComment) {
            doComment(root)
            sleep(FudaiConfig.likeCommentDwellSec)
        }

        // 3) 点赞
        if (tasks.needLike && FudaiConfig.enableLike) {
            doLike(root)
            sleep(FudaiConfig.likeCommentDwellSec)
        }

        // 4) 粉丝团（未点亮才点）
        if (tasks.needFansClub && FudaiConfig.enableFansClub && !tasks.isFansClubLit) {
            doFansClub(root)
            sleep(FudaiConfig.dwellBeforeLikeFollowSec)
        }

        // 5) 观看任务：需真实停留，脚本无法加速（诚实提示）
        if (tasks.needWatch) {
            Timber.d("检测到观看任务 ${tasks.watchMinutes} 分钟，需真实停留，脚本不加速")
        }

        return true
    }

    /**
     * 直播间养号随机动作（移植自 .g 的概率触发）
     * 仅按概率触发点赞/评论，不影响福袋参与。
     */
    fun farmingTick(root: AccessibilityNodeInfo?) {
        if (root == null) return
        if (FudaiConfig.enableLike && Random.nextInt(100) < FudaiConfig.likeTriggerProbability) {
            doLike(root)
        }
        if (FudaiConfig.enableComment && Random.nextInt(100) < FudaiConfig.commentTriggerProbability) {
            if (followCountToday < FudaiConfig.maxDailyFollow) {
                doComment(root)
            }
        }
    }

    // ===================== 具体动作 =====================

    /** 关注主播：找「关注」按钮（排除已关注/互相关注/我的关注） */
    fun doFollow(root: AccessibilityNodeInfo): Boolean {
        val node = findClickableByText(root, "关注") { text ->
            !text.contains("已关注") && !text.contains("互相关注") && !text.contains("我的关注")
        } ?: return false
        return if (clickNode(node)) {
            Timber.d("已点击关注")
            true
        } else false
    }

    /** 发表评论：输入框 setText 随机评论 → 点「发送」 */
    fun doComment(root: AccessibilityNodeInfo): Boolean {
        val comments = FudaiConfig.commentList()
        val text = comments.random()
        val input = findCommentInput(root) ?: run {
            Timber.w("未找到评论输入框")
            return false
        }
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
            Timber.w("评论输入 setText 失败")
            return false
        }
        sleep(1)
        val send = findClickableByText(root, "发送") ?: findClickableByText(root, "发布")
        return if (send != null && clickNode(send)) {
            Timber.d("已发送评论: $text")
            true
        } else {
            Timber.w("未找到发送按钮")
            false
        }
    }

    /** 点赞：找「点赞/赞」按钮（排除纯数字计数） */
    fun doLike(root: AccessibilityNodeInfo): Boolean {
        val node = findClickableByText(root, "点赞") { !isPureNumber(it) }
            ?: findClickableByText(root, "赞") { !isPureNumber(it) }
        return if (node != null && clickNode(node)) {
            Timber.d("已点赞")
            true
        } else {
            Timber.w("未找到点赞按钮")
            false
        }
    }

    /** 加入粉丝团 */
    fun doFansClub(root: AccessibilityNodeInfo): Boolean {
        val node = findClickableByText(root, "加入粉丝团") ?: findClickableByText(root, "粉丝团")
        return if (node != null && clickNode(node)) {
            Timber.d("已点击加入粉丝团")
            true
        } else {
            Timber.w("未找到加入粉丝团按钮")
            false
        }
    }

    /** 点击参与福袋（命中其一即可） */
    fun clickParticipate(root: AccessibilityNodeInfo): Boolean {
        val keywords = listOf("参与福袋", "参与抽奖", "立即参与", "发送评论", "一键发表评论")
        for (k in keywords) {
            val node = findClickableByText(root, k)
            if (node != null && clickNode(node)) {
                Timber.d("已点击参与: $k")
                return true
            }
        }
        return false
    }

    /** 返回（详情页→直播间）：优先 back_btn/close 资源，其次「返回/关闭」文字 */
    fun clickBack(root: AccessibilityNodeInfo): Boolean {
        val byId = findNodeByViewId(root, "back_btn") ?: findNodeByViewId(root, "close")
        if (byId != null && clickNode(byId)) return true
        val byText = findClickableByText(root, "返回") ?: findClickableByText(root, "关闭")
        return byText != null && clickNode(byText)
    }

    // ===================== 节点查找工具 =====================

    private fun findClickableByText(
        root: AccessibilityNodeInfo,
        text: String,
        filter: ((String) -> Boolean)? = null
    ): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun traverse(node: AccessibilityNodeInfo) {
            if (found != null) return
            val t = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
            if (t.contains(text) && (filter == null || filter(t))) {
                // 向上找可点击祖先（与福多多点击节点逻辑一致）
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable && clickable.parent != null) {
                    clickable = clickable.parent
                }
                if (clickable != null && clickable.isClickable) {
                    found = clickable
                    return
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { traverse(it) }
            }
        }
        traverse(root)
        return found
    }

    private fun findNodeByViewId(root: AccessibilityNodeInfo, idSuffix: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun traverse(node: AccessibilityNodeInfo) {
            if (found != null) return
            val vid = node.viewIdResourceName ?: ""
            if (vid.endsWith(idSuffix)) {
                found = node
                return
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { traverse(it) }
            }
        }
        traverse(root)
        return found
    }

    private fun findCommentInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun traverse(node: AccessibilityNodeInfo) {
            if (found != null) return
            val cls = node.className?.toString() ?: ""
            val hint = AccessibilityNodeInfoCompat.wrap(node).hintText?.toString() ?: ""
            val vid = node.viewIdResourceName ?: ""
            val isEdit = cls.contains("EditText")
            val hintOk = hint.contains("说点什么") || hint.contains("评论") ||
                    hint.contains("聊一聊") || hint.contains("友善")
            val idOk = vid.contains("et") || vid.contains("comment") || vid.contains("input")
            if (isEdit || hintOk || idOk) {
                found = node
                return
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { traverse(it) }
            }
        }
        traverse(root)
        return found
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // 坐标手势兜底（福多多 Lh1/h.b0/c0）
        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (e: Exception) {
            return false
        }
        if (rect.width() > 0 && rect.height() > 0) {
            clickSimulator.click(rect.centerX(), rect.centerY())
            return true
        }
        return false
    }

    private fun isPureNumber(s: String): Boolean {
        return s.matches(Regex("\\d+(\\.\\d+)?(万)?")) ||
                s.matches(Regex("\\d+\\.\\d+[wk]?"))
    }

    private fun sleep(seconds: Int) {
        if (seconds <= 0) return
        try {
            Thread.sleep((seconds * 1000).toLong())
        } catch (e: InterruptedException) {
            // ignore
        }
    }
}
