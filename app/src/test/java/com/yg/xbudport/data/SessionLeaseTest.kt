package com.yg.xbudport.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 续跳 body 契约：字段名与 xbudsrv /session/lease 对齐，token 必须原样带上。

class SessionLeaseTest {

    @Test
    fun leaseBody_fieldsMatchServerContract() {
        val body = JSONObject(SessionLease.leaseBody("894824", "19551338692", "tok-abc", "小米14"))
        assertEquals("894824", body.getString("uid"))
        assertEquals("19551338692", body.getString("username"))
        assertEquals("tok-abc", body.getString("token"))
        assertEquals("小米14", body.getString("profile"))
        assertEquals("", body.getString("proxy"))
    }

    @Test
    fun leaseBody_blankProfileAndProxyAreValid() {
        val body = JSONObject(SessionLease.leaseBody("1", "", "t", ""))
        assertTrue(body.has("uid"))
        assertTrue(body.has("token"))
        assertEquals("", body.getString("profile"))
        assertEquals("", body.getString("proxy"))
    }
}
