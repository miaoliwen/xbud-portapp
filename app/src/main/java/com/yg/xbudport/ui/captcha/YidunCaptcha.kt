package com.yg.xbudport.ui.captcha

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yg.xbudport.ui.theme.XbudColors

// 易盾智能无感验证码：与原 App 同款机制（LOGIN-AUTH-20261116 §4）——
// 官方 H5 + JSInterface 回调，页面自己的 JS 完成无感/挑战，取回 validate。
// 不逆向验证码协议；captchaId 漂移时改 CAPTCHA_ID。

const val YIDUN_CAPTCHA_ID = "2451e9031eb340078cdb51c0e3691570"
private const val SDK_VER = "3.4.3"

fun yidunUrl(osVer: String): String =
    "https://cstaticdun.126.net/api/v2/mobile.v2.13.5.html" +
        "?captchaId=$YIDUN_CAPTCHA_ID&mode=bind&os=android&osVer=$osVer" +
        "&sdkVer=$SDK_VER&defaultFallback=true&errorFallbackCount=3&mobileTimeout=10000"

// JSInterface：H5 页通过 window.JSInterface 回调（官方接入契约）。
// onReady(w,h) → 调 window.captchaVerify()；
// onValidate(result,validate,msg,next)：result=="true" 才是成功态；失败态（智能无感
// 未通过）validate 为空、页面随后自己出兜底挑战——忽略它，弹窗保持打开等重试。
// 所有回调都过 alive 闸门：弹窗关掉后 H5 仍可能异步回一次，此时 WebView 已销毁，
// 直接 evaluateJavascript/回调会抛 RuntimeException 杀进程。
private class YidunBridge(
    private val web: WebView,
    private val main: Handler,
    private val alive: () -> Boolean,
    private val onValidate: (String) -> Unit,
) {
    @JavascriptInterface
    fun onReady(w: Int, h: Int) {
        main.post {
            if (!alive()) return@post
            web.evaluateJavascript("window.captchaVerify()", null)
        }
    }

    @JavascriptInterface
    fun onValidate(result: String, validate: String, msg: String, next: String) {
        if (result == "true" && validate.isNotBlank()) {
            main.post { if (alive()) onValidate(validate) }
        }
        // 失败态：不关弹窗、不回传，页面按 errorFallbackCount 自己重试
    }
}

/** 安全销毁 WebView：先脱离父容器再 stopLoading/loadUrl/destroy，顺序反了会崩。 */
private fun destroyWebView(web: WebView) {
    runCatching {
        (web.parent as? ViewGroup)?.removeView(web)
        web.removeJavascriptInterface("JSInterface")
        web.stopLoading()
        web.loadUrl("about:blank")
        web.destroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YidunCaptchaDialog(
    osVer: String,
    onValidate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val main = remember { Handler(Looper.getMainLooper()) }
    val webRef = remember { mutableStateOf<WebView?>(null) }
    val disposed = remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(XbudColors.Surface),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "安全验证",
                    style = MaterialTheme.typography.titleLarge,
                    color = XbudColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "关闭",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                    color = XbudColors.Primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(XbudColors.Divider))
            Box(Modifier.fillMaxWidth().height(320.dp).padding(12.dp)) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(296.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            webViewClient = WebViewClient()
                            addJavascriptInterface(
                                YidunBridge(this, main, { !disposed.value }, onValidate),
                                "JSInterface",
                            )
                            loadUrl(yidunUrl(osVer))
                            webRef.value = this
                        }
                    },
                )
            }
            Text(
                "完成验证后会自动继续登录",
                style = MaterialTheme.typography.bodySmall,
                color = XbudColors.TextHint,
                modifier = Modifier.padding(start = 20.dp, bottom = 16.dp),
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // 先立闸门，再销毁：在飞的 JS 回调会看到 disposed=true 直接返回
            disposed.value = true
            webRef.value?.let { destroyWebView(it) }
            webRef.value = null
        }
    }
}
