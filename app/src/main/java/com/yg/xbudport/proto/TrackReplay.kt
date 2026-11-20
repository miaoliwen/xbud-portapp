package com.yg.xbudport.proto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

// 历史记录轨迹回放：直接读 COS 的 stable/<rrid//1e6>/<rrid>.json（gzip），
// 不依赖 get_record_detail 的 allLocJson（服务端恒为 null）。
//
// 防崩溃契约（对齐官方 dx0.a3 的轨迹解析）：
// - 解压/解析任一失败都转成 ProtoException，由 ErrNorm 归一为用户文案；
// - 坐标必须有限且落在 [lat ±90, lng ±180]，脏点直接跳过而不是喂给地图。

data class TrackPoint(val lat: Double, val lng: Double, val type: Int)

data class ReplayCheckpoint(
    val lat: Double,
    val lng: Double,
    val name: String,
    val isFixed: Int,
)

data class TrackReplayData(
    val points: List<TrackPoint>,
    val checkpoints: List<ReplayCheckpoint>,
)

object TrackReplay {
    suspend fun fetch(token: String, rrid: Long): TrackReplayData = withContext(Dispatchers.IO) {
        if (token.isEmpty()) throw ProtoException("缺少 token")
        val cos = CosClient.fromToken(token)
        val key = CosClient.trackKey(rrid)
        val (st, raw) = cos.get(key)
        if (st != 200) throw ProtoException("轨迹下载失败 HTTP $st")
        val plain = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            try {
                GZIPInputStream(ByteArrayInputStream(raw)).use { input ->
                    ByteArrayOutputStream().use { out ->
                        input.copyTo(out)
                        out.toByteArray()
                    }
                }
            } catch (e: Exception) {
                throw ProtoException("轨迹解压失败：${e.javaClass.simpleName}")
            }
        } else {
            raw
        }
        val obj = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull()
            ?: throw ProtoException("轨迹数据解析失败")
        TrackReplayData(
            points = parseLoc(runData(obj)?.opt("allLocJson")),
            checkpoints = parseCheckpoints(obj.opt("fixedPointJson")),
        )
    }

    private fun runData(obj: JSONObject): JSONObject? = when (val v = obj.opt("runData")) {
        is JSONObject -> v
        is String -> runCatching { JSONObject(v) }.getOrNull()
        else -> null
    }

    private fun validCoord(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    private fun parseLoc(v: Any?): List<TrackPoint> {
        val arr = when (v) {
            is JSONArray -> v
            is String -> runCatching { JSONArray(v) }.getOrNull()
            else -> null
        } ?: return emptyList()
        val out = ArrayList<TrackPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", 0.0)
            val lng = o.optDouble("lng", 0.0)
            if (lat == 0.0 && lng == 0.0) continue
            if (!validCoord(lat, lng)) continue
            out.add(TrackPoint(lat, lng, o.optInt("type", 0)))
        }
        return out
    }

    private fun parseCheckpoints(v: Any?): List<ReplayCheckpoint> {
        val holder = when (v) {
            is JSONObject -> v
            is String -> runCatching { JSONObject(v) }.getOrNull()
            else -> null
        } ?: return emptyList()
        val raw = when (val f = holder.opt("fivePointJson")) {
            is JSONArray -> f
            is String -> runCatching { JSONArray(f) }.getOrNull()
            else -> null
        } ?: return emptyList()
        val out = ArrayList<ReplayCheckpoint>(raw.length())
        for (i in 0 until raw.length()) {
            val o = raw.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", 0.0)
            val lng = o.optDouble("lon", o.optDouble("lng", 0.0))
            if (!validCoord(lat, lng)) continue
            out.add(
                ReplayCheckpoint(
                    lat = lat,
                    lng = lng,
                    name = o.optString("pointName", ""),
                    isFixed = o.optInt("isFixed", 0),
                )
            )
        }
        return out
    }
}
