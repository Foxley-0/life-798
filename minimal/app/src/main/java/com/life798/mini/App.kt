package com.life798.mini

import android.app.Application
import com.life798.mini.core.AppLog

/**
 * 应用入口。
 *
 * 只做一件事：初始化应用内日志（含崩溃捕获）。
 * 不初始化任何第三方 SDK：无统计、无推送、无广告、无崩溃上报。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}
