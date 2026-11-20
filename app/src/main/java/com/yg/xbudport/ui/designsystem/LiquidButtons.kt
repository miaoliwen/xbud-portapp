package com.yg.xbudport.ui.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 按钮 / 图标按钮 / chip / 加载圈。
// 动效约定：缩放走 graphicsLayer lambda（Draw 阶段，零重组）；
// 按压变色只切两个静态值（一次重组一次按压），不逐帧插值。
// 玻璃面经 LiquidSurface（LiquidGlassView 互操作）；主按钮保持实底
// （白字对比度 4.96:1 演算，玻璃面达不到 AA）。

enum class LiquidButtonStyle { Filled, Tonal, Ghost, Text, DangerText }

@Composable
fun LiquidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    style: LiquidButtonStyle = LiquidButtonStyle.Filled,
    height: Dp = LiquidSize.Button,
    fill: Boolean = true,
    // 材质覆盖：地图 HUD 上的次按钮改用不采样遮罩面，与所在面板同源
    surface: LiquidMaterial? = null,
    // 对话框动作档：15sp Medium + 12dp 内容边距——文字按钮不做 16dp 大内边距，
    // 否则两个动作之间的「文字间距」= 16+8+16 = 40dp 空白，排版散掉
    dense: Boolean = false,
) {
    val palette = LocalLiquidChrome.current.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by pressScale(pressed)
    val clickable = enabled && !loading
    val shape = RoundedCornerShape(LiquidRadius.Button)

    // 主按钮实底（对比度按白字 4.96:1 演算）；次按钮走玻璃面
    val fillColor = when (style) {
        LiquidButtonStyle.Filled -> if (pressed) {
            lerp(palette.solidButton, Color.Black, 0.14f)
        } else {
            palette.solidButton
        }
        LiquidButtonStyle.Tonal, LiquidButtonStyle.Ghost, LiquidButtonStyle.Text,
        LiquidButtonStyle.DangerText -> Color.Unspecified
    }
    val contentColor = when (style) {
        LiquidButtonStyle.Filled -> Color.White
        LiquidButtonStyle.Tonal -> palette.primaryText
        LiquidButtonStyle.Ghost, LiquidButtonStyle.Text -> palette.textSecondary
        LiquidButtonStyle.DangerText -> palette.error
    }
    val useGlass = style == LiquidButtonStyle.Tonal || style == LiquidButtonStyle.Ghost
    // 尺寸语义：玻璃直接吃外层 sizing（fill=true 撑满可用宽，false 包住文字）；
    // 内容盒填满玻璃的确定维度——fill=true 双向撑满，fill=false 玻璃高度仍由
    // height(h) 锁定，内容只撑高度、宽度按文字收缩（可点区域 = 玻璃面）
    val buttonSizing = modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).height(height)
    val contentSizing = if (fill) Modifier.fillMaxSize() else Modifier.fillMaxHeight()
    val contentPad = if (dense) 12.dp else LiquidSpace.Lg
    val labelStyle = if (dense) {
        LiquidType.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    } else {
        LiquidType.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }

    if (useGlass) {
        LiquidSurface(
            material = surface ?: when (style) {
                LiquidButtonStyle.Tonal -> LiquidMaterials.tonal(palette)
                else -> LiquidMaterials.ghost(palette)
            },
            shape = shape,
            modifier = buttonSizing,
            pressed = pressed,
        ) {
            Box(
                modifier = contentSizing
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = clickable,
                        onClick = onClick,
                    )
                    .padding(horizontal = contentPad),
                contentAlignment = Alignment.Center,
            ) {
                ButtonLabel(text, loading, contentColor, enabled, palette, labelStyle, contentPad)
            }
        }
    } else {
        Box(modifier = buttonSizing) {
            Box(
                modifier = (if (fill) Modifier.fillMaxSize() else Modifier.fillMaxHeight())
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(shape)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = clickable,
                        onClick = onClick,
                    )
                    .then(
                        if (style == LiquidButtonStyle.Filled) {
                            Modifier.background(fillColor, shape)
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = contentPad),
                contentAlignment = Alignment.Center,
            ) {
                ButtonLabel(text, loading, contentColor, enabled, palette, labelStyle, contentPad)
            }
        }
    }
}

@Composable
private fun ButtonLabel(
    text: String,
    loading: Boolean,
    contentColor: Color,
    enabled: Boolean,
    palette: LiquidPalette,
    labelStyle: androidx.compose.ui.text.TextStyle,
    contentPad: Dp,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            LiquidSpinner(size = 15.dp, color = contentColor, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(LiquidSpace.Sm))
        }
        Text(
            text = text,
            style = labelStyle,
            color = if (enabled) contentColor else palette.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun LiquidIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalLiquidChrome.current.palette.textSecondary,
) {
    val palette = LocalLiquidChrome.current.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LiquidSurface(
        material = LiquidMaterials.ghost(palette),
        shape = CircleShape,
        modifier = modifier.size(LiquidSize.TouchTarget),
        pressed = pressed,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else palette.textTertiary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
fun LiquidChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    material: LiquidMaterial? = null,
) {
    val palette = LocalLiquidChrome.current.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(LiquidRadius.Pill)
    LiquidSurface(
        material = material ?: if (selected) LiquidMaterials.tonal(palette) else LiquidMaterials.ghost(palette),
        shape = shape,
        modifier = modifier.height(36.dp),
        pressed = pressed,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = LiquidType.Label.copy(fontSize = 14.sp),
                color = when {
                    !enabled -> palette.textTertiary
                    selected -> palette.primaryText
                    else -> palette.textSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 加载圈。旋转角在 graphicsLayer lambda 里读动画值，
 * InfiniteTransition 的状态不进组合，转起来不重组。
 */
@Composable
fun LiquidSpinner(
    size: Dp = 20.dp,
    color: Color = LocalLiquidChrome.current.palette.textPrimary,
    strokeWidth: Dp = 2.dp,
) {
    val transition = rememberInfiniteTransition(label = "Spinner")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "SpinnerAngle",
    )
    Canvas(
        modifier = Modifier.size(size).graphicsLayer { rotationZ = angle.value },
    ) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
