package com.yg.xbudport.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// PointSelector 对齐原版 dn0.s2 的行为约束：
//   - 3 或 4 个点；必经点恰好出现一次；
//   - position 连续 0..n-1；
//   - 无必经点时不排序（position 保持 999）。
class PointSelectorTest {

    private fun cp(key: Int, lat: Double, lon: Double, fixed: Int) =
        Checkpoint(
            key = key,
            pid = 50000 + key,
            id = key,
            name = "P$key",
            lat = lat,
            lon = lon,
            isFixed = fixed,
        )

    // 约 300 m 间距的一串点位，覆盖校区的真实尺度
    private fun field(): List<Checkpoint> = listOf(
        cp(0, 31.913367, 120.972567, 0),
        cp(1, 31.913367, 120.975033, 1),   // 必经点，约 230 m 东
        cp(2, 31.916067, 120.972567, 0),   // 约 300 m 北
        cp(3, 31.913367, 120.970100, 0),   // 约 230 m 西
        cp(4, 31.910667, 120.972567, 0),   // 约 300 m 南
        cp(5, 31.916067, 120.975033, 0),
        cp(6, 31.910667, 120.970100, 0),
    )

    @Test
    fun `每次选出 3 或 4 个点且必经点恰好一次`() {
        repeat(200) {
            val ordered = PointSelector.order(field(), 1000, 31.913367 to 120.972567)
            assertTrue("点数 ${ordered.size}", ordered.size in 3..4)
            assertEquals(1, ordered.count { it.isFixed == 1 })
        }
    }

    @Test
    fun `position 连续且从 0 开始`() {
        repeat(100) {
            val ordered = PointSelector.order(field(), 2000, 31.913367 to 120.972567)
            assertEquals(ordered.indices.toList(), ordered.map { it.position })
        }
    }

    @Test
    fun `选出的点全部来自输入集合`() {
        val all = field().map { it.key }.toSet()
        repeat(100) {
            val ordered = PointSelector.order(field(), 1000, null)
            assertTrue(ordered.all { it.key in all })
            assertEquals(ordered.size, ordered.map { it.key }.toSet().size)
        }
    }

    @Test
    fun `没有必经点时保持原样不排序`() {
        val noFixed = field().map { it.copy(isFixed = 0) }
        val ordered = PointSelector.order(noFixed, 1000, null)
        assertEquals(noFixed.size, ordered.size)
        assertTrue(ordered.all { it.position == 999 })
    }

    @Test
    fun `单点场景不崩`() {
        val one = listOf(cp(0, 31.913367, 120.972567, 1))
        val ordered = PointSelector.order(one, 1000, null)
        assertEquals(1, ordered.size)
        assertEquals(0, ordered[0].position)
    }

    @Test
    fun `点位少于选点数时不会越界`() {
        val two = listOf(cp(0, 31.913367, 120.972567, 0), cp(1, 31.913367, 120.975033, 1))
        val ordered = PointSelector.order(two, 1000, null)
        assertFalse(ordered.isEmpty())
        assertTrue(ordered.size <= 2)
    }
}
