package com.yg.xbudport.proto

// Envelope — 信封组装与响应解密（字段序与 P 字节序严格对齐 loginauth/直连通道实测形态）。
// P 的键序不可动：h = md5(P字节串)，服务端解 d 后按字节重算 md5 对 h。
// 手工拼串而不是 JSONObject：org.json 不保证字段序，信封必须字节级确定。

object Envelope {
    fun b64(data: ByteArray): String = java.util.Base64.getEncoder().encodeToString(data)

    fun b64d(text: String): ByteArray {
        val clean = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit() || c == '+' || c == '/' || c == '=') clean.append(c)
        }
        return java.util.Base64.getDecoder().decode(clean.toString())
    }

    /** P 字节串：data 末尾带 JSON 转义的换行（真机形态，字节级反斜杠+n 两字符）。 */
    fun wrapP(innerJSON: ByteArray, sec: Long, kd: Map<String, String>): ByteArray {
        val data = b64(innerJSON)
        val nl = BACKSLASH_N
        val text = "{\"data\":\"" + data + nl + "\"," +
            "\"keyDataFour\":\"" + kd["keyDataFour"] + "\"," +
            "\"keyDataOne\":\"" + kd["keyDataOne"] + "\"," +
            "\"keyDataThree\":\"" + kd["keyDataThree"] + "\"," +
            "\"keyDataTwo\":\"" + kd["keyDataTwo"] + "\"," +
            "\"platform\":2,\"timeStamp\":" + sec + "}"
        return text.toByteArray(Charsets.UTF_8)
    }

    /** 一次生成完整信封：返回 (信封 JSON, keyData)。 */
    fun offlineEnvelope(innerJSON: ByteArray, sec: Long): Pair<String, Map<String, String>> {
        val kd = Crypto.randomKeyData()
        val p = wrapP(innerJSON, sec, kd)
        val k0 = Crypto.k0FromSeed(sec).toByteArray(Charsets.ISO_8859_1)
        val d = Crypto.aesCbcEncrypt(k0, p)
        val h = Crypto.md5Hex(p)
        val k = Crypto.rsaEncryptK0(k0)
        val text = "{\"d\":\"" + b64(d) + "\",\"h\":\"" + h + "\",\"k\":\"" + b64(k) +
            "\",\"p\":\"101\",\"t\":\"0\"}"
        return text to kd
    }

    /** 响应解密：{"r": b64} → AES-CBC(session16(keyData)) → {"data": b64(inner)} → 明文。 */
    fun decryptOffline(respBody: ByteArray, kd: Map<String, String>): String? {
        val r = runCatching {
            org.json.JSONObject(String(respBody, Charsets.UTF_8)).optString("r", "")
        }.getOrDefault("")
        if (r.isEmpty()) return null
        val ct = runCatching { b64d(r) }.getOrNull() ?: return null
        val key = Crypto.session16(
            kd["keyDataOne"] ?: "", kd["keyDataTwo"] ?: "",
            kd["keyDataThree"] ?: "", kd["keyDataFour"] ?: "",
        )
        val outer = Crypto.aesCbcDecrypt(key, ct) ?: return null
        val env = runCatching { org.json.JSONObject(String(outer, Charsets.UTF_8)) }.getOrNull() ?: return null
        val data = env.optString("data", "")
        if (data.isEmpty()) return null
        return runCatching { String(b64d(data), Charsets.UTF_8) }.getOrNull()
    }

    /** 依次尝试多套 keyData 解响应（业务响应 body kd 优先，登录响应 header kd）。 */
    fun decryptAny(respBody: ByteArray, vararg kds: Map<String, String>): String? {
        for (kd in kds) {
            if (kd.isEmpty()) continue
            val pt = decryptOffline(respBody, kd)
            if (!pt.isNullOrEmpty()) return pt
        }
        return null
    }

    /** 两字符序列：反斜杠 + n（JSON 转义换行）。 */
    private val BACKSLASH_N = StringBuilder().append('\\').append('n').toString()
}
