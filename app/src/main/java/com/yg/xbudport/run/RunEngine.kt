package com.yg.xbudport.run

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

// RunEngine — 学分跑采集引擎：系统定位 2s 采样 + 计步传感器 + 打卡进圈判定。
//
// 计时 / 暂停与原版对齐（ex0.java:494-790 / fw0.java:162-220）：
//   - totalTime = 墙钟时长 − 累计暂停时长；暂停期间不计步、不采点；
//   - selMinRunTime 是第五项判定（原版 unCompleteReason=10）；
//   - unCompleteReason 码：1 全通过 / 2 距离 / 3 点位 / 9 配速或步频 / 10 时长；
//   - maxRunTime、pauseTime 到点自动结束。
//
// 天玑（MediaTek）适配：
//   - GNSS 卫星数优先走 GnssStatus 回调（MTK 的 Location.extras 常不带 satellites）；
//   - accuracy 为 0/缺省视为无效定位（MTK 网络定位会回 0，不能当高精度用）；
//   - STEP_COUNTER 缺失或恒 0 时退化为里程/步幅估算；
//   - 计步改为增量累加，传感器原始值回退/清零不会把累计值打回负或跳变。

data class RunSample(
    val t: Long,
    val lat: Double,
    val lng: Double,
    val speed: Double,
    val radius: Double,
    val sat: Int,
    val steps: Int,
)

data class Checkpoint(
    val key: Int,                 // 本次跑内的稳定槽位（0..n-1），不依赖 pid 是否归并成功
    val pid: Int,
    val id: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val isFixed: Int,
    val position: Int = 999,      // 原版 FixedPointTable.position，999 = 未参与排序
    val relatedPids: List<Int> = emptyList(),
)

data class PassRecord(val cp: Checkpoint, val position: Int)

/** 跑步规则阈值：来自 get_config.runRuleModel，缺省值与原 App 兜底一致。 */
data class RunLimits(
    val minDistanceM: Double = 1000.0,
    val minRunTimeSec: Int = 0,
    val speedTop: Double = 3.5,
    val speedBottom: Double = 10.0,
    val stepTop: Int = 300,
    val stepBottom: Int = 50,
    val maxRunTimeSec: Int = 0,
    val pauseTimeSec: Long = 0,
)

data class JudgeItem(
    val label: String,
    val pass: Boolean,
    val detail: String,
)

class RunEngine(private val ctx: Context) {
    var phase by mutableStateOf("ready") // ready | running | paused | finished
        private set
    var samples by mutableStateOf(listOf<RunSample>())
        private set
    var meters by mutableStateOf(0.0)
        private set
    var checkpoints by mutableStateOf(listOf<Checkpoint>())
        private set
    var passes by mutableStateOf(listOf<PassRecord>())
        private set
    var totalSteps by mutableStateOf(0)
        private set
    var locationNote by mutableStateOf("")
        private set
    var limits = RunLimits()
        private set

    /** 每秒刷新的已跑秒数——UI 直接读它，否则计时只在有 GPS 采样时才跳。 */
    var elapsedSec by mutableStateOf(0L)
        private set
    var pausedSeconds by mutableStateOf(0L)
        private set
    var weakGps by mutableStateOf(false)
        private set
    var stopReason by mutableStateOf<String?>(null)
        private set
    var satellites by mutableStateOf(0)
        private set

    var startEpoch = 0L
        private set
    var distanceKm = 1
        private set

    private var endEpoch = 0L
    private var pauseStartedAt = 0L
    private var pausedMs = 0L
    private var invalidStreak = 0
    private val handler = Handler(Looper.getMainLooper())
    private var tickerRunning = false
    private val ticker = object : Runnable {
        override fun run() {
            if (phase != "running" && phase != "paused") return
            elapsedSec = currentDurationSec()
            checkLimits()
            handler.postDelayed(this, 1000)
        }
    }

    private var locManager: LocationManager? = null
    private var locListener: LocationListener? = null
    private var gnssCallback: GnssStatus.Callback? = null
    private var lastFixTime = 0L
    private var lastValid: RunSample? = null

    private var sensorMgr: SensorManager? = null
    private var stepListener: SensorEventListener? = null

    // 计步：增量累加。baseline<0 表示等待下一次传感器事件重新起算（暂停恢复/重开跑）。
    @Volatile
    private var stepBaseline = -1f

    @Volatile
    private var stepTotal = 0

    fun currentDurationSec(): Long {
        if (startEpoch <= 0L) return 0L
        val now = if (endEpoch > 0L) endEpoch else System.currentTimeMillis()
        val extraPause = if (phase == "paused" && pauseStartedAt > 0L) now - pauseStartedAt else 0L
        return ((now - startEpoch - pausedMs - extraPause) / 1000L).coerceAtLeast(0L)
    }

    fun currentPoint(): Pair<Double, Double>? =
        lastValid?.let { it.lat to it.lng }

    /** 下一个待打卡点：按原版 position 升序（999=未排序，排在最后）。 */
    fun nextCheckpoint(): Checkpoint? {
        val remaining = checkpoints.filter { cp -> passes.none { it.cp.key == cp.key } }
        if (remaining.isEmpty()) return null
        return remaining.minWithOrNull(
            compareBy({ if (it.position >= 999) Int.MAX_VALUE else it.position }, { it.key })
        )
    }

    private fun markInvalidFix() {
        invalidStreak++
        if (invalidStreak >= 4) weakGps = true
    }

    private fun checkLimits() {
        if (startEpoch <= 0L) return
        if (phase == "running") {
            val max = limits.maxRunTimeSec
            if (max > 0 && currentDurationSec() >= max) {
                stopWithReason("达到最大跑步时长 ${max / 60} 分钟，已自动结束")
            }
        } else if (phase == "paused") {
            pausedSeconds = ((System.currentTimeMillis() - pauseStartedAt) / 1000L).coerceAtLeast(0L)
            val limit = limits.pauseTimeSec
            if (limit > 0 && pausedSeconds >= limit) {
                stopWithReason("暂停超过 ${limit / 60} 分钟，已自动结束")
            }
        }
    }

    private fun stopWithReason(reason: String) {
        stopReason = reason
        stop()
    }

    private fun stopTicker() {
        tickerRunning = false
        handler.removeCallbacks(ticker)
    }

    // —— 打卡判定：进圈半径 50 m（与 realrun.go 的 realValidRadius 同源）——
    private fun onFix(lat: Double, lng: Double, speed: Double, radius: Double, sat: Int) {
        if (phase != "running") return
        if (radius > 200.0) {
            locationNote = "定位漂移较大（${radius.roundToInt()} m），等待稳定…"
            return
        }

        val valid = radius <= 50.0 && (lat != 0.0 || lng != 0.0)
        if (valid) {
            invalidStreak = 0
            weakGps = false
            lastValid?.let { prev -> meters += haversine(prev.lat, prev.lng, lat, lng) }
        } else {
            markInvalidFix()
        }
        val steps = effectiveSteps()
        val t = currentDurationSec()
        val s = RunSample(t, lat, lng, speed, radius, sat, steps)
        samples = samples + s
        totalSteps = steps
        if (valid) lastValid = s

        locationNote = if (valid) {
            buildString {
                append("已定位 · 精度 ${radius.roundToInt()} m")
                if (sat > 0) append(" · 卫星 $sat")
            }
        } else {
            "定位信号弱（精度 ${radius.roundToInt()} m），等待 GPS…"
        }

        if (!valid) return
        var changed = false
        val added = ArrayList<PassRecord>(2)
        for (cp in checkpoints) {
            if (passes.any { it.cp.key == cp.key } || added.any { it.cp.key == cp.key }) continue
            if (haversine(cp.lat, cp.lon, lat, lng) < 50.0) {
                added.add(PassRecord(cp, added.size))
                changed = true
            }
        }
        if (changed) passes = passes + added
    }

    private fun onSystemLocation(location: Location) {
        val fixTime = if (location.time > 0L) location.time else System.currentTimeMillis()
        if (fixTime <= lastFixTime) return
        // MTK 网络定位会回 accuracy=0：当作无效，不能当高精度点入库
        val accuracy = if (location.hasAccuracy() && location.accuracy > 0f) {
            location.accuracy.toDouble()
        } else {
            9999.0
        }
        if (accuracy > 200.0) {
            markInvalidFix()
            locationNote = "定位漂移较大（${accuracy.roundToInt()} m），等待稳定…"
            return
        }
        lastFixTime = fixTime
        val (lat, lng) = Geo.wgs84ToGcj02(location.latitude, location.longitude)
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val sat = if (satellites > 0) satellites else satellitesOf(location)
        onFix(lat, lng, speed, accuracy, sat)
    }

    /** MTK 部分机型 Location.extras 不带 satellites，键名也不统一。 */
    private fun satellitesOf(location: Location): Int {
        val ex = location.extras ?: return 0
        for (k in SATELLITE_KEYS) {
            val v = runCatching { ex.getInt(k, 0) }.getOrDefault(0)
            if (v > 0) return v
        }
        return 0
    }

    private fun distanceSteps(): Int = (meters / STRIDE_M).toInt()

    private fun effectiveSteps(): Int = if (stepTotal > 0) stepTotal else distanceSteps()

    private fun hasLocationPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        stopLocationUpdates()
        if (!hasLocationPermission()) {
            locationNote = "缺少定位权限，请在系统设置中授权"
            return
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            locationNote = "系统定位服务不可用"
            return
        }
        locManager = lm
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            locationNote = "系统定位未开启（GPS / 网络定位均不可用）"
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onSystemLocation(location)

            override fun onProviderEnabled(provider: String) {
                locationNote = "定位服务已开启，等待定位…"
            }

            override fun onProviderDisabled(provider: String) {
                val anyEnabled = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .any { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
                if (!anyEnabled) locationNote = "系统定位已关闭"
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            providers.forEach {
                lm.requestLocationUpdates(it, SAMPLE_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            }
            locListener = listener
            locationNote = "定位中（${providers.joinToString("/") { it.substringBefore('_') }}）…"
        } catch (e: SecurityException) {
            locationNote = "缺少定位权限，请在系统设置中授权"
        } catch (e: Exception) {
            locationNote = "定位启动失败：${e.message ?: e}"
        }
        startGnssStatus(lm)
    }

    /** 卫星数走 GnssStatus：MTK GNSS 的 extras 不可靠，这个回调是稳定来源。 */
    private fun startGnssStatus(lm: LocationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val used = status.satelliteCount
                if (used > 0) satellites = used
            }
        }
        val ok = runCatching {
            lm.registerGnssStatusCallback(cb, handler)
        }.getOrDefault(false)
        if (ok) gnssCallback = cb
    }

    private fun stopLocationUpdates() {
        locListener?.let { listener ->
            locManager?.let { lm -> runCatching { lm.removeUpdates(listener) } }
        }
        locListener = null
        gnssCallback?.let { cb -> locManager?.let { lm -> runCatching { lm.unregisterGnssStatusCallback(cb) } } }
        gnssCallback = null
        satellites = 0
    }

    fun start(distanceKm: Int, checkpoints: List<Checkpoint>, limits: RunLimits = RunLimits()) {
        this.distanceKm = distanceKm
        this.checkpoints = checkpoints
        this.limits = limits
        samples = emptyList()
        passes = emptyList()
        meters = 0.0
        totalSteps = 0
        stepTotal = 0
        locationNote = "等待定位…"
        stopReason = null
        elapsedSec = 0L
        pausedSeconds = 0L
        weakGps = false
        invalidStreak = 0
        stepBaseline = -1f
        lastFixTime = 0L
        lastValid = null
        endEpoch = 0L
        pauseStartedAt = 0L
        pausedMs = 0L
        startEpoch = System.currentTimeMillis()
        phase = "running"

        startLocationUpdates()
        startStepCounter()
        handler.removeCallbacks(ticker)
        if (!tickerRunning) {
            tickerRunning = true
            handler.post(ticker)
        }
    }

    private fun startStepCounter() {
        if (stepListener != null) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values.firstOrNull() ?: return
                if (v <= 0f) return
                val base = stepBaseline
                if (base < 0f || v < base) {
                    // 首次/暂停恢复/传感器计数器回退：以当前值为新基线，不补跳变
                    stepBaseline = v
                    return
                }
                val delta = (v - base).roundToInt()
                if (delta > 0) {
                    stepTotal += delta
                    stepBaseline = v
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val ok = runCatching { sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME) }
            .getOrDefault(false)
        if (!ok) return
        sensorMgr = sm
        stepListener = listener
    }

    private fun stopStepCounter() {
        stepListener?.let { sensorMgr?.unregisterListener(it) }
        stepListener = null
        sensorMgr = null
    }

    fun pause() {
        if (phase == "running") {
            // 暂停即停采：定位不再入库，计步 listener 一并摘掉（原版暂停不计步）
            stopLocationUpdates()
            stopStepCounter()
            stepBaseline = -1f
            pauseStartedAt = System.currentTimeMillis()
            pausedSeconds = 0L
            phase = "paused"
            val limit = limits.pauseTimeSec
            locationNote = if (limit > 0) {
                "已暂停（上限 ${limit / 60} 分钟）"
            } else {
                "已暂停"
            }
        }
    }

    fun resume() {
        if (phase == "paused") {
            if (pauseStartedAt > 0L) {
                pausedMs += System.currentTimeMillis() - pauseStartedAt
            }
            pauseStartedAt = 0L
            pausedSeconds = 0L
            stepBaseline = -1f
            phase = "running"
            locationNote = "等待定位…"
            startLocationUpdates()
            startStepCounter()
        }
    }

    fun stop() {
        if (phase == "finished") return
        if (phase == "paused" && pauseStartedAt > 0L) {
            pausedMs += System.currentTimeMillis() - pauseStartedAt
            pauseStartedAt = 0L
        }
        endEpoch = System.currentTimeMillis()
        stopTicker()
        stopLocationUpdates()
        stopStepCounter()
        phase = "finished"
        pausedSeconds = 0L
        elapsedSec = currentDurationSec()
        locationNote = "已结束"
    }

    /** 放弃/完成一局后回到 ready，可直接开下一跑。 */
    fun reset() {
        stopTicker()
        stopLocationUpdates()
        stopStepCounter()
        locManager = null
        phase = "ready"
        samples = emptyList()
        passes = emptyList()
        checkpoints = emptyList()
        meters = 0.0
        totalSteps = 0
        stepTotal = 0
        elapsedSec = 0L
        pausedSeconds = 0L
        weakGps = false
        stopReason = null
        locationNote = ""
        startEpoch = 0L
        endEpoch = 0L
        pauseStartedAt = 0L
        pausedMs = 0L
        invalidStreak = 0
        stepBaseline = -1f
    }

    // 五项判定结论（App 算完再上报，服务端四规则复算）
    data class Judgement(
        val complete: Boolean,
        val reason: Int,        // 1 全满足 / 2 距离 / 3 点位 / 9 配速或步频 / 10 时长
        val paceMinPerKm: Double,
        val freq: Int,
        val note: String,
    )

    private fun durationNow(): Long =
        maxOf(samples.lastOrNull()?.t ?: 0L, currentDurationSec()).coerceAtLeast(1L)

    fun judgeItems(): List<JudgeItem> {
        val dur = durationNow()
        val pace = if (meters > 0.0) (dur / meters) * 50.0 / 3.0 else 0.0
        val freq = (totalSteps * 60.0 / dur).roundToInt()
        val passedNormal = passes.count { it.cp.isFixed == 0 }
        val passedFixed = passes.count { it.cp.isFixed == 1 }
        val needFixed = checkpoints.any { it.isFixed == 1 }
        return listOf(
            JudgeItem(
                "距离",
                meters >= limits.minDistanceM,
                "%.2f / %.2f km".format(meters / 1000, limits.minDistanceM / 1000),
            ),
            JudgeItem(
                "时长",
                limits.minRunTimeSec <= 0 || dur >= limits.minRunTimeSec,
                if (limits.minRunTimeSec <= 0) "不限" else {
                    "%d / %d 秒".format(dur, limits.minRunTimeSec)
                },
            ),
            JudgeItem(
                "配速",
                pace >= limits.speedTop && pace <= limits.speedBottom,
                "%.2f 分/km（%.1f–%.1f）".format(pace, limits.speedTop, limits.speedBottom),
            ),
            JudgeItem(
                "步频",
                freq >= limits.stepBottom && freq <= limits.stepTop,
                "$freq 步/分（${limits.stepBottom}–${limits.stepTop}）",
            ),
            JudgeItem(
                "打卡",
                passedNormal >= 2 && (!needFixed || passedFixed >= 1),
                "普通 $passedNormal/2 · 必经 $passedFixed/${if (needFixed) 1 else 0}",
            ),
        )
    }

    fun judge(): Judgement {
        val items = judgeItems()
        val ok = items.all { it.pass }
        val dur = durationNow()
        val pace = if (meters > 0.0) (dur / meters) * 50.0 / 3.0 else 0.0
        val freq = (totalSteps * 60.0 / dur).roundToInt()
        val reason = when {
            ok -> 1
            // 原版优先级：配速/步频(9) → 点位(3) → 距离(2) → 时长(10)
            !items[2].pass || !items[3].pass -> 9
            !items[4].pass -> 3
            !items[0].pass -> 2
            !items[1].pass -> 10
            else -> 9
        }
        return Judgement(
            complete = ok,
            reason = reason,
            paceMinPerKm = pace,
            freq = freq,
            note = if (ok) "五项全满足" else items.filter { !it.pass }.joinToString("；") {
                "${it.label}未达标（${it.detail}）"
            },
        )
    }

    fun dispose() {
        stopTicker()
        stopLocationUpdates()
        stopStepCounter()
        locManager = null
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 2000L
        private const val STRIDE_M = 0.9
        private val SATELLITE_KEYS = arrayOf("satellites", "satelliteCount", "SATELLITES", "numsatellites")

        fun systemLocationEnabled(ctx: Context): Boolean {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .any { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        }

        fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
            return 2 * r * Math.asin(Math.sqrt(a))
        }
    }
}
