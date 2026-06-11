package com.example.livefudai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台服务 - 保持后台运行（Android 8.0+ 要求）
 */
class FudaiForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "fudai_foreground_channel"
        private const val NOTIFICATION_ID = 10001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundService()
    }

    /**
     * 创建通知渠道（Android 8.0+ 要求）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "福袋助手前台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于保持福袋助手后台运行"
            }

            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 启动前台服务
     */
    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("福袋助手运行中")
            .setContentText("正在监听直播间福袋...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details) // 使用系统默认图标
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
