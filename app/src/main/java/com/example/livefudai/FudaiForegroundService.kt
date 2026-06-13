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
 * 
 * 通知会显示当前状态：运行中/已暂停
 */
class FudaiForegroundService : Service() {
    
    companion object {
        private const val TAG = "FudaiForegroundService"
        private const val CHANNEL_ID = "fudai_service_channel"
        private const val NOTIFICATION_ID = 10001
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "前台服务创建")
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "ACTION_START"
        
        when (action) {
            "ACTION_START" -> {
                Log.d(TAG, "启动前台服务")
                startForegroundService()
            }
            
            "ACTION_STOP" -> {
                Log.d(TAG, "停止前台服务")
                stopForeground(true)
                stopSelf()
            }
            
            "ACTION_UPDATE_NOTIFICATION" -> {
                Log.d(TAG, "更新通知状态")
                updateNotification()
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
                description = "福袋自动参与服务状态"
                setShowBadge(false) // 不显示角标
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            
            Log.d(TAG, "通知渠道已创建")
        }
    }
    
    /**
     * 创建通知
     * 根据 PreferencesManager 的状态显示"运行中"或"已暂停"
     */
    private fun createNotification(): Notification {
        val isRunning = PreferencesManager.isServiceRunning(this)
        val statusText = if (isRunning) "🟢 运行中，正在监听福袋..." else "🟡 已暂停，点击"开始运行"激活"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("福袋自动助手")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details) // 使用系统默认图标
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // 不可滑动删除
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
    
    /**
     * 更新通知内容（当运行状态改变时调用）
     */
    fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "通知已更新")
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "前台服务销毁")
    }
}
