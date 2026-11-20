package com.yg.xbudport.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// CryptoTest — 用 loginauth/crypto.py 自测向量 + 真机登录信封逐字节验证 Kotlin 密码层。

class CryptoTest {
    /** crypto.py selftest：k0_from_seed(1794635458) == "hOf>7W%+[@ML^PK"（含反斜杠）。 */
    @Test
    fun k0FromSeed_matchesPythonVector() {
        val expect = StringBuilder()
            .append("hOf>7W%+[@")
            .append('\\')
            .append("ML^PK")
            .toString()
        assertEquals(expect, Crypto.k0FromSeed(1794635458L))
    }

    /** crypto.py selftest：session16 四串向量。 */
    @Test
    fun session16_matchesPythonVector() {
        val key = Crypto.session16("Aa11Bb22", "Cc33Dd44", "Ee55Ff66", "Gg77Hh88")
        assertEquals("fcfcfcfcf3f3f3f3fbfbfbfb08080808", key.joinToString("") { "%02x".format(it) })
    }

    /** 用真机登录信封（analysis/workdirs/_devdata/login_env.json）逐字节解出 body 明文。 */
    @Test
    fun decryptRealLoginEnvelope() {
        val envFile = System.getenv("LOGIN_ENV_JSON")
            ?: "D:/XbudWork/draft-d46d7dde-489f-4cb1-8e0d-53d8c89f12b6/analysis/workdirs/_devdata/login_env.json"
        val f = java.io.File(envFile)
        if (!f.exists()) {
            System.err.println("SKIP: 不存在 $envFile")
            return
        }
        val root = org.json.JSONObject(f.readText())
        val env = if (root.optJSONObject("env") != null) root.getJSONObject("env")
        else org.json.JSONObject(root.getString("env"))
        val d = b64Any(env.getString("d"))
        // P.timeStamp 未知，扫 13:50 登录时刻 ±3000 秒
        for (sec in 1794632000L..1794638000L) {
            val k0 = Crypto.k0FromSeed(sec).toByteArray(Charsets.ISO_8859_1)
            val pt = Crypto.aesCbcDecrypt(k0, d) ?: continue
            val ptText = String(pt, Charsets.UTF_8)
            if (!ptText.startsWith("{\"data\"")) continue
            val p = org.json.JSONObject(ptText)
            assertEquals("P.timeStamp 与种子一致", sec, p.getLong("timeStamp"))
            val dataClean = p.getString("data").trim().filter { b64char(it) }
            val inner = String(java.util.Base64.getDecoder().decode(dataClean), Charsets.UTF_8)
            assertTrue("真机 body 明文", inner.startsWith("{\"loginType\":1"))
            assertEquals("h = md5(P)", env.getString("h"), Crypto.md5Hex(pt))
            return
        }
        fail("6000 秒窗口内未解出真机信封")
    }

    /** k 票据形态：RSA-1024 → 128 字节。 */
    @Test
    fun rsaEncryptK0_shape() {
        val k = Crypto.rsaEncryptK0("0123456789abcdef".toByteArray(Charsets.ISO_8859_1))
        assertEquals(128, k.size)
    }

    /** AES 往返 + P 的 data 尾部带转义换行（两字符）+ decryptOffline 往返。 */
    @Test
    fun envelopeRoundTrip() {
        val kd = mapOf(
            "keyDataOne" to "Aa11Bb22", "keyDataTwo" to "Cc33Dd44",
            "keyDataThree" to "Ee55Ff66", "keyDataFour" to "Gg77Hh88",
        )
        val sec = 1794635458L
        val inner = "{\"hello\":\"世界\"}".toByteArray(Charsets.UTF_8)
        val p = Envelope.wrapP(inner, sec, kd)
        val k0 = Crypto.k0FromSeed(sec).toByteArray(Charsets.ISO_8859_1)
        val rt = Crypto.aesCbcDecrypt(k0, Crypto.aesCbcEncrypt(k0, p))
        assertNotNull(rt)
        assertEquals(String(p, Charsets.UTF_8), String(rt!!, Charsets.UTF_8))
        // P 的 data 尾部带 JSON 转义换行：一个反斜杠字符 + n 字符
        val pText = String(p, Charsets.UTF_8)
        assertTrue(
            "P 尾部转义换行",
            pText.contains("\\" + "n\"") || pText.contains("\\n\"")
        )
        // decryptOffline 往返
        val key = Crypto.session16(
            kd["keyDataOne"]!!, kd["keyDataTwo"]!!, kd["keyDataThree"]!!, kd["keyDataFour"]!!
        )
        val data = java.util.Base64.getEncoder().encodeToString(inner)
        val outer = Crypto.aesCbcEncrypt(key, "{\"data\":\"$data\"}".toByteArray(Charsets.UTF_8))
        val resp = "{\"r\":\"" + java.util.Base64.getEncoder().encodeToString(outer) + "\"}"
        assertEquals("{\"hello\":\"世界\"}", Envelope.decryptOffline(resp.toByteArray(Charsets.UTF_8), kd))
    }

    /** xutils 签名形态：tokenSign 输出 32 位小写 hex。 */
    @Test
    fun sign_shape() {
        val sig = Sign.tokenSign(-1, "", 1794635458005L)
        assertEquals(32, sig.length)
    }

    private fun b64char(c: Char): Boolean =
        c.isLetterOrDigit() || c == '+' || c == '/' || c == '='

    private fun b64Any(s: String): ByteArray {
        val clean = StringBuilder()
        for (c in s) if (b64char(c)) clean.append(c)
        return java.util.Base64.getDecoder().decode(clean.toString())
    }
}
