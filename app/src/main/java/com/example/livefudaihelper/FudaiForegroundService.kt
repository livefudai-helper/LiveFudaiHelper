package com.example.livefudaihelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class FudaiForegroundService : Service() {

    companion object {
        private const val TAG = "FudaiForegroundService"
        private const val CHANNEL_ID = "fudai_channel"
        private const val NOTIFICATION_ID = 10001

        private var instance: FudaiForegroundService? = null

        fun getInstance(): FudaiForegroundService? = instance
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")
        instance = this
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动")
        startForeground(NOTIFICATION_ID, buildNotification("福袋助手已启动，点击「开始运行」"))
        startFudaiScanning()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")
        instance = null
        stopFudaiScanning()
        serviceScope.cancel()
    }

    /**
     * 创建通知渠道（Android 8.0+需要）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "福袋助手通知",
                NotificationManager.IMPORTANCE_LOW  // 低优先级，不打扰用户
            ).apply {
                description = "福袋助手运行状态通知"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 构建通知
     */
    private fun buildNotification(status: String): Notification {
        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("福袋助手")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)

        return notificationBuilder.build()
    }

    /**
     * 更新通知内容
     */
    fun updateNotification(status: String) {
        if (!::notificationBuilder.isInitialized) {
            buildNotification(status)
        }

        notificationBuilder.setContentText(status)
        val notification = notificationBuilder.build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 启动福袋扫描
     */
    private fun startFudaiScanning() {
        Log.d(TAG, "启动福袋扫描")

        // 启动无障碍服务中的扫描逻辑
        val service = FudaiAccessibilityService()
        // 注意：这里不能直接创建Service实例
        // 实际是通过无障碍服务自己管理的
        // 这里只是启动前台服务，无障碍服务会在onAccessibilityEvent中工作

        updateNotification("正在运行 - 等待进入直播间...")
    }

    /**
     * 停止福袋扫描
     */
    private fun stopFudaiScanning() {
        Log.d(TAG, "停止福袋扫描")
        updateNotification("已停止")
    }
}
