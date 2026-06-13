package com.example.livefudaihelper

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_OVERLAY = 1001
        private const val REQUEST_CODE_ACCESSIBILITY = 1002
        private const val REQUEST_CODE_STORAGE = 1003
    }

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvGuide: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
        updateStatus()
    }

    private fun initViews() {
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvGuide = findViewById(R.id.tvGuide)

        btnStart.setOnClickListener {
            if (checkAccessibilityService()) {
                startFudaiService()
            } else {
                openAccessibilitySettings()
            }
        }

        btnStop.setOnClickListener {
            stopFudaiService()
        }

        // 显示使用说明
        tvGuide.text = """
            🎯 福袋助手使用说明：

            1. 打开直播APP（抖音、快手等）
            2. 进入直播间列表页面
            3. 点击「开始运行」按钮
            4. 保持屏幕开启，不要操作手机
            5. 助手会自动识别左上角的福袋（有倒计时）
            6. 每10秒扫描一次，30秒没找到会自动切换下一个直播间

            ⚠️ 注意事项：
            - 需要开启无障碍服务和悬浮窗权限
            - 保持手机联网（OCR识别需要网络）
            - 建议充电时使用
            
            🔄 自动更新：
            - 每次启动会自动检查更新
            - 也可以点击「检查更新」按钮手动检查
            - 发现新版本会自动下载并安装
        """.trimIndent()

        // 添加检查更新按钮
        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        btnCheckUpdate.setOnClickListener {
            checkForUpdate()
        }
    }

    private fun startFudaiService() {
        // 启动前台服务
        val serviceIntent = Intent(this, FudaiForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(this, "福袋助手已启动", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun stopFudaiService() {
        val serviceIntent = Intent(this, FudaiForegroundService::class.java)
        stopService(serviceIntent)

        Toast.makeText(this, "福袋助手已停止", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun updateStatus() {
        val isServiceRunning = FudaiForegroundService.getInstance() != null
        val isAccessibilityEnabled = checkAccessibilityService()

        when {
            !isAccessibilityEnabled -> {
                tvStatus.text = "⚠️ 状态：无障碍服务未开启"
                btnStart.isEnabled = false
                btnStop.isEnabled = false
            }
            !isServiceRunning -> {
                tvStatus.text = "⏸️ 状态：已就绪（点击开始运行）"
                btnStart.isEnabled = true
                btnStop.isEnabled = false
            }
            else -> {
                tvStatus.text = "▶️ 状态：正在运行中..."
                btnStart.isEnabled = false
                btnStop.isEnabled = true
            }
        }
    }

    private fun checkAccessibilityService(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "请开启「福袋助手」无障碍服务", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun checkPermissions() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            }
        }

        // 检查存储权限（Android 10以下需要）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    REQUEST_CODE_STORAGE
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        
        // 每次回到前台，自动检查更新（静默检查，不提示）
        checkForUpdateSilent()
    }
    
    /**
     * 手动检查更新（有提示）
     */
    private fun checkForUpdate() {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            packageInfo.versionCode
        }
        
        UpdateManager.checkForUpdate(
            context = this,
            currentVersionCode = currentVersionCode,
            onUpdateAvailable = { versionName, downloadUrl, releaseNotes ->
                // 发现新版本，显示对话框
                UpdateManager.showUpdateDialogAndDownload(this, versionName, downloadUrl, releaseNotes)
            },
            onNoUpdate = {
                Toast.makeText(this, "已经是最新版本", Toast.LENGTH_SHORT).show()
            },
            onError = { errorMsg ->
                Toast.makeText(this, "检查更新失败: $errorMsg", Toast.LENGTH_LONG).show()
            }
        )
    }
    
    /**
     * 自动检查更新（静默模式，只在发现新版本时提示）
     */
    private fun checkForUpdateSilent() {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            packageInfo.versionCode
        }
        
        UpdateManager.checkForUpdate(
            context = this,
            currentVersionCode = currentVersionCode,
            onUpdateAvailable = { versionName, downloadUrl, releaseNotes ->
                // 发现新版本，显示对话框
                UpdateManager.showUpdateDialogAndDownload(this, versionName, downloadUrl, releaseNotes)
            },
            onNoUpdate = {
                // 静默模式，不提示
            },
            onError = { errorMsg ->
                // 静默模式，不提示错误
                Log.w("MainActivity", "静默检查更新失败: $errorMsg")
            }
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
                }
            }
        }
        updateStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "存储权限已开启", Toast.LENGTH_SHORT).show()
            }
        }
        updateStatus()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 注销更新广播接收器
        UpdateManager.unregisterReceiver(this)
    }
}
