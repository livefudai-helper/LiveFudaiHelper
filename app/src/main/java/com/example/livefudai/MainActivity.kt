package com.example.livefudai

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var screenshotButton: Button
    private lateinit var screenshotStatus: TextView
    private lateinit var blindClickButton: Button
    private lateinit var blindClickStatus: TextView
    private lateinit var testButton: Button

    private val REQUEST_SCREENSHOT_PERMISSION = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        enableButton = findViewById(R.id.enable_button)
        screenshotButton = findViewById(R.id.screenshot_button)
        screenshotStatus = findViewById(R.id.screenshot_status)
        blindClickButton = findViewById(R.id.blind_click_button)
        blindClickStatus = findViewById(R.id.blind_click_status)
        testButton = findViewById(R.id.test_button)

        Timber.d("MainActivity 启动 - v26 图像识别版")

        enableButton.setOnClickListener {
            openAccessibilitySettings()
        }

        screenshotButton.setOnClickListener {
            requestScreenshotPermission()
        }

        blindClickButton.setOnClickListener {
            toggleBlindClick()
        }

        testButton.setOnClickListener {
            testOCR()
        }

        updateStatus()
        updateScreenshotStatus()
        updateBlindClickStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateScreenshotStatus()
        updateBlindClickStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_SCREENSHOT_PERMISSION) {
            try {
                val screenshotManager = ScreenshotManager.handlePermissionResult(this, resultCode, data)
                if (screenshotManager != null) {
                    // 把截图管理器传给服务
                    FudaiAccessibilityService.screenshotManager = screenshotManager
                    Toast.makeText(this, "✅ 截图识别已开启", Toast.LENGTH_SHORT).show()
                    Timber.d("截图权限获取成功")
                } else {
                    Toast.makeText(this, "❌ 截图识别初始化失败，将使用文字检测模式", Toast.LENGTH_LONG).show()
                    Timber.w("截图权限获取失败或初始化失败")
                }
            } catch (e: Exception) {
                Toast.makeText(this, "❌ 截图识别出错: ${e.message}", Toast.LENGTH_LONG).show()
                Timber.e(e, "处理截图权限结果异常")
            }
            updateScreenshotStatus()
        }
    }

    private fun updateStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        statusText.text = if (isEnabled) "✅ 无障碍服务已开启" else "❌ 无障碍服务未开启"
        statusText.setTextColor(if (isEnabled) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
    }

    private fun updateScreenshotStatus() {
        val isEnabled = FudaiAccessibilityService.screenshotManager != null
        screenshotStatus.text = if (isEnabled) "截图识别：已开启" else "截图识别：未开启"
        screenshotStatus.setTextColor(if (isEnabled) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
    }

    private fun toggleBlindClick() {
        FudaiAccessibilityService.enableBlindClick = !FudaiAccessibilityService.enableBlindClick
        val isEnabled = FudaiAccessibilityService.enableBlindClick
        Toast.makeText(this, if (isEnabled) "✅ 盲点击模式已开启" else "❌ 盲点击模式已关闭", Toast.LENGTH_SHORT).show()
        updateBlindClickStatus()
    }

    private fun updateBlindClickStatus() {
        val isEnabled = FudaiAccessibilityService.enableBlindClick
        blindClickStatus.text = if (isEnabled) "盲点击：已开启" else "盲点击：未开启"
        blindClickStatus.setTextColor(if (isEnabled) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
        blindClickButton.text = if (isEnabled) "关闭盲点击模式" else "开启盲点击模式"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = packageName + "/" + FudaiAccessibilityService::class.java.name
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(service) == true
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "请找到「福袋助手」并开启", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun requestScreenshotPermission() {
        Toast.makeText(this, "请允许屏幕截图权限", Toast.LENGTH_LONG).show()
        ScreenshotManager.requestPermission(this, REQUEST_SCREENSHOT_PERMISSION)
    }

    private fun testOCR() {
        Toast.makeText(this, "开始测试 OCR...", Toast.LENGTH_SHORT).show()
        val ocrManager = OCRManager()
        ocrManager.recognizeText("测试倒计时: 00:19") { result ->
            runOnUiThread {
                Toast.makeText(this, "OCR 结果: $result", Toast.LENGTH_LONG).show()
            }
        }
    }
}
