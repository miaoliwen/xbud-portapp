package com.yg.xbudport.ui.main

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.xbudport.data.AccountStore
import com.yg.xbudport.data.Diagnostics
import com.yg.xbudport.data.SessionLease
import com.yg.xbudport.proto.ErrTokenExpired
import com.yg.xbudport.proto.Proto
import com.yg.xbudport.proto.ProtoException
import com.yg.xbudport.ui.designsystem.LiquidAvatar
import com.yg.xbudport.ui.designsystem.LiquidButton
import com.yg.xbudport.ui.designsystem.LiquidButtonStyle
import com.yg.xbudport.ui.designsystem.LiquidDialog
import com.yg.xbudport.ui.designsystem.LiquidRow
import com.yg.xbudport.ui.designsystem.LiquidSection
import com.yg.xbudport.ui.designsystem.LiquidSpace
import com.yg.xbudport.ui.designsystem.LiquidStateView
import com.yg.xbudport.ui.designsystem.LiquidSurface
import com.yg.xbudport.ui.designsystem.LiquidTag
import com.yg.xbudport.ui.designsystem.LiquidTopBar
import com.yg.xbudport.ui.designsystem.LiquidType
import com.yg.xbudport.ui.designsystem.LocalLiquidChrome
import com.yg.xbudport.ui.designsystem.LocalLiquidContentPadding
import com.yg.xbudport.ui.designsystem.LiquidMaterials
import com.yg.xbudport.ui.designsystem.LiquidRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDateTime
import java.util.Locale

// 主页（M3：跑步进度）/ 我的（M4：个人主页）。
// 数据源不变：get_config.runRuleModel + home_page_info + get_record_list + get_info；
// UI 换 designsystem 玻璃面。页面自身不画不透明底——App 级 LiquidScaffold 的
// 背景渐变是唯一采样源，玻璃浮层从它取背景。

internal data class HomeUi(
    val loading: Boolean = true,
    val error: String? = null,
    val tokenExpired: Boolean = false,
    val rule: RunRule? = null,
    val semester: SemesterInfo? = null,
    val weekCount: Int = 0,
    val weekDisM: Double = 0.0,
    val todayDisM: Double = 0.0,
    val note: String? = null,
)

private suspend fun loadHome(store: AccountStore): HomeUi = withContext(Dispatchers.IO) {
    try {
        val s = Proto.newSession(store.dir, store.uid ?: "", store.token ?: "", store.profileName ?: "")

        var cfgErr: String? = null
        var recErr: String? = null
        val cfgRaw = try {
            s.getConfig()
        } catch (e: ErrTokenExpired) {
            throw e
        } catch (e: Exception) {
            cfgErr = e.message ?: e.toString()
            null
        }
        val recRaw = try {
            s.records(1, 20)
        } catch (e: ErrTokenExpired) {
            throw e
        } catch (e: Exception) {
            recErr = e.message ?: e.toString()
            null
        }
        if (cfgRaw == null && recRaw == null) {
            throw ProtoException(cfgErr ?: recErr ?: "加载失败")
        }

        val rule = cfgRaw?.let { runCatching { HomeData.parseRunRule(it) }.getOrNull() }
        val records = recRaw?.let { runCatching { HomeData.parseRecords(it) }.getOrNull() } ?: emptyList()

        var semester: SemesterInfo? = null
        var semesterFromRecords = false
        val homeRaw = try {
            s.call("/v1/user/home_page_info", "{}")
        } catch (e: ErrTokenExpired) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (homeRaw != null) semester = runCatching { HomeData.parseHomeInfo(homeRaw) }.getOrNull()

        val rrid = records.firstOrNull { it.complete && it.rrid > 0L }?.rrid
            ?: records.firstOrNull { it.rrid > 0L }?.rrid
        if (semester == null && rrid != null) {
            val completeRaw = try {
                s.call("/v1/sport/run/completed_info", "{\"rrid\":$rrid}")
            } catch (e: ErrTokenExpired) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (completeRaw != null) {
                semester = runCatching { HomeData.parseCompletedInfo(completeRaw) }.getOrNull()
            }
        }
        if (semester == null) {
            semester = HomeData.fromRecords(records)
            semesterFromRecords = true
        }

        val now = LocalDateTime.now()
        val (weekCount, weekDisM) = HomeData.aggregate(records, HomeData.weekStartMs(now))
        val (_, todayDisM) = HomeData.aggregate(records, HomeData.dayStartMs(now))

        val notes = buildList {
            cfgErr?.let { add("规则加载失败：$it") }
            recErr?.let { add("记录加载失败：$it") }
            if (rule == null) add("服务端未下发跑步规则")
            if (semesterFromRecords) add("学期统计按最近记录估算")
        }.joinToString("；")

        HomeUi(
            loading = false,
            rule = rule,
            semester = semester,
            weekCount = weekCount,
            weekDisM = weekDisM,
            todayDisM = todayDisM,
            note = notes.ifBlank { null },
        )
    } catch (e: ErrTokenExpired) {
        HomeUi(loading = false, tokenExpired = true, error = "登录已过期")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (t: Throwable) {
        // Error 级（OOM/LinkageError 等）也归一为错误态，不允许逃出协程杀进程
        HomeUi(loading = false, error = t.message ?: t.toString())
    }
}

@Composable
fun HomeScreen(
    store: AccountStore,
    onGoRun: () -> Unit,
    onHistory: () -> Unit = {},
    onSessionExpired: () -> Unit = {},
) {
    // 成绩缓存命中 = 免二次加载：重进页面直接渲染上次数据，网络只在首次 /
    // 显式刷新 / 缓存被失效（跑步提交、换会话）时走
    var ui by remember { mutableStateOf(ScoreCache.home ?: HomeUi()) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadTick) {
        if (reloadTick == 0) {
            val cached = ScoreCache.home
            if (cached != null && !cached.loading && cached.error == null && !cached.tokenExpired) {
                ui = cached
                return@LaunchedEffect
            }
        }
        ui = HomeUi()
        ui = loadHome(store)
        if (ui.error == null && !ui.tokenExpired) ScoreCache.home = ui
    }

    Column(Modifier.fillMaxSize()) {
        LiquidTopBar(
            title = "跑步进度",
            actions = {
                LiquidButton(
                    text = "刷新",
                    onClick = { reloadTick++ },
                    style = LiquidButtonStyle.Text,
                    enabled = !ui.loading,
                    height = 44.dp,
                    fill = false,
                )
            },
        )
        when {
            ui.loading -> LiquidStateView(title = "加载中")
            ui.error != null -> LiquidStateView(
                title = if (ui.tokenExpired) "会话已失效" else "加载失败",
                message = ui.error,
                actionText = if (ui.tokenExpired) "重新登录" else "重试",
                onAction = { if (ui.tokenExpired) onSessionExpired() else reloadTick++ },
                error = true,
            )
            else -> Column(
                Modifier
                    .padding(LocalLiquidContentPadding.current)
                    .fillMaxSize()
                    .clipToBounds()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LiquidSpace.Page, vertical = LiquidSpace.Md),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ProgressCard(ui)
                GoRunButton(ui.rule, onGoRun)
                HomeSections(ui, onHistory)
                ui.note?.let {
                    Text(
                        it,
                        style = LiquidType.Caption,
                        color = LocalLiquidChrome.current.palette.textTertiary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun HomeSections(ui: HomeUi, onHistory: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LiquidSpace.Md)) {
        ui.rule?.let { r ->
            LiquidSection(
                title = "跑步规则",
                rows = buildList {
                    add(LiquidRow("有效时段", r.windowLabel()))
                    if (r.minDistanceM > 0) add(LiquidRow("单次最低", "${HomeData.kmText(r.minDistanceM)} km"))
                    if (r.speedTop > 0 || r.speedBottom > 0) {
                        add(
                            LiquidRow(
                                "配速区间",
                                "%.1f–%.1f 分/km".format(Locale.ROOT, r.speedTop, r.speedBottom),
                            )
                        )
                    }
                    if (r.stepTop > 0 || r.stepBottom > 0) {
                        add(LiquidRow("步频区间", "${r.stepBottom}–${r.stepTop} 步/分"))
                    }
                    if (r.maxRunTimeSec > 0) add(LiquidRow("单次上限", "${r.maxRunTimeSec / 60} 分钟"))
                    if (r.pauseTimeSec > 0) add(LiquidRow("暂停上限", "${r.pauseTimeSec / 60} 分钟"))
                },
            )
        }
        LiquidSection(
            rows = listOf(LiquidRow("跑步历史", chevron = true, onClick = onHistory)),
        )
    }
}

@Composable
private fun ProgressCard(ui: HomeUi) {
    val palette = LocalLiquidChrome.current.palette
    val weekGoal = ui.rule?.weekCountGoal ?: 0
    LiquidSurface(
        material = LiquidMaterials.card(palette),
        shape = RoundedCornerShape(LiquidRadius.Card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(LiquidSpace.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SemesterRing(ui.semester)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatCell(
                    "本周",
                    if (weekGoal > 0) "${ui.weekCount}/$weekGoal" else "${ui.weekCount}",
                    "次",
                    Modifier.weight(1f),
                )
                StatDivider()
                StatCell("本周跑量", HomeData.kmText(ui.weekDisM), "km", Modifier.weight(1f))
                StatDivider()
                StatCell("今日跑量", HomeData.kmText(ui.todayDisM), "km", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).height(30.dp).background(LocalLiquidChrome.current.palette.divider))
}

@Composable
private fun SemesterRing(sem: SemesterInfo?) {
    val palette = LocalLiquidChrome.current.palette
    val progress = sem?.progress ?: 0f
    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = palette.primarySoft,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = palette.primary,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("本学期", style = LiquidType.Label, color = palette.textTertiary)
            when {
                sem == null -> Text(
                    "—",
                    style = LiquidType.Display,
                    color = palette.textPrimary,
                )
                sem.goalMethod == 1 && sem.goalCount > 0 -> {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${sem.validCount}",
                            style = LiquidType.Display,
                            color = palette.textPrimary,
                        )
                        Text(
                            "/ ${sem.goalCount} 次",
                            style = LiquidType.Body,
                            color = palette.textTertiary,
                            modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
                        )
                    }
                }
                sem.goalMethod == 0 && sem.goalDisM > 0.0 -> {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            HomeData.kmText(sem.validDisM, 1),
                            style = LiquidType.Display,
                            color = palette.textPrimary,
                        )
                        Text(
                            "/ ${HomeData.kmText(sem.goalDisM, 1)} km",
                            style = LiquidType.Body,
                            color = palette.textTertiary,
                            modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
                        )
                    }
                }
                else -> {
                    Text(
                        "${sem.validCount} 次",
                        style = LiquidType.Title,
                        color = palette.textPrimary,
                    )
                    Text(
                        "${HomeData.kmText(sem.validDisM, 1)} km",
                        style = LiquidType.Label,
                        color = palette.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val palette = LocalLiquidChrome.current.palette
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = LiquidType.Caption, color = palette.textTertiary)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = LiquidType.Title.copy(fontFeatureSettings = "tnum"),
                color = palette.textPrimary,
            )
            Text(
                unit,
                style = LiquidType.Caption,
                color = palette.textTertiary,
                modifier = Modifier.padding(start = 2.dp, bottom = 1.dp),
            )
        }
    }
}

@Composable
private fun GoRunButton(rule: RunRule?, onGoRun: () -> Unit) {
    val palette = LocalLiquidChrome.current.palette
    val open = rule?.isOpen() ?: true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LiquidButton(text = "去跑步", onClick = onGoRun, enabled = open, height = 50.dp)
        if (!open) {
            val hint = rule?.nextOpenHint()?.let { "有效时段外 · $it" }
                ?: "有效时段外 · ${rule?.windowLabel() ?: ""}"
            Text(
                hint,
                style = LiquidType.Caption,
                color = palette.textTertiary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private data class ProfileUi(
    val loading: Boolean = true,
    val error: String? = null,
    val tokenExpired: Boolean = false,
    val info: ProfileInfo? = null,
)

private suspend fun loadProfile(store: AccountStore): ProfileUi = withContext(Dispatchers.IO) {
    try {
        val s = Proto.newSession(store.dir, store.uid ?: "", store.token ?: "", store.profileName ?: "")
        val info = ProfileData.parse(s.getInfo())
            ?: throw ProtoException("get_info 返回结构异常")
        ProfileUi(loading = false, info = info)
    } catch (e: ErrTokenExpired) {
        ProfileUi(loading = false, tokenExpired = true, error = "登录已过期")
    } catch (e: Exception) {
        ProfileUi(loading = false, error = e.message ?: e.toString())
    }
}

@Composable
fun ProfileScreen(
    store: AccountStore,
    onLoggedOut: () -> Unit,
    onHistory: () -> Unit = {},
    onSessionExpired: () -> Unit = {},
) {
    val context = LocalContext.current
    var ui by remember { mutableStateOf(ProfileUi()) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var logoutConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadTick) {
        ui = ProfileUi()
        ui = loadProfile(store)
    }

    fun logout() {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    Proto.newSession(
                        store.dir,
                        store.uid ?: "",
                        store.token ?: "",
                        store.profileName ?: "",
                    ).logout()
                }
            }
            // 释放租约：看门狗不再踢这枚已登出的 token
            SessionLease.release(context, store.uid ?: "")
            store.clear()
            onLoggedOut()
        }
    }

    Column(Modifier.fillMaxSize()) {
        LiquidTopBar(
            title = "我的",
            actions = {
                LiquidButton(
                    text = "刷新",
                    onClick = { reloadTick++ },
                    style = LiquidButtonStyle.Text,
                    enabled = !ui.loading,
                    height = 44.dp,
                    fill = false,
                )
            },
        )
        when {
            ui.loading -> LiquidStateView(title = "加载中")
            ui.error != null -> LiquidStateView(
                title = if (ui.tokenExpired) "会话已失效" else "加载失败",
                message = ui.error,
                actionText = if (ui.tokenExpired) "重新登录" else "重试",
                onAction = { if (ui.tokenExpired) onSessionExpired() else reloadTick++ },
                error = true,
            )
            else -> Column(
                Modifier
                    .padding(LocalLiquidContentPadding.current)
                    .fillMaxSize()
                    .clipToBounds()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LiquidSpace.Page, vertical = LiquidSpace.Md),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ui.info?.let { info ->
                    ProfileHeader(info)
                    ProfileSections(info, onHistory) { logoutConfirm = true }
                }
                LiquidSection(
                    title = "开发者选项",
                    rows = listOf(
                        LiquidRow(
                            title = "导出诊断日志",
                            detail = "logcat + 卡死/崩溃栈 + 设备信息",
                            onClick = {
                                scope.launch {
                                    val file = withContext(Dispatchers.IO) {
                                        runCatching { Diagnostics.export(context) }.getOrNull()
                                    }
                                    if (file != null) {
                                        runCatching {
                                            context.startActivity(
                                                Intent.createChooser(
                                                    Diagnostics.shareIntent(context, file),
                                                    "导出诊断日志",
                                                )
                                            )
                                        }
                                    }
                                }
                            },
                        ),
                    ),
                )
                Text(
                    "v0.1.0",
                    style = LiquidType.Caption,
                    color = LocalLiquidChrome.current.palette.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
                )
            }
        }
    }

    if (logoutConfirm) {
        LiquidDialog(
            visible = true,
            title = "退出登录",
            message = null,
            confirmText = "退出登录",
            onConfirm = {
                logoutConfirm = false
                logout()
            },
            onDismiss = { logoutConfirm = false },
            dismissText = "取消",
            danger = true,
        )
    }
}

@Composable
private fun ProfileSections(
    info: ProfileInfo,
    onHistory: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LiquidSpace.Md)) {
        LiquidSection(
            title = "个人资料",
            rows = buildList {
                add(LiquidRow("学号", info.studentId.ifBlank { "—" }))
                add(LiquidRow("学院", info.department.ifBlank { "—" }))
                if (info.gradeClass.isNotBlank()) add(LiquidRow("班级", info.gradeClass))
                add(LiquidRow("校区", info.campusName.ifBlank { "—" }))
                add(LiquidRow("性别", info.genderLabel()))
                if (info.enrollmentYear > 0) add(LiquidRow("入学年份", "${info.enrollmentYear}"))
            },
        )
        LiquidSection(
            title = "账号",
            rows = listOf(
                LiquidRow("手机号", maskPhone(info.phone)),
                LiquidRow("实名认证", info.identificationLabel()),
                LiquidRow("三方绑定", info.bindingLabel()),
            ),
        )
        LiquidSection(
            rows = buildList {
                add(LiquidRow("跑步历史", chevron = true, onClick = onHistory))
                add(LiquidRow("退出登录", danger = true, onClick = onLogout))
            },
        )
    }
}

private fun maskPhone(phone: String): String {
    if (phone.length < 7) return phone
    return phone.replaceRange(3, 7, "****")
}

@Composable
private fun ProfileHeader(info: ProfileInfo) {
    val palette = LocalLiquidChrome.current.palette
    val name = info.name.ifBlank { info.nickname }
    LiquidSurface(
        material = LiquidMaterials.card(palette),
        shape = RoundedCornerShape(LiquidRadius.Card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(LiquidSpace.Xl),
            horizontalArrangement = Arrangement.spacedBy(LiquidSpace.Lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(info.avatarUrl, name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    name.ifBlank { "未命名" },
                    style = LiquidType.Title.copy(fontSize = 19.sp),
                    color = palette.textPrimary,
                    maxLines = 1,
                )
                val subtitle = listOf(info.studentId, info.department)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = LiquidType.Body,
                        color = palette.textTertiary,
                        maxLines = 1,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (info.identificationStatus >= 0) {
                        LiquidTag(
                            info.identificationLabel(),
                            color = if (info.identificationStatus == 1) {
                                palette.success
                            } else {
                                palette.warning
                            },
                        )
                    }
                    LiquidTag(
                        if (info.infoComplete) "已完善" else "待完善",
                        color = if (info.infoComplete) palette.primary else palette.textTertiary,
                    )
                    if (info.campusName.isNotBlank()) {
                        LiquidTag(info.campusName, color = palette.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileAvatar(url: String, name: String) {
    val image by produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = if (url.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    avatarClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            null
                        } else {
                            resp.body?.bytes()?.let { bytes ->
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                        }
                    }
                }.getOrNull()
            }
        }
    }
    LiquidAvatar(bitmap = image?.asImageBitmap(), name = name)
}

private val avatarClient = OkHttpClient()
