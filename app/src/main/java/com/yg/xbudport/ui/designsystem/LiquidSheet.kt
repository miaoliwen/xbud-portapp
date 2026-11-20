package com.yg.xbudport.ui.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// 弹层 / 对话框。两者都是同组合树 overlay（scrim + 内容），不是 Dialog 窗口——
// 独立窗口采不到本页背景，玻璃必须留在树内。reduceMotion 时滑窗退化为淡入淡出。

@Composable
fun LiquidBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    maxHeightFraction: Float = 0.92f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalLiquidChrome.current.palette
    val reduceMotion = LocalLiquidChrome.current.reduceMotion

    // 同组合树 overlay 不吃系统返回键，自己接
    BackHandler(enabled = visible, onBack = onDismiss)

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(fadeInSpec()),
            exit = fadeOut(fadeOutSpec()),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.scrim.copy(alpha = 0.38f))
                    .clickable(onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = if (reduceMotion) {
                fadeIn(fadeInSpec())
            } else {
                slideInVertically(tween(220)) { it } + fadeIn(fadeInSpec())
            },
            exit = if (reduceMotion) {
                fadeOut(fadeOutSpec())
            } else {
                slideOutVertically(tween(180)) { it } + fadeOut(fadeOutSpec())
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            androidx.compose.foundation.layout.BoxWithConstraints {
            LiquidSurface(
                material = LiquidMaterials.sheet(palette),
                shape = RoundedCornerShape(topStart = LiquidRadius.Sheet, topEnd = LiquidRadius.Sheet),
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight * maxHeightFraction),
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = LiquidSpace.Xl)
                    .navigationBarsPadding()
                    .padding(bottom = LiquidSpace.Lg),
            ) {
                Box(
                    Modifier
                        .padding(top = LiquidSpace.Md)
                        .size(width = LiquidSize.SheetHandle, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.divider)
                        .align(Alignment.CenterHorizontally)
                )
                if (title != null) {
                    Text(
                        text = title,
                        style = LiquidType.Headline,
                        color = palette.textPrimary,
                        maxLines = 1,
                        modifier = Modifier.padding(top = LiquidSpace.Md),
                    )
                }
                content()
            }
            }
            }
        }
    }
}

@Composable
fun LiquidDialog(
    visible: Boolean,
    title: String,
    message: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = null,
    danger: Boolean = false,
) {
    val palette = LocalLiquidChrome.current.palette

    BackHandler(enabled = visible, onBack = onDismiss)

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(fadeInSpec()),
            exit = fadeOut(fadeOutSpec()),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.scrim.copy(alpha = 0.38f))
                    .clickable(onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(initialScale = 0.92f, animationSpec = tween(180)) + fadeIn(fadeInSpec()),
            exit = scaleOut(targetScale = 0.95f, animationSpec = tween(140)) + fadeOut(fadeOutSpec()),
            modifier = Modifier.align(Alignment.Center),
        ) {
            LiquidSurface(
                material = LiquidMaterials.sheet(palette),
                shape = RoundedCornerShape(LiquidRadius.Dialog),
                modifier = modifier
                    .padding(horizontal = LiquidSpace.Xxl)
                    .widthIn(max = 340.dp)
                    .fillMaxWidth(),
            ) {
                Column(Modifier.padding(LiquidSpace.Xl)) {
                Text(
                    text = title,
                    style = LiquidType.Headline,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (message != null) {
                    Text(
                        text = message,
                        style = LiquidType.Body,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = LiquidSpace.Sm),
                    )
                }
                // iOS 式等宽动作行：取消｜确认 各占一半，细分隔线隔开，
                // 危险动作用红色文字——文字按钮不做右对齐散排
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = if (message == null) LiquidSpace.Lg else LiquidSpace.Xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissText != null) {
                        LiquidButton(
                            text = dismissText,
                            onClick = onDismiss,
                            style = LiquidButtonStyle.Text,
                            height = 44.dp,
                            dense = true,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .width(0.5.dp)
                                .height(24.dp)
                                .background(palette.divider)
                        )
                    }
                    LiquidButton(
                        text = confirmText,
                        onClick = onConfirm,
                        style = if (danger) LiquidButtonStyle.DangerText else LiquidButtonStyle.Filled,
                        height = 44.dp,
                        dense = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                }
            }
        }
    }
}

/**
 * 输入型对话框（替 QMUI EditTextDialog）：调试 validate、导入画像 JSON。
 * 标签即标题，字段是同一只 LiquidInput；multiline 走 8 行上限。
 */
@Composable
fun LiquidPromptDialog(
    visible: Boolean,
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialText: String = "",
    placeholder: String? = null,
    confirmText: String = "确定",
    dismissText: String? = "取消",
    multiline: Boolean = false,
) {
    val palette = LocalLiquidChrome.current.palette
    var text by remember(visible) { mutableStateOf(initialText) }

    BackHandler(enabled = visible, onBack = onDismiss)

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(fadeInSpec()),
            exit = fadeOut(fadeOutSpec()),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.scrim.copy(alpha = 0.38f))
                    .clickable(onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(initialScale = 0.92f, animationSpec = tween(180)) + fadeIn(fadeInSpec()),
            exit = scaleOut(targetScale = 0.95f, animationSpec = tween(140)) + fadeOut(fadeOutSpec()),
            modifier = Modifier.align(Alignment.Center),
        ) {
            LiquidSurface(
                material = LiquidMaterials.sheet(palette),
                shape = RoundedCornerShape(LiquidRadius.Dialog),
                modifier = modifier
                    .padding(horizontal = LiquidSpace.Xxl)
                    .widthIn(max = 340.dp)
                    .fillMaxWidth(),
            ) {
                Column(Modifier.padding(LiquidSpace.Xl)) {
                LiquidInput(
                    value = text,
                    onValueChange = { text = it },
                    label = title,
                    placeholder = placeholder,
                    multiline = multiline,
                    imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
                    onDone = { if (!multiline) onConfirm(text) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = LiquidSpace.Lg),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (dismissText != null) {
                        LiquidButton(
                            text = dismissText,
                            onClick = onDismiss,
                            style = LiquidButtonStyle.Text,
                            height = 44.dp,
                            fill = false,
                            dense = true,
                            modifier = Modifier.padding(end = LiquidSpace.Sm),
                        )
                    }
                    LiquidButton(
                        text = confirmText,
                        onClick = { onConfirm(text) },
                        height = 44.dp,
                        fill = false,
                        dense = true,
                    )
                }
                }
            }
        }
    }
}
