package com.yg.xbudport.run

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yg.xbudport.MainActivity

// 学分跑前台服务：只负责把进程保活并显示常驻通知；定位/计步仍在 RunSession 引擎里，
// 这样切 tab、锁屏后轨迹继续采，回到页面直接接上。
class RunService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (RunSession.current() == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // location 型前台服务在 Android 14+ 要求启动瞬间仍持有定位权限（缺则
        // SecurityException），Android 12+ 还禁止后台启动（ForegroundServiceStart
        // NotAllowedException）。两者都是进程级未捕获异常，必须自己兜住并退栈。
        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                },
            )
        } catch (_: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "学分跑",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "跑步进行中的定位与轨迹记录"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("学分跑进行中")
        .setContentText("正在记录定位与轨迹，返回应用查看")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "xbud_run"
        private const val NOTIFICATION_ID = 4101
    }
}
