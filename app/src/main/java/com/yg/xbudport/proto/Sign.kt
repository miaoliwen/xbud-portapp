package com.yg.xbudport.proto

import java.util.Locale
import java.security.MessageDigest

// Sign — 请求头 tokenSign / 跑步记录 signature 的离线复现器（sign.go 同源）。
//
// sign(map) = md5( join_sorted(map_without_null_and_signature) + salt )
//   排序   key 按 Java String.compareToIgnoreCase 升序
//   拼接   k=v 用 '&' 连接，末尾直接接 salt（无分隔符）
//   值     String.valueOf(value)；null 值条目剔除；"signature" 键剔除
//   MD5    xutils MD5.md5(String)：逐 char 截断为 byte（低 8 位）后 MD5，小写 hex

object Sign {
    private const val SALT = "2slhe02lsfiwowlcixisla_sls-_slaor"

    val RUN_SIGN_KEYS = listOf(
        "avgStepFreq", "calorie", "complete", "getPrize", "policy", "selDistance",
        "selRunTime", "selectedUnid", "speed", "sportType", "startTime", "status",
        "stopTime", "totalDis", "totalSteps", "totalTime", "uid", "unCompleteReason",
        "uuid", "validDis", "validTime",
    )

    private fun javaValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is String -> v
        is Int -> v.toString()
        is Long -> v.toString()
        is Double ->
            if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e16) {
                // Java Double.toString 整数态是 "114.0"；格式随 Locale，钉死 US 保线格式
                String.format(Locale.US, "%.1f", v)
            } else v.toString()
        else -> v.toString()
    }

    private fun cmpIgnoreCase(a: String, b: String): Int {
        val ra = a.lowercase()
        val rb = b.lowercase()
        val n = minOf(ra.length, rb.length)
        for (i in 0 until n) {
            if (ra[i] != rb[i]) return if (ra[i] < rb[i]) -1 else 1
        }
        return ra.length.compareTo(rb.length)
    }

    private fun signBytes(s: String): String {
        val raw = ByteArray(s.length) { (s[it].code and 0xff).toByte() }
        return Crypto.md5Hex(raw)
    }

    /** 通用签名：Map → md5 小写 hex。 */
    fun sign(m: Map<String, Any?>): String {
        val filtered = m.filterKeys { it != "signature" && it != "Signature" }
            .filterValues { it != null }
            .entries
            .sortedWith { a, b -> cmpIgnoreCase(a.key, b.key) }
        return signBytes(filtered.joinToString("&") { "${it.key}=${javaValue(it.value)}" } + SALT)
    }

    /** 请求头 tokenSign：timeStamp/token/uid 三键。tsMs 为毫秒。 */
    fun tokenSign(uid: Long, token: String, tsMs: Long): String = sign(
        mapOf(
            "timeStamp" to tsMs.toString(),
            "token" to token,
            "uid" to uid,
        )
    )

    /** 跑步记录 signature：21 键缺一不可。 */
    fun runSignature(bean: Map<String, Any?>): String {
        val data = HashMap<String, Any?>()
        for (k in RUN_SIGN_KEYS) {
            require(bean.containsKey(k)) { "签名缺键：$k" }
            data[k] = bean[k]
        }
        return sign(data)
    }
}
