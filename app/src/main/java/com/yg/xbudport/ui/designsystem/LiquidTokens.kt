package com.yg.xbudport.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 液态玻璃设计系统的单一事实来源：间距 / 圆角 / 模糊 / 不透明度 / 字阶，
// 以及「材质」（glass recipe）与双主题 Palette。
//
// 对比度约定：玻璃是半透明材质，每个材质都带 tint + contentColor 配对。
// 配对按最坏背景演算过（页面渐变最暗/最亮端、地图最暗区），全部 ≥ 4.5:1（WCAG AA），
// 由 LiquidTokensTest 锁死——改令牌先跑测试。

// —— 间距网格（4dp 基准）——
object LiquidSpace {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
    val Page = 16.dp
}

// —— 固定尺寸（chrome 不随内容伸缩，scaffold 据此算 content padding）——
object LiquidSize {
    val TopBar = 56.dp
    val BottomBar = 64.dp
    val Button = 50.dp
    val ButtonSmall = 40.dp
    val Field = 50.dp
    val Row = 52.dp
    val Avatar = 64.dp
    val TouchTarget = 48.dp
    val SheetHandle = 32.dp
}

// —— 圆角 ——
object LiquidRadius {
    val Chip = 8.dp
    val Field = 12.dp
    val Button = 14.dp
    val Card = 20.dp
    val Pill = 16.dp
    val Sheet = 28.dp
    val Dialog = 28.dp
}

// —— 字阶：行高 = 字号 × 1.29–1.4；数字样式强制等宽（tnum）防刷新抖动 ——
object LiquidType {
    val Display: TextStyle = TextStyle(
        fontSize = 34.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
    val Title: TextStyle = TextStyle(
        fontSize = 20.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val Headline: TextStyle = TextStyle(
        fontSize = 17.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val Body: TextStyle = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    )
    val Label: TextStyle = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    )
    val Caption: TextStyle = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    )
}

// —— 双主题 Palette。浅色玻璃 = 白 tint  + 深字；深色玻璃 = 黑 tint + 浅字 ——
@Immutable
data class LiquidPalette(
    val isDark: Boolean,
    val pageTop: Color,
    val pageBottom: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val primaryDeep: Color,
    val primaryText: Color,
    val primarySoft: Color,
    val error: Color,
    val success: Color,
    val warning: Color,
    val divider: Color,
    val scrim: Color,
    val glassTint: Color,
    val glassTintAlpha: Float,
    val solidButton: Color,
) {
    val pageBrush: Brush get() = Brush.verticalGradient(listOf(pageTop, pageBottom))
}

val LightPalette = LiquidPalette(
    isDark = false,
    pageTop = Color(0xFFF7F8FA),
    pageBottom = Color(0xFFEDF0F4),
    textPrimary = Color(0xFF1F2430),
    textSecondary = Color(0xFF5A6472),
    textTertiary = Color(0xFF66707C),
    primary = Color(0xFF1B88EE),
    primaryDeep = Color(0xFF1370CE),
    primaryText = Color(0xFF1168C8),
    primarySoft = Color(0x1A1B88EE),
    error = Color(0xFFA81F1F),
    success = Color(0xFF155F27),
    warning = Color(0xFF8A5D00),
    divider = Color(0xFFDEE0E2),
    scrim = Color(0xFF0B0E12),
    glassTint = Color.White,
    glassTintAlpha = 0.62f,
    solidButton = Color(0xFF1370CE),
)

val DarkPalette = LiquidPalette(
    isDark = true,
    pageTop = Color(0xFF171B22),
    pageBottom = Color(0xFF0E1116),
    textPrimary = Color(0xFFE8EBF0),
    textSecondary = Color(0xFFA8B2C0),
    textTertiary = Color(0xFF8A94A2),
    primary = Color(0xFF5FA8F0),
    primaryDeep = Color(0xFF6FB6FF),
    primaryText = Color(0xFF6FB6FF),
    primarySoft = Color(0x295FA8F0),
    error = Color(0xFFFF8A80),
    success = Color(0xFF6BD48A),
    warning = Color(0xFFFFC94D),
    divider = Color(0xFF2A3038),
    scrim = Color(0xFF000000),
    glassTint = Color.Black,
    glassTintAlpha = 0.42f,
    solidButton = Color(0xFF1370CE),
)

// —— 运行时环境：主题 + 玻璃能力 + 减少动效 ——
enum class GlassLevel { Full, Blur, Flat }

@Immutable
data class LiquidChrome(
    val isDark: Boolean,
    val palette: LiquidPalette,
    val glassLevel: GlassLevel,
    val reduceMotion: Boolean,
) {
    companion object {
        val Default = LiquidChrome(
            isDark = false,
            palette = LightPalette,
            glassLevel = GlassLevel.Full,
            reduceMotion = false,
        )
    }
}

val LocalLiquidChrome = staticCompositionLocalOf { LiquidChrome.Default }

// —— 材质：一处玻璃的完整配方 ——
@Immutable
data class LiquidMaterial(
    val cornerRadius: Dp,
    val blurRadius: Dp,
    val tint: Color,
    val tintAlpha: Float,
    val highlightAlpha: Float,
    val shadowAlpha: Float,
    val shadowRadius: Dp = 20.dp,
    val contentColor: Color,
    // 是否采样页面背景。地图 HUD / 近不透明输入框不采样：TextureMapView 不进
    // Compose GraphicsLayer，采样只会拿到空层，透明 tint 直接叠在地图上更省。
    val sampled: Boolean = true,
    // 边缘折射高度（0 = 关闭）。仅 Full 级（API 33+）生效，且形状须为 RoundedCornerShape。
    val lensHeight: Dp = 0.dp,
)

object LiquidMaterials {

    // 顶栏 / 底栏：贴边全宽，模糊最强，投影压出层次
    fun chrome(p: LiquidPalette) = LiquidMaterial(
        cornerRadius = 0.dp,
        blurRadius = 22.dp,
        tint = p.glassTint,
        tintAlpha = p.glassTintAlpha,
        highlightAlpha = if (p.isDark) 0.10f else 0.45f,
        shadowAlpha = if (p.isDark) 0.30f else 0.08f,
        shadowRadius = 18.dp,
        contentColor = p.textPrimary,
    )

    // 底部弹层 / 对话框：比 chrome 再实一档，保证长文可读
    fun sheet(p: LiquidPalette) = LiquidMaterial(
        cornerRadius = LiquidRadius.Sheet,
        blurRadius = 26.dp,
        tint = p.glassTint,
        tintAlpha = p.glassTintAlpha + 0.06f,
        highlightAlpha = if (p.isDark) 0.14f else 0.55f,
        shadowAlpha = if (p.isDark) 0.40f else 0.14f,
        shadowRadius = 28.dp,
        contentColor = p.textPrimary,
        lensHeight = 6.dp,
    )

    // 内容卡片：轻一档，让页面渐变透出来
    fun card(p: LiquidPalette) = LiquidMaterial(
        cornerRadius = LiquidRadius.Card,
        blurRadius = 16.dp,
        tint = p.glassTint,
        tintAlpha = p.glassTintAlpha - 0.08f,
        highlightAlpha = if (p.isDark) 0.08f else 0.35f,
        shadowAlpha = if (p.isDark) 0.26f else 0.06f,
        shadowRadius = 16.dp,
        contentColor = p.textPrimary,
    )

    // 输入框：近不透明磨砂，文字区绝对可读
    fun field(p: LiquidPalette) = LiquidMaterial(
        cornerRadius = LiquidRadius.Field,
        blurRadius = 10.dp,
        tint = p.glassTint,
        tintAlpha = 0.70f,
        highlightAlpha = if (p.isDark) 0.06f else 0.25f,
        shadowAlpha = 0f,
        contentColor = p.textPrimary,
    )

    // 地图 HUD：不采样，白/黑遮罩直接压在地图上，前景色按遮罩演算
    fun hud(p: LiquidPalette) = LiquidMaterial(
        cornerRadius = LiquidRadius.Chip,
        blurRadius = 0.dp,
        tint = if (p.isDark) Color.Black else Color.White,
        tintAlpha = if (p.isDark) 0.55f else 0.80f,
        highlightAlpha = if (p.isDark) 0.10f else 0.50f,
        shadowAlpha = if (p.isDark) 0.30f else 0.10f,
        shadowRadius = 12.dp,
        contentColor = p.textPrimary,
        sampled = false,
    )

    // 次操作 / 选中态胶囊：主色 12% 底 + 主色文字
    fun tonal(p: LiquidPalette) = LiquidMaterial(
        cornerRadius = LiquidRadius.Button,
        blurRadius = 8.dp,
        tint = p.primary,
        tintAlpha = 0.12f,
        highlightAlpha = if (p.isDark) 0.08f else 0.30f,
        shadowAlpha = 0f,
        contentColor = p.primaryText,
    )

    // 幽灵面（未选中 chip / 次级按钮底）：近乎透明的玻璃
    fun ghost(p: LiquidPalette) = LiquidMaterial(
        cornerRadius = LiquidRadius.Button,
        blurRadius = 8.dp,
        tint = p.glassTint,
        tintAlpha = if (p.isDark) 0.10f else 0.45f,
        highlightAlpha = if (p.isDark) 0.08f else 0.35f,
        shadowAlpha = 0f,
        contentColor = p.textSecondary,
    )
}
