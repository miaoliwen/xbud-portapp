package com.yg.xbudport.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.xbudport.data.AccountStore
import com.yg.xbudport.proto.ErrNorm
import com.yg.xbudport.proto.Proto
import com.yg.xbudport.proto.ProtoException
import com.yg.xbudport.proto.TrackReplay
import com.yg.xbudport.proto.TrackReplayData
import com.yg.xbudport.run.RunEngine
import com.yg.xbudport.ui.designsystem.LiquidBottomSheet
import com.yg.xbudport.ui.designsystem.LiquidButton
import com.yg.xbudport.ui.designsystem.LiquidButtonStyle
import com.yg.xbudport.ui.designsystem.LiquidIconButton
import com.yg.xbudport.ui.designsystem.LiquidMaterials
import com.yg.xbudport.ui.designsystem.LiquidRadius
import com.yg.xbudport.ui.designsystem.LiquidSpace
import com.yg.xbudport.ui.designsystem.LiquidSpinner
import com.yg.xbudport.ui.designsystem.LiquidStateView
import com.yg.xbudport.ui.designsystem.LiquidSurface
import com.yg.xbudport.ui.designsystem.LiquidTag
import com.yg.xbudport.ui.designsystem.LiquidTopBar
import com.yg.xbudport.ui.designsystem.LiquidType
import com.yg.xbudport.ui.designsystem.LocalLiquidChrome
import com.yg.xbudport.ui.theme.XbudColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.min

// 历史跑步记录：get_record_list 分页 + get_record_detail 详情。
// 距离/时长/配速均来自服务端记录，不在端上重算。
// UI 是 designsystem 玻璃面：卡片列表 + 同组合树弹层（Dialog 窗口采不到
// 本页背景，详情/回放一律走 LiquidBottomSheet）。
//
// 防崩溃契约（对齐官方 RunRecordPImpl/ex0）：
// - 解析失败与空数据分开走：错误态 vs 空态（ex0.java:272-297）。
// - 首页失败 = 全屏错误 + 重试；翻页失败 = footer 错误 + 重试，列表保留
//   （ex0.Cnew.onError 按 page==1 分流，ex0.java:252-256）。
// - 详情/回放重入先取消在途请求，旧结果不覆盖新结果（官方 Q1 重入哨兵，ex0.java:455-463）。
// - 所有异常经 ErrNorm 归一为「错误码 + 文案」，栈信息不透出（官方 HttpUtil.error）。

private const val PAGE_SIZE = 20

internal data class HistoryUi(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val loadMoreError: String? = null,
    val tokenExpired: Boolean = false,
    val records: List<RunRecordItem> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = true,
)

private data class PageResult(
    val records: List<RunRecordItem> = emptyList(),
    val error: String? = null,
    val tokenExpired: Boolean = false,
)

private suspend fun loadHistoryPage(store: AccountStore, page: Int): PageResult =
    withContext(Dispatchers.IO) {
        try {
            val s = Proto.newSession(store.dir, store.uid ?: "", store.token ?: "", store.profileName ?: "")
            val records = HistoryData.parseList(s.records(page.toLong(), PAGE_SIZE.toLong()))
                ?: return@withContext PageResult(error = "服务端数据格式异常")
            PageResult(records = records)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Error 级（OOM/LinkageError 等）也归一为错误态：加载路径不允许
            // 把任何 Throwable 逃成协程外的进程崩溃
            val err = ErrNorm.of(t)
            PageResult(error = err.message, tokenExpired = err.tokenExpired)
        }
    }

@Composable
fun HistoryScreen(
    store: AccountStore,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit = {},
) {
    // 成绩缓存命中 = 免二次加载：重进页面直接渲染上次列表，网络只在首次 /
    // 显式刷新 / 缓存被失效（跑步提交、换会话）时走
    var ui by remember { mutableStateOf(ScoreCache.history ?: HistoryUi()) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<RunRecordDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var replayRecord by remember { mutableStateOf<RunRecordItem?>(null) }
    var replayData by remember { mutableStateOf<TrackReplayData?>(null) }
    var replayLoading by remember { mutableStateOf(false) }
    var replayError by remember { mutableStateOf<String?>(null) }
    var detailJob by remember { mutableStateOf<Job?>(null) }
    var replayJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadTick) {
        if (reloadTick == 0) {
            val cached = ScoreCache.history
            if (cached != null && !cached.loading && cached.error == null && !cached.tokenExpired) {
                ui = cached
                return@LaunchedEffect
            }
        }
        ui = HistoryUi()
        val res = loadHistoryPage(store, 1)
        ui = HistoryUi(
            loading = false,
            error = res.error,
            tokenExpired = res.tokenExpired,
            records = res.records,
            page = 1,
            hasMore = res.error == null && res.records.size >= PAGE_SIZE,
        )
        if (res.error == null && !res.tokenExpired) ScoreCache.history = ui
    }

    fun openDetail(record: RunRecordItem) {
        detail = null
        detailError = null
        detailLoading = true
        // 重入先取消在途详情请求：旧记录的结果不覆盖新记录（官方 Q1 哨兵，ex0.java:455-463）。
        detailJob?.cancel()
        detailJob = scope.launch {
            // 整段包在 runCatching 里：网络异常/token 失效都走错误态，
            // 不能让 withContext 的重抛出逃出 launch（那会直接杀进程）。
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val s = Proto.newSession(store.dir, store.uid ?: "", store.token ?: "", store.profileName ?: "")
                    HistoryData.parseDetail(s.recordDetail(record.rrid, record.uuid))
                        ?: throw ProtoException("详情结构异常")
                }
            }
            // 被新请求取消时不再落状态：CancellationException 不能被 runCatching 吞成错误态。
            coroutineContext.ensureActive()
            detailLoading = false
            result.onSuccess { detail = it }
                .onFailure { e ->
                    detailError = ErrNorm.of(e).message
                }
        }
    }

    fun openReplay(record: RunRecordItem) {
        replayRecord = record
        replayData = null
        replayError = null
        replayLoading = true
        replayJob?.cancel()
        replayJob = scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val token = store.token ?: ""
                    TrackReplay.fetch(token, record.rrid)
                }
            }
            // 被新请求取消时不再落状态。
            coroutineContext.ensureActive()
            replayLoading = false
            res.onSuccess { replayData = it }
                .onFailure { replayError = ErrNorm.of(it).message }
        }
    }

    fun loadMore() {
        if (ui.loading || ui.loadingMore || !ui.hasMore) return
        val nextPage = ui.page + 1
        ui = ui.copy(loadingMore = true, loadMoreError = null)
        scope.launch {
            val res = loadHistoryPage(store, nextPage)
            if (res.error != null) {
                // 翻页失败只降级 footer：列表保留、可重试（官方 ex0.java:252-256 的 page>1 分支）。
                ui = ui.copy(loadingMore = false, loadMoreError = res.error, tokenExpired = res.tokenExpired)
                return@launch
            }
            val existing = ui.records.map { it.rrid }.toSet()
            ui = ui.copy(
                loadingMore = false,
                records = ui.records + res.records.filter { it.rrid !in existing },
                page = nextPage,
                hasMore = res.records.size >= PAGE_SIZE,
                loadMoreError = null,
            )
            ScoreCache.history = ui
        }
    }

    fun closeDetail() {
        detail = null
        detailError = null
    }

    fun closeReplay() {
        replayRecord = null
        replayData = null
        replayError = null
    }

    Column(Modifier.fillMaxSize()) {
        LiquidTopBar(
            title = "跑步记录",
            navigationIcon = {
                LiquidIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                )
            },
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
            ui.records.isEmpty() && ui.error != null -> LiquidStateView(
                title = if (ui.tokenExpired) "会话已失效" else "加载失败",
                message = ui.error,
                actionText = if (ui.tokenExpired) "重新登录" else "重试",
                onAction = { if (ui.tokenExpired) onSessionExpired() else reloadTick++ },
                error = true,
            )
            ui.records.isEmpty() -> LiquidStateView(title = "暂无记录")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = LiquidSpace.Page, vertical = LiquidSpace.Md),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ui.records, key = { it.rrid }) { record ->
                    RecordCard(record) { openDetail(record) }
                }
                item {
                    when {
                        !ui.hasMore -> Text(
                            "已加载全部记录",
                            style = LiquidType.Caption,
                            color = LocalLiquidChrome.current.palette.textTertiary,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            textAlign = TextAlign.Center,
                        )
                        ui.loadingMore -> Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LiquidSpinner(size = 20.dp, strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "加载中",
                                style = LiquidType.Caption,
                                color = LocalLiquidChrome.current.palette.textTertiary,
                            )
                        }
                        ui.loadMoreError != null -> Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { if (ui.tokenExpired) onSessionExpired() else loadMore() }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${ui.loadMoreError}，点击重试",
                                style = LiquidType.Label,
                                color = LocalLiquidChrome.current.palette.error,
                            )
                        }
                        else -> Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { loadMore() }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "加载更多",
                                style = LiquidType.Label,
                                color = LocalLiquidChrome.current.palette.primaryText,
                            )
                        }
                    }
                }
            }
        }
    }

    val detailSheet = detail != null || detailLoading || detailError != null
    if (detailSheet) {
        LiquidBottomSheet(
            visible = true,
            onDismiss = { if (!detailLoading) closeDetail() },
            title = "记录详情",
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    detailLoading -> Row(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LiquidSpinner(size = 20.dp, strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "加载中",
                            style = LiquidType.Body,
                            color = LocalLiquidChrome.current.palette.textSecondary,
                        )
                    }
                    detailError != null -> {
                        val err = detailError
                        Text(
                            err ?: "加载失败",
                            color = LocalLiquidChrome.current.palette.error,
                            style = LiquidType.Body,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    detail != null -> {
                        val d = detail
                        if (d != null) DetailContent(d) { openReplay(d.record) }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    val replay = replayRecord
    if (replay != null) {
        LiquidBottomSheet(
            visible = true,
            onDismiss = { if (!replayLoading) closeReplay() },
            title = "轨迹回放",
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    HistoryData.dateText(replay.startMs),
                    style = LiquidType.Caption,
                    color = LocalLiquidChrome.current.palette.textTertiary,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    replayLoading -> Row(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LiquidSpinner(size = 20.dp, strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "下载轨迹",
                            style = LiquidType.Body,
                            color = LocalLiquidChrome.current.palette.textSecondary,
                        )
                    }
                    replayError != null -> {
                        val err = replayError
                        Text(
                            err ?: "轨迹加载失败",
                            color = LocalLiquidChrome.current.palette.error,
                            style = LiquidType.Body,
                        )
                    }
                    replayData != null -> {
                        // 快照一次：组合期状态被并发改写时回落空数据，不走 !!
                        val data = replayData ?: TrackReplayData(emptyList(), emptyList())
                        // 逐点 haversine 是 O(n) 几何累加，几千个轨迹点算一次就够，
                        // 不随重组重跑
                        val meters = remember(data) {
                            data.points.zipWithNext().sumOf { (a, b) ->
                                RunEngine.haversine(a.lat, a.lng, b.lat, b.lng)
                            }
                        }
                        Text(
                            "${data.points.size} 个轨迹点 · ${HistoryData.kmText(meters)} km",
                            style = LiquidType.Body,
                            color = LocalLiquidChrome.current.palette.textSecondary,
                        )
                        if (data.points.isEmpty()) {
                            Text(
                                "服务端未返回轨迹点",
                                style = LiquidType.Caption,
                                color = LocalLiquidChrome.current.palette.textTertiary,
                            )
                        } else {
                            ReplayCanvas(
                                data,
                                Modifier
                                    .fillMaxWidth()
                                    .height(360.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                        }
                        if (data.checkpoints.isNotEmpty()) {
                            val palette = LocalLiquidChrome.current.palette
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.padding(top = 10.dp),
                            ) {
                                Text("●", color = XbudColors.CheckpointNormal, style = LiquidType.Caption)
                                Text("打卡", style = LiquidType.Caption, color = palette.textTertiary)
                                Text("●", color = XbudColors.CheckpointFixed, style = LiquidType.Caption)
                                Text("必经", style = LiquidType.Caption, color = palette.textTertiary)
                                Text("●", color = XbudColors.Success, style = LiquidType.Caption)
                                Text("起点", style = LiquidType.Caption, color = palette.textTertiary)
                                Text("●", color = palette.textPrimary, style = LiquidType.Caption)
                                Text("终点", style = LiquidType.Caption, color = palette.textTertiary)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun ReplayCanvas(data: TrackReplayData, modifier: Modifier = Modifier) {
    val dark = LocalLiquidChrome.current.isDark
    val canvasBg = if (dark) Color(0xFF10141A) else Color(0xFFF6F7FB)
    Canvas(modifier.background(canvasBg)) {
        val all = data.points.map { it.lat to it.lng } +
            data.checkpoints.map { it.lat to it.lng }
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
        val scale = min(size.width * 0.82 / spanX, size.height * 0.82 / spanY)
        fun toOffset(lat: Double, lng: Double) = Offset(
            size.width / 2 + ((lng - midLng) * mPerLng * scale).toFloat(),
            size.height / 2 - ((lat - midLat) * mPerLat * scale).toFloat(),
        )

        val gridColor = if (dark) Color(0x14FFFFFF) else Color(0x14000000)
        for (i in 1..9) {
            val x = size.width * i / 10f
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            val y = size.height * i / 10f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        if (data.points.size >= 2) {
            val path = Path()
            data.points.forEachIndexed { i, p ->
                val o = toOffset(p.lat, p.lng)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(
                path,
                color = XbudColors.Track,
                style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        data.checkpoints.forEach { cp ->
            drawCircle(
                if (cp.isFixed == 1) XbudColors.CheckpointFixed else XbudColors.CheckpointNormal,
                radius = 14f,
                center = toOffset(cp.lat, cp.lng),
            )
        }
        data.points.firstOrNull()?.let {
            drawCircle(XbudColors.Success, radius = 10f, center = toOffset(it.lat, it.lng))
        }
        data.points.lastOrNull()?.let {
            drawCircle(
                if (dark) Color.White else XbudColors.TextPrimary,
                radius = 10f,
                center = toOffset(it.lat, it.lng),
            )
        }
    }
}

@Composable
private fun RecordCard(record: RunRecordItem, onClick: () -> Unit) {
    val palette = LocalLiquidChrome.current.palette
    val statusColor = when {
        record.complete && record.status == 0 -> palette.success
        !record.complete -> palette.error
        else -> palette.textTertiary
    }
    LiquidSurface(
        material = LiquidMaterials.card(palette),
        shape = RoundedCornerShape(LiquidRadius.Card),
        modifier = Modifier.fillMaxWidth(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                HistoryData.dateText(record.startMs),
                style = LiquidType.Headline,
                color = palette.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            LiquidTag(record.statusLabel, color = statusColor)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                HistoryData.kmText(record.totalDisM),
                style = LiquidType.Display.copy(fontSize = 24.sp, lineHeight = 30.sp),
                color = palette.textPrimary,
            )
            Text(
                "km",
                style = LiquidType.Caption,
                color = palette.textTertiary,
                modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
            )
            Spacer(Modifier.size(14.dp))
            Text(
                "用时 ${HistoryData.durationText(record.totalTimeSec)} · 配速 ${HistoryData.paceText(record.paceMinPerKm)} 分/km",
                style = LiquidType.Caption,
                color = palette.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(bottom = 3.dp),
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.textTertiary,
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp).size(20.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "步频 ${record.avgStepFreq} 步/分 · 有效距离 ${HistoryData.kmText(record.validDisM)} km",
            style = LiquidType.Caption,
            color = palette.textTertiary,
        )
    }
    }
}

@Composable
private fun DetailContent(detail: RunRecordDetail, onReplay: () -> Unit) {
    val palette = LocalLiquidChrome.current.palette
    val r = detail.record
    Column(Modifier.fillMaxWidth()) {
        DetailLine("状态", r.statusLabel, danger = !r.complete)
        DetailDivider()
        DetailLine("总距离", "${HistoryData.kmText(r.totalDisM)} km")
        DetailDivider()
        DetailLine("有效距离", "${HistoryData.kmText(r.validDisM)} km")
        DetailDivider()
        DetailLine("时长", HistoryData.durationText(r.totalTimeSec))
        DetailDivider()
        DetailLine("配速", "${HistoryData.paceText(r.paceMinPerKm)} 分/km")
        DetailDivider()
        DetailLine("步频", "${r.avgStepFreq} 步/分")
        if (detail.selDistance > 0.0) {
            DetailDivider()
            DetailLine("领取跑距", "${detail.selDistance} km")
        }
        if (detail.isCheat == 1) {
            DetailDivider()
            DetailLine("风控标记", "异常", danger = true)
        }
    }

    val failed = detail.reasons.filter { !it.complete }
    if (failed.isEmpty() && detail.reasons.isNotEmpty()) {
        Text(
            "判定项：全部通过",
            style = LiquidType.Label,
            color = palette.success,
            modifier = Modifier.padding(top = 12.dp),
        )
    } else if (failed.isNotEmpty()) {
        Text(
            "未通过项",
            style = LiquidType.Headline,
            color = palette.textPrimary,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        )
        failed.forEach { reason ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                Text("✕", color = palette.error, style = LiquidType.Caption)
                Text(reason.reason, style = LiquidType.Caption, color = palette.textSecondary)
            }
        }
    }

    if (r.rrid > 0L) {
        Spacer(Modifier.height(18.dp))
        LiquidButton(text = "轨迹回放", onClick = onReplay, height = 46.dp)
    }
}

@Composable
private fun DetailLine(label: String, value: String, danger: Boolean = false) {
    val palette = LocalLiquidChrome.current.palette
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = LiquidType.Body,
            color = palette.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = LiquidType.Body,
            color = if (danger) palette.error else palette.textPrimary,
        )
    }
}

@Composable
private fun DetailDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalLiquidChrome.current.palette.divider.copy(alpha = 0.7f))
    )
}
