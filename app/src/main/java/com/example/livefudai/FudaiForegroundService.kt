package com.example.livefudai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台服务 - 保持应用在后台运行
 * Android 8.0+ 要求后台服务必须显示通知
 */
class FudaiForegroundService : Service() {
    
    companion object {
        private const val TAG = "FudaiForegroundService"
        private const val CHANNEL_ID = "fudai_service_channel"
        private const val NOTIFICATION_ID = 10001
        
        // 服务动作
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "前台服务创建")
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        
        when (action) {
            ACTION_START -> {
                Log.d(TAG, "启动前台服务")
                startForegroundService()
            }
            
            ACTION_STOP -> {
                Log.d(TAG, "停止前台服务")
                stopForeground(true)
                stopSelf()
            }
        }
        
        return START_STICKY // 服务被杀死后会自动重启
    }
    
    /**
     * 启动前台服务（显示通知）
     */
    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "前台服务已启动")
    }
    
    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "福袋助手服务",
                NotificationManager.IMPORTANCE_LOW // 低优先级，不发出声音
            ).apply {
                description = "福袋自动参与服务正在运行"
                setShowBadge(false) // 不显示角标
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            
            Log.d(TAG, "通知渠道已创建")
        }
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher) // 需要添加图标资源
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // 不可滑动删除
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
    
    /**
     * 更新通知内容（可选，用于显示状态）
     */
    fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "前台服务销毁")
    }
}
