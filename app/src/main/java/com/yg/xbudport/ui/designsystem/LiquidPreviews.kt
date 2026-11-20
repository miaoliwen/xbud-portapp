package com.yg.xbudport.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// @Preview 覆盖：浅色 / 深色 / 超长文案 / fontScale 1.3 / 320dp 窄屏。
// 布局里每个 Text 都带 maxLines + Ellipsis，容器不写死高度——预览即验收。

private val previewTabs = listOf(
    LiquidNavItem("home", "主页", Icons.Default.Home),
    LiquidNavItem("run", "跑步", Icons.Default.PlayArrow),
    LiquidNavItem("profile", "我的", Icons.Default.Person),
)

@Composable
private fun Gallery() {
    var checked by remember { mutableStateOf(true) }
    var slider by remember { mutableFloatStateOf(0.6f) }
    var chip by remember { mutableStateOf(2) }
    var phone by remember { mutableStateOf("") }
    LiquidScaffold(
        topBar = {
            LiquidTopBar(
                title = "跑步进度",
                actions = {
                    LiquidButton(
                        text = "刷新",
                        onClick = {},
                        style = LiquidButtonStyle.Text,
                        height = 44.dp,
                        fill = false,
                    )
                },
            )
        },
        bottomBar = {
            LiquidBottomBar(
                items = previewTabs,
                selectedRoute = "home",
                onSelect = {},
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LiquidSpace.Page),
            verticalArrangement = Arrangement.spacedBy(LiquidSpace.Md),
        ) {
            LiquidSurface(
                material = LiquidMaterials.card(LocalLiquidChrome.current.palette),
                shape = RoundedCornerShape(LiquidRadius.Card),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(LiquidSpace.Xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(LiquidSpace.Sm),
                ) {
                Text("本学期", style = LiquidType.Label, color = LocalLiquidChrome.current.palette.textTertiary)
                Text("28 / 60 次", style = LiquidType.Display, color = LocalLiquidChrome.current.palette.textPrimary)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Stat("本周", "3/5", "次")
                    Stat("本周跑量", "12.4", "km")
                    Stat("今日跑量", "1.2", "km")
                }
                }
            }
            LiquidButton(text = "去跑步", onClick = {})
            Row(horizontalArrangement = Arrangement.spacedBy(LiquidSpace.Sm)) {
                (1..4).forEach { km ->
                    LiquidChip(
                        text = "$km km",
                        selected = chip == km,
                        onClick = { chip = km },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("夜间提醒", style = LiquidType.Body, color = LocalLiquidChrome.current.palette.textPrimary)
                LiquidToggle(checked = checked, onCheckedChange = { checked = it })
            }
            LiquidSlider(value = slider, onValueChange = { slider = it })
            LiquidInput(
                value = phone,
                onValueChange = { phone = it },
                label = "手机号",
                placeholder = "请输入手机号",
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done,
            )
            LiquidSection(
                title = "跑步规则",
                rows = listOf(
                    LiquidRow("有效时段", "06:30–21:00"),
                    LiquidRow("单次最低", "1.00 km"),
                    LiquidRow("跑步历史", chevron = true, onClick = {}),
                    LiquidRow("退出登录", danger = true, onClick = {}),
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(LiquidSpace.Sm)) {
                LiquidTag("已实名")
                LiquidTag("待完善", color = LocalLiquidChrome.current.palette.warning)
            }
            Spacer(Modifier.height(LiquidSpace.Md))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String) {
    val palette = LocalLiquidChrome.current.palette
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = LiquidType.Caption, color = palette.textTertiary)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = LiquidType.Title, color = palette.textPrimary)
            Text(
                unit,
                style = LiquidType.Caption,
                color = palette.textTertiary,
                modifier = Modifier.padding(start = 2.dp, bottom = 1.dp),
            )
        }
    }
}

@Preview(name = "浅色", showBackground = true, device = "spec:width=360dp,height=800dp")
@Composable
private fun GalleryLightPreview() {
    LiquidTheme(darkTheme = false) { Gallery() }
}

@Preview(name = "深色", showBackground = true, device = "spec:width=360dp,height=800dp")
@Composable
private fun GalleryDarkPreview() {
    LiquidTheme(darkTheme = true) { Gallery() }
}

// 极端文案 + 系统字体放大 1.3x：标题截断、统计值换行、规则行长文都不允许重叠
@Preview(
    name = "极端文案 fontScale1.3",
    showBackground = true,
    fontScale = 1.3f,
    device = "spec:width=320dp,height=780dp",
)
@Composable
private fun ExtremeTextPreview() {
    LiquidTheme(darkTheme = false) {
        LiquidScaffold(
            topBar = { LiquidTopBar(title = "超长标题溢出截断验证跑步进度页面") },
            bottomBar = { LiquidBottomBar(previewTabs, "home", {}) },
        ) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().padding(LiquidSpace.Page),
                verticalArrangement = Arrangement.spacedBy(LiquidSpace.Md),
            ) {
                LiquidSection(
                    title = "分组标题超长时也要收得住",
                    rows = listOf(
                        LiquidRow(
                            "有效时段",
                            "06:30–21:00 超长说明文字会被省略号截断而不是换行错位",
                        ),
                        LiquidRow("单次最低", "1.00 km"),
                    ),
                )
                LiquidButton(text = "超长按钮文案按钮按钮按钮", onClick = {})
                LiquidInput(
                    value = "",
                    onValueChange = {},
                    label = "超长输入标签超长输入标签",
                    placeholder = "占位提示超长占位提示超长",
                )
            }
        }
    }
}

@Preview(name = "弹层", showBackground = true, device = "spec:width=360dp,height=800dp")
@Composable
private fun SheetPreview() {
    LiquidTheme(darkTheme = false) {
        LiquidScaffold(
            topBar = { LiquidTopBar(title = "登录") },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                LiquidDialog(
                    visible = true,
                    title = "结束跑步",
                    message = null,
                    confirmText = "结束",
                    onConfirm = {},
                    onDismiss = {},
                    dismissText = "取消",
                    danger = true,
                )
                LiquidBottomSheet(
                    visible = true,
                    onDismiss = {},
                    title = "设备画像",
                ) {
                    LiquidSection(
                        rows = listOf(
                            LiquidRow("三星 A52", detail = "当前", onClick = {}),
                            LiquidRow("＋ 导入画像", onClick = {}),
                        ),
                    )
                }
            }
        }
    }
}
