package com.yg.xbudport.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 加载 / 空态 / 错误态。文案最小集：状态一句话，动作一个按钮。

@Composable
fun LiquidLoading(
    text: String = "加载中",
    modifier: Modifier = Modifier,
) {
    val palette = LocalLiquidChrome.current.palette
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LiquidSpace.Md),
        ) {
            LiquidSpinner(size = 28.dp, color = palette.primaryText, strokeWidth = 2.5.dp)
            Text(text = text, style = LiquidType.Label, color = palette.textTertiary)
        }
    }
}

@Composable
fun LiquidStateView(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    error: Boolean = false,
) {
    val palette = LocalLiquidChrome.current.palette
    Box(
        modifier.fillMaxSize().padding(horizontal = LiquidSpace.Xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LiquidSpace.Sm),
        ) {
            Text(
                text = title,
                style = LiquidType.Body.copy(fontSize = 16.sp),
                color = if (error) palette.error else palette.textPrimary,
                textAlign = TextAlign.Center,
            )
            if (message != null) {
                Text(
                    text = message,
                    style = LiquidType.Label,
                    color = palette.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionText != null && onAction != null) {
                Box(Modifier.padding(top = LiquidSpace.Sm)) {
                    LiquidButton(
                        text = actionText,
                        onClick = onAction,
                        style = if (error) LiquidButtonStyle.Tonal else LiquidButtonStyle.Ghost,
                        height = 44.dp,
                        fill = false,
                    )
                }
            }
        }
    }
}
