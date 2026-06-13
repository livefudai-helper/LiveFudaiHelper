package com.example.livefudai

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.example.livefudai.databinding.ActivityMainBinding
import kotlinx.coroutines.*

/**
 * 主界面 - 配置和启动福袋自动参与服务
 * 
 * 使用说明：
 * 1. 首先开启无障碍服务（系统设置）
 * 2. 然后点击"开始运行"按钮，服务才会真正工作
 * 3. 不需要时点击"暂停运行"，服务会停止监听
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private lateinit var binding: ActivityMainBinding
    private var isAccessibilityEnabled = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        updateUI()
    }
    
    override fun onResume() {
        super.onResume()
        // 每次回到界面都检查状态
        checkAccessibilityStatus()
        updateUI()
    }
    
    /**
     * 设置UI事件
     */
    private fun setupUI() {
        // 开始/暂停按钮（核心功能）
        binding.btnToggleRunning.setOnClickListener {
            if (!isAccessibilityEnabled) {
                // 无障碍服务未开启，先引导用户开启
                openAccessibilitySettings()
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            
            // 切换运行状态
            val isRunning = PreferencesManager.toggleServiceRunning(this)
            
            if (isRunning) {
                Toast.makeText(this, "福袋助手已开始运行，打开抖音即可自动抢福袋", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "福袋助手已暂停", Toast.LENGTH_SHORT).show()
            }
            
            // 更新通知栏状态
            updateNotification()
            
            updateUI()
        }
        
        // 无障碍服务设置按钮
        binding.btnAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }
        
        // 使用说明
        binding.btnHelp.setOnClickListener {
            showHelpDialog()
        }
        
        // 测试OCR
        binding.btnTestOCR.setOnClickListener {
            testOCR()
        }
        
        // 检查更新
        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdates()
        }
    }
    
    /**
     * 检查无障碍服务状态
     */
    private fun checkAccessibilityStatus() {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        
        isAccessibilityEnabled = enabledServices.any { serviceInfo ->
            serviceInfo.resolveInfo.serviceInfo.packageName == packageName &&
            serviceInfo.resolveInfo.serviceInfo.name.contains("FudaiAccessibilityService")
        }
    }
    
    /**
     * 更新UI状态
     */
    private fun updateUI() {
        val isRunning = PreferencesManager.isServiceRunning(this)
        
        // 更新无障碍服务状态
        if (isAccessibilityEnabled) {
            binding.tvAccessibilityStatus.text = "✅ 无障碍服务已开启"
            binding.tvAccessibilityStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.btnAccessibilitySettings.text = "重新设置无障碍服务"
        } else {
            binding.tvAccessibilityStatus.text = "❌ 无障碍服务未开启"
            binding.tvAccessibilityStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            binding.btnAccessibilitySettings.text = "开启无障碍服务"
        }
        
        // 更新运行状态
        if (isRunning && isAccessibilityEnabled) {
            binding.tvRunningStatus.text = "🟢 运行中"
            binding.tvRunningStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.btnToggleRunning.text = "⏸ 暂停运行"
            binding.btnToggleRunning.backgroundTintList = getColorStateList(android.R.color.holo_orange_dark)
            binding.layoutStatus.isVisible = true
            binding.tvStatusDetail.text = "服务正在监听福袋，打开抖音进入直播间即可自动参与"
        } else if (!isAccessibilityEnabled) {
            binding.tvRunningStatus.text = "⚪ 未就绪"
            binding.tvRunningStatus.setTextColor(getColor(android.R.color.darker_gray))
            binding.btnToggleRunning.text = "⚠️ 请先开启无障碍服务"
            binding.btnToggleRunning.backgroundTintList = getColorStateList(android.R.color.darker_gray)
            binding.layoutStatus.isVisible = false
        } else {
            binding.tvRunningStatus.text = "🟡 已暂停"
            binding.tvRunningStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
            binding.btnToggleRunning.text = "▶️ 开始运行"
            binding.btnToggleRunning.backgroundTintList = getColorStateList(android.R.color.holo_green_dark)
            binding.layoutStatus.isVisible = true
            binding.tvStatusDetail.text = "服务已暂停，点击"开始运行"后才会自动抢福袋"
        }
    }
    
    /**
     * 更新通知栏状态
     */
    private fun updateNotification() {
        val intent = Intent(this, FudaiForegroundService::class.java).apply {
            action = "ACTION_UPDATE_NOTIFICATION"
        }
        startService(intent)
    }
    
    /**
     * 打开无障碍设置
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            
            Toast.makeText(this, "找到「福袋助手」并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // 有些设备可能不支持直接跳转，使用备用方案
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "请手动进入: 设置 > 无障碍 > 福袋助手", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 显示使用说明
     */
    private fun showHelpDialog() {
        val helpText = """
            📖 使用说明
            
            🚀 快速开始
            1. 点击"开启无障碍服务"按钮
            2. 在系统设置中找到"福袋助手"并开启
            3. 回到本应用，点击"开始运行"
            4. 打开抖音，进入直播间
            5. 看到福袋后，软件会自动参与
            
            ⚙️ 运行控制
            • 开始运行：服务开始监听福袋
            • 暂停运行：服务停止监听（无障碍服务仍开启）
            
            💡 使用技巧
            • 不需要时记得点"暂停运行"
            • 保持屏幕常亮，不要锁屏
            • 建议充电时使用
            • 过度使用可能被平台检测
            
            ⚠️ 免责声明
            本工具仅供学习交流，请遵守平台规则
            因使用本工具导致的账号问题概不负责
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("使用说明")
            .setMessage(helpText)
            .setPositiveButton("知道了", null)
            .show()
    }
    
    /**
     * 测试OCR功能
     */
    private fun testOCR() {
        Toast.makeText(this, "正在测试OCR配置...", Toast.LENGTH_SHORT).show()
        
        // 创建OCR管理器并测试初始化
        val ocrManager = OCRManager(this)
        
        // 显示测试结果
        Toast.makeText(this, "OCR初始化完成，请查看Logcat日志", Toast.LENGTH_LONG).show()
        
        // 提示用户如何查看日志
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("✅ OCR配置已更新")
            .setMessage("""
                API Key: LfAuoZ7ESwrdb4uHELPEskB
                Secret Key: zM48dwXfX01RE6idOOASnCswD0KoSX15
                
                已成功配置到 OCRManager.kt
                
                查看日志命令：
                adb logcat | grep OCRManager
            """.trimIndent())
            .setPositiveButton("知道了", null)
            .show()
    }
    
    /**
     * 检查更新
     */
    private fun checkForUpdates() {
        Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show()
        
        val updateManager = UpdateManager(this)
        
        updateManager.checkUpdate { hasUpdate, latestRelease, error ->
            runOnUiThread {
                if (error != null) {
                    Toast.makeText(this, "检查更新失败: $error", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                
                if (hasUpdate && latestRelease != null) {
                    // 显示更新对话框
                    showUpdateDialog(updateManager, latestRelease)
                } else {
                    Toast.makeText(this, "已经是最新版本", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 显示更新对话框
     */
    private fun showUpdateDialog(updateManager: UpdateManager, release: GitHubRelease) {
        val message = """
            🆕 发现新版本：${release.tagName}
            
            📅 发布时间：${release.publishedAt}
            
            📝 更新内容：
            ${release.body}
            
            📦 文件大小：${formatFileSize(release.assets.firstOrNull()?.size ?: 0)}
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔄 发现新版本")
            .setMessage(message)
            .setPositiveButton("立即更新") { _, _ ->
                // 开始下载
                downloadAndInstallUpdate(updateManager, release)
            }
            .setNegativeButton("稍后再说", null)
            .setCancelable(false)
            .show()
    }
    
    /**
     * 下载并安装更新
     */
    private fun downloadAndInstallUpdate(updateManager: UpdateManager, release: GitHubRelease) {
        // 显示下载进度对话框
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("正在下载更新")
            setMessage("请稍候...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 100
            show()
        }
        
        updateManager.downloadAndInstall(release) { progress, error ->
            runOnUiThread {
                if (error != null) {
                    progressDialog.dismiss()
                    Toast.makeText(this, "下载失败: $error", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                
                if (progress >= 0) {
                    // 更新进度
                    progressDialog.progress = progress
                    progressDialog.setMessage("下载中... $progress%")
                } else {
                    // 下载完成，开始安装
                    progressDialog.setMessage("下载完成，正在安装...")
                }
            }
        }
    }
    
    /**
     * 格式化文件大小
     */
    private fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        
        return String.format("%.2f %s", size, units[unitIndex])
    }
}
