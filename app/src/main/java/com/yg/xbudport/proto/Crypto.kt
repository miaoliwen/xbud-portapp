package com.yg.xbudport.proto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.RSAPublicKeySpec
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Crypto — 协议密码学原语（纯 Kotlin，与 goxbud/pkg/proto + loginauth/crypto.py 同源）。
//
// 请求 P = {"data": b64(inner)+"\n", keyData×4, platform:2, timeStamp:sec}
// d = AES-128-CBC(K0, IV=0, PKCS7(P))；h = md5(P)；k = RSA-1024 PKCS#1 v1.5(N_REAL, K0)
// K0 = bionic TYPE_3 random(srand=epoch秒) 从 94 字符表取 16 字符（K-TRUTH §10.6/§11.1）
// 响应 r = AES-128-CBC(session16, IV=0)，session16 = F(keyData×4)（0x3b4b8 逆向）

object Crypto {
    const val ALPHABET94: String =
        "0123456789abcdefghijklmnopqrstuvwxyz" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
            "`~!@#\$%^&*()-_=+\\|[]{};:'\",.<>/?"

    // 设备 0x360fc 捕获的 RSAPublicKey（.so 内嵌 SPKI 是诱饵）
    val N_REAL: BigInteger = BigInteger(
        "c82dafc4297f1ae7938afb5537c570b2c9208bebf3781543c6ff0e258087b36b" +
            "f6a51c2aef90b1627d308c0017943440d90b9eadb39a673ed5c8a9815af18a" +
            "2d83e801005cb92c2de58cee5ff65e9e090551441ccc46b54f541f0983c998" +
            "4b5bd03f794617b967829403f5255b0d728e120386f2213732bb5679f0677ce" +
            "65b0f", 16
    )
    const val E_REAL = 65537

    /** bionic TYPE_3 random()：31 槽环形缓冲，就地 +=，首计算槽 slot3。 */
    class BRand(seed: Long) {
        private val r = IntArray(31)
        private var f = 3
        private var p = 0

        init {
            val u = (if (seed == 0L) 1L else seed).toInt() and -0x1
            r[0] = u
            for (i in 1 until 31) {
                val prev = r[i - 1]
                val hi = prev / 127773
                val lo = prev % 127773
                var w = 16807 * lo - 2836 * hi
                if (w < 0) w += 2147483647
                r[i] = w
            }
            repeat(310) {
                r[f] = r[f] + r[p]
                f = (f + 1) % 31
                p = (p + 1) % 31
            }
        }

        fun rand(): Int {
            r[f] = r[f] + r[p]
            val v = (r[f] shr 1) and 0x7fffffff
            f = (f + 1) % 31
            p = (p + 1) % 31
            return v
        }
    }

    /** epoch 秒 → 16 字节 K0（ASCII）。 */
    fun k0FromSeed(sec: Long): String {
        val b = BRand(sec)
        val out = CharArray(16)
        for (i in out.indices) out[i] = ALPHABET94[b.rand() % ALPHABET94.length]
        return String(out)
    }

    fun md5Hex(data: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    fun sha1Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }

    fun hmacSha1Hex(key: String, data: String): String {
        val m = Mac.getInstance("HmacSHA1")
        m.init(SecretKeySpec(key.toByteArray(Charsets.ISO_8859_1), "HmacSHA1"))
        return m.doFinal(data.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }
    }

    private fun pkcs7Pad(data: ByteArray): ByteArray {
        val p = 16 - data.size % 16
        return data + ByteArray(p) { p.toByte() }
    }

    fun aesCbcEncrypt(key: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == 16) { "aes key 必须 16 字节" }
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        return c.doFinal(pkcs7Pad(plain))
    }

    /** 解密 + 验证 PKCS7 填充；非法返回 null（解不开 = 信封/密钥不对）。 */
    fun aesCbcDecrypt(key: ByteArray, ct: ByteArray): ByteArray? {
        if (ct.isEmpty() || ct.size % 16 != 0) return null
        return try {
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            val out = c.doFinal(ct)
            val p = out[out.size - 1].toInt() and 0xff
            if (p < 1 || p > 16) return null
            for (i in out.size - p until out.size) {
                if ((out[i].toInt() and 0xff) != p) return null
            }
            out.copyOf(out.size - p)
        } catch (_: Exception) {
            null
        }
    }

    /** k 票据：RSA-1024 PKCS#1 v1.5(N_REAL, K0)。 */
    fun rsaEncryptK0(k0: ByteArray): ByteArray {
        val pub = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(N_REAL, BigInteger.valueOf(E_REAL.toLong())))
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, pub)
        return c.doFinal(k0)
    }

    private fun fold32(s: String): Int {
        var w = 0
        for (ch in s) {
            val c = ch.code and 0xff
            w = (((w shl 8) or c) xor (w ushr 24))
        }
        return w
    }

    private fun mix1(x: Int): Int =
        java.lang.Integer.rotateLeft(x, 8) xor java.lang.Integer.rotateLeft(x, 24) xor x
    // ror24 = rol8；ror8 = rol24

    /** session16 = F(keyDataOne..Four)，16 字节响应会话密钥（LE 四字组）。 */
    fun session16(g0: String, g1: String, g2: String, g3: String): ByteArray {
        var a = fold32(g0)
        var b = fold32(g1)
        var c = fold32(g2)
        var d = fold32(g3)

        var v = mix1(c xor a)
        b = b xor v
        d = d xor v
        v = mix1(b xor d)
        a = a xor v
        c = c xor v

        val v1 = ((c or d).inv()) xor b
        val d2 = a xor (v1 and c)
        val c2 = (c xor d) xor v1 xor d2
        val v2 = ((c2 or d2).inv()) xor v1
        val b2 = v2
        val a2 = (v2 and c2) xor d

        val out = ByteArray(16)
        fun put(off: Int, x: Int) {
            out[off] = (x and 0xff).toByte()
            out[off + 1] = ((x shr 8) and 0xff).toByte()
            out[off + 2] = ((x shr 16) and 0xff).toByte()
            out[off + 3] = ((x shr 24) and 0xff).toByte()
        }
        put(0, a2); put(4, b2); put(8, c2); put(12, d2)
        return out
    }

    private val random = Random()

    /** 4 个 8~12 字符 keyData（字母+数字），服务端按请求 P 派生响应密钥。 */
    fun randomKeyData(): Map<String, String> {
        val alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        fun gen(): String = buildString { repeat(8 + random.nextInt(5)) { append(alpha[random.nextInt(alpha.length)]) } }
        return mapOf(
            "keyDataOne" to gen(),
            "keyDataTwo" to gen(),
            "keyDataThree" to gen(),
            "keyDataFour" to gen(),
        )
    }

    fun uuid4(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = b.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }
}
