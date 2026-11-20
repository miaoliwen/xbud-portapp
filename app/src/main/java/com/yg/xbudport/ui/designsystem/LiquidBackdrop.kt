package com.yg.xbudport.ui.designsystem

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.example.liquidglass.LiquidGlassView

// 玻璃底座：主题 / 引擎材质映射 / 降级。
//
// 引擎换成 LiquidGlass for Android v2.0.11（View 体系）后，背景采样不再是
// Compose GraphicsLayer：LiquidGlassView 挂在 Compose 树里（AndroidView 互操作），
// 采样时用公开 View.draw 重放宿主（AndroidComposeView）的绘制——背景渐变与
// 页面内容都在其中，玻璃自身的分支在捕获期间置 INVISIBLE 跳过，库内还带
// 「嵌套采样只允许一层」的多玻璃互采防护。因此：
//  * 每屏不再有唯一的 layerBackdrop 采样源，采样隔离由库的捕获机制保证
//    （背景来源 = 直接父容器，玻璃分支被排除，玻璃不折射玻璃）；
//  * HUD（地图上方）材质仍走 sampled=false 的纯 Compose 遮罩：TextureMapView
//    的 GL 内容不进 Compose 画布重放，采样不可靠，遮罩叠在地图上对比度可控。

val LocalLiquidContentPadding = staticCompositionLocalOf { PaddingValues(0.dp) }

@Composable
fun rememberGlassLevel(): GlassLevel = remember {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> GlassLevel.Full
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> GlassLevel.Blur
        else -> GlassLevel.Flat
    }
}

@Composable
fun LiquidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val reduceMotion by rememberReduceMotion()
    val level = rememberGlassLevel()
    val chrome = remember(darkTheme, level, reduceMotion) {
        LiquidChrome(
            isDark = darkTheme,
            palette = if (darkTheme) DarkPalette else LightPalette,
            glassLevel = level,
            reduceMotion = reduceMotion,
        )
    }
    CompositionLocalProvider(LocalLiquidChrome provides chrome, content = content)
}

/**
 * 页面骨架：背景渐变画在根层（玻璃经宿主重放自然采到它）；bottomBar 是叠在
 * 内容层上的固定高度玻璃 chrome，scaffold 按固定高度给 content 算 padding，
 * 页面把滚动容器 pad 进去，页面级弹层因此能盖住底栏。
 */
@Composable
fun LiquidScaffold(
    modifier: Modifier = Modifier,
    pageBackground: Boolean = true,
    topBar: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val palette = LocalLiquidChrome.current.palette
    // 底栏玻璃自身会延伸到导航栏底下（内容行 navigationBarsPadding），
    // 内容层让出的高度 = 底栏高度 + 导航栏 inset
    val density = LocalDensity.current
    val navBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val contentPadding = PaddingValues(
        top = 0.dp,
        bottom = if (bottomBar != null) LiquidSize.BottomBar + navBottomDp else 0.dp,
    )

    Box(modifier.fillMaxSize()) {
        if (pageBackground) {
            Box(Modifier.fillMaxSize().background(palette.pageBrush))
        }
        CompositionLocalProvider(LocalLiquidContentPadding provides contentPadding) {
            if (topBar != null) {
                Column(Modifier.fillMaxSize()) {
                    topBar()
                    Box(Modifier.weight(1f)) {
                        content(contentPadding)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    content(contentPadding)
                }
            }
            if (bottomBar != null) {
                Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) { bottomBar() }
            }
        }
    }
}

/**
 * LiquidMaterial → LiquidGlassView 参数映射。半径在视图自身的 density 下换算；
 * blurAmount 引擎语义是 (4 + x*32) px，我们的 dp 值超出引擎上限的部分按 1 封顶。
 * 引擎自带管线分级：33+ 透镜（折射/色散/高光）、31+ 硬件模糊、24+ C++/Kotlin
 * 管线——低版本零崩溃降级，这里只把折射参数在 Full 级下发。
 */
internal fun LiquidGlassView.applyLiquidMaterial(
    material: LiquidMaterial,
    shape: Shape,
    chrome: LiquidChrome,
) {
    val d = resources.displayMetrics.density
    val radii = shape.cornerRadiiPx(d)
    cornerRadiusTopLeft = radii[0]
    cornerRadiusTopRight = radii[1]
    cornerRadiusBottomRight = radii[2]
    cornerRadiusBottomLeft = radii[3]

    val blurPx = material.blurRadius.value * d
    blurAmount = ((blurPx - 4f) / 32f).coerceIn(0.04f, 1f)

    val a = (material.tintAlpha.coerceIn(0f, 1f) * 255f).toInt()
    glassTint = (a shl 24) or (material.tint.toArgb() and 0xFFFFFF)

    edgeHighlightOpacity = material.highlightAlpha.coerceIn(0f, 1f) * 100f
    saturation = 100f

    if (chrome.glassLevel == GlassLevel.Full && material.lensHeight > 0.dp) {
        refractionHeight = material.lensHeight.value * d
    }
    // 关掉引擎自带的所有自适应行为：对比度、配色、触感全部由设计系统令牌驱动
    enableSensorHighlight = false
    enableAdaptiveTint = false
    enableChromaticAberration = false
    enableChromaticDispersion = false
    enableShadow = false
    enablePressEffect = false
    if (deferredBackdrop) {
        // Compose 互操作：实时采样会重入 Compose 层（崩溃）或成环（整块丢弃），
        // 背景固定为主题页面渐变；静态位图录进 RenderNode，模糊由 RenderEffect
        // 在 GPU 上完成——绘制阶段零采样、零 CPU 像素处理、零 JNI
        enableDynamicBackground = false
        setStaticBackdropGradient(chrome.palette.pageTop.toArgb(), chrome.palette.pageBottom.toArgb())
    } else {
        enableDynamicBackground = true
    }
}

private fun Shape.cornerRadiiPx(d: Float): FloatArray {
    val pill = 9999f
    return when (this) {
        is androidx.compose.foundation.shape.RoundedCornerShape -> {
            // Dp 基准的 CornerSize 与参考尺寸无关；百分比档（CircleShape=50%）按
            // 参考尺寸算出偏大值，引擎内部会钳到视图短边的一半，胶囊形状自然成立
            val ref = androidx.compose.ui.geometry.Size(1000f, 1000f)
            val dens = androidx.compose.ui.unit.Density(d)
            floatArrayOf(
                topStart.toPx(ref, dens),
                topEnd.toPx(ref, dens),
                bottomEnd.toPx(ref, dens),
                bottomStart.toPx(ref, dens),
            )
        }
        androidx.compose.ui.graphics.RectangleShape -> floatArrayOf(0f, 0f, 0f, 0f)
        else -> floatArrayOf(pill, pill, pill, pill)
    }
}

/** 半透明遮罩面（地图 HUD 等 sampled=false 材质）：不接引擎，直接压一层 tint。 */
@Composable
fun Modifier.liquidTint(
    material: LiquidMaterial,
    shape: Shape,
): Modifier {
    val tint = remember(material.tint, material.tintAlpha) {
        material.tint.copy(alpha = material.tintAlpha)
    }
    val edge = remember(material.highlightAlpha) {
        androidx.compose.ui.graphics.Color.White.copy(alpha = material.highlightAlpha * 0.5f)
    }
    return this
        .clip(shape)
        .background(tint)
        .then(
            if (material.shadowAlpha > 0f) {
                Modifier.shadow(material.shadowRadius, shape)
            } else {
                Modifier
            }
        )
        .border(0.75.dp, edge, shape)
}
