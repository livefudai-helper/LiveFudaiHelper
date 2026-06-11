# ProGuard规则 - 福袋自动助手

# 基础规则
-keep class com.example.livefudai.** { *; }

# 百度OCR SDK (如果使用)
-keep class com.baidu.ocr.** { *; }
-keep class com.baidu.aip.** { *; }

# OkHttp (百度OCR依赖)
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Kotlin协程
-keepclassmembers class kotlinx.coroutines.* { *; }

# AccessibilityService (重要！不能混淆)
-keep public class * extends android.accessibilityservice.AccessibilityService {
    public <init>(...);
    public void onAccessibilityEvent(...);
    public void onServiceConnected(...);
    public void onInterrupt(...);
}

# 保留注解
-keepattributes *Annotation*

# 保留泛型
-keepattributes Signature

# ViewBinding
-keep class * implements androidx.viewbinding.ViewBinding {
    *;
}

# Material Components
-keep class com.google.android.material.** { *; }

# 移除日志 (release版本)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# 优化
-optimizationpasses 5
-dontpreverify
-verbose
