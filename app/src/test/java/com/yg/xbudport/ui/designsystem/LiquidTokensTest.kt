package com.yg.xbudport.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 令牌闸门：玻璃材质的前景/背景配对必须满足 WCAG AA（≥ 4.5:1）。
 * 配对按最坏背景演算——浅色玻璃取页面渐变最暗端、深色玻璃取最亮端、
 * 地图 HUD 取深色地图区。改任何令牌先跑这里。
 */
class LiquidTokensTest {

    // —— WCAG 2.x 相对亮度与对比度 ——

    private fun channelLin(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun Float.pow(e: Float) = Math.pow(this.toDouble(), e.toDouble()).toFloat()

    private fun luminance(argb: Int): Float {
        val r = channelLin(((argb shr 16) and 0xFF) / 255f)
        val g = channelLin(((argb shr 8) and 0xFF) / 255f)
        val b = channelLin((argb and 0xFF) / 255f)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun ratio(fg: Color, bg: Color): Float {
        val lf = luminance(fg.toArgb())
        val lb = luminance(bg.toArgb())
        val hi = maxOf(lf, lb)
        val lo = minOf(lf, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** tint 以 alpha 叠到 bg 上（白/黑 tint 在 sRGB 直混是精确的） */
    private fun over(tint: Color, alpha: Float, bg: Color): Color {
        val t = tint.toArgb()
        val b = bg.toArgb()
        fun mix(ts: Int, bs: Int) = Math.round(alpha * ts + (1f - alpha) * bs).toInt()
        return Color(
            (0xFF shl 24) or
                (mix((t shr 16) and 0xFF, (b shr 16) and 0xFF) shl 16) or
                (mix((t shr 8) and 0xFF, (b shr 8) and 0xFF) shl 8) or
                mix(t and 0xFF, b and 0xFF)
        )
    }

    private fun assertAA(pair: String, fg: Color, bg: Color) {
        val r = ratio(fg, bg)
        assertTrue("$pair 对比度 %.2f < 4.5".format(r), r >= 4.5f)
    }

    // —— 浅色玻璃：tint 叠到页面渐变最暗端 ——

    @Test
    fun lightGlassPairsMeetAA() {
        val p = LightPalette
        val glass = over(p.glassTint, p.glassTintAlpha, p.pageBottom)
        assertAA("textPrimary/浅色玻璃", p.textPrimary, glass)
        assertAA("textSecondary/浅色玻璃", p.textSecondary, glass)
        assertAA("textTertiary/浅色玻璃", p.textTertiary, glass)
        assertAA("primaryText/浅色玻璃", p.primaryText, glass)
        assertAA("error/浅色玻璃", p.error, glass)
        assertAA("success/浅色玻璃", p.success, glass)
        assertAA("warning/浅色玻璃", p.warning, glass)
        assertAA("白字/主按钮实底", Color.White, p.solidButton)
    }

    // —— 深色玻璃：tint 叠到页面渐变最亮端 ——

    @Test
    fun darkGlassPairsMeetAA() {
        val p = DarkPalette
        val glass = over(p.glassTint, p.glassTintAlpha, p.pageTop)
        assertAA("textPrimary/深色玻璃", p.textPrimary, glass)
        assertAA("textSecondary/深色玻璃", p.textSecondary, glass)
        assertAA("textTertiary/深色玻璃", p.textTertiary, glass)
        assertAA("primaryText/深色玻璃", p.primaryText, glass)
        assertAA("error/深色玻璃", p.error, glass)
        assertAA("success/深色玻璃", p.success, glass)
        assertAA("warning/深色玻璃", p.warning, glass)
        assertAA("白字/深色主按钮实底", Color.White, p.solidButton)
    }

    // —— 地图 HUD：白 0.80 遮罩压在最暗地图区上，前景仍要 AA ——

    @Test
    fun hudPairsMeetAA() {
        val hud = LiquidMaterials.hud(LightPalette)
        val darkestMap = Color(0xFF0B1D13) // 公园/水体类深色底图
        val scrim = over(hud.tint, hud.tintAlpha, darkestMap)
        assertAA("textPrimary/HUD", hud.contentColor, scrim)
        assertAA("error/HUD", LightPalette.error, scrim)
        assertAA("success/HUD", LightPalette.success, scrim)
    }

    // —— 材质不变量：采样材质必须带模糊；alpha 在界内 ——

    @Test
    fun materialInvariants() {
        val materials = listOf(
            LiquidMaterials.chrome(LightPalette),
            LiquidMaterials.chrome(DarkPalette),
            LiquidMaterials.sheet(LightPalette),
            LiquidMaterials.sheet(DarkPalette),
            LiquidMaterials.card(LightPalette),
            LiquidMaterials.card(DarkPalette),
            LiquidMaterials.field(LightPalette),
            LiquidMaterials.field(DarkPalette),
            LiquidMaterials.hud(LightPalette),
            LiquidMaterials.hud(DarkPalette),
            LiquidMaterials.tonal(LightPalette),
            LiquidMaterials.ghost(LightPalette),
        )
        materials.forEach { m ->
            assertTrue("blurRadius 为负: $m", m.blurRadius.value >= 0f)
            assertTrue("tintAlpha 越界: $m", m.tintAlpha in 0f..1f)
            assertTrue("highlightAlpha 越界: $m", m.highlightAlpha in 0f..1f)
            assertTrue("shadowAlpha 越界: $m", m.shadowAlpha in 0f..1f)
            if (m.sampled) {
                assertTrue("采样材质必须带模糊: $m", m.blurRadius.value > 0f)
            }
        }
    }
}
