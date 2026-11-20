package com.yg.xbudport

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yg.xbudport.data.AccountStore
import com.yg.xbudport.data.SessionLease
import com.yg.xbudport.ui.auth.ForgotScreen
import com.yg.xbudport.ui.auth.LoginScreen
import com.yg.xbudport.ui.auth.RegisterScreen
import com.yg.xbudport.ui.designsystem.LiquidBottomBar
import com.yg.xbudport.ui.designsystem.LiquidNavItem
import com.yg.xbudport.ui.designsystem.LiquidScaffold
import com.yg.xbudport.ui.designsystem.LiquidTheme
import com.yg.xbudport.ui.main.HistoryScreen
import com.yg.xbudport.ui.main.ScoreCache
import com.yg.xbudport.ui.main.HomeScreen
import com.yg.xbudport.ui.main.ProfileScreen
import com.yg.xbudport.ui.main.RunScreen
import com.yg.xbudport.ui.theme.XbudTheme

// 单 Activity + Compose Nav。认证页无底栏；主页/跑步/我的挂玻璃底栏。
// 页面骨架是 LiquidScaffold：背景渐变为唯一采样源，底栏是玻璃 chrome。
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 统一 edge-to-edge（targetSdk 35 在 Android 15+ 已默认；显式声明让老设备一致），
        // 系统栏安全区由 designsystem 的玻璃 chrome 自行消费——玻璃延伸到系统栏底下，
        // 内容避开状态栏/导航栏
        enableEdgeToEdge()
        applyMaxRefreshRate()
        // 租约服务器地址覆盖：真机/云机默认的 10.0.2.2 不可达时，用
        //   adb shell am start -n com.yg.xbudport/.MainActivity --es lease_url http://<PC-IP>:18788
        // 推一次即可持久化；不推则用 SessionLease.DEFAULT_URL。
        runCatching {
            intent?.getStringExtra("lease_url")?.takeIf { it.startsWith("http") }
                ?.let { SessionLease.setBaseUrl(this, it) }
        }
        setContent {
            // 诊断期：上次卡死/崩溃的全线程栈全屏展示（看完一键清除）
            var dump by remember { mutableStateOf(XbudApp.dumpText(this@MainActivity)) }
            val dumpText = dump
            if (dumpText != null) {
                DumpScreen(text = dumpText, onClear = {
                    XbudApp.clearDumps(this@MainActivity)
                    dump = null
                })
                return@setContent
            }
            val dark = isSystemInDarkTheme()
            // 玻璃浮层半透明：状态栏/导航栏图标随主题反转，避免沉浸式下撞色
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            XbudTheme(darkTheme = dark) {
                LiquidTheme(darkTheme = dark) {
                    AppRoot()
                }
            }
        }
    }

    // 高刷适配：把窗口首选刷新率钉到屏幕支持的最高档（120/144Hz），
    // 计时的秒表与地图平移才会按整屏帧率走，而不是被系统压到 60Hz。
    private fun applyMaxRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        } ?: return
        val max = display.supportedModes.maxOfOrNull { it.refreshRate } ?: return
        if (max <= 0f) return
        window.attributes = window.attributes.apply { preferredRefreshRate = max }
    }
}

private val mainTabs = setOf("home", "run", "profile")

private val bottomTabs = listOf(
    LiquidNavItem("home", "主页", Icons.Default.Home),
    LiquidNavItem("run", "跑步", Icons.Default.PlayArrow),
    LiquidNavItem("profile", "我的", Icons.Default.Person),
)

// 底栏切页：单顶栈 + 状态保持（主页/跑步/我的互切不重载）。
private fun androidx.navigation.NavController.navigateTab(target: String) {
    navigate(target) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppRoot() {
    val appContext = LocalContext.current
    val store = remember { AccountStore(appContext) }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "login"
    val bottomBar: (@Composable () -> Unit)? =
        if (route in mainTabs) {
            {
                LiquidBottomBar(
                    items = bottomTabs,
                    selectedRoute = route,
                    onSelect = { nav.navigateTab(it) },
                )
            }
        } else {
            null
        }

    LiquidScaffold(bottomBar = bottomBar) {
        // 不加 padding：底栏是叠在内容层上的玻璃 chrome，页面自己把滚动容器
        // pad 进去；页面级弹层（对话框/底部弹层）因此能盖住底栏。
        NavHost(
            navController = nav,
            startDestination = if (store.isLoggedIn) "home" else "login",
            modifier = Modifier,
        ) {
            composable("login") {
                LoginScreen(
                    store,
                    onLoggedIn = {
                        // 换号登录：成绩缓存属于上一个会话，立即失效
                        ScoreCache.invalidate()
                        nav.navigate("home") { popUpTo("login") { inclusive = true } }
                    },
                    onGoRegister = { nav.navigate("register") },
                    onGoForgot = { nav.navigate("forgot") },
                )
            }
            composable("register") {
                RegisterScreen(
                    onRegistered = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("forgot") {
                ForgotScreen(
                    onResetDone = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("home") {
                HomeScreen(
                    store,
                    onGoRun = { nav.navigate("run") },
                    onHistory = { nav.navigate("history") },
                    onSessionExpired = {
                        // 会话已失效：释放租约，看门狗不再踢这枚死 token
                        SessionLease.release(appContext, store.uid ?: "")
                        store.clear()
                        ScoreCache.invalidate()
                        nav.navigate("login") { popUpTo(0) }
                    },
                )
            }
            composable("history") {
                HistoryScreen(
                    store,
                    onBack = { nav.popBackStack() },
                    onSessionExpired = {
                        SessionLease.release(appContext, store.uid ?: "")
                        store.clear()
                        ScoreCache.invalidate()
                        nav.navigate("login") { popUpTo(0) }
                    },
                )
            }
            composable("profile") {
                ProfileScreen(
                    store,
                    onLoggedOut = {
                        ScoreCache.invalidate()
                        nav.navigate("login") { popUpTo(0) }
                    },
                    onHistory = { nav.navigate("history") },
                    onSessionExpired = {
                        SessionLease.release(appContext, store.uid ?: "")
                        store.clear()
                        ScoreCache.invalidate()
                        nav.navigate("login") { popUpTo(0) }
                    },
                )
            }
            composable("run") {
                RunScreen(store, onDone = {
                    // 跑步提交成功：产生了新成绩，成绩缓存失效（回主页重拉一次）
                    ScoreCache.invalidate()
                    nav.popBackStack()
                })
            }
        }
    }
}

// 诊断期：卡死/崩溃 dump 全屏展示（等宽字 + 可滚动 + 一键清除）
@Composable
private fun DumpScreen(text: String, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(12.dp),
    ) {
        Text(
            "诊断转储（主线程卡死或崩溃现场）",
            color = Color(0xFFFF6B6B),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onClear) { Text("清除并继续") }
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            color = Color(0xFFD8E0E8),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        )
    }
}
