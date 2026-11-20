package com.yg.xbudport.proto

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

// Cos — 轨迹桶（runjson-1325593214@ap-shanghai）最小客户端（cosc.go 同源）。
// 认证：GET /v2/util/sts 拿临时凭证（Bearer token），之后每请求现算 q-sign-algorithm=sha1。

class CosClient private constructor(
    private val ak: String,
    private val sk: String,
    private val sToken: String,
) {
    companion object {
        const val BUCKET = "runjson-1325593214"
        const val REGION = "ap-shanghai"
        const val HOST = "$BUCKET.cos.$REGION.myqcloud.com"
        const val BASE = "https://campusapi.xbud.run"

        // 共享传输层：每个 OkHttpClient 自带连接池 + dispatcher 线程池，
        // 每次调用现建会随查看详情/提交次数堆积，统一复用一个
        private val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** 轨迹对象路径：stable/<rrid//1e6>/<rrid>.json */
        fun trackKey(rrid: Long): String = "stable/${rrid / 1000000}/$rrid.json"

        fun fromToken(token: String): CosClient {
            require(token.isNotEmpty()) { "缺少 token" }
            val req = Request.Builder()
                .url("$BASE/v2/util/sts")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.bytes() ?: ByteArray(0)
                if (resp.code != 200) throw ProtoException("STS 失败 HTTP ${resp.code}: ${String(body).take(300)}")
                val o = org.json.JSONObject(String(body, Charsets.UTF_8))
                val cr = o.optJSONObject("credentials")
                val ak = cr?.optString("tmpSecretId") ?: o.optString("tmpSecretId")
                val sk = cr?.optString("tmpSecretKey") ?: o.optString("tmpSecretKey")
                val st = cr?.optString("sessionToken") ?: o.optString("sessionToken")
                if (ak.isEmpty()) throw ProtoException("STS 未返回凭证: ${String(body).take(300)}")
                return CosClient(ak, sk, st)
            }
        }

        private fun quote(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8")
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~")
    }

    /** COS v5 签名（q-sign-algorithm=sha1）。 */
    private fun sign(method: String, path: String, query: String, headers: Map<String, String>, ttl: Long): String {
        val now = System.currentTimeMillis() / 1000
        val keyTime = "${now - 60};${now + ttl}"
        val signKey = Crypto.hmacSha1Hex(sk, keyTime)

        val keys = headers.keys.map { it.lowercase() }.sorted()
        val hdrList = keys.joinToString(";")
        val hdrStr = keys.joinToString("&") { "$it=${quote(headers[it]!!)}" }

        var paramList = ""
        var paramStr = ""
        if (query.isNotEmpty()) {
            val parts = query.split("&").filter { it.isNotEmpty() }
                .map { val sp = it.split("=", limit = 2); (sp[0] to if (sp.size > 1) sp[1] else "") }
                .sortedBy { it.first }
            paramList = parts.joinToString(";") { it.first }
            paramStr = parts.joinToString("&") { "${it.first}=${quote(it.second)}" }
        }

        val httpStr = "${method.lowercase()}\n$path\n$paramStr\n$hdrStr\n"
        val sts = "sha1\n$keyTime\n${Crypto.sha1Hex(httpStr.toByteArray(Charsets.ISO_8859_1))}\n"
        val sig = Crypto.hmacSha1Hex(signKey, sts)

        return "q-sign-algorithm=sha1&q-ak=$ak&q-sign-time=$keyTime&q-key-time=$keyTime" +
            "&q-header-list=$hdrList&q-url-param-list=$paramList&q-signature=$sig"
    }

    private fun doCall(
        method: String,
        path: String,
        query: String,
        body: ByteArray?,
        contentType: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Pair<Int, ByteArray> {
        val p = if (path.startsWith("/")) path else "/$path"
        val hdrs = mapOf(
            "host" to HOST,
            "x-cos-security-token" to sToken,
        ) + if (contentType.isNotEmpty()) mapOf("content-type" to contentType) else emptyMap()
        val auth = sign(method, p, query, hdrs, 3600)

        var url = "https://$HOST$p"
        if (query.isNotEmpty()) url += "?$query"
        val rb = when {
            body == null -> RequestBody.create(null, ByteArray(0))
            onProgress != null -> ProgressBody(body, contentType.toMediaTypeOrNull(), onProgress)
            else -> RequestBody.create(contentType.toMediaTypeOrNull(), body)
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("x-cos-security-token", sToken)
            .apply { if (contentType.isNotEmpty()) header("Content-Type", contentType) }
            .method(method, if (body != null || method != "GET") rb else null)
            .build()
        http.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.bytes() ?: ByteArray(0))
        }
    }

    fun get(path: String): Pair<Int, ByteArray> = doCall("GET", path, "", null, "")

    fun put(
        path: String,
        body: ByteArray,
        contentType: String = "application/json",
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Pair<Int, ByteArray> = doCall("PUT", path, "", body, contentType, onProgress)

    private class ProgressBody(
        private val bytes: ByteArray,
        private val mediaType: MediaType?,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = bytes.size.toLong()

        override fun writeTo(sink: BufferedSink) {
            val total = bytes.size.toLong()
            var offset = 0
            var lastReported = 0L
            while (offset < bytes.size) {
                val n = minOf(32 * 1024, bytes.size - offset)
                sink.write(bytes, offset, n)
                offset += n
                if (offset - lastReported >= 64 * 1024 || offset == bytes.size) {
                    lastReported = offset.toLong()
                    onProgress(lastReported, total)
                }
            }
            if (bytes.isEmpty()) onProgress(0L, 0L)
        }
    }
}
