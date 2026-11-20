package com.yg.xbudport.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 数据读取防崩溃契约的回归测试：
// 畸形响应体 → null（错误态）；合法空数据 → 空列表（空态）；脏值 → 钳制。

class HistoryDataTest {

    private fun listBody(item: String) =
        """{"error":10000,"data":[$item]}"""

    @Test
    fun parseList_malformedBody_returnsNull() {
        assertNull(HistoryData.parseList(""))
        assertNull(HistoryData.parseList("<html>502 Bad Gateway</html>"))
        assertNull(HistoryData.parseList("{\"data\": [}"))
    }

    @Test
    fun parseList_wrongShape_fallsToEmptyState() {
        // 合法 JSON 但 data 不是数组：走空态而不是错误态。
        assertEquals(emptyList<RunRecordItem>(), HistoryData.parseList("{\"data\": \"oops\"}"))
    }

    @Test
    fun parseList_emptyData_returnsEmptyList() {
        assertEquals(emptyList<RunRecordItem>(), HistoryData.parseList("""{"error":10000,"data":[]}"""))
        assertEquals(emptyList<RunRecordItem>(), HistoryData.parseList("""{"error":10000}"""))
    }

    @Test
    fun parseList_dirtyValues_areClamped() {
        val raw = listBody(
            """{"rrid":7,"uuid":"u","startTime":-5,"stopTime":-9,"totalDis":-100.0,"validDis":3000.0,"totalTime":999999,"validTime":-1,"complete":true,"status":0,"avgStepFreq":-20}"""
        )
        val item = HistoryData.parseList(raw)!!.single()
        assertEquals(0L, item.startMs)
        assertEquals(0L, item.stopMs)
        assertEquals(0.0, item.totalDisM, 1e-9)
        assertEquals(3000.0, item.validDisM, 1e-9)
        assertEquals(86_400, item.totalTimeSec)
        assertEquals(0, item.validTimeSec)
        assertEquals(0, item.avgStepFreq)
    }

    @Test
    fun pace_isClampedAndNeverNegative() {
        val raw = listBody("""{"rrid":1,"totalDis":100.0,"totalTime":-50}""")
        val item = HistoryData.parseList(raw)!!.single()
        assertEquals(0.0, item.paceMinPerKm, 1e-9)
    }

    @Test
    fun parseDetail_structureMissing_returnsNull() {
        assertNull(HistoryData.parseDetail("not json"))
        assertNull(HistoryData.parseDetail("""{"error":10000}"""))
    }

    @Test
    fun kmText_negative_staysZero() {
        assertEquals("0.00", HistoryData.kmText(-1234.0))
    }
}
