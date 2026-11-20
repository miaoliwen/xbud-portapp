package com.yg.xbudport.proto

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.asin
import kotlin.math.sqrt
import kotlin.random.Random

// RealRun — 学分跑真实提交（纯 Kotlin，pkg/track/realrun.go + flow_realrun.go 同源）。
//
// 输入 runJSON：{start, policy, selDistance, selectedUnid, selRunTime, complete,
//                unCompleteReason, freq, fivePoints[], samples[]}
// 流程：AssembleReal 真实样本 → 轨迹对象 → save_record（真实值 + 21 键重签）
//       → COS PUT/回读。totalDis 取逐点几何累加（几何比天然 ≈ 1）。

object RealRun {
    private const val M_PER_DEG_LAT = 111320.0
    private const val WIN_SEC = 10
    private const val REAL_VALID_RADIUS = 50.0

    data class Part(
        val loc: JSONArray,
        val spd: JSONArray,
        val sf: JSONArray,
        val five: JSONArray,
        val meters: Double,
        val dur: Int,
        val freq: Int,
        val geoRatio: Double,
    )

    private fun round6(v: Double): Double = (v * 1e6).roundToLong() / 1e6
    private fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0

    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(a))
    }

    /** 真实样本 → 轨迹对象各部件（JSON 字符串）。 */
    fun assemble(
        uidStr: String, uuid: String, start: Long,
        samples: List<JSONObject>, five: JSONArray,
    ): Part {
        require(samples.size >= 2) { "样本不足：${samples.size} 条" }
        val ss = samples.sortedBy { it.optLong("t") }
        val dur = ss.last().optLong("t").toInt()
        require(dur > 0) { "时长不合法：dur=$dur" }

        val rnd = Random(start + 7919)
        val flag = start * 1000
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

        // 1. 几何累加；无效点坐标沿用上一有效点、距离不推进
        val coords = arrayOfNulls<DoubleArray>(ss.size) // [lat, lng]
        val cum = DoubleArray(ss.size)
        var prevLat = 0.0; var prevLng = 0.0; var hasPrev = false
        for (i in ss.indices) {
            val lat = ss[i].optDouble("lat", 0.0)
            val lng = ss[i].optDouble("lng", 0.0)
            val radius = ss[i].optDouble("radius", 999.0)
            val valid = radius <= REAL_VALID_RADIUS && (lat != 0.0 || lng != 0.0)
            if (!valid || !hasPrev) {
                coords[i] = doubleArrayOf(prevLat, prevLng)
                cum[i] = if (i > 0) cum[i - 1] else 0.0
                if (valid) { prevLat = lat; prevLng = lng; hasPrev = true }
                continue
            }
            coords[i] = doubleArrayOf(lat, lng)
            cum[i] = (if (i > 0) cum[i - 1] else 0.0) + haversine(prevLat, prevLng, lat, lng)
            prevLat = lat; prevLng = lng; hasPrev = true
        }
        val dis = round6(cum[ss.size - 1])
        require(dis >= 1.0) { "有效距离为 0（全部样本无效定位）" }

        var totalSteps = 0
        for (s in ss) totalSteps = max(totalSteps, s.optInt("steps", 0))
        val stride = if (totalSteps > 0) dis / totalSteps else 0.0

        var firstValid = -1
        var lastValid = -1
        for (i in ss.indices) {
            val r = ss[i].optDouble("radius", 999.0)
            val c = coords[i]!!
            if (r <= REAL_VALID_RADIUS && (c[0] != 0.0 || c[1] != 0.0)) {
                if (firstValid < 0) firstValid = i
                lastValid = i
            }
        }

        // 2. 逐点
        val loc = JSONArray()
        for (i in ss.indices) {
            val s = ss[i]
            val tt = s.optLong("t").toInt()
            val radius = s.optDouble("radius", 999.0)
            val c = coords[i]!!
            val valid = i == firstValid || i == lastValid ||
                (radius <= REAL_VALID_RADIUS && (c[0] != 0.0 || c[1] != 0.0))
            var typ = if (valid) 0 else -1
            if (i == firstValid) typ = 5
            if (i == ss.size - 1) typ = 6
            var sat = s.optInt("sat", 0)
            if (sat <= 0) sat = 8 + rnd.nextInt(33)
            var sd = 0.0
            if (i > 0 && valid && totalSteps > 0) {
                val stepsHere = max(0, s.optInt("steps", 0) - ss[i - 1].optInt("steps", 0))
                sd = stride * stepsHere
            }
            val avgV = if (tt > 0) cum[i] / tt else 0.0
            val gain = fmt.format(Date(start * 1000 + tt * 1000))
            loc.put(JSONObject().apply {
                put("avgSpeed", round2(avgV))
                put("bdA", round2(rnd.nextDouble() * 360))
                put("bdD", round2(rnd.nextDouble() * 30))
                put("bdG", 1)
                put("bdS", round2(0.3 + rnd.nextDouble() * 0.9))
                put("coorType", "GCJ02")
                put("count", sat)
                put("flag", flag)
                put("gLat", round6(c[0]))
                put("gLng", round6(c[1]))
                put("gainTime", gain)
                put("id", 0)
                put("lat", round6(c[0]))
                put("lng", round6(c[1]))
                put("locType", 1)
                put("radius", round6(radius))
                put("speed", round2(s.optDouble("speed", 0.0)))
                put("state", 1)
                put("stepDistance", round6(sd))
                put("totalDis", round6(cum[i]))
                put("totalTime", tt)
                put("type", typ)
                put("validDis", round6(cum[i]))
                put("validTime", tt)
            })
        }
        val lastLoc = loc.getJSONObject(loc.length() - 1)
        lastLoc.put("totalDis", dis); lastLoc.put("validDis", dis)
        lastLoc.put("totalTime", dur); lastLoc.put("validTime", dur)
        lastLoc.put("avgSpeed", round2(dis / dur))
        lastLoc.put("type", 6)

        // 3. 10s 窗（真机起点毫秒偏移形态）
        val offs = longArrayOf(3033, 3086, 4113)
        val offMs = offs[rnd.nextInt(offs.size)]
        val nWin = ceil(dur.toDouble() / WIN_SEC).toInt()
        val width = dur.toDouble() / nWin
        val bounds = LongArray(nWin + 1)
        for (k in 0..nWin) bounds[k] = flag + offMs + (k * width * 1000).roundToLong()
        bounds[nWin] = flag + offMs + dur * 1000

        fun cumAtT(sec: Double): Double {
            if (sec <= 0) return 0.0
            if (sec >= dur) return dis
            var lo = 0; var hi = ss.size - 1
            while (lo < hi - 1) {
                val mid = (lo + hi) / 2
                if (ss[mid].optLong("t").toDouble() <= sec) lo = mid else hi = mid
            }
            val tLo = ss[lo].optLong("t").toDouble()
            val tHi = ss[hi].optLong("t").toDouble()
            if (tHi == tLo) return cum[lo]
            val f = (sec - tLo) / (tHi - tLo)
            return cum[lo] + (cum[hi] - cum[lo]) * f
        }
        fun stepsAtT(sec: Double): Int {
            if (sec <= 0) return 0
            if (sec >= dur) return totalSteps
            var lo = 0; var hi = ss.size - 1
            while (lo < hi - 1) {
                val mid = (lo + hi) / 2
                if (ss[mid].optLong("t").toDouble() <= sec) lo = mid else hi = mid
            }
            val tLo = ss[lo].optLong("t").toDouble()
            val tHi = ss[hi].optLong("t").toDouble()
            if (tHi == tLo) return ss[lo].optInt("steps", 0)
            val f = (sec - tLo) / (tHi - tLo)
            return (ss[lo].optInt("steps", 0) + (ss[hi].optInt("steps", 0) - ss[lo].optInt("steps", 0)) * f)
                .roundToInt()
        }

        val spd = JSONArray()
        var sum = 0.0
        for (k in 0 until nWin) {
            val a = bounds[k]; val b = bounds[k + 1]
            val d = cumAtT((b - flag) / 1000.0) - cumAtT((a - flag) / 1000.0)
            sum += d
            spd.put(JSONObject().apply {
                put("beginTime", a); put("distance", d); put("endTime", b)
                put("flag", flag); put("id", 0)
            })
        }
        if (spd.length() > 0) {
            val w = spd.getJSONObject(spd.length() - 1)
            w.put("distance", w.optDouble("distance") + (dis - sum))
        }

        val sf = JSONArray()
        var ssum = 0
        for (k in 0 until nWin) {
            val a = bounds[k]; val b = bounds[k + 1]
            var cnt = stepsAtT((b - flag) / 1000.0) - stepsAtT((a - flag) / 1000.0)
            if (cnt < 0) cnt = 0
            ssum += cnt
            sf.put(JSONObject().apply {
                put("avgDiff", 0); put("beginTime", a); put("endTime", b)
                put("flag", flag); put("id", 0)
                put("maxDiff", 0); put("minDiff", 1000); put("stepsNum", cnt)
            })
        }
        val diff = totalSteps - ssum
        if (diff != 0 && sf.length() > 0) {
            val w = sf.getJSONObject(sf.length() - 1)
            val n = max(0, w.optInt("stepsNum") + diff)
            w.put("stepsNum", n)
        }

        // 4. id 连续编号（三段独立基址）
        val bLoc = 2000 + rnd.nextInt(4000)
        for (i in 0 until loc.length()) loc.getJSONObject(i).put("id", bLoc + i)
        val bSp = 400 + rnd.nextInt(1100)
        for (i in 0 until spd.length()) spd.getJSONObject(i).put("id", bSp + i)
        val bSf = bSp + rnd.nextInt(61) - 30
        for (i in 0 until sf.length()) sf.getJSONObject(i).put("id", bSf + i)

        // 几何比
        var geo = 0.0
        for (i in 1 until ss.size) {
            val c0 = coords[i - 1]!!; val c1 = coords[i]!!
            geo += haversine(c0[0], c0[1], c1[0], c1[1])
        }

        return Part(loc, spd, sf, five, dis, dur, totalSteps, geo / max(dis, 1e-9))
    }

    /** 真实提交：save_record → COS PUT/回读。返回 RealRunResult JSON。 */
    suspend fun saveRealRun(
        uid: Long,
        identity: DeviceIdentity,
        token: String,
        runJSON: String,
        onProgress: (String, Float) -> Unit = { _, _ -> },
    ): String {
        val inJson = JSONObject(runJSON)
        val start = inJson.optLong("start")
        require(start > 0) { "start 不合法" }
        val samples = mutableListOf<JSONObject>()
        val sArr = inJson.optJSONArray("samples")
        if (sArr != null) for (i in 0 until sArr.length()) {
            sArr.optJSONObject(i)?.let { samples.add(it) }
        }
        val five = inJson.optJSONArray("fivePoints") ?: JSONArray()
        val dur = samples.lastOrNull()?.optLong("t")?.toInt() ?: 0
        require(dur > 0) { "samples 为空或时长不合法" }

        var totalSteps = 0
        for (s in samples) totalSteps = max(totalSteps, s.optInt("steps", 0))
        var freq = inJson.optDouble("freq", 0.0)
        if (freq <= 0 && dur > 0) freq = totalSteps * 60.0 / dur

        val uuid = Crypto.uuid4()
        val uidStr = uid.toString()
        onProgress("组装轨迹", 0.05f)
        val part = assemble(uidStr, uuid, start, samples, five)
        onProgress("组装轨迹", 0.15f)
        val meters = part.meters.roundToInt()
        val km = meters / 1000.0
        val pace = dur.toDouble() / meters * 50.0 / 3.0

        // save_record 25 字段 + 21 键重签（真实值）
        val startLat = samples[0].optDouble("lat", 0.0)
        val startLng = samples[0].optDouble("lng", 0.0)
        val body = JSONObject().apply {
            put("avgStepFreq", freq.roundToInt())
            put("calorie", (km * 62).roundToInt())
            put("complete", inJson.optBoolean("complete", false))
            put("fivePointJson", JSONObject().put("fivePointJson", five.toString()).put("useZip", false).toString())
            put("getPrize", false)
            put("isUpload", false)
            put("latitude", startLat)
            put("longitude", startLng)
            put("maxRunTime", 0)
            put("minSteps", 0)
            put("more", true)
            put("policy", inJson.optLong("policy", 1))
            put("roomId", 0)
            put("selDistance", inJson.optLong("selDistance", 1))
            put("selRunTime", inJson.optLong("selRunTime", 0))
            put("selectedUnid", inJson.optLong("selectedUnid", 1001104))
            put("speed", (pace * 1000).roundToLong())
            put("sportType", 1)
            put("status", 0)
            put("totalDis", meters)
            put("totalSteps", totalSteps)
            put("totalTime", dur)
            put("uid", uid)
            put("unCompleteReason", inJson.optInt("unCompleteReason", 1))
            put("uuid", uuid)
            put("validDis", meters)
            put("validTime", dur)
            put("startTime", start * 1000)
            put("stopTime", (start + dur) * 1000)
        }
        body.put("signature", Sign.runSignature(body.toMap()))

        // 信封发送
        onProgress("提交记录", 0.2f)
        val session = Session(uidStr, token, identity, "")
        val pt = session.call("/v4/sport/run/save_record", body.toString())
        val resp = JSONObject(pt)
        val error = resp.optInt("error", -1)
        if (error != 10000) throw ProtoException("服务端拒收 error=$error message=${resp.optString("message")}")
        val data = resp.optJSONObject("data") ?: throw ProtoException("save_record 响应缺 data")
        val rrid = data.optLong("rrid", 0L)
        val uuidOut = data.optString("uuid", uuid)
        if (rrid == 0L) throw ProtoException("save_record 未返回 rrid")
        onProgress("提交记录", 0.4f)

        // 轨迹对象 + gzip + COS
        val locJSON = part.loc.toString()
        val spdJSON = part.spd.toString()
        val sfJSON = part.sf.toString()
        val rd = JSONObject().put("allLocJson", locJSON).put("useZip", false).toString()
        val fw = JSONObject().put("fivePointJson", part.five.toString()).put("useZip", false).toString()
        val obj = JSONObject().apply {
            put("fixedPointJson", fw)
            put("rrid", rrid.toString())
            put("runData", rd)
            put("speedJson", spdJSON)
            put("stepFreqJson", sfJSON)
            put("uid", uidStr)
            put("uuid", uuidOut)
        }
        val plain = obj.toString().toByteArray(Charsets.UTF_8)
        val gz = ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { it.write(plain) }
            bos.toByteArray()
        }
        val cos = CosClient.fromToken(token)
        val key = CosClient.trackKey(rrid)
        onProgress("上传轨迹", 0.45f)
        val (st, rb) = cos.put(key, gz) { sent, total ->
            val frac = if (total > 0L) sent.toFloat() / total else 0f
            onProgress("上传轨迹", 0.45f + 0.45f * frac.coerceIn(0f, 1f))
        }
        if (st != 200) throw ProtoException("PUT $key HTTP $st ${String(rb).take(200)}")
        onProgress("校验轨迹", 0.92f)
        val (st2, back) = cos.get(key)
        if (st2 != 200) throw ProtoException("轨迹回读失败 HTTP $st2")
        onProgress("完成", 1f)

        return JSONObject().apply {
            put("rrid", rrid)
            put("uuid", uuidOut)
            put("meters", meters)
            put("dur", dur)
            put("avgStepFreq", freq.roundToInt())
            put("geoRatio", part.geoRatio)
            put("summary", "PUT $key 200，回读 ${back.size} 字节")
            put("verifyOk", true)
        }.toString()
    }
}

private fun JSONObject.toMap(): Map<String, Any?> {
    val m = HashMap<String, Any?>()
    for (key in keys()) m[key] = opt(key)
    return m
}
