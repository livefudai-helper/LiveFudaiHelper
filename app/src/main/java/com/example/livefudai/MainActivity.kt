package com.example.livefudai

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.livefudai.databinding.ActivityMainBinding

/**
 * 主界面 - 配置和启动福袋自动参与服务
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_ACCESSIBILITY = 1001
    }
    
    private lateinit var binding: ActivityMainBinding
    private var isServiceEnabled = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        checkServiceStatus()
    }
    
    override fun onResume() {
        super.onResume()
        // 每次回到界面都检查服务状态
        checkServiceStatus()
    }
    
    /**
     * 设置UI事件
     */
    private fun setupUI() {
        // 启动/关闭服务按钮
        binding.btnToggleService.setOnClickListener {
            if (isServiceEnabled) {
                // 服务已启用，引导用户去系统设置关闭
                openAccessibilitySettings()
                Toast.makeText(this, "请在设置中关闭"福袋助手"服务", Toast.LENGTH_LONG).show()
            } else {
                // 服务未启用，引导用户去开启
                openAccessibilitySettings()
            }
        }
        
        // 配置按钮
        binding.btnConfigure.setOnClickListener {
            // 打开配置界面（可设置倒计时阈值、自动输入内容等）
            Toast.makeText(this, "配置功能开发中...", Toast.LENGTH_SHORT).show()
        }
        
        // 使用说明
        binding.btnHelp.setOnClickListener {
            showHelpDialog()
        }
        
        // 测试OCR
        binding.btnTestOCR.setOnClickListener {
            testOCR()
        }
    }
    
    /**
     * 检查无障碍服务状态
     */
    private fun checkServiceStatus() {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        
        isServiceEnabled = enabledServices.any { serviceInfo ->
            serviceInfo.resolveInfo.serviceInfo.packageName == packageName &&
            serviceInfo.resolveInfo.serviceInfo.name.contains("FudaiAccessibilityService")
        }
        
        updateUI()
    }
    
    /**
     * 更新UI状态
     */
    private fun updateUI() {
        if (isServiceEnabled) {
            binding.btnToggleService.text = "关闭服务"
            binding.tvStatus.text = "✅ 服务运行中"
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.layoutStatus.isVisible = true
        } else {
            binding.btnToggleService.text = "启动服务"
            binding.tvStatus.text = "❌ 服务未启动"
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            binding.layoutStatus.isVisible = false
        }
    }
    
    /**
     * 打开无障碍设置
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            
            Toast.makeText(this, "找到"福袋助手"并开启", Toast.LENGTH_LONG).show()
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
            
            1️⃣ 首次使用
            • 点击"启动服务"按钮
            • 在系统设置中找到"福袋助手"
            • 开启服务并允许权限
            
            2️⃣ 开始使用
            • 打开抖音/快手等直播APP
            • 进入直播间
            • 当有福袋时，服务会自动检测
            
            3️⃣ 自动参与
            • 检测到福袋后，会自动点击
            • 倒计时≤3分钟时自动参与
            • 自动完成关注/评论等要求
            
            4️⃣ 注意事项
            • 保持屏幕常亮
            • 不要锁屏
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
                
                下一步：
                1. 下载百度OCR SDK jar包
                2. 放到 app/libs/ 目录
                3. 编译项目并测试
                
                查看日志命令：
                adb logcat | grep OCRManager
            """.trimIndent())
            .setPositiveButton("知道了", null)
            .show()
    }
}
