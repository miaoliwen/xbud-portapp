package com.yg.xbudport.ui.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 输入 / 开关 / 滑块 / 分组列表 / 标签 / 头像。
// 输入框是「近不透明磨砂」：文字区对比度不依赖背后内容，字体放大时容器不伸缩。

@Composable
fun LiquidInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
    numeric: Boolean = false,
    multiline: Boolean = false,
    onDone: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val palette = LocalLiquidChrome.current.palette
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LiquidRadius.Field)

    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = LiquidType.Label,
            color = if (focused) palette.primaryText else palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )
        // 尺寸语义：单行 = 玻璃固定 50dp、内容撑满（文字垂直居中）；
        // 多行 = 玻璃按内容生长（下限 50dp）、内容顶部对齐（JSON 导入场景）
        LiquidSurface(
            material = LiquidMaterials.field(palette),
            shape = shape,
            modifier = if (multiline) {
                Modifier.fillMaxWidth().heightIn(min = LiquidSize.Field)
            } else {
                Modifier.fillMaxWidth().height(LiquidSize.Field)
            },
        ) {
        Box(
            modifier = (
                if (multiline) {
                    Modifier.fillMaxWidth().heightIn(min = LiquidSize.Field)
                } else {
                    Modifier.fillMaxSize()
                }
                )
        ) {
            // 聚焦环画在玻璃边缘（与玻璃外形同圆角），文字区内缩 14dp
            if (focused) {
                Box(Modifier.matchParentSize().border(1.dp, palette.primaryText, shape))
            }
            Box(
                modifier = (
                    if (multiline) {
                        Modifier.fillMaxWidth().heightIn(min = LiquidSize.Field)
                    } else {
                        Modifier.fillMaxSize()
                    }
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
            ) {
            Row(
                modifier = if (multiline) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
                verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = LiquidType.Body.copy(fontSize = 16.sp),
                            color = palette.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        singleLine = !multiline,
                        maxLines = if (multiline) 8 else 1,
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            color = if (enabled) palette.textPrimary else palette.textTertiary,
                            fontFeatureSettings = if (numeric) "tnum" else null,
                        ),
                        cursorBrush = SolidColor(palette.primaryText),
                        visualTransformation = if (password) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboardType,
                            imeAction = imeAction,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = {
                                focusManager.moveFocus(FocusDirection.Down)
                            },
                            onDone = {
                                focusManager.clearFocus()
                                onDone?.invoke()
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused },
                    )
                }
                if (trailing != null) {
                    Spacer(Modifier.width(LiquidSpace.Sm))
                    trailing()
                }
            }
        }
        }
        }
    }
}

@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = LocalLiquidChrome.current.palette
    val haptic = rememberConfirmHaptic()
    val density = LocalDensity.current
    val thumbTravel = with(density) { 20.dp.toPx() }
    val offsetX by indicatorOffset(if (checked) thumbTravel else 0f)
    val shape = RoundedCornerShape(LiquidRadius.Pill)

    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(shape)
            .background(if (checked) palette.primary else palette.divider)
            .clickable(enabled = enabled) {
                haptic()
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(start = 4.dp)
                .graphicsLayer { translationX = offsetX }
                .size(24.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
) {
    val palette = LocalLiquidChrome.current.palette
    val density = LocalDensity.current
    val thumb = 22.dp
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(44.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val usable = maxWidth - thumb
        val fraction = ((value - valueRange.start) /
            (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        val thumbX = usable * fraction
        val usablePx = with(density) { usable.toPx() }
        val thumbXPx = with(density) { thumbX.toPx() }

        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(palette.divider)
        )
        Box(
            Modifier
                .width(thumb + usable * fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(palette.primary)
        )
        Box(
            Modifier
                .graphicsLayer { translationX = thumbXPx }
                .size(thumb)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        )
        if (enabled) {
            fun seek(x: Float) {
                val f = (x / usablePx).coerceIn(0f, 1f)
                onValueChange(valueRange.start + f * (valueRange.endInclusive - valueRange.start))
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(valueRange) {
                        detectTapGestures { offset -> seek(offset.x) }
                    }
                    .pointerInput(valueRange) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> seek(offset.x) },
                            onHorizontalDrag = { change, _ -> seek(change.position.x) }
                        )
                    }
            )
        }
    }
}

data class LiquidRow(
    val title: String,
    val detail: String = "",
    val chevron: Boolean = false,
    val danger: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/** 分组列表（替 QMUI GroupList）：纯 Compose，玻璃卡片 + 细分隔线。 */
@Composable
fun LiquidSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    rows: List<LiquidRow>,
) {
    val palette = LocalLiquidChrome.current.palette
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = LiquidType.Label,
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp),
            )
        }
        LiquidSurface(
            material = LiquidMaterials.card(palette),
            shape = RoundedCornerShape(LiquidRadius.Card),
            modifier = Modifier.fillMaxWidth(),
        ) {
        Column {
            rows.forEachIndexed { index, row ->
                SectionRow(row)
                if (index != rows.lastIndex) {
                    Box(
                        Modifier
                            .padding(start = LiquidSpace.Lg)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(palette.divider)
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun SectionRow(row: LiquidRow) {
    val palette = LocalLiquidChrome.current.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LiquidSize.Row)
            .background(if (pressed) palette.primarySoft else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = row.onClick != null,
            ) { row.onClick?.invoke() }
            .padding(horizontal = LiquidSpace.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.title,
            style = LiquidType.Body,
            color = if (row.danger) palette.error else palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.detail.isNotBlank()) {
            Spacer(Modifier.width(LiquidSpace.Sm))
            Text(
                text = row.detail,
                style = LiquidType.Label,
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        if (row.chevron) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun LiquidTag(
    text: String,
    color: Color = LocalLiquidChrome.current.palette.primary,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = LiquidType.Caption.copy(fontWeight = FontWeight.Medium),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun LiquidAvatar(
    bitmap: ImageBitmap?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = LiquidSize.Avatar,
) {
    val palette = LocalLiquidChrome.current.palette
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(palette.primarySoft),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.trim().take(1).ifBlank { "我" },
                style = LiquidType.Title.copy(fontSize = (size.value / 3).sp),
                color = palette.primaryText,
                maxLines = 1,
            )
        }
    }
}
