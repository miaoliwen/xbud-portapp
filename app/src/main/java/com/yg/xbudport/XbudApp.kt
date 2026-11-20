package com.yg.xbudport

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.yg.xbudport.data.AccountStore
import com.yg.xbudport.data.SecretStore
import com.yg.xbudport.data.SessionLease
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// XbudApp 进程入口：先从加密资产解出高德 key 并注入 SDK，
// 再同意隐私合规（2021 后版本的强制行为，顺序不能反）。
// 最后挂会话租约：进程活着就每 5 分钟续跳，app 被删除后服务端租约过期
// 自动踢号，会话不卡死。
class XbudApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            SecretStore.amapKey(this)?.let { key ->
                MapsInitializer.setApiKey(key)
                AMapLocationClient.setApiKey(key)
            }
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
            AMapLocationClient.updatePrivacyShow(this, true, true)
            AMapLocationClient.updatePrivacyAgree(this, true)
        } catch (_: Throwable) {
            // SDK 缺失/版本差异不阻塞 App 启动；地图页会自动回退到轨迹画布
        }
        runCatching { SessionLease.start(this, AccountStore(this)) }
        installCrashCatcher()
        installHangWatchdog()
    }

    // —— 诊断期临时设施：主线程卡死/崩溃的全线程栈落盘，下次启动全屏展示 ——

    private fun installCrashCatcher() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(filesDir, "crash_dump.txt").writeText(
                    "CRASH at ${System.currentTimeMillis()} thread=${t.name}\n\n" + e.stackTraceToString()
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun installHangWatchdog() {
        val main = Looper.getMainLooper()
        Thread({
            while (true) {
                Thread.sleep(2000)
                val latch = CountDownLatch(1)
                runCatching { Handler(main).post { latch.countDown() } }
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    runCatching {
                        File(filesDir, "hang_dump.txt").writeText(buildString {
                            append("HANG at ${System.currentTimeMillis()}\n\n")
                            Thread.getAllStackTraces().forEach { (th, st) ->
                                append("=== ${th.name} state=${th.state} ===\n")
                                st.forEach { append("    at $it\n") }
                                append("\n")
                            }
                        })
                    }
                }
            }
        }, "hang-watchdog").apply { isDaemon = true }.start()
    }

    companion object {
        fun dumpText(ctx: android.content.Context): String? {
            val crash = File(ctx.filesDir, "crash_dump.txt")
            val hang = File(ctx.filesDir, "hang_dump.txt")
            return when {
                hang.exists() -> hang.readText()
                crash.exists() -> crash.readText()
                else -> null
            }
        }

        fun clearDumps(ctx: android.content.Context) {
            runCatching { File(ctx.filesDir, "crash_dump.txt").delete() }
            runCatching { File(ctx.filesDir, "hang_dump.txt").delete() }
        }
    }
}
