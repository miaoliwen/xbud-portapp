package com.yg.xbudport.proto

import org.json.JSONException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 数据读取错误码：与官方 HttpUtil.error 的异常分流同构（HttpUtil.java:82-130）。 */
enum class ReadErrCode { TIMEOUT, CONNECT, DNS, TLS, HTTP, PARSE, AUTH, UNKNOWN }

data class ReadError(val code: ReadErrCode, val message: String) {
    val tokenExpired: Boolean get() = code == ReadErrCode.AUTH
}

/**
 * 异常归一化：任何 Throwable 都映射为「错误码 + 用户文案」，
 * 原始栈信息不透到界面。网络族映射为可重试文案，解析族映射为数据异常，
 * token 族映射为需重新登录。对齐官方三段式：
 *   异常分流（HttpUtil.error）→ -999 解析降级（HttpUtil.success）→
 *   首页全屏错误 / 翻页 footer 错误（ex0.Cnew.onError）。
 */
object ErrNorm {
    fun of(t: Throwable): ReadError = when (t) {
        is ErrTokenExpired -> ReadError(ReadErrCode.AUTH, "登录已过期，请重新登录")
        is SocketTimeoutException -> ReadError(ReadErrCode.TIMEOUT, "网络超时，请稍后重试")
        is UnknownHostException -> ReadError(ReadErrCode.DNS, "无法解析服务器域名，请检查网络")
        is ConnectException -> ReadError(ReadErrCode.CONNECT, "无法连接服务器，请检查网络后重试")
        is SSLException -> ReadError(ReadErrCode.TLS, "安全连接失败，请检查系统时间或网络代理")
        is JSONException -> ReadError(ReadErrCode.PARSE, "服务端数据格式异常")
        is ProtoException -> ofProto(t)
        else -> ReadError(ReadErrCode.UNKNOWN, "请求失败，请稍后重试")
    }

    /** HTTP 状态码分流：4xx/5xx 各自文案，401/403 归为需重新登录。 */
    fun http(status: Int): ReadError = when (status) {
        401, 403 -> ReadError(ReadErrCode.AUTH, "登录已过期，请重新登录")
        in 500..599 -> ReadError(ReadErrCode.HTTP, "服务器开小差了（$status），请稍后重试")
        404 -> ReadError(ReadErrCode.HTTP, "服务不存在（404），请更新 App")
        else -> ReadError(ReadErrCode.HTTP, "网络请求失败（HTTP $status）")
    }

    private fun ofProto(t: ProtoException): ReadError {
        val m = t.message ?: ""
        val status = Regex("""HTTP\s+(\d{3})""").find(m)?.groupValues?.get(1)?.toIntOrNull()
        if (status != null) return http(status)
        return when {
            m.contains("解密") || m.contains("解析") || m.contains("结构") ->
                ReadError(ReadErrCode.PARSE, "服务端数据格式异常")
            m.contains("token") -> ReadError(ReadErrCode.AUTH, "登录已过期，请重新登录")
            else -> ReadError(ReadErrCode.UNKNOWN, "请求失败，请稍后重试")
        }
    }
}
