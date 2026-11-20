package com.yg.xbudport.ui.auth

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.xbudport.R
import com.yg.xbudport.data.AccountStore
import com.yg.xbudport.data.SessionLease
import com.yg.xbudport.proto.AuthOut
import com.yg.xbudport.proto.Proto
import com.yg.xbudport.ui.captcha.YidunCaptchaDialog
import com.yg.xbudport.ui.designsystem.LiquidBottomSheet
import com.yg.xbudport.ui.designsystem.LiquidButton
import com.yg.xbudport.ui.designsystem.LiquidButtonStyle
import com.yg.xbudport.ui.designsystem.LiquidDialog
import com.yg.xbudport.ui.designsystem.LiquidIconButton
import com.yg.xbudport.ui.designsystem.LiquidInput
import com.yg.xbudport.ui.designsystem.LiquidPromptDialog
import com.yg.xbudport.ui.designsystem.LiquidRow
import com.yg.xbudport.ui.designsystem.LiquidSection
import com.yg.xbudport.ui.designsystem.LiquidSpace
import com.yg.xbudport.ui.designsystem.LiquidTopBar
import com.yg.xbudport.ui.designsystem.LocalLiquidChrome
import com.yg.xbudport.ui.designsystem.LiquidType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 认证三件套：登录 / 注册 / 忘记密码。
// 共同机制：易盾 validate 走 WebView 官方 H5（YidunCaptchaDialog），
// 协议调用走 Proto（纯 Kotlin 协议层，Dispatchers.IO 上阻塞）。
// UI 是 designsystem 的玻璃面：输入框 / 按钮 / 底栏弹层 / 提示对话框。

private const val DEFAULT_PROFILE = "三星 A52"

private suspend fun loadProfileNames(dir: String): List<String> {
    val storeNames = runCatching { Proto.profilesList(dir) }.getOrDefault(emptyList())
    val presets = runCatching { Proto.presetList() }.getOrDefault(emptyList())
    return (storeNames + presets).distinct().ifEmpty { listOf(DEFAULT_PROFILE) }
}

private fun authErrorText(e: Throwable): String = e.message ?: e.toString()

@Composable
fun LoginScreen(
    store: AccountStore,
    onLoggedIn: () -> Unit,
    onGoRegister: () -> Unit,
    onGoForgot: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(store.username ?: "") }
    var password by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf(store.profileName ?: DEFAULT_PROFILE) }
    var profileNames by remember { mutableStateOf(listOf(DEFAULT_PROFILE)) }
    var debugValidate by remember { mutableStateOf("") }
    var captchaVisible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var settingsSheet by remember { mutableStateOf(false) }
    var profileSheet by remember { mutableStateOf(false) }
    var importPrompt by remember { mutableStateOf(false) }
    var debugPrompt by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun refreshProfiles() {
        scope.launch {
            profileNames = withContext(Dispatchers.IO) { loadProfileNames(store.dir) }
        }
    }

    LaunchedEffect(Unit) {
        refreshProfiles()
        // 模拟器/自动化通道：PC 侧 run-as 推 files/debug_validate.txt。
        // 异步读——文件 IO 不放组合帧上，否则慢盘上首帧直接 ANR。
        debugValidate = withContext(Dispatchers.IO) {
            runCatching {
                java.io.File(store.dir, "debug_validate.txt").takeIf { it.exists() }
                    ?.readText()?.trim().orEmpty()
            }.getOrDefault("")
        }
    }

    val doLogin: (String) -> Unit = login@{ validate ->
        if (validate.isBlank()) {
            failure = "验证未完成"
            return@login
        }
        busy = true
        failure = null
        scope.launch(Dispatchers.IO) {
            try {
                val out = Proto.login(store.dir, username.trim(), password, validate, profile)
                if (out.error == 10000 && out.token.isNotBlank()) {
                    store.uid = out.uid
                    store.token = out.token
                    store.username = username.trim()
                    store.profileName = profile
                    // 挂会话租约并立刻续跳：服务端同时顶掉旧 token 的会话
                    runCatching { SessionLease.onLoggedIn(context, store) }
                    launch(Dispatchers.Main) { onLoggedIn() }
                } else {
                    launch(Dispatchers.Main) { failure = "[${out.error}] ${out.message}" }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { failure = authErrorText(e) }
            } finally {
                launch(Dispatchers.Main) { busy = false }
            }
        }
    }

    val startLogin: () -> Unit = {
        if (com.yg.xbudport.BuildConfig.DEBUG && debugValidate.isNotBlank()) {
            doLogin(debugValidate)
        } else {
            captchaVisible = true
        }
    }

    val palette = LocalLiquidChrome.current.palette

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(84.dp))
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "小步点",
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "校园跑步",
                style = LiquidType.Body,
                color = palette.textTertiary,
            )
            Spacer(Modifier.height(34.dp))

            LiquidInput(
                value = username,
                onValueChange = { username = it },
                label = "手机号",
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Next,
                enabled = !busy,
            )
            Spacer(Modifier.height(14.dp))
            LiquidInput(
                value = password,
                onValueChange = { password = it },
                label = "密码",
                password = true,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                enabled = !busy,
                onDone = {
                    if (username.isNotBlank() && password.isNotBlank() && !busy) startLogin()
                },
            )

            failure?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = LiquidType.Label, color = palette.error)
            }

            Spacer(Modifier.height(22.dp))
            LiquidButton(
                text = "登录",
                onClick = startLogin,
                enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                loading = busy,
                height = 50.dp,
            )

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AuthLink("注册", onGoRegister)
                AuthLink("忘记密码", onGoForgot)
            }
            Spacer(Modifier.height(48.dp))
        }

        LiquidIconButton(
            icon = Icons.Default.Settings,
            contentDescription = "登录设置",
            onClick = { settingsSheet = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        )
    }

    if (settingsSheet) {
        LiquidBottomSheet(
            visible = true,
            onDismiss = { settingsSheet = false },
            title = "登录设置",
        ) {
            LiquidSection(
                rows = buildList {
                    add(
                        LiquidRow(
                            title = "设备画像",
                            detail = profile,
                            chevron = true,
                            onClick = { profileSheet = true },
                        )
                    )
                    if (com.yg.xbudport.BuildConfig.DEBUG) {
                        add(
                            LiquidRow(
                                title = "调试 validate",
                                detail = debugValidate.ifBlank { "未设置" },
                                onClick = { debugPrompt = true },
                            )
                        )
                    }
                },
            )
        }
    }

    if (profileSheet) {
        LiquidBottomSheet(
            visible = true,
            onDismiss = { profileSheet = false },
            title = "设备画像",
        ) {
            LiquidSection(
                rows = buildList {
                    profileNames.forEach { name ->
                        add(
                            LiquidRow(
                                title = name,
                                detail = if (name == profile) "当前" else "",
                                onClick = {
                                    profile = name
                                    profileSheet = false
                                },
                            )
                        )
                    }
                    add(LiquidRow("＋ 导入画像", onClick = { importPrompt = true }))
                },
            )
        }
    }

    if (importPrompt) {
        LiquidPromptDialog(
            visible = true,
            title = "导入设备画像",
            placeholder = "粘贴 JSON",
            multiline = true,
            confirmText = "导入",
            onConfirm = { text ->
                importPrompt = false
                scope.launch(Dispatchers.IO) {
                    val n = runCatching { Proto.profileImport(store.dir, text) }.getOrElse { -1 }
                    withContext(Dispatchers.Main) {
                        if (n > 0) {
                            refreshProfiles()
                            alert = "导入完成" to "已导入 $n 个设备画像。"
                        } else {
                            alert = "导入失败" to "JSON 不合法，未写入任何画像。"
                        }
                    }
                }
            },
            onDismiss = { importPrompt = false },
        )
    }

    if (debugPrompt) {
        LiquidPromptDialog(
            visible = true,
            title = "调试 validate",
            placeholder = "留空则走易盾弹窗",
            onConfirm = {
                debugValidate = it.trim()
                debugPrompt = false
            },
            onDismiss = { debugPrompt = false },
        )
    }

    alert?.let { (title, message) ->
        LiquidDialog(
            visible = true,
            title = title,
            message = message,
            confirmText = "知道了",
            onConfirm = { alert = null },
            onDismiss = { alert = null },
        )
    }

    if (captchaVisible) {
        YidunCaptchaDialog(
            osVer = Build.VERSION.RELEASE,
            onValidate = { validate ->
                captchaVisible = false
                doLogin(validate)
            },
            onDismiss = { captchaVisible = false },
        )
    }
}

@Composable
private fun AuthLink(text: String, onClick: () -> Unit) {
    val palette = LocalLiquidChrome.current.palette
    Text(
        text = text,
        style = LiquidType.Body.copy(fontSize = 14.sp),
        color = palette.primaryText,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

// 两步流：① 易盾 → sendRegisterCode(type) 拿 verifyToken（服务器同时发短信）
//        ② 短信码 + 密码 → register / reset_password
@Composable
private fun TwoStepAuthScreen(
    title: String,
    smsType: Long,
    submitLabel: String,
    onResult: (dir: String, phone: String, verifyToken: String, code: String, pass: String, profile: String) -> AuthOut,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val store = remember { AccountStore(ctx) }
    // 注册/改密沿用登录设置里选定的画像，避免两处各选一次
    val profile = store.profileName ?: DEFAULT_PROFILE
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var verifyToken by remember { mutableStateOf<String?>(null) }
    var captchaVisible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val palette = LocalLiquidChrome.current.palette

    val sendSms: (String) -> Unit = { validate ->
        busy = true
        failure = null
        scope.launch(Dispatchers.IO) {
            try {
                val out = Proto.sendRegisterCode(store.dir, phone.trim(), validate, smsType, profile)
                if (out.error == 10000 && out.verifyToken.isNotBlank()) {
                    verifyToken = out.verifyToken
                    launch(Dispatchers.Main) { note = "验证码已发送" }
                } else {
                    launch(Dispatchers.Main) { failure = "[${out.error}] ${out.message}" }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { failure = authErrorText(e) }
            } finally {
                launch(Dispatchers.Main) { busy = false }
            }
        }
    }

    val submit: () -> Unit = submit@{
        val vt = verifyToken
        if (vt == null) {
            failure = "请先获取验证码"
            return@submit
        }
        busy = true
        failure = null
        scope.launch(Dispatchers.IO) {
            try {
                val out = onResult(store.dir, phone.trim(), vt, code.trim(), pass, profile)
                if (out.error == 10000) {
                    launch(Dispatchers.Main) { onDone() }
                } else {
                    launch(Dispatchers.Main) { failure = "[${out.error}] ${out.message}" }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { failure = authErrorText(e) }
            } finally {
                launch(Dispatchers.Main) { busy = false }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LiquidTopBar(
            title = title,
            navigationIcon = {
                LiquidIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                )
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(26.dp))
            Text(
                if (verifyToken == null) "获取短信验证码" else "输入短信验证码并设置密码",
                style = LiquidType.Title,
                color = palette.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (verifyToken == null) "第 1 步" else "第 2 步",
                style = LiquidType.Caption,
                color = palette.textTertiary,
            )
            Spacer(Modifier.height(24.dp))

            if (verifyToken == null) {
                LiquidInput(
                    value = phone,
                    onValueChange = { phone = it },
                    label = "手机号",
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done,
                    enabled = !busy,
                    onDone = { if (phone.isNotBlank() && !busy) captchaVisible = true },
                )
                failure?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = LiquidType.Label, color = palette.error)
                }
                Spacer(Modifier.height(22.dp))
                LiquidButton(
                    text = "发验证码",
                    onClick = { captchaVisible = true },
                    enabled = !busy && phone.isNotBlank(),
                    loading = busy,
                    height = 50.dp,
                )
            } else {
                note?.let {
                    Text(it, style = LiquidType.Body, color = palette.primaryText)
                    Spacer(Modifier.height(16.dp))
                }
                LiquidInput(
                    value = code,
                    onValueChange = { code = it },
                    label = "短信验证码",
                    numeric = true,
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                    enabled = !busy,
                )
                Spacer(Modifier.height(14.dp))
                LiquidInput(
                    value = pass,
                    onValueChange = { pass = it },
                    label = if (smsType == 0L) "设置密码" else "新密码",
                    password = true,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    enabled = !busy,
                    onDone = { if (code.isNotBlank() && pass.isNotBlank() && !busy) submit() },
                )
                failure?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = LiquidType.Label, color = palette.error)
                }
                Spacer(Modifier.height(22.dp))
                LiquidButton(
                    text = submitLabel,
                    onClick = submit,
                    enabled = !busy && code.isNotBlank() && pass.isNotBlank(),
                    loading = busy,
                    height = 50.dp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "重新获取",
                    style = LiquidType.Body,
                    color = palette.primaryText,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !busy) {
                            verifyToken = null
                            note = null
                            failure = null
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (captchaVisible) {
        YidunCaptchaDialog(
            osVer = Build.VERSION.RELEASE,
            onValidate = { validate ->
                captchaVisible = false
                sendSms(validate)
            },
            onDismiss = { captchaVisible = false },
        )
    }
}

@Composable
fun RegisterScreen(onRegistered: () -> Unit, onBack: () -> Unit) {
    TwoStepAuthScreen(
        title = "注册",
        smsType = 0,
        submitLabel = "注册",
        onResult = { dir, phone, vt, code, pass, profile ->
            Proto.register(dir, phone, vt, code, pass, profile)
        },
        onDone = onRegistered,
        onBack = onBack,
    )
}

@Composable
fun ForgotScreen(onResetDone: () -> Unit, onBack: () -> Unit) {
    TwoStepAuthScreen(
        title = "忘记密码",
        smsType = 1,
        submitLabel = "重置密码",
        onResult = { dir, phone, vt, code, pass, profile ->
            Proto.resetPassword(dir, phone, vt, code, pass, profile)
        },
        onDone = onResetDone,
        onBack = onBack,
    )
}
