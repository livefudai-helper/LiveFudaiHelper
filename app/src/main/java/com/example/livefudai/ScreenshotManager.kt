package com.example.livefudai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * 截图管理器
 * 使用MediaProjection API截取屏幕
 */
class ScreenshotManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    /**
     * 初始化截图功能
     * 需要先通过Activity申请权限
     */
    fun setup(mediaProjection: MediaProjection) {
        this.mediaProjection = mediaProjection

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        Timber.d("屏幕尺寸: ${screenWidth}x${screenHeight}, density: $screenDensity")

        // 创建ImageReader
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        // 创建VirtualDisplay
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenshotDisplay",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            Handler(Looper.getMainLooper())
        )

        Timber.d("ScreenshotManager 初始化完成")
    }

    /**
     * 截取屏幕
     * @return 屏幕截图Bitmap，失败返回null
     */
    fun takeScreenshot(): Bitmap? {
        val imageReader = this.imageReader ?: return null
        val mediaProjection = this.mediaProjection ?: return null

        try {
            // 获取最新的一帧
            val image: Image = imageReader.acquireLatestImage() ?: return null

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            // 创建Bitmap
            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 裁剪掉padding部分
            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

            image.close()

            return croppedBitmap
        } catch (e: Exception) {
            Timber.e(e, "截图失败")
            return null
        }
    }

    /**
     * 截取指定区域的屏幕
     * @param x 区域左上角X坐标
     * @param y 区域左上角Y坐标
     * @param width 区域宽度
     * @param height 区域高度
     * @return 区域截图Bitmap，失败返回null
     */
    fun takeScreenshotRegion(x: Int, y: Int, width: Int, height: Int): Bitmap? {
        val fullBitmap = takeScreenshot() ?: return null

        // 确保坐标在范围内
        val safeX = x.coerceAtLeast(0)
        val safeY = y.coerceAtLeast(0)
        val safeWidth = width.coerceAtMost(fullBitmap.width - safeX)
        val safeHeight = height.coerceAtMost(fullBitmap.height - safeY)

        if (safeWidth <= 0 || safeHeight <= 0) return null

        return Bitmap.createBitmap(fullBitmap, safeX, safeY, safeWidth, safeHeight)
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
            Timber.d("ScreenshotManager 已释放")
        } catch (e: Exception) {
            Timber.e(e, "释放ScreenshotManager失败")
        }
    }

    companion object {
        /**
         * 申请截图权限
         * @param activity Activity实例
         * @param requestCode 请求码
         */
        fun requestPermission(activity: Activity, requestCode: Int) {
            val mediaProjectionManager =
                activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            activity.startActivityForResult(captureIntent, requestCode)
        }

        /**
         * 处理权限申请结果
         * @param context Context
         * @param resultCode 结果码
         * @param data Intent数据
         * @return ScreenshotManager实例，失败返回null
         */
        fun handlePermissionResult(context: Context, resultCode: Int, data: Intent?): ScreenshotManager? {
            if (resultCode != Activity.RESULT_OK || data == null) {
                Timber.w("截图权限被拒绝")
                return null
            }

            val mediaProjectionManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            val screenshotManager = ScreenshotManager(context)
            screenshotManager.setup(mediaProjection)

            return screenshotManager
        }
    }
}
