package com.example.livefudai

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import timber.log.Timber

class ClickSimulator(private val service: AccessibilityService) {

    fun click(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path()
            path.moveTo(x.toFloat(), y.toFloat())
            
            val gestureDescription = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            
            service.dispatchGesture(gestureDescription, null, null)
            Timber.d("点击: $x, $y")
        }
    }
}
