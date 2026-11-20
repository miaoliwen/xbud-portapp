package com.yg.xbudport.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

// get_config 解析契约的回归测试：
// - runMode=1 + 有效时段内 → error 10000 + data.runRuleModel（完整规则）。
// - 有效时段外 → error 11016 + 空数据，时段从 message 解出（窗口规则）。
// - 其余 error（-10000 网关兜底等）→ null，走加载失败/默认阈值。
// 2026-11-19 实测：runMode 必须 1，0/2/3/4/5 一律 -10000/ERROR。

class HomeDataTest {

    @Test
    fun parseRunRule_fullRuleFromData() {
        val raw = """
            {"error":10000,"message":"成功",
             "data":{"policy":0,
                     "runRuleModel":{"dayGoal":3000,"weekCountGoal":5,"minDistance":1000,
                                     "minRunTime":300,"speedTop":3.0,"speedBottom":10.0,
                                     "stepTop":180,"stepBottom":60,
                                     "validTime":[{"start":"06:30","end":"21:00"}],
                                     "maxRunTime":1800,"pauseTime":120},
                     "geoFence":null}}
        """.trimIndent()
        val rule = HomeData.parseRunRule(raw)!!
        assertEquals(3000.0, rule.dayGoalM, 1e-9)
        assertEquals(5, rule.weekCountGoal)
        assertEquals(1000.0, rule.minDistanceM, 1e-9)
        assertEquals(300, rule.minRunTimeSec)
        assertEquals(3.0, rule.speedTop, 1e-9)
        assertEquals(10.0, rule.speedBottom, 1e-9)
        assertEquals(180, rule.stepTop)
        assertEquals(60, rule.stepBottom)
        assertEquals(listOf(ValidWindow("06:30", "21:00")), rule.validWindows)
        assertEquals(1800, rule.maxRunTimeSec)
        assertEquals(120L, rule.pauseTimeSec)
        assertEquals(0, rule.policy)
    }

    @Test
    fun parseRunRule_outOfWindow_extractsWindowFromMessage() {
        val raw = """
            {"error":11016,"message":"当前不在有效时间内，无法开启学分跑，您可进行休闲跑，跑步有效时间为06:30-21:00","data":null}
        """.trimIndent()
        val rule = HomeData.parseRunRule(raw)!!
        assertEquals(listOf(ValidWindow("06:30", "21:00")), rule.validWindows)
        // 窗口规则：门槛照常生效
        assertFalse(rule.isOpen(LocalDateTime.of(2026, 11, 20, 0, 15)))
        assertFalse(rule.isOpen(LocalDateTime.of(2026, 11, 20, 22, 0)))
        assertTrue(rule.isOpen(LocalDateTime.of(2026, 11, 20, 12, 0)))
        assertEquals("明日 06:30", rule.nextOpenHint(LocalDateTime.of(2026, 11, 20, 22, 0)))
        assertEquals("今日 06:30", rule.nextOpenHint(LocalDateTime.of(2026, 11, 20, 5, 0)))
    }

    @Test
    fun parseRunRule_gatewayError_returnsNull() {
        assertNull(HomeData.parseRunRule("""{"error":-10000,"message":"ERROR","data":null}"""))
        assertNull(HomeData.parseRunRule("{}"))
        assertNull(HomeData.parseRunRule("not json"))
        // 11016 但消息里没有可解析的时段 → 也按加载失败走
        assertNull(
            HomeData.parseRunRule(
                """{"error":11016,"message":"当前不在有效时间内","data":null}"""
            )
        )
    }
}
