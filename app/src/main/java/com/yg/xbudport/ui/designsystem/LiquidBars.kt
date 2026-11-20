package com.yg.xbudport.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// 顶栏 / 底栏。两者都是贴边全宽 chrome：玻璃面 + 高光上边 + 投影，
// 内容是固定布局（不参与页面滚动），scaffold 按固定高度算 content padding。

data class LiquidNavItem(val route: String, val label: String, val icon: ImageVector)

@Composable
fun LiquidTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit) = {},
) {
    val palette = LocalLiquidChrome.current.palette
    LiquidSurface(
        material = LiquidMaterials.chrome(palette),
        shape = RectangleShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        // 玻璃延伸到状态栏底下，内容行避开安全区，高度保持 56dp。
        // 只 fillMaxWidth：玻璃高度是 wrap（inset+56），纵向 fill 会撑满整屏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(LiquidSize.TopBar)
                .padding(horizontal = LiquidSpace.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(LiquidSize.TouchTarget), contentAlignment = Alignment.Center) {
                navigationIcon?.invoke()
            }
            Box(
                modifier = Modifier.weight(1f).padding(horizontal = LiquidSpace.Sm),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = title,
                        style = LiquidType.Headline,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = LiquidType.Caption,
                            color = palette.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/**
 * 底栏。液态滑块（选中项背后的主色胶囊）位移走 graphicsLayer translationX——
 * 只重绘不重排；图标/文字变色只发生在两个叶子节点上。
 */
@Composable
fun LiquidBottomBar(
    items: List<LiquidNavItem>,
    selectedRoute: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalLiquidChrome.current.palette
    LiquidSurface(
        material = LiquidMaterials.chrome(palette),
        shape = RectangleShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(LiquidSize.BottomBar)
        ) {
            val itemWidth = maxWidth / items.size.toFloat()
            val pillWidth = 56.dp
            val index = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
            val targetX = itemWidth * index + (itemWidth - pillWidth) / 2f
            val offsetX by indicatorOffset(with(LocalDensity.current) { targetX.toPx() })

            Box(
                Modifier
                    .graphicsLayer { translationX = offsetX }
                    .size(pillWidth, 32.dp)
                    .clip(RoundedCornerShape(LiquidRadius.Pill))
                    .background(palette.primarySoft)
            )
        }
        Row(Modifier.fillMaxWidth()) {
            items.forEach { item ->
                BottomBarItem(
                    item = item,
                    selected = item.route == selectedRoute,
                    onClick = { if (item.route != selectedRoute) onSelect(item.route) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    item: LiquidNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalLiquidChrome.current.palette
    val tint by animateColorAsState(
        targetValue = if (selected) palette.primaryText else palette.textTertiary,
        label = "TabTint",
    )
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = item.label,
            style = LiquidType.Caption,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
