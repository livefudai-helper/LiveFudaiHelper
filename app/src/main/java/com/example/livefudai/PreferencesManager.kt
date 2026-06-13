package com.example.livefudai

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置管理器 - 管理应用的所有配置项
 */
object PreferencesManager {
    
    private const val PREF_NAME = "fudai_preferences"
    private const val KEY_SERVICE_RUNNING = "service_running"
    private const val KEY_LAST_PACKAGE = "last_package"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 设置服务运行状态
     * @param running true=运行中, false=已暂停
     */
    fun setServiceRunning(context: Context, running: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SERVICE_RUNNING, running).apply()
    }
    
    /**
     * 获取服务运行状态
     */
    fun isServiceRunning(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SERVICE_RUNNING, false)
    }
    
    /**
     * 切换运行状态
     * @return 切换后的状态
     */
    fun toggleServiceRunning(context: Context): Boolean {
        val current = isServiceRunning(context)
        val newState = !current
        setServiceRunning(context, newState)
        return newState
    }
}
