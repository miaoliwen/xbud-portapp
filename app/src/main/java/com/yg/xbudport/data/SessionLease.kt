package com.yg.xbudport.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// SessionLease — 会话租约客户端：app 活着就每 5 分钟向 xbudsrv 续跳一次。
//
// 解决的问题：app 被删除后，服务端会话不能卡死（旧 token 挂到自然过期、
// 重登撞 10121 设备冲突）。Android 没有「自身被卸载」回调，所以：
//   app 活着 → 心跳续租
//   app 没了（删除/强杀/关机）→ 心跳停 → 服务端租约过期 → 自动厂商 logout
//
// 心跳失败只记日志，绝不影响 App 任何功能；服务器不可达时租约自然过期，
// 语义正好是「app 不在了就放会话」。
object SessionLease {
    private const val TAG = "SessionLease"
    private const val PREF = "xbud_port"
    private const val KEY_URL = "lease_url"

    /** 默认走宿主机：模拟器 10.0.2.2 = PC。真机/云机改成 PC 局域网 IP，
     *  例：adb shell am start -n com.yg.xbudport/.MainActivity --es lease_url http://192.168.1.9:18788 */
    const val DEFAULT_URL = "http://10.0.2.2:18788"
    private const val BEAT_MS = 5 * 60 * 1000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var beatJob: Job? = null

    fun baseUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun setBaseUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url.trimEnd('/')).apply()
    }

    /** 进程启动且已登录时开始续跳；幂等，重复调用不叠加循环。 */
    fun start(ctx: Context, store: AccountStore) {
        if (!store.isLoggedIn) return
        if (beatJob?.isActive == true) return
        beatJob = scope.launch {
            while (isActive) {
                beat(ctx, store)
                delay(BEAT_MS)
            }
        }
    }

    /** 登录成功：换 token 重启循环并立刻续跳（服务端会顶掉旧会话）。 */
    fun onLoggedIn(ctx: Context, store: AccountStore) {
        beatJob?.cancel()
        beatJob = scope.launch {
            beat(ctx, store)
            while (isActive) {
                delay(BEAT_MS)
                beat(ctx, store)
            }
        }
    }

    /** 停止续跳（登出/会话失效时调用；不再自动踢，会话已在 app 侧处理）。 */
    fun stop() {
        beatJob?.cancel()
        beatJob = null
    }

    /** 主动释放租约；app 侧登出后调，避免看门狗再踢一枚已死的 token。 */
    fun release(ctx: Context, uid: String) {
        stop()
        if (uid.isBlank()) return
        scope.launch {
            runCatching {
                val req = Request.Builder()
                    .url(baseUrl(ctx) + "/session/lease?uid=" + uid)
                    .delete()
                    .build()
                http.newCall(req).execute().use { resp ->
                    Log.i(TAG, "release uid=$uid HTTP ${resp.code}")
                }
            }
        }
    }

    private suspend fun beat(ctx: Context, store: AccountStore) {
        val uid = store.uid ?: return
        val token = store.token ?: return
        runCatching {
            val body = leaseBody(
                uid = uid,
                username = store.username ?: "",
                token = token,
                profile = store.profileName ?: "",
            ).toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(baseUrl(ctx) + "/session/lease").post(body).build()
            http.newCall(req).execute().use { resp ->
                if (resp.code != 200) Log.w(TAG, "beat uid=$uid HTTP ${resp.code}")
            }
        }.onFailure { Log.w(TAG, "beat uid=$uid 失败（不影响 App）：${it.javaClass.simpleName}") }
    }

    /** 续跳 body；独立成函数便于单测。 */
    fun leaseBody(uid: String, username: String, token: String, profile: String): String =
        JSONObject().apply {
            put("uid", uid)
            put("username", username)
            put("token", token)
            put("profile", profile)
            put("proxy", "")
        }.toString()
}
