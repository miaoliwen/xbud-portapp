package com.yg.xbudport.ui.designsystem

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import com.example.liquidglass.LiquidGlassView

/** Compose 内容层（1.9.x 起为 internal，按类名识别；类名自 1.0 起稳定） */
private const val COMPOSE_HOST_CLASS = "androidx.compose.ui.platform.AndroidComposeView"

/**
 * 玻璃面基元：LiquidGlassView（View 互操作）+ Compose 内容子层。
 *
 * 玻璃效果（模糊 / 高光 / tint）由引擎绘制，前景内容是玻璃里的 ComposeView。
 * 按压微缩放走 graphicsLayer lambda（Draw 阶段读值，零重组）；对比度由
 * material 的 tint + contentColor 配对保证（LiquidTokensTest 锁死）。
 *
 * ui 1.9.4 的 AndroidView 没有 content 重载，内容子层手动挂 ComposeView。
 * 子组合不继承外层 CompositionLocal，chrome / contentPadding 用
 * rememberUpdatedState 桥进去（状态读在子组合里，外层更新会透传）。
 *
 * 背景：Compose 互操作下不做实时采样——AndroidView 的直接父容器是只装着玻璃
 * 自己的 holder，按引擎默认"采直接父容器"等于采空（透明黑）；挂到 Compose
 * 内容层（AndroidComposeView）重放又有两重死路：draw 时重放会重入正在录制的
 * Compose GraphicsLayer（崩溃），preDraw 重放的背景节点引用 Compose 图层、
 * 图层里又含玻璃自己的绘制指令，渲染图成环被 HWUI 整块丢弃。因此互操作玻璃
 * 用主题页面渐变的静态背景位图 + RenderEffect GPU 模糊（位图录进 RenderNode，
 * 重绘零采样），
 * 深浅主题各自正确（deferredBackdrop / setStaticBackdropGradient）。
 */
@Composable
fun LiquidSurface(
    material: LiquidMaterial,
    shape: Shape,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val chrome = LocalLiquidChrome.current
    val contentPadding = LocalLiquidContentPadding.current
    val scale by pressScale(pressed)
    val currentContent = rememberUpdatedState(content)
    val currentChrome = rememberUpdatedState(chrome)
    val currentPadding = rememberUpdatedState(contentPadding)
    AndroidView(
        factory = { ctx ->
            LiquidGlassView(ctx).apply {
                addView(
                    ComposeView(ctx).apply {
                        setContent {
                            CompositionLocalProvider(
                                LocalLiquidChrome provides currentChrome.value,
                                LocalLiquidContentPadding provides currentPadding.value,
                            ) {
                                currentContent.value()
                            }
                        }
                    },
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        // 采样源挂 Compose 内容层 + 打开互操作标记：玻璃从此走
                        // CPU 管线 + 主题渐变静态背景（实时采样会重入正在录制的
                        // Compose 层崩溃、RenderNode 引用会成环被整块丢弃）。
                        // applyLiquidMaterial 在组合期跑、早于挂载，首帧的静态
                        // 背景在这里补齐
                        deferredBackdrop = true
                        var p = v.parent as? View
                        while (p != null && p.javaClass.name != COMPOSE_HOST_CLASS) {
                            p = p.parent as? View
                        }
                        backdropSource = p
                        enableDynamicBackground = false
                        setStaticBackdropGradient(
                            currentChrome.value.palette.pageTop.toArgb(),
                            currentChrome.value.palette.pageBottom.toArgb(),
                        )
                    }

                    override fun onViewDetachedFromWindow(v: View) {}
                })
            }
        },
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        update = { glass -> glass.applyLiquidMaterial(material, shape, chrome) },
    )
}
