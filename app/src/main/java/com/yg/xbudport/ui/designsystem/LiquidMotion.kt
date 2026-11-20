package com.yg.xbudport.ui.designsystem

import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

// 动效规格：一律 spring 物理曲线，按压轻快、面板沉稳。
// 减少动效（系统动画时长缩放 = 0）时全部退化为瞬时。

object LiquidSpring {
    val Press: SpringSpec<Float> = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
    val Indicator: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow)
    val Panel: SpringSpec<Float> = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
}

private val Snap: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh)

/** 按压缩放：reduceMotion 时无动画。 */
@Composable
fun pressScale(pressed: Boolean): State<Float> {
    val chrome = LocalLiquidChrome.current
    return animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = if (chrome.reduceMotion) Snap else LiquidSpring.Press,
        label = "PressScale",
    )
}

/** 滑块位移：reduceMotion 时瞬时到位。 */
@Composable
fun indicatorOffset(target: Float): State<Float> {
    val chrome = LocalLiquidChrome.current
    return animateFloatAsState(
        targetValue = target,
        animationSpec = if (chrome.reduceMotion) Snap else LiquidSpring.Indicator,
        label = "IndicatorOffset",
    )
}

/** 面板淡入时长：reduceMotion 时压到 60ms 极简淡入。 */
@Composable
fun fadeInSpec(): FiniteAnimationSpec<Float> =
    if (LocalLiquidChrome.current.reduceMotion) {
        tween(durationMillis = 60)
    } else {
        tween(durationMillis = 150)
    }

@Composable
fun fadeOutSpec(): FiniteAnimationSpec<Float> =
    if (LocalLiquidChrome.current.reduceMotion) {
        tween(durationMillis = 40)
    } else {
        tween(durationMillis = 110)
    }

/**
 * 监听系统「减少动态效果」。动画时长缩放或过渡缩放为 0 即视为开启；
 * 会话中改动设置通过 ContentObserver 实时生效，onDispose 反注册。
 */
@Composable
fun rememberReduceMotion(): State<Boolean> {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val state = remember { mutableStateOf(isReduceMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = isReduceMotion(resolver)
            }
        }
        val uris = listOf(
            Settings.Global.ANIMATOR_DURATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
        ).map { Settings.Global.getUriFor(it) }
        uris.forEach { resolver.registerContentObserver(it, false, observer) }
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return state
}

private fun isReduceMotion(resolver: android.content.ContentResolver): Boolean =
    runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

/** 克制触觉：只给确认类操作（结束跑步 / 退出登录 / 开关切换），切页不加。 */
@Composable
fun rememberConfirmHaptic(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
}
