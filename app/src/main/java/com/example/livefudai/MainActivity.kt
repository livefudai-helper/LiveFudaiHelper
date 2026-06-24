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
    private lateinit var testButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        enableButton = findViewById(R.id.enable_button)
        testButton = findViewById(R.id.test_button)

        Timber.d("MainActivity 启动 - v25")

        enableButton.setOnClickListener {
            openAccessibilitySettings()
        }

        testButton.setOnClickListener {
            testOCR()
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        statusText.text = if (isEnabled) "✅ 无障碍服务已开启" else "❌ 无障碍服务未开启"
        statusText.setTextColor(if (isEnabled) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
    val am = getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK)
    for (service in enabledServices) {
        if (service.resolveInfo.serviceInfo.packageName == packageName &&
            service.resolveInfo.serviceInfo.name == FudaiAccessibilityService::class.java.name) {
            return true
        }
    }
    return false
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "请找到「福袋助手」并开启", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
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
