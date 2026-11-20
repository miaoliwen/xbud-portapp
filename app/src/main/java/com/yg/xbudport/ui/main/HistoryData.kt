package com.yg.xbudport.ui.main

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 历史跑步记录：get_record_list + get_record_detail 的展示模型。
// 列表距离/时长单位来自服务端（米/秒），时间戳毫秒。
//
// 防崩溃契约（对齐官方 RunRecordPImpl）：
// - parseList/parseDetail 返回 null = 响应体解析失败，调用方走错误态；
//   返回空集合 = 服务端无数据，调用方走空态（官方 ex0.Cnew.onSuccess：
//   解析异常 catch 后停留旧态，空数据 view.mo5131for() 空态，ex0.java:272-297）。
// - 服务端脏值一律钳制：距离/时长非负、总时长上限 24h、步频上限 600，
//   配速钳制 (0, 10000] 分/km（官方 ex0.Q2 同款，ex0.java:385-388）。

private const val MAX_TOTAL_TIME_SEC = 86_400
private const val MAX_PACE_MIN_PER_KM = 10_000.0
private const val MAX_STEP_FREQ = 600

/** 距离类字段：拒绝 NaN/Infinity/负值，回落 0。 */
private fun Double.sanitize(): Double = if (isFinite() && this > 0.0) this else 0.0

data class RunRecordItem(
    val rrid: Long,
    val uuid: String,
    val startMs: Long,
    val stopMs: Long,
    val totalDisM: Double,
    val validDisM: Double,
    val totalTimeSec: Int,
    val validTimeSec: Int,
    val complete: Boolean,
    val status: Int,
    val avgStepFreq: Int,
    val appealStatus: Int,
) {
    val paceMinPerKm: Double
        get() {
            if (totalDisM <= 0.0 || totalTimeSec <= 0) return 0.0
            return (totalTimeSec * 1000.0 / totalDisM / 60.0).coerceIn(0.0, MAX_PACE_MIN_PER_KM)
        }

    val statusLabel: String
        get() = when {
            complete && status == 0 -> "合格"
            !complete -> "不合格"
            status == 1 -> "待同步"
            else -> "已记录"
        }
}

data class ReasonItem(val complete: Boolean, val reason: String)

data class RunRecordDetail(
    val record: RunRecordItem,
    val speed: Double,
    val policy: Int,
    val selDistance: Double,
    val unCompleteReason: Int,
    val canAppeal: Int,
    val isCheat: Int,
    val reasons: List<ReasonItem>,
)

object HistoryData {
    /** 记录列表；null = 解析失败（错误态），空列表 = 无数据（空态）。 */
    fun parseList(raw: String): List<RunRecordItem>? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val arr = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<RunRecordItem>(arr.length())
        val seen = HashSet<Long>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val item = parseItem(o)
            // 列表以 rrid 为 LazyColumn key：缺 rrid 或同页重复会让 Compose
            // 抛 "Key was already used" 直接崩；缺 rrid 的记录也开不了详情，
            // 与官方"脏记录不进列表"同形，丢弃
            if (item.rrid <= 0L || !seen.add(item.rrid)) continue
            out.add(item)
        }
        return out
    }

    /** 记录详情；null = 解析失败或结构异常。 */
    fun parseDetail(raw: String): RunRecordDetail? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: return null
        val reasons = ArrayList<ReasonItem>()
        data.optJSONArray("reasonList")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                reasons.add(
                    ReasonItem(
                        complete = o.optBoolean("complete", false),
                        reason = o.optString("reason", ""),
                    )
                )
            }
        }
        return RunRecordDetail(
            record = parseItem(data),
            speed = data.optDouble("speed", 0.0).sanitize(),
            policy = data.optInt("policy", 0),
            selDistance = data.optDouble("selDistance", 0.0).sanitize(),
            unCompleteReason = data.optInt("unCompleteReason", 0),
            canAppeal = data.optInt("canAppeal", 0),
            isCheat = data.optInt("isCheat", 0),
            reasons = reasons,
        )
    }

    private fun parseItem(o: JSONObject): RunRecordItem = RunRecordItem(
        rrid = o.optLong("rrid", 0L),
        uuid = o.optString("uuid", ""),
        startMs = o.optLong("startTime", 0L).coerceAtLeast(0L),
        stopMs = o.optLong("stopTime", 0L).coerceAtLeast(0L),
        totalDisM = o.optDouble("totalDis", 0.0).sanitize(),
        validDisM = o.optDouble("validDis", 0.0).sanitize(),
        totalTimeSec = o.optInt("totalTime", 0).coerceIn(0, MAX_TOTAL_TIME_SEC),
        validTimeSec = o.optInt("validTime", 0).coerceIn(0, MAX_TOTAL_TIME_SEC),
        complete = o.optBoolean("complete", false),
        status = o.optInt("status", 0),
        avgStepFreq = o.optInt("avgStepFreq", 0).coerceIn(0, MAX_STEP_FREQ),
        appealStatus = o.optInt("appealStatus", 0),
    )

    fun dateText(ms: Long): String =
        if (ms > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms)) else "—"

    fun timeText(ms: Long): String =
        if (ms > 0L) SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ms)) else "—"

    fun durationText(sec: Int): String {
        val s = sec.coerceIn(0, MAX_TOTAL_TIME_SEC)
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s % 60)
        }
    }

    fun paceText(pace: Double): String =
        if (pace > 0.0) String.format(Locale.US, "%.2f", pace) else "—"

    fun kmText(meters: Double): String =
        String.format(Locale.US, "%.2f", meters.coerceAtLeast(0.0) / 1000.0)
}
