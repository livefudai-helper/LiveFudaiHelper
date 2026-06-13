package com.example.livefudaihelper

import android.app.Application
import com.jakewharton.timber.Timber

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化Timber日志
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
    }

    /**
     * 发布版的日志树（不输出日志）
     */
    class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // 发布版不输出日志
        }
    }
}
