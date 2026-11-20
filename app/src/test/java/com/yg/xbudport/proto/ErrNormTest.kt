package com.yg.xbudport.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// ErrNorm 归一化契约：异常类型 → 错误码 + 用户文案，token 族判定需重新登录。

class ErrNormTest {

    @Test
    fun timeout_mapsToTimeout() {
        val e = ErrNorm.of(SocketTimeoutException("timeout"))
        assertEquals(ReadErrCode.TIMEOUT, e.code)
        assertTrue(e.message.contains("超时"))
    }

    @Test
    fun dns_mapsToDns() {
        val e = ErrNorm.of(UnknownHostException("campusapi.xbud.run"))
        assertEquals(ReadErrCode.DNS, e.code)
    }

    @Test
    fun tokenExpired_mapsToAuth() {
        val e = ErrNorm.of(ErrTokenExpired())
        assertEquals(ReadErrCode.AUTH, e.code)
        assertTrue(e.tokenExpired)
    }

    @Test
    fun protoHttpStatus_mapsToHttpBranch() {
        val e = ErrNorm.of(ProtoException("records HTTP 503"))
        assertEquals(ReadErrCode.HTTP, e.code)
        assertTrue(e.message.contains("503"))
    }

    @Test
    fun protoHttp401_mapsToAuth() {
        val e = ErrNorm.of(ProtoException("get_info HTTP 401"))
        assertEquals(ReadErrCode.AUTH, e.code)
        assertTrue(e.tokenExpired)
    }

    @Test
    fun protoParseFailure_mapsToParse() {
        val e = ErrNorm.of(ProtoException("详情结构异常"))
        assertEquals(ReadErrCode.PARSE, e.code)
        assertEquals("服务端数据格式异常", e.message)
    }

    @Test
    fun unknownException_neverLeaksStackTrace() {
        val e = ErrNorm.of(IllegalStateException("boom at com.yg.internal.Secret"))
        assertEquals(ReadErrCode.UNKNOWN, e.code)
        assertTrue(!e.message.contains("com.yg"))
    }
}
