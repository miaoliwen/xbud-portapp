package com.yg.xbudport.ui.main

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// 主页 M3 的数据模型：get_config.runRuleModel + get_record_list + home_page_info。
// 记录距离/时长：米、秒；时间戳毫秒。home_page_info 的 runModel 距离是公里、
// semesterGoal 是米，parseHomeInfo 内统一转成米。weekCountGoal 为每周完成次数目标，
// 0 表示学校未配置（界面显示“未设置”），不是“必跑 0 次”。
//
// 防崩溃契约（对齐官方 RunRecordPImpl 的解析层）：
// - 四个 parse* 对畸形响应体一律返回 null，由调用方走兜底分支，
//   不让 JSONObject 构造异常逃逸（官方 ex0.Cnew.onSuccess 整段 try/catch，ex0.java:272-297）。
// - 服务端脏值钳制：距离/次数/时长非负（官方 ex0.Q2 同款思路，ex0.java:385-388）。

data class ValidWindow(val start: String, val end: String)

data class RunRule(
    val dayGoalM: Double = 0.0,
    val weekCountGoal: Int = 0,
    val minDistanceM: Double = 0.0,
    val minRunTimeSec: Int = 0,
    val speedTop: Double = 0.0,
    val speedBottom: Double = 0.0,
    val stepTop: Int = 0,
    val stepBottom: Int = 0,
    val validWindows: List<ValidWindow> = emptyList(),
    val maxRunTimeSec: Int = 0,
    val pauseTimeSec: Long = 0,
    val policy: Int = 0,
) {
    fun windowLabel(): String =
        if (validWindows.isEmpty()) "不限时段"
        else validWindows.joinToString("、") { w ->
            val start = parseClock(w.start)
            val end = parseClock(w.end)
            if (start != null && end != null) {
                "${clockText(start)}–${clockText(end)}"
            } else {
                "${w.start}–${w.end}"
            }
        }

    /** 当前是否处于有效跑步时段；无配置时视为不限时段。 */
    fun isOpen(now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (validWindows.isEmpty()) return true
        val t = now.toLocalTime()
        return validWindows.any { w ->
            val start = parseClock(w.start) ?: return@any false
            val end = parseClock(w.end) ?: return@any false
            if (end > start) {
                !t.isBefore(start) && t.isBefore(end)
            } else {
                // 跨午夜时段（如 22:00–01:00）
                !t.isBefore(start) || t.isBefore(end)
            }
        }
    }

    /** 不开放时返回下一次开放时间提示，如“今日 18:00”“明日 06:30”。 */
    fun nextOpenHint(now: LocalDateTime = LocalDateTime.now()): String? {
        if (validWindows.isEmpty() || isOpen(now)) return null
        val nowTime = now.toLocalTime()
        val starts = validWindows.mapNotNull { parseClock(it.start) }
        val first = starts.minOrNull() ?: return null
        val laterToday = starts.filter { it > nowTime }.minOrNull()
        return if (laterToday != null) {
            "今日 ${clockText(laterToday)}"
        } else {
            "明日 ${clockText(first)}"
        }
    }
}

data class RunRecord(
    val rrid: Long,
    val startMs: Long,
    val distanceM: Double,
    val complete: Boolean,
)

data class SemesterInfo(
    val validCount: Int = 0,
    val validDisM: Double = 0.0,
    val goalMethod: Int = -1,
    val goalCount: Int = 0,
    val goalDisM: Double = 0.0,
    val startMs: Long? = null,
    val endMs: Long? = null,
) {
    val hasGoal: Boolean
        get() = (goalMethod == 1 && goalCount > 0) || (goalMethod == 0 && goalDisM > 0)

    val progress: Float
        get() {
            val p = when {
                goalMethod == 1 && goalCount > 0 -> validCount.toFloat() / goalCount
                goalMethod == 0 && goalDisM > 0 -> (validDisM / goalDisM).toFloat()
                else -> 0f
            }
            return p.coerceIn(0f, 1f)
        }
}

object HomeData {
    private const val S = 1000.0

    // 服务端把 get_config 的时段闸门做在了规则下发上：有效时段外回
    // {"error":11016,"message":"…跑步有效时间为06:30-21:00","data":null}，
    // 不带 runRuleModel。从 message 里解出时段，规则只剩窗口——闸门与
    // 「有效时段外 · 06:30–21:00」提示照常工作；解不出来按加载失败走（null）。
    private val MESSAGE_WINDOW = Regex("""(\d{1,2}:\d{2})\s*[-–~至]\s*(\d{1,2}:\d{2})""")

    fun parseRunRule(raw: String): RunRule? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val error = root.optInt("error", 10000)
        val data = root.optJSONObject("data") ?: root
        val m = data.optJSONObject("runRuleModel")
        if (m == null || m.length() == 0) {
            if (error == 11016) return runRuleFromMessage(root.optString("message"))
            return null
        }

        val windows = mutableListOf<ValidWindow>()
        m.optJSONArray("validTime")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val start = o.optString("start", o.optString("startTime", ""))
                val end = o.optString("end", o.optString("endTime", ""))
                if (start.isNotBlank() && end.isNotBlank()) windows.add(ValidWindow(start, end))
            }
        }

        return RunRule(
            dayGoalM = m.num("dayGoal").nonNeg(),
            weekCountGoal = m.optInt("weekCountGoal", 0).nonNeg(),
            minDistanceM = m.num("minDistance").nonNeg(),
            minRunTimeSec = m.optInt("minRunTime", 0).nonNeg(),
            speedTop = m.num("speedTop").nonNeg(),
            speedBottom = m.num("speedBottom").nonNeg(),
            stepTop = m.optInt("stepTop", 0).nonNeg(),
            stepBottom = m.optInt("stepBottom", 0).nonNeg(),
            validWindows = windows,
            maxRunTimeSec = m.optInt("maxRunTime", 0).nonNeg(),
            pauseTimeSec = m.optLong("pauseTime", 0L).nonNeg(),
            policy = m.optInt("policy", data.optInt("policy", 0)),
        )
    }

    /** 11016 的 message → 仅有效时段的规则；时段解不出来返回 null（按加载失败走）。 */
    private fun runRuleFromMessage(message: String): RunRule? {
        if (message.isBlank()) return null
        val windows = MESSAGE_WINDOW.findAll(message)
            .map { ValidWindow(it.groupValues[1], it.groupValues[2]) }
            .toList()
        if (windows.isEmpty()) return null
        return RunRule(validWindows = windows)
    }

    fun parseRecords(raw: String): List<RunRecord> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val data = root.opt("data")
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("list") ?: data.optJSONArray("records") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<RunRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val valid = o.num("validDis")
            val total = o.num("totalDis")
            out.add(
                RunRecord(
                    rrid = o.optLong("rrid", 0L),
                    startMs = o.optLong("startTime", 0L).nonNeg(),
                    distanceM = (if (valid > 0.0) valid else total).nonNeg(),
                    complete = o.optBoolean("complete", false),
                )
            )
        }
        return out
    }

    /** /v1/user/home_page_info：runModel 的学期累计 + semesterInfoModel 的学期目标。 */
    fun parseHomeInfo(raw: String): SemesterInfo? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: return null
        val run = data.optJSONObject("runModel") ?: return null
        val sem = data.optJSONObject("semesterInfoModel")
            ?: data.optJSONArray("semesterInfo")?.optJSONObject(0)

        val startMs = sem?.optLong("startDate", 0L)?.takeIf { it > 0L }?.let(::normalizeEpochMs)
        val endMs = sem?.optLong("endDate", 0L)?.takeIf { it > 0L }?.let(::normalizeEpochMs)
        return SemesterInfo(
            validCount = run.optInt("validTotalCount", 0).nonNeg(),
            // home_page_info 的 runModel 距离是公里（原 App 直接格式化显示），
            // 而 semesterGoal 是米；内部统一按米存。
            validDisM = run.num("validTotalDis").nonNeg() * S,
            goalMethod = sem?.optInt("goalMethod", -1) ?: -1,
            goalCount = sem?.optInt("semesterGoalCount", 0)?.nonNeg() ?: 0,
            goalDisM = sem?.num("semesterGoal")?.nonNeg() ?: 0.0,
            startMs = startMs,
            endMs = endMs,
        )
    }

    /** /v1/sport/run/completed_info：home_page_info 不可用时的学期统计兜底。 */
    fun parseCompletedInfo(raw: String): SemesterInfo? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: root
        val count = data.optInt("semesterValidCount", -1)
        val dis = data.num("semesterValidDis")
        if (count < 0 && dis <= 0.0) return null
        return SemesterInfo(
            validCount = count.coerceAtLeast(0),
            validDisM = dis.nonNeg(),
            goalMethod = data.optInt("goalMethod", -1),
            goalCount = data.optInt("semesterGoalCount", 0).nonNeg(),
            goalDisM = data.num("semesterGoal").nonNeg(),
        )
    }

    /** 接口都拿不到时按记录列表估算；没有目标值，只保证次数/里程不是 0。 */
    fun fromRecords(records: List<RunRecord>, startMs: Long? = null, endMs: Long? = null): SemesterInfo {
        val from = startMs ?: 0L
        val to = endMs ?: Long.MAX_VALUE
        val (count, dis) = aggregate(records, from, to)
        return SemesterInfo(validCount = count, validDisM = dis)
    }

    fun aggregate(records: List<RunRecord>, fromMs: Long, toMs: Long = Long.MAX_VALUE): Pair<Int, Double> {
        var count = 0
        var dis = 0.0
        for (r in records) {
            if (!r.complete || r.startMs <= 0L) continue
            if (r.startMs < fromMs || r.startMs >= toMs) continue
            count++
            dis += r.distanceM
        }
        return count to dis
    }

    fun weekStartMs(now: LocalDateTime = LocalDateTime.now()): Long =
        now.toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    fun dayStartMs(now: LocalDateTime = LocalDateTime.now()): Long =
        now.toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    fun kmText(meters: Double, digits: Int = 2): String =
        String.format(Locale.US, "%.${digits}f", meters.nonNeg() / S)

    /** 距离/时长类字段钳制：拒绝 NaN/Infinity/负值，回落 0。 */
    private fun Double.nonNeg(): Double = if (isFinite() && this > 0.0) this else 0.0

    private fun Int.nonNeg(): Int = if (this > 0) this else 0

    private fun Long.nonNeg(): Long = if (this > 0L) this else 0L

    private fun normalizeEpochMs(v: Long): Long =
        if (v in 1..9_999_999_999L) v * 1000L else v

    private fun JSONObject.num(key: String, default: Double = 0.0): Double {
        val d = optDouble(key, default)
        return if (d.isFinite()) d else default
    }
}

private fun parseClock(s: String): LocalTime? = runCatching {
    val parts = s.trim().split(':')
    LocalTime.of(parts[0].toInt(), parts.getOrElse(1) { "0" }.toInt())
}.getOrNull()

private fun clockText(t: LocalTime): String =
    String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
