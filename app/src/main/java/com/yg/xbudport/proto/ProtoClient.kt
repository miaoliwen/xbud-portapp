package com.yg.xbudport.proto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ProtoClient — 纯 Kotlin 协议客户端（零 Go 零 AAR）。
// 传输层 OkHttp：与原 App 同款网络栈（xutils 底层就是 okhttp），
// TLS 指纹天然是真机形状，PC 侧的 utls 指纹/DoH 自愈在端上都不需要。
// WAF 首连 11307 拦截照旧自动重发（见 authSend）。

const val HOST = "campusapi.xbud.run"

open class ProtoException(message: String) : Exception(message)
class ErrTokenExpired : ProtoException("token expired")

data class AuthOut(
    val error: Int,
    val message: String,
    val token: String,
    val uid: String,
    val verifyToken: String,
    val raw: String,
)

// header 明文字段序（与 Go headerJSON / Python Device.header 一致，服务端按 JSON 解，
// 同序只为对拍方便）。uid="-1"、token=""、tokenSign="" 为未登录态。
private fun headerJSON(
    sec: Long, id: DeviceIdentity, uid: String, token: String, tokenSign: String,
): String {
    val ms = sec * 1000 + 5
    val nonce = Crypto.uuid4()
    return "{\"physicPixel\":\"${id.pixel}\",\"logicPixel\":\"${id.logicPixel}\"," +
        "\"isRoot\":\"${id.isRoot}\",\"osType\":\"${id.osType}\",\"uid\":\"$uid\"," +
        "\"IMEI\":\"${id.imei}\",\"timeStamp\":\"$ms\",\"blMac\":\"${id.blMac}\"," +
        "\"nonce\":\"$nonce\",\"token\":\"$token\",\"tokenSign\":\"$tokenSign\"," +
        "\"cpuModel\":\"${id.cpuModel}\",\"deviceName\":\"${id.deviceName}\"," +
        "\"appInstallTime\":\"${id.appInstall}\",\"androidId\":\"${id.androidID}\"," +
        "\"DeviceId\":\"${id.deviceID}\",\"appUpdateTime\":\"${id.appUpdate}\"," +
        "\"wifiMac\":\"${id.wifiMac}\",\"CustomDeviceId\":\"${id.customID}\"," +
        "\"osVersion\":\"${id.osVersion}\"}"
}

/** 认证族固定 UA（loginauth 实测形态）。 */
const val AUTH_UA =
    "Dalvik/2.1.0 (Linux; U; Android 15; SM-A5260 Build/d8107ca.0)"

private val http = okhttp3.OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .writeTimeout(25, TimeUnit.SECONDS)
    .build()

private fun httpPost(path: String, headerSign: String, body: String, ua: String, extra: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
    val builder = okhttp3.Request.Builder()
        .url("https://$HOST$path")
        .header("Accept", "application/json")
        .header("headerSign", headerSign)
        .header("appVersion", ProtoClient.APP_VERSION)
        .header("Content-Type", "application/json;charset=UTF-8")
        .header("User-Agent", ua)
        .header("Connection", "close")
        .post(okhttp3.RequestBody.create(null, body.toByteArray(Charsets.UTF_8)))
    for ((k, v) in extra) builder.header(k, v)
    http.newCall(builder.build()).execute().use { resp ->
        return resp.code to (resp.body?.bytes() ?: ByteArray(0))
    }
}

private fun httpGet(path: String, headerSign: String, ua: String): Pair<Int, ByteArray> {
    val req = okhttp3.Request.Builder()
        .url("https://$HOST$path")
        .header("Accept", "application/json")
        .header("headerSign", headerSign)
        .header("appVersion", ProtoClient.APP_VERSION)
        .header("User-Agent", ua)
        .header("Connection", "close")
        .get()
        .build()
    http.newCall(req).execute().use { resp ->
        return resp.code to (resp.body?.bytes() ?: ByteArray(0))
    }
}

private fun isTokenRejected(status: Int, body: ByteArray): Boolean =
    status == 401 || String(body, Charsets.UTF_8).contains("\"status\":401")

private fun isEnvelopeAccepted(status: Int, body: ByteArray): Boolean =
    status == 200 && body.isNotEmpty()

/** 11307 拦截判定：解出的 error 码。 */
private fun isWafIntercept(rb: ByteArray, bodyKD: Map<String, String>, headerKD: Map<String, String>): Boolean {
    val pt = Envelope.decryptAny(rb, bodyKD, headerKD) ?: return false
    return runCatching { JSONObject(pt).optInt("error") == 11307 }.getOrDefault(false)
}

// —— 认证族（未登录态）——

private fun sendAuth(
    path: String, sec: Long, headerInner: String, bodyInner: String,
    extraHeaders: Map<String, String> = emptyMap(),
): Triple<Int, ByteArray, Pair<Map<String, String>, Map<String, String>>> {
    val (hEnv, hKD) = Envelope.offlineEnvelope(headerInner.toByteArray(Charsets.UTF_8), sec)
    val (bEnv, bKD) = Envelope.offlineEnvelope(bodyInner.toByteArray(Charsets.UTF_8), sec)
    var (st, rb) = httpPost(path, hEnv, bEnv, AUTH_UA, extraHeaders)
    // WAF 对新源首连拦一次（11307），信封字节无错——自动重发两次
    var tryn = 0
    while (tryn < 2 && st == 200 && rb.isNotEmpty() && isWafIntercept(rb, bKD, hKD)) {
        Thread.sleep(2000)
        val again = httpPost(path, hEnv, bEnv, AUTH_UA, extraHeaders)
        st = again.first
        rb = again.second
        tryn++
    }
    return Triple(st, rb, Pair(bKD, hKD))
}

private fun authResultOf(path: String, st: Int, rb: ByteArray, bKD: Map<String, String>, hKD: Map<String, String>): AuthOut {
    if (st == 200 && rb.isEmpty()) throw ProtoException("$path 信封被拒（HTTP 200 + 0B）")
    if (st != 200) {
        val tail = String(rb, Charsets.UTF_8).take(300)
        throw ProtoException("$path HTTP $st: $tail")
    }
    val pt = Envelope.decryptAny(rb, hKD, bKD)
        ?: throw ProtoException("$path 响应解密失败")
    val o = JSONObject(pt)
    return AuthOut(
        error = o.optInt("error", -1),
        message = o.optString("message"),
        token = findToken(o).first,
        uid = findToken(o).second,
        verifyToken = findVerifyToken(o),
        raw = pt,
    )
}

private fun findToken(obj: JSONObject): Pair<String, String> {
    val data = obj.optJSONObject("data")
    if (data != null) {
        for (key in listOf("token", "requestToken", "request_token")) {
            val v = data.optString(key, "")
            if (v.isNotEmpty()) return v to (if (data.optLong("uid", 0L) != 0L) data.optLong("uid").toString() else "")
        }
        for (sub in listOf("userInfo", "user", "userInfoBean")) {
            val m = data.optJSONObject(sub)
            if (m != null) {
                val v = m.optString("token", "")
                if (v.isNotEmpty()) return v to (if (m.optLong("uid", 0L) != 0L) m.optLong("uid").toString() else "")
            }
        }
    }
    var token = ""
    var uid = 0L
    val stack = ArrayDeque<JSONObject>()
    stack.add(obj)
    while (stack.isNotEmpty()) {
        val cur = stack.removeLast()
        for (key in cur.keys()) {
            when (val v = cur.opt(key)) {
                is JSONObject -> stack.add(v)
                is org.json.JSONArray -> {
                    for (i in 0 until v.length()) {
                        val item = v.optJSONObject(i)
                        if (item != null) stack.add(item)
                    }
                }
                is String -> if (key == "token" && token.isEmpty() && v.length == 36 && v.count { it == '-' } == 4) token = v
                else -> if (key == "uid" && uid == 0L) uid = cur.optLong("uid", 0L)
            }
        }
    }
    return token to (if (uid != 0L) uid.toString() else "")
}

private fun findVerifyToken(obj: JSONObject): String {
    val stack = ArrayDeque<JSONObject>()
    stack.add(obj)
    while (stack.isNotEmpty()) {
        val cur = stack.removeLast()
        for (key in cur.keys()) {
            when (val v = cur.opt(key)) {
                is JSONObject -> stack.add(v)
                is org.json.JSONArray -> {
                    for (i in 0 until v.length()) {
                        val item = v.optJSONObject(i)
                        if (item != null) stack.add(item)
                    }
                }
                is String -> if (key == "verifyToken" && v.isNotEmpty()) return v
            }
        }
    }
    return ""
}

/** 已登录会话：一个账号一个实例（全部 suspend，调用方在 Dispatchers.IO 语义里用）。 */
class Session internal constructor(
    val uidStr: String,
    internal var token: String,
    internal val identity: DeviceIdentity,
    internal val dir: String,
) {
    private val uid: Long = uidStr.toLongOrNull() ?: 0L

    private fun headerEnv(sec: Long): Pair<String, Map<String, String>> =
        Envelope.offlineEnvelope(
            headerJSON(sec, identity, uidStr, token, Sign.tokenSign(uid, token, sec * 1000 + 5))
                .toByteArray(Charsets.UTF_8), sec)

    private fun sendFull(path: String, innerBody: String): Triple<Int, ByteArray, Pair<Map<String, String>, Map<String, String>>> {
        val sec = System.currentTimeMillis() / 1000
        val (hEnv, hKD) = headerEnv(sec)
        val (bEnv, bKD) = Envelope.offlineEnvelope(innerBody.toByteArray(Charsets.UTF_8), sec)
        return sendWithRetry(path, hEnv, bEnv, Pair(bKD, hKD), userAgentFor(identity))
    }

    private fun sendWithRetry(
        path: String, hEnv: String, bEnv: String, kdPair: Pair<Map<String, String>, Map<String, String>>, ua: String,
    ): Triple<Int, ByteArray, Pair<Map<String, String>, Map<String, String>>> {
        var (st, rb) = httpPost(path, hEnv, bEnv, ua)
        val (bKD, hKD) = kdPair
        var tryn = 0
        while (tryn < 2 && st == 200 && rb.isNotEmpty() && isWafIntercept(rb, bKD, hKD)) {
            Thread.sleep(2000)
            val again = httpPost(path, hEnv, bEnv, ua)
            st = again.first
            rb = again.second
            tryn++
        }
        return Triple(st, rb, kdPair)
    }

    private fun decrypt(res: Triple<Int, ByteArray, Pair<Map<String, String>, Map<String, String>>>): String {
        val (st, rb, kd) = res
        val (bKD, hKD) = kd
        if (isTokenRejected(st, rb)) throw ErrTokenExpired()
        if (!isEnvelopeAccepted(st, rb)) throw ProtoException("信封未受理（HTTP $st，body ${rb.size} B）")
        return Envelope.decryptAny(rb, bKD, hKD) ?: throw ProtoException("响应解密失败")
    }

    /** 任意信封 POST：未定型端点全走这里。 */
    suspend fun call(path: String, bodyJSON: String): String = withContext(Dispatchers.IO) {
        decrypt(sendFull(path, bodyJSON))
    }

    /** GET get_info（无 body 端点）。 */
    suspend fun getInfo(): String = withContext(Dispatchers.IO) {
        val sec = System.currentTimeMillis() / 1000
        val (hEnv, hKD) = headerEnv(sec)
        val (st, rb) = httpGet("/v4/user/account/get_info", hEnv, userAgentFor(identity))
        if (isTokenRejected(st, rb)) throw ErrTokenExpired()
        if (st != 200 || rb.isEmpty()) throw ProtoException("get_info HTTP $st body ${rb.size} B")
        Envelope.decryptOffline(rb, hKD) ?: throw ProtoException("get_info 响应解密失败")
    }

    suspend fun health(): String = records(1, 1)

    suspend fun getConfig(): String =
        // runMode 只有 1（学分跑）被服务端受理：0/2/3/4/5 一律
        // {"error":-10000,"message":"ERROR"}（2026-11-19 实测，见 HomeDataTest）。
        call("/v1/sport/run/get_config", "{\"runMode\":1,\"ruleUpdateTime\":0,\"geoFenceUpdateTime\":0,\"selectUnid\":1001104,\"operateType\":0}")

    suspend fun campusPoints(): String =
        call("/v1/university/get_campus_point_list", "{\"unid\":1001104,\"createdTime\":\"\"}")

    suspend fun fetchFixedPoints(distanceM: Long): String {
        val d = listOf(500L, 1000L, 2000L, 3000L, 4000L).minByOrNull { Math.abs(it - distanceM) } ?: 1000L
        val path = "/v1/sport/run/get_fixed_point/1/$d"
        val body = "{\"longitude\":120.972567,\"latitude\":31.913367,\"selectedUnid\":1001104," +
            "\"uuid\":\"00000000-0000-0000-0000-000000000000\",\"runec\":0," +
            "\"sign\":\"${pointsSign(d.toInt())}\"}"
        return call(path, body)
    }

    suspend fun records(page: Long, pageSize: Long): String =
        // 原 App 的参数名是 pageNum（ex0.java:443），不是 page；page 会被服务端忽略。
        call("/v1/sport/run/get_record_list", "{\"pageNum\":$page,\"pageSize\":$pageSize}")

    suspend fun recordDetail(rrid: Long, uuid: String): String {
        val o = JSONObject().put("rrid", rrid)
        if (uuid.isNotEmpty()) o.put("uuid", uuid)
        return call("/v4/sport/run/get_record_detail", o.toString())
    }

    suspend fun logout(): String {
        val res = sendFull("/v1/user/login/logout", "{}")
        val (st, rb, kd) = res
        val (bKD, hKD) = kd
        if (isTokenRejected(st, rb)) return "{\"error\":401,\"message\":\"token 已失效\"}"
        if (!isEnvelopeAccepted(st, rb)) throw ProtoException("logout 信封未受理（HTTP $st）")
        return Envelope.decryptAny(rb, bKD, hKD) ?: "{\"error\":-1,\"message\":\"响应解密失败\"}"
    }

    /** 学分跑真实提交：见 RealRun.saveRealRun。 */
    suspend fun submitRun(
        runJSON: String,
        onProgress: (String, Float) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        RealRun.saveRealRun(uidStr.toLongOrNull() ?: 0L, identity, token, runJSON, onProgress)
    }
}

// get_fixed_point 的 sign：md5("http://campusapi.xbud.run" + path + salt4)
private fun pointsSign(d: Int): String {
    val path = "/v1/sport/run/get_fixed_point/1/$d"
    return Crypto.md5Hex(("http://campusapi.xbud.run$path" + "2slhe02lsfiwowlcixisla_sls-_slaor").toByteArray(Charsets.UTF_8))
}

/** 门面：App 各屏的唯一入口（替代原 xbudmobile 绑定层）。 */
object ProtoClient {
    const val APP_VERSION = "2.6.6"
    val version: String get() = "0.4.0-pure-kotlin"

    // —— 认证三件套 ——
    fun login(dir: String, username: String, password: String, validate: String, profileName: String): AuthOut {
        if (validate.isBlank()) throw ProtoException("缺少易盾 validate（App 先跑 WebView 验证码）")
        val id = loginIdentity(dir, profileName)
        val sec = System.currentTimeMillis() / 1000
        val hdr = headerJSON(sec, id, "-1", "", "")
        val body = "{\"loginType\":1,\"device_model\":\"${id.deviceName}\",\"os_version\":\"${id.osVersion}\"," +
            "\"mac_address\":\"${id.blMac}\",\"imei\":\"${id.imei}\",\"uuid\":\"${Crypto.uuid4()}\"," +
            "\"neteaseValidate\":\"$validate\"}"
        val auth = "Basic " + android.util.Base64.encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val (st, rb, kd) = sendAuth(
            "/v4/user/login/login", sec, hdr, body,
            mapOf("Authorization" to auth),
        )
        return authResultOf("/v4/user/login/login", st, rb, kd.first, kd.second)
    }

    fun sendRegisterCode(dir: String, phone: String, validate: String, type: Long, profileName: String): AuthOut {
        if (validate.isBlank()) throw ProtoException("缺少易盾 validate（App 先跑 WebView 验证码）")
        val id = loginIdentity(dir, profileName)
        val sec = System.currentTimeMillis() / 1000
        val hdr = headerJSON(sec, id, "-1", "", "")
        val body = "{\"phone\":\"$phone\",\"code\":\"$validate\",\"type\":$type}"
        val (st, rb, kd) = sendAuth("/v1/user/login/register_verify_code", sec, hdr, body)
        return authResultOf("/v1/user/login/register_verify_code", st, rb, kd.first, kd.second)
    }

    fun register(dir: String, phone: String, verifyToken: String, code: String, password: String, profileName: String): AuthOut {
        val id = loginIdentity(dir, profileName)
        val sec = System.currentTimeMillis() / 1000
        val hdr = headerJSON(sec, id, "-1", "", "")
        val body = "{\"phone\":\"$phone\",\"verifyToken\":\"$verifyToken\",\"code\":\"$code\",\"password\":\"$password\"}"
        val (st, rb, kd) = sendAuth("/v1/user/login/register", sec, hdr, body)
        return authResultOf("/v1/user/login/register", st, rb, kd.first, kd.second)
    }

    fun resetPassword(dir: String, phone: String, verifyToken: String, code: String, password: String, profileName: String): AuthOut {
        val id = loginIdentity(dir, profileName)
        val sec = System.currentTimeMillis() / 1000
        val hdr = headerJSON(sec, id, "-1", "", "")
        val body = "{\"phone\":\"$phone\",\"verifyToken\":\"$verifyToken\",\"code\":\"$code\",\"password\":\"$password\"}"
        val (st, rb, kd) = sendAuth("/v1/user/login/reset_password", sec, hdr, body)
        return authResultOf("/v1/user/login/reset_password", st, rb, kd.first, kd.second)
    }

    fun newSession(dir: String, uidStr: String, token: String, profileName: String): Session {
        require(uidStr.toLongOrNull() != null) { "uid 不是数字: $uidStr" }
        val store = ProfileStore.load(dir)
        val prof = profileName.trim().takeIf { it.isNotEmpty() }?.let { store.ensure(it) }
        var id = defaultIdentity(uidStr.toLong()).let { base ->
            base.copy(
                deviceName = prof?.model ?: base.deviceName,
                cpuModel = prof?.cpu ?: base.cpuModel,
                pixel = prof?.pixel ?: base.pixel,
                logicPixel = prof?.logicPixel ?: base.logicPixel,
                osVersion = prof?.osVersion ?: base.osVersion,
                isRoot = prof?.isRoot ?: base.isRoot,
                androidID = prof?.androidId ?: base.androidID,
                deviceID = prof?.deviceId ?: base.deviceID,
                customID = prof?.customId ?: base.customID,
                imei = prof?.imei ?: base.imei,
                blMac = prof?.blMac ?: base.blMac,
                wifiMac = prof?.wifiMac ?: base.wifiMac,
                appInstall = prof?.appInstall ?: base.appInstall,
                appUpdate = prof?.appUpdate ?: base.appUpdate,
                platform = prof?.platform ?: base.platform,
            )
        }
        return Session(uidStr, token, id, dir)
    }

    // —— 画像管理 ——
    fun presetList(): List<String> = presetNames()

    fun profilesList(dir: String): List<String> = ProfileStore.load(dir).names()

    /** 导入单个 profile 或整个 device_profiles.json（profiles 数组展开），返回导入数。 */
    fun profileImport(dir: String, text: String): Int {
        val root = JSONObject(text)
        val arr = root.optJSONArray("profiles")
        return if (arr != null) {
            var n = 0
            val store = ProfileStore.load(dir)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                store.import(Profile.fromJSON(o))
                n++
            }
            n
        } else {
            ProfileStore.load(dir).import(Profile.fromJSON(root))
            1
        }
    }
}
