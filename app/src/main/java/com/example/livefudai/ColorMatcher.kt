package com.example.livefudai

import android.graphics.Bitmap
import android.graphics.Rect
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

/**
 * 颜色匹配器
 * 通过颜色特征在截图中找到福袋位置
 */
class ColorMatcher {

    // 紫色的RGB范围（福袋图标是紫色的）
    private val purpleMinRed = 120
    private val purpleMaxRed = 255
    private val purpleMinGreen = 30
    private val purpleMaxGreen = 160
    private val purpleMinBlue = 150
    private val purpleMaxBlue = 255

    // 滑动窗口大小（福袋图标的大致尺寸）
    private val windowSize = 120

    // 滑动步长
    private val stepSize = 10

    // 最小紫色像素数量（低于这个值认为不是福袋）
    private val minPurplePixels = 500

    /**
     * 在指定区域内找福袋位置
     * @param bitmap 截图
     * @param searchRect 搜索区域
     * @return 福袋中心坐标，如果没找到返回null
     */
    fun findFudai(bitmap: Bitmap, searchRect: Rect): Pair<Int, Int>? {
        if (bitmap.width == 0 || bitmap.height == 0) return null

        // 确保搜索区域在图片范围内
        val left = searchRect.left.coerceAtLeast(0)
        val top = searchRect.top.coerceAtLeast(0)
        val right = searchRect.right.coerceAtMost(bitmap.width)
        val bottom = searchRect.bottom.coerceAtMost(bitmap.height)

        if (right - left < windowSize || bottom - top < windowSize) {
            Timber.w("搜索区域太小: ${right-left}x${bottom-top}")
            return null
        }

        var maxPurpleCount = 0
        var bestCenterX = 0
        var bestCenterY = 0

        // 滑动窗口遍历
        var x = left
        while (x + windowSize <= right) {
            var y = top
            while (y + windowSize <= bottom) {
                // 计算这个窗口内的紫色像素数量
                val purpleCount = countPurplePixels(bitmap, x, y, windowSize, windowSize)

                if (purpleCount > maxPurpleCount) {
                    maxPurpleCount = purpleCount
                    bestCenterX = x + windowSize / 2
                    bestCenterY = y + windowSize / 2
                }

                y += stepSize
            }
            x += stepSize
        }

        if (maxPurpleCount >= minPurplePixels) {
            Timber.d("找到福袋，紫色像素数: $maxPurpleCount, 坐标: ($bestCenterX, $bestCenterY)")
            return Pair(bestCenterX, bestCenterY)
        }

        Timber.d("未找到福袋，最大紫色像素数: $maxPurpleCount")
        return null
    }

    /**
     * 计算指定区域内的紫色像素数量
     */
    private fun countPurplePixels(
        bitmap: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Int {
        var count = 0

        val safeWidth = min(x + width, bitmap.width) - x
        val safeHeight = min(y + height, bitmap.height) - y

        if (safeWidth <= 0 || safeHeight <= 0) return 0

        // 为了提高性能，每隔几个像素采样一次
        val sampleStep = 2

        var sampleY = 0
        while (sampleY < safeHeight) {
            var sampleX = 0
            while (sampleX < safeWidth) {
                val pixel = bitmap.getPixel(x + sampleX, y + sampleY)
                if (isPurple(pixel)) {
                    count++
                }
                sampleX += sampleStep
            }
            sampleY += sampleStep
        }

        // 因为采样了，所以乘以采样步长的平方来估算总数
        return count * sampleStep * sampleStep
    }

    /**
     * 判断一个像素是不是紫色
     */
    private fun isPurple(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF

        // 紫色的判断条件：红和蓝比较高，绿比较低
        // 并且红色和蓝色的值比较接近
        val isPurpleRange = r in purpleMinRed..purpleMaxRed &&
                g in purpleMinGreen..purpleMaxGreen &&
                b in purpleMinBlue..purpleMaxBlue

        // 红色和蓝色不能差太多（否则可能是红色或蓝色）
        val rbDiff = Math.abs(r - b)
        val isRbClose = rbDiff < 80

        // 红色和蓝色都要明显高于绿色
        val isHigherThanGreen = (r - g) > 30 && (b - g) > 30

        return isPurpleRange && isRbClose && isHigherThanGreen
    }

    /**
     * 调整紫色阈值（用于调试）
     */
    fun adjustPurpleThreshold(
        minRed: Int? = null,
        maxRed: Int? = null,
        minGreen: Int? = null,
        maxGreen: Int? = null,
        minBlue: Int? = null,
        maxBlue: Int? = null
    ) {
        minRed?.let { purpleMinRed = it }
        maxRed?.let { purpleMaxRed = it }
        minGreen?.let { purpleMinGreen = it }
        maxGreen?.let { purpleMaxGreen = it }
        minBlue?.let { purpleMinBlue = it }
        maxBlue?.let { purpleMaxBlue = it }
    }
}
