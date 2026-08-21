package com.example.livefudai.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import timber.log.Timber
import kotlin.math.min

/**
 * 福袋模板匹配器
 *
 * 技术路线（参考"福多多"反编译结果）：
 *   1. 加载多张 PNG 模板（assets/templates/）
 *   2. 对目标 Bitmap 转灰度
 *   3. 滑动窗口 + SAD 绝对误差和算法找最佳匹配点
 *   4. 主模板（find.png）做多尺度匹配（0.7~1.4 倍率），兼容不同分辨率
 *
 * 为什么不引入 OpenCV native：
 *   - org.opencv:opencv-android:4.8.0 在 Maven 上不存在
 *   - 装 OpenCV Manager 又太折腾
 *   - Android Bitmap + 像素遍历完全够用，匹配延迟 ~30ms（1080P 截图 + 50×50 模板）
 *
 * 核心思路（来自福多多反汇编）：
 *   ImageBlurMatcher.e() → Imgproc.matchTemplate + Core.minMaxLoc
 *   我们用纯 Kotlin 实现同样的功能
 */
class TemplateMatcher(private val context: Context) {

    /**
     * 匹配结果
     * @param x 命中点在原图中的 x 坐标
     * @param y 命中点在原图中的 y 坐标
     * @param score 相似度分数，0.0~1.0，越大越像（1.0 = 完全一致）
     * @param templateName 命中的模板名（用于区分福袋等级）
     * @param scale 命中的缩放比例（多尺度匹配时）
     */
    data class MatchResult(
        val x: Int,
        val y: Int,
        val score: Float,
        val templateName: String,
        val scale: Float = 1.0f
    )

    /**
     * 模板数据结构（预加载后缓存）
     */
    private data class Template(
        val name: String,
        val bitmap: Bitmap,
        val grayPixels: IntArray,  // 灰度像素数组
        val width: Int,
        val height: Int
    )

    private val templates = mutableListOf<Template>()
    private var isLoaded = false

    /**
     * 加载所有模板（在 Service 启动时调用一次）
     * 模板文件名映射：
     *   find.png       主福袋（用于精确定位）
     *   normal.png     普通福袋
     *   super.png      钻石福袋
     *   super2.png     钻石福袋变种
     *   redreward.png  红包类
     *   musupper.png   上升光柱
     *   exsupper.png   上升光柱变种
     */
    fun loadTemplates() {
        if (isLoaded) return
        try {
            val templateFiles = listOf(
                "templates/find.png",
                "templates/normal.png",
                "templates/super.png",
                "templates/super2.png",
                "templates/redreward.png",
                "templates/musupper.png",
                "templates/exsupper.png"
            )
            for (path in templateFiles) {
                val bmp = BitmapFactory.decodeStream(context.assets.open(path))
                if (bmp == null) {
                    Timber.w("模板加载失败: $path")
                    continue
                }
                val name = path.substringAfterLast('/')
                val gray = toGrayPixels(bmp)
                templates.add(
                    Template(
                        name = name,
                        bitmap = bmp,
                        grayPixels = gray,
                        width = bmp.width,
                        height = bmp.height
                    )
                )
                Timber.d("模板已加载: $name (${bmp.width}x${bmp.height})")
            }
            isLoaded = true
            Timber.d("共加载 ${templates.size} 个模板")
        } catch (e: Exception) {
            Timber.e(e, "模板加载异常")
        }
    }

    /**
     * 在目标图片中找福袋
     * @param source 原始截图（一般取左上角 30%×25% 区域）
     * @param searchRegion 搜索区域（一般是 Rect(0,0,source.width,source.height)）
     * @param minScore 最低分数阈值，默认 0.7（70% 相似度）
     * @return 最佳匹配结果，没有找到返回 null
     */
    fun findFudai(
        source: Bitmap,
        searchRegion: Rect = Rect(0, 0, source.width, source.height),
        minScore: Float = 0.70f
    ): MatchResult? {
        if (!isLoaded) {
            Timber.w("模板未加载，请先调用 loadTemplates()")
            return null
        }

        val srcGray = toGrayPixels(source)
        val srcW = source.width
        val srcH = source.height

        var bestResult: MatchResult? = null

        // 对每个模板做匹配
        for (template in templates) {
            // 多尺度匹配（仅主模板 find.png 全跑，其他用单尺度）
            val scales = if (template.name == "find.png") {
                floatArrayOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f)
            } else {
                floatArrayOf(1.0f)
            }

            for (scale in scales) {
                val tw = (template.width * scale).toInt()
                val th = (template.height * scale).toInt()

                // 模板尺寸必须 <= 源图尺寸
                if (tw > srcW || th > srcH) continue

                val scaledTemplate = if (scale == 1.0f) {
                    template.grayPixels
                } else {
                    // 缩放模板（最近邻，简单粗暴但够用）
                    scaleGray(template.grayPixels, template.width, template.height, tw, th)
                }

                val result = matchTemplate(
                    srcGray = srcGray,
                    srcW = srcW,
                    srcH = srcH,
                    template = scaledTemplate,
                    tw = tw,
                    th = th,
                    searchRegion = searchRegion,
                    minScore = minScore
                )

                if (result != null && (bestResult == null || result.score > bestResult.score)) {
                    bestResult = result.copy(templateName = template.name, scale = scale)
                }
            }
        }

        return bestResult
    }

    /**
     * SAD 模板匹配（核心算法）
     * 在源图中滑动窗口，计算每个位置与模板的绝对误差和
     * SAD 越小越像，归一化成 score = 1 - SAD / (maxPossibleSAD)
     */
    private fun matchTemplate(
        srcGray: IntArray,
        srcW: Int,
        srcH: Int,
        template: IntArray,
        tw: Int,
        th: Int,
        searchRegion: Rect,
        minScore: Float
    ): MatchResult? {
        val startX = searchRegion.left
        val startY = searchRegion.top
        val endX = min(srcW - tw, searchRegion.right)
        val endY = min(srcH - th, searchRegion.bottom)

        if (endX <= startX || endY <= startY) return null

        var bestX = -1
        var bestY = -1
        var bestSAD = Int.MAX_VALUE
        val maxPossibleSAD = tw * th * 255  // 最大误差

        // 步长（每隔 2 像素采样一次，8 倍加速，福多多用相似策略）
        val step = 2

        var y = startY
        while (y <= endY) {
            var x = startX
            while (x <= endX) {
                var sad = 0
                var ty = 0
                while (ty < th) {
                    var tx = 0
                    while (tx < tw) {
                        val srcIdx = (y + ty) * srcW + (x + tx)
                        val tplIdx = ty * tw + tx
                        val diff = srcGray[srcIdx] - template[tplIdx]
                        sad += if (diff < 0) -diff else diff
                        if (sad >= bestSAD) break  // 提前退出（SAD 已超过当前最佳）
                        tx += step  // 模板内也用步长加速
                    }
                    ty += step
                }

                if (sad < bestSAD) {
                    bestSAD = sad
                    bestX = x
                    bestY = y
                }

                x += step
            }
            y += step
        }

        if (bestX < 0) return null

        val score = 1.0f - (bestSAD.toFloat() / maxPossibleSAD)
        if (score < minScore) return null

        // 返回命中点的中心坐标（不是左上角）
        return MatchResult(
            x = bestX + tw / 2,
            y = bestY + th / 2,
            score = score,
            templateName = "",  // 会被外层填充
            scale = 1.0f
        )
    }

    /**
     * Bitmap 转灰度像素数组（0~255）
     * 加权公式：Gray = 0.299*R + 0.587*G + 0.114*B（与福多多一致）
     */
    private fun toGrayPixels(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // 转灰度（标准 ITU-R BT.601 加权）
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
        }
        return gray
    }

    /**
     * 最近邻缩放灰度图
     */
    private fun scaleGray(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        val dst = IntArray(dstW * dstH)
        val ratioX = srcW.toFloat() / dstW
        val ratioY = srcH.toFloat() / dstH
        for (y in 0 until dstH) {
            val srcY = (y * ratioY).toInt().coerceIn(0, srcH - 1)
            for (x in 0 until dstW) {
                val srcX = (x * ratioX).toInt().coerceIn(0, srcW - 1)
                dst[y * dstW + x] = src[srcY * srcW + srcX]
            }
        }
        return dst
    }

    /**
     * 释放模板资源
     */
    fun release() {
        templates.forEach { it.bitmap.recycle() }
        templates.clear()
        isLoaded = false
    }

    /**
     * 仅查找特定类型的福袋（比如只找钻石福袋）
     */
    fun findFudaiByType(
        source: Bitmap,
        templateName: String,
        minScore: Float = 0.70f
    ): MatchResult? {
        if (!isLoaded) return null
        val template = templates.firstOrNull { it.name == templateName } ?: return null

        val srcGray = toGrayPixels(source)
        val result = matchTemplate(
            srcGray = srcGray,
            srcW = source.width,
            srcH = source.height,
            template = template.grayPixels,
            tw = template.width,
            th = template.height,
            searchRegion = Rect(0, 0, source.width, source.height),
            minScore = minScore
        ) ?: return null

        return result.copy(templateName = template.name)
    }
}