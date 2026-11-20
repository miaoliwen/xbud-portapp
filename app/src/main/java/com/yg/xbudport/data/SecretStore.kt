package com.yg.xbudport.data

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// 高德 key 不再写进 Manifest：assets/amap_secret.bin 为 AES-GCM 密文，
// 运行时用 包名 + 固定 pepper 派生的密钥解密，再交给 MapsInitializer.setApiKey。
// 客户端密钥无法做到绝对不可提取；这里只避免明文出现在 APK/配置中，抬高静态提取成本。
object SecretStore {
    private const val ASSET = "amap_secret.bin"
    private const val MAGIC = "XBK1"
    private const val PEPPER = "6e13c1edd16e88ca603673478a3669b110a405d95b8bf13a"

    @Volatile
    private var loaded = false

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cachedSecurityCode: String? = null

    @Volatile
    private var cachedPlatform: String = ""

    fun amapKey(ctx: Context): String? {
        load(ctx)
        return cachedKey
    }

    fun securityJsCode(ctx: Context): String? {
        load(ctx)
        return cachedSecurityCode
    }

    /** 高德 key 平台：android / web / 空。Android 原生 SDK 只接受 android。 */
    fun amapPlatform(ctx: Context): String {
        load(ctx)
        return cachedPlatform
    }

    @Synchronized
    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val raw = runCatching { ctx.assets.open(ASSET).use { it.readBytes() } }.getOrNull() ?: return
        if (raw.size < 32) return
        if (String(raw, 0, 4, Charsets.US_ASCII) != MAGIC) return

        val nonce = raw.copyOfRange(4, 16)
        val cipherText = raw.copyOfRange(16, raw.size)
        val aesKey = MessageDigest.getInstance("SHA-256").digest(
            ("xbudport/amap/v1|${ctx.packageName}|$PEPPER").toByteArray(Charsets.UTF_8)
        )
        val plain = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(ctx.packageName.toByteArray(Charsets.UTF_8))
                doFinal(cipherText)
            }
        }.getOrNull() ?: return

        val obj = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        cachedKey = obj.optString("key").takeIf { it.isNotBlank() }
        cachedSecurityCode = obj.optString("securityJsCode").takeIf { it.isNotBlank() }
        cachedPlatform = obj.optString("platform").lowercase()
    }
}
