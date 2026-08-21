package com.example.livefudai

import android.content.Context
import android.content.SharedPreferences

/**
 * 福袋助手运行配置（移植自「福多多」设置项）
 *
 * 字段命名对齐福多多 UI 文案，默认值参考其常见默认配置：
 *   - 直播间点赞触发概率（默认 50%）
 *   - 直播间评论触发概率（默认 30%）
 *   - 每日最多关注 / 加入粉丝团（默认 20）
 *   - 超级福袋每日最多参与（默认 5）
 *   - 抖币福袋每日最多参与（默认 10）
 *   - 评论内容（每句以 / 隔开）
 *
 * 通过 SharedPreferences 持久化，MainActivity 的开关直接读写本对象。
 */
object FudaiConfig {

    private const val PREFS_NAME = "fudai_config"
    private lateinit var prefs: SharedPreferences

    // ===== 检测参数 =====
    /** 模板匹配最低相似度（0.0~1.0） */
    var fudaiMinScore: Float = 0.70f

    // ===== 每日参与上限（移植自「超级福袋每日最多参与」「抖币福袋每日最多参与」「每日最多关注/加入粉丝团」）=====
    var maxDailySuperFudai: Int = 5
    var maxDailyCoinFudai: Int = 10
    var maxDailyFollow: Int = 20

    // ===== 任务开关（是否自动完成对应动作）=====
    var enableFollow: Boolean = true      // 自动关注主播
    var enableComment: Boolean = true     // 自动发表评论
    var enableLike: Boolean = true        // 自动点赞
    var enableFansClub: Boolean = false   // 是否加粉丝团（默认关，避免误扣钻石）
    var spendDiamondFansClub: Boolean = false // 是否花费钻石进粉丝团

    // ===== 概率触发（移植自「直播间点赞触发概率」「直播间评论触发概率」）=====
    var likeTriggerProbability: Int = 50    // 直播间点赞触发概率 %
    var commentTriggerProbability: Int = 30 // 直播间评论触发概率 %

    // ===== 行为参数 =====
    var likeCommentDwellSec: Int = 3        // 点赞/评论停留时间(秒)（移植自「点赞/评论停留时间(秒)」）
    var dwellBeforeLikeFollowSec: Int = 2   // 每次点赞/关注前需要停留(秒)（移植自「每次点赞/关注前需要停留多少秒」）
    var minWinProbabilityCoin: Int = 0      // 抖币福袋中奖概率要求（低于则放弃）

    // ===== 评论内容（每句以 / 隔开，移植自「评论内容(每句以/隔开)」）=====
    var commentContents: String = "主播好棒/666/支持一下/好喜欢这个主播/已关注"

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    fun load() {
        if (!::prefs.isInitialized) return
        fudaiMinScore = prefs.getFloat("fudaiMinScore", fudaiMinScore)
        maxDailySuperFudai = prefs.getInt("maxDailySuperFudai", maxDailySuperFudai)
        maxDailyCoinFudai = prefs.getInt("maxDailyCoinFudai", maxDailyCoinFudai)
        maxDailyFollow = prefs.getInt("maxDailyFollow", maxDailyFollow)
        enableFollow = prefs.getBoolean("enableFollow", enableFollow)
        enableComment = prefs.getBoolean("enableComment", enableComment)
        enableLike = prefs.getBoolean("enableLike", enableLike)
        enableFansClub = prefs.getBoolean("enableFansClub", enableFansClub)
        spendDiamondFansClub = prefs.getBoolean("spendDiamondFansClub", spendDiamondFansClub)
        likeTriggerProbability = prefs.getInt("likeTriggerProbability", likeTriggerProbability)
        commentTriggerProbability = prefs.getInt("commentTriggerProbability", commentTriggerProbability)
        likeCommentDwellSec = prefs.getInt("likeCommentDwellSec", likeCommentDwellSec)
        dwellBeforeLikeFollowSec = prefs.getInt("dwellBeforeLikeFollowSec", dwellBeforeLikeFollowSec)
        minWinProbabilityCoin = prefs.getInt("minWinProbabilityCoin", minWinProbabilityCoin)
        commentContents = prefs.getString("commentContents", commentContents) ?: commentContents
    }

    fun save() {
        if (!::prefs.isInitialized) return
        prefs.edit().apply {
            putFloat("fudaiMinScore", fudaiMinScore)
            putInt("maxDailySuperFudai", maxDailySuperFudai)
            putInt("maxDailyCoinFudai", maxDailyCoinFudai)
            putInt("maxDailyFollow", maxDailyFollow)
            putBoolean("enableFollow", enableFollow)
            putBoolean("enableComment", enableComment)
            putBoolean("enableLike", enableLike)
            putBoolean("enableFansClub", enableFansClub)
            putBoolean("spendDiamondFansClub", spendDiamondFansClub)
            putInt("likeTriggerProbability", likeTriggerProbability)
            putInt("commentTriggerProbability", commentTriggerProbability)
            putInt("likeCommentDwellSec", likeCommentDwellSec)
            putInt("dwellBeforeLikeFollowSec", dwellBeforeLikeFollowSec)
            putInt("minWinProbabilityCoin", minWinProbabilityCoin)
            putString("commentContents", commentContents)
            apply()
        }
    }

    /** 把 / 隔开的评论内容拆成列表（移植自福多多「评论内容(每句以/隔开)」） */
    fun commentList(): List<String> {
        return commentContents.split("/")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("666") }
    }
}
