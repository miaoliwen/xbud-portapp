package com.yg.xbudport.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

// 诊断导出：把本进程 logcat（main + crash 缓冲）、卡死/崩溃 dump、
// 设备与构建信息打成一个文本文件，经系统分享面板发出。
// 真机出问题时不需要连 adb 也能拿到现场——「我的 → 开发者选项」一键导出。
object Diagnostics {

    fun export(context: Context): File {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "xbud-diag-${System.currentTimeMillis()}.txt")
        file.writeText(buildString {
            appendLine("=== 小步点 诊断导出 ${java.util.Date()} ===")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("版本: ${com.yg.xbudport.BuildConfig.VERSION_NAME} (${com.yg.xbudport.BuildConfig.VERSION_CODE})")
            appendLine("密度: ${context.resources.displayMetrics.density}  屏幕: ${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")
            appendLine()

            // 卡死/崩溃 dump（XbudApp 看门狗与崩溃捕获写出的全线程栈）
            for (name in listOf("hang_dump.txt", "crash_dump.txt")) {
                val f = File(context.filesDir, name)
                if (f.exists()) {
                    appendLine("=== $name ===")
                    append(f.readText())
                    appendLine()
                }
            }

            appendLine("=== logcat (pid=${android.os.Process.myPid()}, main+crash) ===")
            append(runLogcat(android.os.Process.myPid()))
        })
        return file
    }

    private fun runLogcat(pid: Int): String = runCatching {
        val pb = ProcessBuilder(
            "logcat", "-d", "-v", "time",
            "-b", "main", "-b", "crash",
            "--pid=$pid",
        )
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    }.getOrElse { "logcat 读取失败: $it" }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "小步点诊断日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
