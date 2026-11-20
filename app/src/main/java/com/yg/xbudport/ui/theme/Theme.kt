package com.yg.xbudport.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// QMUI 设计令牌。与 res/values/themes.xml 的 Theme.Xbud 同源：
// 主色 #1B88EE、页面底 #F4F5F7、分隔线 #DEE0E2、正文 #353C46。
// Compose 层负责页面与地图浮层，QMUI View 组件读 XML 主题，两边保持同一套颜色。
object XbudColors {
    val Primary = Color(0xFF1B88EE)
    val PrimaryPressed = Color(0xFF4E9FF2)
    val PrimaryDisabled = Color(0xFFA9CFF6)
    val PrimarySoft = Color(0x141B88EE)

    val Background = Color(0xFFF4F5F7)
    val Surface = Color(0xFFFFFFFF)
    val SurfacePressed = Color(0xFFEEF0F2)
    val InputFill = Color(0xFFF7F8FA)

    val TextPrimary = Color(0xFF353C46)
    val TextSecondary = Color(0xFF5D646E)
    val TextTertiary = Color(0xFF858C96)
    val TextHint = Color(0xFF99A0AA)

    val Divider = Color(0xFFDEE0E2)
    val Error = Color(0xFFFA3A3A)
    val Success = Color(0xFF4CAF50)
    val Warning = Color(0xFFFF9800)

    // 打卡点/轨迹配色（地图、画布、历史回放共用）
    val Track = Color(0xFF1B88EE)
    val CheckpointNormal = Color(0xFFFF9800)
    val CheckpointFixed = Color(0xFFFA3A3A)
    val CheckpointPassed = Color(0xFF4CAF50)
    val CheckpointNext = Color(0xFF1B88EE)
}

object XbudDimens {
    val CardRadius = 12.dp
    val ButtonHeight = 48.dp
    val PagePadding = 16.dp
}

private val XbudColorScheme = lightColorScheme(
    primary = XbudColors.Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F0FD),
    onPrimaryContainer = XbudColors.Primary,
    secondary = XbudColors.TextSecondary,
    onSecondary = Color.White,
    background = XbudColors.Background,
    onBackground = XbudColors.TextPrimary,
    surface = XbudColors.Surface,
    onSurface = XbudColors.TextPrimary,
    surfaceVariant = XbudColors.Background,
    onSurfaceVariant = XbudColors.TextTertiary,
    outline = XbudColors.TextHint,
    outlineVariant = XbudColors.Divider,
    error = XbudColors.Error,
    onError = Color.White,
)

// 深色方案与 designsystem 的 DarkPalette 同源：页面底 #171B22→#0E1116、
// 正文 #E8EBF0、分隔线 #2A3038。液态玻璃页走 designsystem 的 chrome，
// 这里兜住 Material3 默认样式（遗留 QMUI 时代组件仍在用）。
private val XbudDarkColorScheme = darkColorScheme(
    primary = Color(0xFF5FA8F0),
    onPrimary = Color(0xFF06233F),
    primaryContainer = Color(0xFF1C3A5E),
    onPrimaryContainer = Color(0xFFD6E8FB),
    secondary = Color(0xFFA8B2C0),
    onSecondary = Color(0xFF1A1E24),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE8EBF0),
    surface = Color(0xFF171B22),
    onSurface = Color(0xFFE8EBF0),
    surfaceVariant = Color(0xFF22272F),
    onSurfaceVariant = Color(0xFF8A94A2),
    outline = Color(0xFF5A6472),
    outlineVariant = Color(0xFF2A3038),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3A0F0F),
)

private val XbudTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        color = XbudColors.TextPrimary,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        color = XbudColors.TextPrimary,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        color = XbudColors.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = XbudColors.TextPrimary,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = XbudColors.TextPrimary,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = XbudColors.TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = XbudColors.TextSecondary,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = XbudColors.TextTertiary,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        color = Color.White,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = XbudColors.TextSecondary,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = XbudColors.TextHint,
    ),
)

private val XbudShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun XbudTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) XbudDarkColorScheme else XbudColorScheme,
        typography = XbudTypography,
        shapes = XbudShapes,
        content = content,
    )
}