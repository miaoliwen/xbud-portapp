package com.yg.xbudport.run

import android.content.Context

// 跑步引擎单例：Activity/Compose 页面只做展示，切 tab 或锁屏时引擎由前台服务保活。
object RunSession {
    @Volatile
    private var engine: RunEngine? = null

    fun engine(ctx: Context): RunEngine = synchronized(this) {
        engine ?: RunEngine(ctx.applicationContext).also { engine = it }
    }

    fun current(): RunEngine? = engine

    fun reset() = synchronized(this) {
        engine?.dispose()
        engine = null
    }
}
