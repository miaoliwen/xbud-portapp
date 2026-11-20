package com.yg.xbudport.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import com.yg.xbudport.ui.designsystem.fadeInSpec
import com.yg.xbudport.ui.designsystem.fadeOutSpec
import com.yg.xbudport.ui.designsystem.pressScale
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.yg.xbudport.data.AccountStore
import com.yg.xbudport.proto.Proto
import com.yg.xbudport.proto.Session
import com.yg.xbudport.run.Checkpoint
import com.yg.xbudport.run.Geo
import com.yg.xbudport.run.MapSupport
import com.yg.xbudport.run.PointSelector
import com.yg.xbudport.run.RunEngine
import com.yg.xbudport.run.RunLimits
import com.yg.xbudport.run.RunService
import com.yg.xbudport.run.RunSession
import com.yg.xbudport.ui.designsystem.LiquidButton
import com.yg.xbudport.ui.designsystem.LiquidButtonStyle
import com.yg.xbudport.ui.designsystem.LiquidChip
import com.yg.xbudport.ui.designsystem.LiquidDialog
import com.yg.xbudport.ui.designsystem.LiquidMaterials
import com.yg.xbudport.ui.designsystem.LiquidRadius
import com.yg.xbudport.ui.designsystem.LiquidSpace
import com.yg.xbudport.ui.designsystem.LiquidTag
import com.yg.xbudport.ui.designsystem.LiquidType
import com.yg.xbudport.ui.designsystem.LocalLiquidChrome
import com.yg.xbudport.ui.designsystem.LocalLiquidContentPadding
import com.yg.xbudport.ui.designsystem.liquidTint
import com.yg.xbudport.ui.designsystem.rememberConfirmHaptic
import com.yg.xbudport.ui.theme.XbudColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.min

private const val MAP_LOAD_TIMEOUT_MS = 10_000L

// 学分跑完整流程：选距离 → fetchFixedPoints（开跑会话）→ 原版选点排序 →
// 定位采样 + 打卡判定 → 结束五项判定 → SubmitRun（save_record + 真实轨迹 COS 上传）。
//
// 地图上的浮层（选距卡 / 计时卡 / 成绩卡 / 打卡条）一律用不采样遮罩材质：
// TextureMapView 的内容不进 Compose GraphicsLayer，采样只会拿到空层，
// 透明 tint 直接压在地图上，文字对比度由遮罩 alpha 保证。

@Composable
fun RunScreen(store: AccountStore, onDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val engine = remember { RunSession.engine(ctx) }

    var rule by remember { mutableStateOf<RunRule?>(null) }
    var configLoading by remember { mutableStateOf(true) }
    var configError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val s = Proto.newSession(store.dir, store.uid ?: "", store.token ?: "", store.profileName ?: "")
                HomeData.parseRunRule(s.getConfig())
            }.getOrNull()
        }
        rule = r
        configLoading = false
        if (r == null) configError = "跑步规则加载失败，将使用默认阈值"
    }

    LaunchedEffect(engine.phase) {
        when (engine.phase) {
            "running", "paused" -> runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RunService::class.java))
            }
            "finished", "ready" -> runCatching {
                ctx.stopService(Intent(ctx, RunService::class.java))
            }
        }
    }

    DisposableEffect(engine.phase) {
        view.keepScreenOn = engine.phase == "running" || engine.phase == "paused"
        onDispose { view.keepScreenOn = false }
    }

    // 轨迹线 + 当前位置 + 相机跟随（仅 arm64/x86_64 + 已配 Android key 时挂地图）。
    // 纹理版地图：天玑/Mali 上掉 surface 只黑一帧，不会像 SurfaceView 版把 native 层带崩。
    val mapView = remember {
        if (MapSupport.amapUsable(ctx)) {
            runCatching { TextureMapView(ctx) }.getOrNull()
        } else {
            null
        }
    }
    var aMap by remember { mutableStateOf<AMap?>(null) }
    var polyline by remember { mutableStateOf<Polyline?>(null) }
    var myMarker by remember { mutableStateOf<Marker?>(null) }
    var cpMarkers by remember { mutableStateOf(listOf<Marker>()) }
    var cpCircles by remember { mutableStateOf(listOf<Circle>()) }
    var nextRing by remember { mutableStateOf<Circle?>(null) }
    var follow by remember { mutableStateOf(true) }
    var mapCreated by remember { mutableStateOf(false) }
    var mapTornDown by remember { mutableStateOf(false) }
    var mapReady by remember { mutableStateOf(false) }
    var mapFailed by remember { mutableStateOf(false) }
    var hasLocPerm by remember { mutableStateOf(false) }
    var locationEnabled by remember { mutableStateOf(RunEngine.systemLocationEnabled(ctx)) }

    // 地图 GL 引擎起不来时（天玑/Mali 上偶发、无网加载不出底图）退回本地画布：
    // 点位标注在画布上照常画，不会出现"页面空白、看不到打卡点"。
    val useAmap = mapView != null && !mapFailed

    // MapView 生命周期：onCreate 在 AndroidView 工厂里（创建即初始化）；onResume/onPause
    // 跟 Activity 生命周期；onDestroy 必须在视图脱离父容器之后调，否则高德 native 层
    // 直接崩（Compose 里 onDispose 时视图可能还挂在树上，所以这里显式先摘）。
    fun teardownMap() {
        if (mapTornDown || !mapCreated) return
        mapTornDown = true
        val mv = mapView
        if (mv != null) {
            runCatching {
                (mv.parent as? ViewGroup)?.removeView(mv)
                mv.onPause()
                mv.onDestroy()
            }
        }
        mapCreated = false
        aMap = null
        polyline = null
        myMarker = null
        cpMarkers = emptyList()
        cpCircles = emptyList()
        nextRing = null
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    locationEnabled = RunEngine.systemLocationEnabled(ctx)
                    if (mapCreated) mapView?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> if (mapCreated) mapView?.onPause()
                Lifecycle.Event.ON_DESTROY -> teardownMap()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            teardownMap()
        }
    }

    // onMapLoaded 是 GL 引擎起来的唯一可靠信号；10s 没等到就按不可用处理
    LaunchedEffect(aMap) {
        val am = aMap ?: return@LaunchedEffect
        am.setOnMapLoadedListener { mapReady = true }
        delay(MAP_LOAD_TIMEOUT_MS)
        if (!mapReady) {
            mapFailed = true
            teardownMap()
        }
    }

    var distanceKm by remember { mutableStateOf(1) }
    var starting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var submitResult by remember { mutableStateOf<String?>(null) }
    var submitRrid by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var uploadStage by remember { mutableStateOf("") }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var session by remember { mutableStateOf<Session?>(null) }
    var stopConfirm by remember { mutableStateOf(false) }
    var gpsAlert by remember { mutableStateOf(false) }
    var readyPanelExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(engine.weakGps) {
        if (engine.weakGps) gpsAlert = true
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasLocPerm = (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
            (perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
    }
    LaunchedEffect(Unit) {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            hasLocPerm = true
        } else {
            permLauncher.launch(runPermissions())
        }
    }

    // —— 轨迹：单条 Polyline 原地更新，不每次 remove+add（避免 GL 对象抖动）——
    LaunchedEffect(aMap, engine.samples.size, engine.phase) {
        if (engine.phase == "ready") {
            polyline?.remove()
            polyline = null
            myMarker?.remove()
            myMarker = null
            return@LaunchedEffect
        }
        val am = aMap ?: return@LaunchedEffect
        val pts = engine.samples
            .filter { it.radius <= 50.0 && (it.lat != 0.0 || it.lng != 0.0) }
            .map { LatLng(it.lat, it.lng) }
        if (pts.isEmpty()) return@LaunchedEffect
        if (pts.size >= 2) {
            val line = polyline
            if (line == null) {
                polyline = am.addPolyline(
                    PolylineOptions().addAll(pts).width(8f).color(0xFF1B88EE.toInt())
                        .setUseTexture(true)
                )
            } else {
                line.points = pts
            }
        }
        val last = pts.last()
        val marker = myMarker
        if (marker == null) {
            myMarker = am.addMarker(
                MarkerOptions().position(last).title("当前位置")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
        } else {
            marker.position = last
        }
        if (follow) {
            am.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition(last, am.cameraPosition.zoom, 0f, 0f)
            ))
        }
    }

    // —— 打卡点标注：key 含 aMap，地图晚于点位就绪时也能补画 ——
    LaunchedEffect(aMap, engine.checkpoints, engine.passes.size) {
        val am = aMap ?: return@LaunchedEffect
        cpMarkers.forEach { it.remove() }
        cpCircles.forEach { it.remove() }
        nextRing?.remove()
        nextRing = null
        val next = engine.nextCheckpoint()
        val markers = mutableListOf<Marker>()
        val circles = mutableListOf<Circle>()
        for (cp in engine.checkpoints) {
            val pos = LatLng(cp.lat, cp.lon)
            val passed = engine.passes.any { it.cp.key == cp.key }
            val isNext = next?.key == cp.key
            val hue = when {
                passed -> BitmapDescriptorFactory.HUE_GREEN
                cp.isFixed == 1 -> BitmapDescriptorFactory.HUE_RED
                else -> BitmapDescriptorFactory.HUE_ORANGE
            }
            val label = when {
                passed -> "已打卡"
                isNext -> "下一个点位"
                cp.isFixed == 1 -> "必经点"
                else -> "普通点"
            }
            markers.add(
                am.addMarker(
                    MarkerOptions().position(pos)
                        .icon(BitmapDescriptorFactory.defaultMarker(hue))
                        .title("${cp.key + 1}. ${cp.name}")
                        .snippet(label)
                        .zIndex(if (isNext) 3f else 1f)
                )
            )
            val fill = when {
                passed -> 0x224CAF50
                isNext -> 0x331B88EE
                else -> 0x22FF9800
            }
            val stroke = when {
                passed -> 0xFF4CAF50.toInt()
                isNext -> 0xFF1B88EE.toInt()
                cp.isFixed == 1 -> 0xFFFA3A3A.toInt()
                else -> 0xFFFF9800.toInt()
            }
            circles.add(
                am.addCircle(
                    CircleOptions().center(pos).radius(50.0)
                        .fillColor(fill).strokeColor(stroke)
                        .strokeWidth(if (isNext) 4f else 2f)
                )
            )
        }
        cpMarkers = markers
        cpCircles = circles
        next?.let { cp ->
            nextRing = am.addCircle(
                CircleOptions().center(LatLng(cp.lat, cp.lon)).radius(70.0)
                    .fillColor(0x111B88EE).strokeColor(0xFF1B88EE.toInt()).strokeWidth(3f)
            )
        }
        if (engine.checkpoints.isNotEmpty() && engine.samples.isEmpty() && follow) {
            val lat = engine.checkpoints.map { it.lat }.average()
            val lon = engine.checkpoints.map { it.lon }.average()
            am.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition(LatLng(lat, lon), 16f, 0f, 0f)
            ))
        }
    }

    val haptic = rememberConfirmHaptic()
    val palette = LocalLiquidChrome.current.palette
    val hudMaterial = LiquidMaterials.hud(palette)

    Box(Modifier.fillMaxSize()) {
        if (useAmap) {
            AndroidView(
                factory = { _ ->
                    val mv = mapView
                    mv.onCreate(null)
                    mapCreated = true
                    aMap = mv.map
                    // 相机跟随开关：手势拖动地图时退出跟随，点按钮再回来
                    aMap?.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                        override fun onCameraChange(position: CameraPosition?) {}
                        override fun onCameraChangeFinish(position: CameraPosition?) {}
                    })
                    aMap?.uiSettings?.isRotateGesturesEnabled = false
                    aMap?.uiSettings?.isTiltGesturesEnabled = false
                    // 校区中心初始视野（ntsf）
                    aMap?.moveCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition(LatLng(31.913367, 120.972567), 15f, 0f, 0f)
                    ))
                    // 进场时 Activity 可能已 RESUMED（不会再回调 ON_RESUME），补一次
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        mv.onResume()
                    }
                    mv
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            TrackCanvas(engine, Modifier.fillMaxSize())
        }

        // 打卡点清单：地图不可用/渲染失败时也能看到点位与进度（跑动与暂停时显示）
        if ((engine.phase == "running" || engine.phase == "paused") && engine.checkpoints.isNotEmpty()) {
            CheckpointStrip(
                engine = engine,
                useAmap = useAmap,
                follow = follow,
                onToggleFollow = { follow = !follow },
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(
                    start = 12.dp,
                    bottom = 12.dp + LocalLiquidContentPadding.current.calculateBottomPadding(),
                ),
            )
        }

        when (engine.phase) {
            "ready" -> {
                // 浓缩入口：圆形纯色播放按钮浮在地图上，点击展开完整面板
                // （面板在按钮上方；深浅色全部走 palette 令牌）
                androidx.compose.animation.AnimatedVisibility(
                    visible = readyPanelExpanded,
                    enter = if (LocalLiquidChrome.current.reduceMotion) {
                        androidx.compose.animation.fadeIn(fadeInSpec())
                    } else {
                        androidx.compose.animation.slideInVertically(tween(220)) { it } +
                            androidx.compose.animation.fadeIn(fadeInSpec())
                    },
                    exit = if (LocalLiquidChrome.current.reduceMotion) {
                        androidx.compose.animation.fadeOut(fadeOutSpec())
                    } else {
                        androidx.compose.animation.slideOutVertically(tween(180)) { it } +
                            androidx.compose.animation.fadeOut(fadeOutSpec())
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 92.dp + LocalLiquidContentPadding.current.calculateBottomPadding(),
                        ),
                ) {
                HudPanel(
                    radius = LiquidRadius.Card,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                Column(Modifier.padding(18.dp)) {
                    Text("学分跑", style = LiquidType.Title, color = palette.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "选择跑距",
                        style = LiquidType.Caption,
                        color = palette.textTertiary,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 3, 4).forEach { km ->
                            LiquidChip(
                                text = "$km km",
                                selected = distanceKm == km,
                                onClick = { distanceKm = km },
                                modifier = Modifier.weight(1f),
                                material = if (distanceKm == km) {
                                    LiquidMaterials.tonal(palette)
                                } else {
                                    hudMaterial.copy(cornerRadius = LiquidRadius.Pill)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (configLoading) {
                        Text("加载跑步规则…", style = LiquidType.Caption, color = palette.textTertiary)
                    } else {
                        rule?.let { r ->
                            // 时段外拿到的窗口规则没有阈值字段，空值行直接隐藏（与主页一致）
                            RuleLine("有效时段", r.windowLabel())
                            if (r.minDistanceM > 0) {
                                RuleLine("单次最低", "${HomeData.kmText(r.minDistanceM)} km")
                            }
                            if (r.speedTop > 0 || r.speedBottom > 0) {
                                RuleLine(
                                    "配速 / 步频",
                                    "%.1f–%.1f 分/km · %d–%d".format(
                                        r.speedTop, r.speedBottom, r.stepBottom, r.stepTop,
                                    ),
                                )
                            }
                            if (r.maxRunTimeSec > 0) {
                                RuleLine(
                                    "时长上限",
                                    "${r.maxRunTimeSec / 60} 分钟 · 暂停 ${r.pauseTimeSec / 60} 分钟",
                                )
                            }
                            if (!r.isOpen()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "未在有效时段",
                                    color = palette.error,
                                    style = LiquidType.Caption,
                                )
                            }
                        }
                    }
                    configError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = palette.error, style = LiquidType.Caption)
                    }
                    message?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = palette.error, style = LiquidType.Caption)
                    }
                    Spacer(Modifier.height(16.dp))
                    LiquidButton(
                        text = "领取点位并开始",
                        loading = starting,
                        enabled = hasLocPerm && locationEnabled && !starting &&
                            !configLoading && (rule?.isOpen() ?: true),
                        onClick = {
                            starting = true
                            message = null
                            scope.launch {
                                try {
                                    val s = withContext(Dispatchers.IO) {
                                        Proto.newSession(store.dir, store.uid ?: "", store.token ?: "", store.profileName ?: "")
                                    }
                                    session = s
                                    val cps = withContext(Dispatchers.IO) {
                                        fetchCheckpoints(s, distanceKm * 1000, lastKnownPoint(ctx))
                                    }
                                    if (cps.isEmpty()) {
                                        message = "未获取到点位"
                                    } else {
                                        engine.start(distanceKm, cps, rule?.toRunLimits() ?: RunLimits())
                                        follow = true
                                        runCatching {
                                            ContextCompat.startForegroundService(
                                                ctx,
                                                Intent(ctx, RunService::class.java),
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    message = "开跑失败：${e.message ?: e}"
                                } finally {
                                    starting = false
                                }
                            }
                        },
                        height = 50.dp,
                    )
                    if (!hasLocPerm) {
                        Spacer(Modifier.height(6.dp))
                        PermissionRow(
                            text = "定位权限",
                            action = "去设置",
                        ) {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${ctx.packageName}"),
                                )
                            )
                        }
                    }
                    if (!locationEnabled) {
                        Spacer(Modifier.height(4.dp))
                        PermissionRow(
                            text = "定位未开启",
                            action = "去开启",
                            danger = true,
                        ) {
                            ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                    }
                }
                }
                }
                ReadyFab(
                    expanded = readyPanelExpanded,
                    onToggle = { readyPanelExpanded = !readyPanelExpanded },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp + LocalLiquidContentPadding.current.calculateBottomPadding()),
                )
            }

            "running", "paused" -> HudPanel(
                radius = LiquidRadius.Card,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(12.dp)
                    .align(Alignment.TopCenter),
            ) {
                Column(Modifier.padding(16.dp)) {
                    val dur = engine.elapsedSec
                    val pace = if (engine.meters > 10) (dur / engine.meters) * 50.0 / 3.0 else 0.0
                    val freq = engine.totalSteps * 60 / dur.coerceAtLeast(1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "计时",
                                style = LiquidType.Caption,
                                color = palette.textTertiary,
                            )
                            Text(
                                "%02d:%02d:%02d".format(dur / 3600, (dur % 3600) / 60, dur % 60),
                                style = LiquidType.Display.copy(fontSize = 30.sp, lineHeight = 38.sp),
                                color = palette.textPrimary,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (engine.phase == "paused") "已暂停" else "采集中",
                                style = LiquidType.Caption,
                                color = if (engine.phase == "paused") palette.warning else palette.primary,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "打卡 ${engine.passes.size}/${engine.checkpoints.size}",
                                style = LiquidType.Caption,
                                color = palette.textTertiary,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RunMetric("距离", "%.2f".format(engine.meters / 1000), "km", Modifier.weight(1f))
                        MetricDivider()
                        RunMetric("配速", "%.2f".format(pace), "分/km", Modifier.weight(1f))
                        MetricDivider()
                        RunMetric("步频", "$freq", "步/分", Modifier.weight(1f))
                    }
                    if (engine.locationNote.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            engine.locationNote,
                            style = LiquidType.Caption,
                            color = if (engine.locationNote.startsWith("已定位")) {
                                palette.primary
                            } else {
                                palette.error
                            },
                        )
                    }
                    if (engine.weakGps) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "GPS 信号弱，请到开阔处",
                            style = LiquidType.Caption,
                            color = palette.error,
                        )
                    }
                    val next = engine.nextCheckpoint()
                    if (next != null) {
                        val cur = engine.currentPoint()
                        val d = cur?.let {
                            RunEngine.haversine(it.first, it.second, next.lat, next.lon)
                        }
                        val dText = when {
                            d == null -> ""
                            d >= 1000.0 -> " · %.1f km".format(d / 1000.0)
                            else -> " · ${d.toInt()} m"
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "下一点 ${next.key + 1}：${next.name}$dText",
                            style = LiquidType.Caption,
                            color = palette.textSecondary,
                        )
                    } else if (engine.checkpoints.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "全部点位已打卡",
                            style = LiquidType.Caption,
                            color = palette.success,
                        )
                    }
                    if (engine.phase == "paused") {
                        val limit = engine.limits.pauseTimeSec
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "暂停 ${engine.pausedSeconds}s" + if (limit > 0) " / ${limit}s" else "",
                            style = LiquidType.Caption,
                            color = palette.error,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (engine.phase == "running") {
                            LiquidButton(
                                text = "暂停",
                                onClick = { engine.pause() },
                                style = LiquidButtonStyle.Ghost,
                                surface = hudMaterial.copy(cornerRadius = LiquidRadius.Button),
                                height = 46.dp,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            LiquidButton(
                                text = "继续",
                                onClick = { engine.resume() },
                                style = LiquidButtonStyle.Ghost,
                                surface = hudMaterial.copy(cornerRadius = LiquidRadius.Button),
                                height = 46.dp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        LiquidButton(
                            text = "结束",
                            onClick = {
                                haptic()
                                stopConfirm = true
                            },
                            height = 46.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            "finished" -> HudPanel(
                radius = LiquidRadius.Card,
                modifier = Modifier
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp + LocalLiquidContentPadding.current.calculateBottomPadding(),
                    )
                    .align(Alignment.BottomCenter),
            ) {
                Column(Modifier.padding(18.dp)) {
                    val j = engine.judge()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "成绩判定",
                            style = LiquidType.Title,
                            color = palette.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        LiquidTag(
                            if (j.complete) "合格" else "不合格",
                            color = if (j.complete) palette.success else palette.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RunMetric("距离", "%.2f".format(engine.meters / 1000), "km", Modifier.weight(1f))
                        MetricDivider()
                        RunMetric("时长", HistoryData.durationText(engine.elapsedSec.toInt()), "", Modifier.weight(1f))
                        MetricDivider()
                        RunMetric("配速", "%.2f".format(j.paceMinPerKm), "分/km", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "步频 ${j.freq} 步/分 · 打卡 ${engine.passes.size}/${engine.checkpoints.size}",
                        style = LiquidType.Caption,
                        color = palette.textTertiary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(palette.primarySoft, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        engine.judgeItems().forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (item.pass) "✓" else "✕",
                                    color = if (item.pass) palette.success else palette.error,
                                    style = LiquidType.Body,
                                    modifier = Modifier.width(20.dp),
                                )
                                Text(
                                    "${item.label}：${item.detail}",
                                    style = LiquidType.Caption,
                                    color = palette.textSecondary,
                                )
                            }
                        }
                    }
                    engine.stopReason?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = LiquidType.Caption, color = palette.error)
                    }
                    if (uploading) {
                        Spacer(Modifier.height(12.dp))
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            UploadProgress(uploadProgress)
                            Text(
                                "$uploadStage ${(uploadProgress * 100).toInt()}%",
                                style = LiquidType.Caption,
                                color = palette.primary,
                            )
                        }
                    }
                    submitResult?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = LiquidType.Caption,
                            color = when {
                                it.startsWith("提交成功") -> palette.success
                                it.startsWith("提交失败") -> palette.error
                                else -> palette.textSecondary
                            },
                        )
                    }
                    submitRrid?.let {
                        Spacer(Modifier.height(2.dp))
                        Text("rrid $it", style = LiquidType.Caption, color = palette.textTertiary)
                    }
                    Spacer(Modifier.height(16.dp))
                    if (submitResult?.startsWith("提交成功") == true) {
                        LiquidButton(
                            text = "完成",
                            height = 48.dp,
                            onClick = {
                                engine.reset()
                                runCatching { ctx.stopService(Intent(ctx, RunService::class.java)) }
                                submitResult = null
                                submitRrid = null
                                onDone()
                            },
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LiquidButton(
                                text = "提交成绩",
                                loading = uploading,
                                enabled = !uploading &&
                                    (submitResult == null || submitResult!!.startsWith("提交失败")),
                                height = 48.dp,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    uploading = true
                                    uploadStage = "准备提交"
                                    uploadProgress = 0f
                                    submitResult = null
                                    submitRrid = null
                                    scope.launch {
                                        try {
                                            val s = session ?: withContext(Dispatchers.IO) {
                                                Proto.newSession(store.dir, store.uid ?: "", store.token ?: "", store.profileName ?: "")
                                            }
                                            val out = withContext(Dispatchers.IO) {
                                                submitRun(s, engine, distanceKm) { stage, p ->
                                                    scope.launch(Dispatchers.Main) {
                                                        uploadStage = stage
                                                        uploadProgress = p.coerceIn(0f, 1f)
                                                    }
                                                }
                                            }
                                            val rrid = runCatching { JSONObject(out).optLong("rrid", 0L) }.getOrDefault(0L)
                                            submitResult = "提交成功"
                                            submitRrid = if (rrid > 0L) "$rrid" else null
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (t: Throwable) {
                                            // 组装/提交抛出的 Error（OOM 等）同样归一为失败态，
                                            // 不允许逃出协程杀进程
                                            uploadStage = "提交失败"
                                            submitResult = "提交失败：${t.message ?: t}"
                                        } finally {
                                            uploading = false
                                        }
                                    }
                                },
                            )
                            LiquidButton(
                                text = "放弃",
                                style = LiquidButtonStyle.Ghost,
                                surface = hudMaterial.copy(cornerRadius = LiquidRadius.Button),
                                enabled = !uploading,
                                height = 48.dp,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    uploading = false
                                    uploadStage = ""
                                    uploadProgress = 0f
                                    submitResult = null
                                    submitRrid = null
                                    message = null
                                    engine.reset()
                                    runCatching { ctx.stopService(Intent(ctx, RunService::class.java)) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (stopConfirm) {
        LiquidDialog(
            visible = true,
            title = "结束跑步",
            message = null,
            confirmText = "结束",
            onConfirm = {
                stopConfirm = false
                engine.stop()
            },
            onDismiss = { stopConfirm = false },
            dismissText = "取消",
            danger = true,
        )
    }

    if (gpsAlert) {
        LiquidDialog(
            visible = true,
            title = "GPS 信号弱",
            message = "请到开阔处",
            confirmText = "知道了",
            onConfirm = { gpsAlert = false },
            onDismiss = { gpsAlert = false },
        )
    }
}

/**
 * ready 状态的浓缩入口：圆形纯色播放按钮（主色实底 + 白色图标），
 * 点击切换完整数据面板；按压微缩放走 graphicsLayer，深浅色自动适配。
 */
@Composable
private fun ReadyFab(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalLiquidChrome.current.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by pressScale(pressed)
    Box(
        modifier = modifier
            .size(64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(palette.primary)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.Close else Icons.Default.PlayArrow,
            contentDescription = if (expanded) "收起学分跑面板" else "展开学分跑面板",
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}

/** 地图上的浮层面板：不采样遮罩材质 + 圆角，直接压在地图/画布上。 */
@Composable
private fun HudPanel(
    radius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalLiquidChrome.current.palette
    Column(
        modifier = modifier.liquidTint(
            LiquidMaterials.hud(palette).copy(cornerRadius = radius),
            RoundedCornerShape(radius),
        ),
        content = content,
    )
}

@Composable
private fun RuleLine(label: String, value: String) {
    val palette = LocalLiquidChrome.current.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = LiquidType.Caption, color = palette.textTertiary)
        Spacer(Modifier.weight(1f))
        Text(value, style = LiquidType.Caption, color = palette.textSecondary)
    }
}

@Composable
private fun PermissionRow(
    text: String,
    action: String,
    danger: Boolean = false,
    onAction: () -> Unit,
) {
    val palette = LocalLiquidChrome.current.palette
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = LiquidType.Caption,
            color = if (danger) palette.error else palette.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            action,
            style = LiquidType.Label,
            color = palette.primaryText,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onAction)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun RunMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val palette = LocalLiquidChrome.current.palette
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = LiquidType.Caption, color = palette.textTertiary)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = LiquidType.Title.copy(fontSize = 20.sp, fontFeatureSettings = "tnum"),
                color = palette.textPrimary,
            )
            if (unit.isNotBlank()) {
                Text(
                    unit,
                    style = LiquidType.Caption,
                    color = palette.textTertiary,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun MetricDivider() {
    Box(Modifier.width(1.dp).height(30.dp).background(LocalLiquidChrome.current.palette.divider))
}

@Composable
private fun UploadProgress(progress: Float) {
    val palette = LocalLiquidChrome.current.palette
    val p = progress.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(palette.primarySoft),
    ) {
        Box(
            Modifier
                .fillMaxWidth(p)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(palette.primary),
        )
    }
}

private fun runPermissions(): Array<String> {
    val perms = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return perms.toTypedArray()
}

// 打卡点清单：地图可用时是图例，不可用时是唯一的点位视图。
@Composable
private fun CheckpointStrip(
    engine: RunEngine,
    useAmap: Boolean,
    follow: Boolean,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalLiquidChrome.current.palette
    val next = engine.nextCheckpoint()
    val cur = engine.currentPoint()
    HudPanel(radius = LiquidRadius.Chip, modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (useAmap) "点位 ${engine.passes.size}/${engine.checkpoints.size}" else "轨迹示意 · 点位",
                    style = LiquidType.Label.copy(fontSize = 14.sp),
                    color = palette.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (useAmap) {
                    Text(
                        if (follow) "跟随中" else "已脱离",
                        style = LiquidType.Caption,
                        color = palette.primaryText,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onToggleFollow)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            engine.checkpoints.take(6).forEach { cp ->
                val passed = engine.passes.any { it.cp.key == cp.key }
                val isNext = next?.key == cp.key
                val dist = cur?.let { RunEngine.haversine(it.first, it.second, cp.lat, cp.lon) }
                val color = when {
                    passed -> XbudColors.CheckpointPassed
                    isNext -> XbudColors.CheckpointNext
                    cp.isFixed == 1 -> XbudColors.CheckpointFixed
                    else -> XbudColors.CheckpointNormal
                }
                Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.padding(end = 6.dp).height(8.dp).width(8.dp)) {
                        drawCircle(color, radius = size.minDimension / 2)
                    }
                    Text(
                        "${cp.key + 1}. ${cp.name}" +
                            if (dist != null) "  ${if (dist >= 1000) "%.1f km".format(dist / 1000) else "${dist.toInt()} m"}" else "",
                        style = LiquidType.Caption,
                        color = if (passed) palette.textTertiary else color,
                    )
                }
            }
            if (engine.checkpoints.size > 6) {
                Text(
                    "… 共 ${engine.checkpoints.size} 个",
                    style = LiquidType.Caption,
                    color = palette.textTertiary,
                )
            }
        }
    }
}

// 无高德地图（x86 模拟器 / 未配 Android key / GL 初始化失败）时的本地轨迹画布：
// 按经纬度等比投影，画打卡点、已完成轨迹与当前位置；不依赖任何地图 key。
// drawWithCache：网格与轨迹 Path 只在尺寸/采样变化时重建，不随每帧重建。
@Composable
private fun TrackCanvas(engine: RunEngine, modifier: Modifier = Modifier) {
    val dark = LocalLiquidChrome.current.isDark
    val canvasBg = if (dark) Color(0xFF10141A) else XbudColors.Background
    val gridColor = if (dark) Color(0x14FFFFFF) else Color(0x14000000)
    val labelColor = if (dark) android.graphics.Color.WHITE else android.graphics.Color.DKGRAY
    val samples = engine.samples
    val checkpoints = engine.checkpoints
    val passes = engine.passes
    val next = engine.nextCheckpoint()
    Canvas(
        modifier.background(canvasBg).drawWithCache {
            val grid = Path().apply {
                for (i in 1..9) {
                    val x = size.width * i / 10f
                    moveTo(x, 0f); lineTo(x, size.height)
                    val y = size.height * i / 10f
                    moveTo(0f, y); lineTo(size.width, y)
                }
            }
            onDrawBehind {
                drawPath(grid, gridColor, style = Stroke(width = 1f))
            }
        }
    ) {
        val valid = samples.filter { it.radius <= 50.0 && (it.lat != 0.0 || it.lng != 0.0) }
        val all = checkpoints.map { it.lat to it.lon } + valid.map { it.lat to it.lng }
        if (all.isEmpty()) return@Canvas

        val minLat = all.minOf { it.first }
        val maxLat = all.maxOf { it.first }
        val minLng = all.minOf { it.second }
        val maxLng = all.maxOf { it.second }
        val midLat = (minLat + maxLat) / 2
        val midLng = (minLng + maxLng) / 2
        val mPerLat = 111_320.0
        val mPerLng = 111_320.0 * cos(Math.toRadians(midLat))
        val spanX = ((maxLng - minLng) * mPerLng).coerceAtLeast(100.0)
        val spanY = ((maxLat - minLat) * mPerLat).coerceAtLeast(100.0)
        // 顶部状态卡与底部按钮区不参与取景，避免点位被卡片遮住
        val topInset = 260.dp.toPx()
        val bottomInset = 140.dp.toPx()
        val availW = (size.width - 48.dp.toPx()).coerceAtLeast(1f)
        val availH = (size.height - topInset - bottomInset).coerceAtLeast(1f)
        val centerX = size.width / 2
        val centerY = topInset + availH / 2
        val scale = min(availW * 0.9 / spanX, availH * 0.9 / spanY)

        fun toOffset(lat: Double, lng: Double): Offset {
            val x = centerX + ((lng - midLng) * mPerLng * scale).toFloat()
            val y = centerY - ((lat - midLat) * mPerLat * scale).toFloat()
            return Offset(x, y)
        }

        if (valid.size >= 2) {
            val path = Path()
            valid.forEachIndexed { i, s ->
                val p = toOffset(s.lat, s.lng)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(
                path,
                color = XbudColors.Track,
                style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        val labelPaint = Paint().apply {
            color = labelColor
            textSize = 12.dp.toPx()
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        checkpoints.forEach { cp ->
            val p = toOffset(cp.lat, cp.lon)
            val passed = passes.any { it.cp.key == cp.key }
            val isNext = next?.key == cp.key
            val color = when {
                passed -> XbudColors.CheckpointPassed
                isNext -> XbudColors.CheckpointNext
                cp.isFixed == 1 -> XbudColors.CheckpointFixed
                else -> XbudColors.CheckpointNormal
            }
            if (isNext) drawCircle(XbudColors.PrimarySoft, radius = 26f, center = p)
            drawCircle(color, radius = 14f, center = p)
            drawCircle(Color.White, radius = 5f, center = p)
            drawContext.canvas.nativeCanvas.drawText("${cp.key + 1}", p.x + 16f, p.y - 12f, labelPaint)
        }

        valid.lastOrNull()?.let { last ->
            val p = toOffset(last.lat, last.lng)
            drawCircle(XbudColors.PrimarySoft, radius = 22f, center = p)
            drawCircle(XbudColors.Track, radius = 10f, center = p)
            drawCircle(Color.White, radius = 4f, center = p)
        }
    }
}

// 分配点位（开跑会话）+ 校园点位库 pid 归并 + 原版选点排序。
private data class CampusPoint(
    val pid: Int,
    val id: Int,
    val lat: Double,
    val lon: Double,
    val relatedPids: List<Int>,
)

private suspend fun fetchCheckpoints(
    s: Session,
    distanceM: Int,
    start: Pair<Double, Double>?,
): List<Checkpoint> =
    withContext(Dispatchers.IO) {
        val raw = s.fetchFixedPoints(distanceM.toLong())
        val data = JSONObject(raw).optJSONObject("data") ?: return@withContext emptyList()
        val models = data.optJSONArray("pointsResModels") ?: return@withContext emptyList()

        // 校园点位库：get_fixed_point 不带 pid，按坐标最近归并出 pid/id/relatedPids
        val campusRaw = runCatching { s.campusPoints() }.getOrNull()
        val campusArr = campusRaw?.let { runCatching { JSONObject(it).optJSONArray("data") }.getOrNull() }
            ?: JSONArray()
        val campus = mutableListOf<CampusPoint>()
        for (i in 0 until campusArr.length()) {
            val o = campusArr.optJSONObject(i) ?: continue
            campus.add(
                CampusPoint(
                    pid = o.optInt("pid"),
                    id = o.optInt("id", 0),
                    lat = o.optDouble("lat"),
                    lon = o.optDouble("lon"),
                    relatedPids = o.optString("relatedPids", "")
                        .split(',')
                        .mapNotNull { it.trim().toIntOrNull() },
                )
            )
        }

        val out = mutableListOf<Checkpoint>()
        for (i in 0 until models.length()) {
            val o = models.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat")
            val lon = o.optDouble("lon")
            var matched: CampusPoint? = null
            var best = 30.0
            for (cp in campus) {
                val d = RunEngine.haversine(lat, lon, cp.lat, cp.lon)
                if (d < best) {
                    best = d
                    matched = cp
                }
            }
            out.add(
                Checkpoint(
                    key = i,
                    pid = matched?.pid ?: o.optInt("pid", 0),
                    id = matched?.id ?: o.optInt("id", 0),
                    name = o.optString("pointName"),
                    lat = lat,
                    lon = lon,
                    isFixed = o.optInt("isFixed", 0),
                    relatedPids = matched?.relatedPids ?: emptyList(),
                )
            )
        }
        // 原版 dn0.s2：3/4 个点 + 必经点随机位 + [0.3d, 0.8d] 折线长度打分
        PointSelector.order(out, distanceM, start).withIndex().map { (i, p) -> p.copy(key = i) }
    }

private fun lastKnownPoint(ctx: android.content.Context): Pair<Double, Double>? {
    val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.time }
        ?: return null
    return Geo.wgs84ToGcj02(loc.latitude, loc.longitude)
}

// 组 runJSON 并提交（RealRunInput 契约，见 pkg/proto/flow_realrun.go）。
private suspend fun submitRun(
    s: Session,
    engine: RunEngine,
    distanceKm: Int,
    onProgress: (String, Float) -> Unit = { _, _ -> },
): String =
    withContext(Dispatchers.IO) {
        val j = engine.judge()
        val samples = JSONArray()
        for (smp in engine.samples) {
            samples.put(JSONObject().apply {
                put("t", smp.t)
                put("lat", smp.lat)
                put("lng", smp.lng)
                put("speed", smp.speed)
                put("radius", smp.radius)
                put("sat", smp.sat)
                put("steps", smp.steps)
            })
        }
        // 原版 fivePointJson 是本次分配的**全部**点位（ex0.java:542-545），
        // 每个点带 isPass/position；服务端据此算「必经点」「≥2 普通点」。
        val five = JSONArray()
        for (cp in engine.checkpoints) {
            val passed = engine.passes.any { it.cp.key == cp.key }
            five.put(JSONObject().apply {
                put("flag", engine.startEpoch)
                put("id", cp.id)
                put("isFixed", cp.isFixed)
                put("isPass", passed)
                put("lat", cp.lat)
                put("lon", cp.lon)
                put("pid", cp.pid)
                put("pointName", cp.name)
                put("position", cp.position)
            })
        }
        val run = JSONObject().apply {
            put("start", engine.startEpoch / 1000)
            put("policy", 1)
            put("selDistance", distanceKm)
            put("selectedUnid", 1001104)
            put("selRunTime", 0)
            put("complete", j.complete)
            put("unCompleteReason", j.reason)
            put("freq", j.freq)
            put("fivePoints", five)
            put("samples", samples)
        }
        s.submitRun(run.toString(), onProgress)
    }

private fun RunRule.toRunLimits() = RunLimits(
    minDistanceM = if (minDistanceM > 0.0) minDistanceM else 1000.0,
    minRunTimeSec = minRunTimeSec,
    speedTop = if (speedTop > 0.0) speedTop else 3.5,
    speedBottom = if (speedBottom > 0.0) speedBottom else 10.0,
    stepTop = if (stepTop > 0) stepTop else 300,
    stepBottom = if (stepBottom > 0) stepBottom else 50,
    maxRunTimeSec = maxRunTimeSec,
    pauseTimeSec = pauseTimeSec,
)
