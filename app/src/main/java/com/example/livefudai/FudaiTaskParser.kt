package com.example.livefudai

import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * 福袋任务需求解析器
 *
 * 移植自「福多多」LB0/d.J(ArrayList,String) / LB0/d.K(ArrayList,String,String)：
 * 福多多 把详情页所有节点文字收集成 ArrayList，再用 J/K 判断是否包含某任务关键词。
 * 这里等价地收集 root 下全部 text/contentDescription，再做子串判断。
 *
 * 抖音福袋详情页常见任务文案：
 *   - "关注主播"            → 需要关注
 *   - "发表评论" / "发送评论" → 需要评论
 *   - "点赞视频" / "点赞"    → 需要点赞
 *   - "加入粉丝团" / "直播粉丝团" → 需要粉丝团
 *   - "观看直播 5 分钟"      → 需要观看
 * 参与按钮文案："参与福袋" / "参与抽奖" / "立即参与" / "发送评论"
 */
data class FudaiTaskSet(
    val needFollow: Boolean,
    val needComment: Boolean,
    val needLike: Boolean,
    val needFansClub: Boolean,
    val needWatch: Boolean,
    val watchMinutes: Int,
    /** 粉丝团是否已点亮（已达成则无需再点） */
    val isFansClubLit: Boolean,
    /** 当前页面是否存在「参与」类按钮（用于判定是否在福袋详情页） */
    val hasParticipateButton: Boolean,
    /** 全部节点文字（调试用） */
    val allTexts: List<String>
) {
    val isEmpty: Boolean
        get() = !needFollow && !needComment && !needLike && !needFansClub && !needWatch
}

object FudaiTaskParser {

    /** 参与按钮候选文案（命中任一即认为在详情页且可参与） */
    private val PARTICIPATE_KEYWORDS = listOf("参与福袋", "参与抽奖", "立即参与", "发送评论", "一键发表评论")

    /** 收集根节点下所有文字 + contentDescription */
    fun collectTexts(root: AccessibilityNodeInfo?): List<String> {
        val out = mutableListOf<String>()
        if (root == null) return out
        fun traverse(node: AccessibilityNodeInfo) {
            node.text?.let { if (it.isNotBlank()) out.add(it.toString()) }
            node.contentDescription?.let { if (it.isNotBlank()) out.add(it.toString()) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { traverse(it) }
            }
        }
        traverse(root)
        return out
    }

    /**
     * 从当前页面节点解析福袋任务需求
     */
    fun parse(root: AccessibilityNodeInfo?): FudaiTaskSet {
        val texts = collectTexts(root)
        val joined = texts.joinToString(" ")
        val lower = joined

        val needFollow = lower.contains("关注") &&
                !lower.contains("已关注") &&
                !lower.contains("互相关注")
        val needComment = lower.contains("评论")
        val needLike = lower.contains("点赞")
        val needFansClub = lower.contains("粉丝团")
        val isFansClubLit = lower.contains("粉丝团") && lower.contains("已点亮")
        val needWatch = lower.contains("观看") && lower.contains("分钟")

        // 提取观看分钟数：匹配 "X分钟" 里的数字
        var watchMinutes = 0
        if (needWatch) {
            val m = Regex("(\\d+)\\s*分钟").find(joined)
            watchMinutes = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        }

        val hasParticipateButton = PARTICIPATE_KEYWORDS.any { joined.contains(it) }

        if (texts.isNotEmpty()) {
            Timber.d("任务解析: 关注=$needFollow 评论=$needComment 点赞=$needLike 粉丝团=$needFansClub(已亮=$isFansClubLit) 观看=$needWatch(${watchMinutes}分) 参与按钮=$hasParticipateButton")
        }

        return FudaiTaskSet(
            needFollow = needFollow,
            needComment = needComment,
            needLike = needLike,
            needFansClub = needFansClub,
            needWatch = needWatch,
            watchMinutes = watchMinutes,
            isFansClubLit = isFansClubLit,
            hasParticipateButton = hasParticipateButton,
            allTexts = texts
        )
    }

    /** 是否处于福袋详情页（有参与按钮即视为详情页） */
    fun isDetailPage(tasks: FudaiTaskSet): Boolean = tasks.hasParticipateButton
}
