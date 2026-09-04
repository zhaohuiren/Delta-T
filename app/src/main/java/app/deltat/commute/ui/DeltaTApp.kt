package app.deltat.commute.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.deltat.commute.BuildConfig
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.data.SessionListRow
import app.deltat.commute.data.SessionEntity
import app.deltat.commute.data.TrackPointEntity
import app.deltat.commute.data.CheckpointRecalculationJobEntity
import app.deltat.commute.data.RecalculationPreview
import app.deltat.commute.data.comparisonPeriodBounds
import app.deltat.commute.domain.CheckpointSelection
import app.deltat.commute.domain.EngineStatus
import app.deltat.commute.domain.CalendarAggregator
import app.deltat.commute.domain.DeltaConfidence
import app.deltat.commute.domain.DeltaReferenceType
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.ResultColor
import app.deltat.commute.location.RuntimeBus
import app.deltat.commute.map.MapCameraPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.time.ZoneId

@Composable
fun DeltaTApp(
    viewModel: AppViewModel,
    permissionGranted: Boolean,
    withPermissions: (() -> Unit) -> Unit,
) {
    val nav = rememberNavController()
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val leaderboard by viewModel.leaderboard.collectAsStateWithLifecycle()
    val runtime by RuntimeBus.state.collectAsStateWithLifecycle()
    val amapPrivacyAgreed by viewModel.amapPrivacyAgreed.collectAsStateWithLifecycle()
    val amapPrivacyPrompted by viewModel.amapPrivacyPrompted.collectAsStateWithLifecycle()
    val currentDestination = nav.currentBackStackEntryAsState().value?.destination?.route
    var showMapPrivacyDialog by remember { mutableStateOf(false) }
    LaunchedEffect(currentDestination, viewModel.mapAvailable, amapPrivacyAgreed, amapPrivacyPrompted) {
        val isMapPage = currentDestination == "timing" || currentDestination?.startsWith("ready/") == true ||
            currentDestination?.startsWith("baseline/") == true || currentDestination?.startsWith("detail/") == true
        if (isMapPage && viewModel.mapAvailable && !amapPrivacyAgreed && !amapPrivacyPrompted) showMapPrivacyDialog = true
    }
    if (showMapPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showMapPrivacyDialog = false; viewModel.declineAmapPrivacy() },
            title = { Text("启用高德地图？") },
            text = {
                Text("启用后会加载高德路网底图。原始通勤轨迹仍只保存在本机；高德地图 SDK 会处理提供地图所需的设备与网络信息。不同意时仍可使用黑底简洁轨迹。")
            },
            confirmButton = {
                TextButton(onClick = { showMapPrivacyDialog = false; viewModel.agreeToAmapPrivacy() }) { Text("同意并启用") }
            },
            dismissButton = {
                TextButton(onClick = { showMapPrivacyDialog = false; viewModel.declineAmapPrivacy() }) { Text("继续简洁模式") }
            },
        )
    }
    Scaffold(
            bottomBar = { BottomNavigation(nav) },
            containerColor = Background,
        ) { padding ->
            CompositionLocalProvider(
                LocalMapFeatureConfig provides MapFeatureConfig(viewModel.mapAvailable, amapPrivacyAgreed),
            ) {
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(
                    routes, sessions, runtime, permissionGranted,
                    onRoutes = { nav.navigate("routes") },
                    onAutoPrepare = { withPermissions { nav.navigate("countdown") } },
                    onResume = {
                        if (runtime.mode == "BASELINE_RECORDING" && runtime.routeId != null) nav.navigate("baseline/${runtime.routeId}")
                        else nav.navigate("timing")
                    },
                    onPermission = { withPermissions {} },
                    onRoute = { route ->
                        nav.navigate(
                            if (route.activeCheckpointVersionId != null && route.referenceSessionId != null) "edit/${route.referenceSessionId}"
                            else "baseline/${route.id}",
                        )
                    },
                )
            }
            composable("routes") { RoutesScreen(nav, routes, sessions, runtime, viewModel) { nav.navigate("new-route") } }
            composable("new-route") {
                NewRouteScreen(nav) { name -> viewModel.createRoute(name) { id -> nav.navigate("baseline/$id") { popUpTo("new-route") { inclusive = true } } } }
            }
            composable("baseline/{routeId}", arguments = listOf(navArgument("routeId") { type = NavType.StringType })) { entry ->
                val routeId = entry.arguments?.getString("routeId") ?: return@composable
                BaselineScreen(
                    nav, routes.firstOrNull { it.id == routeId }, runtime,
                    start = { withPermissions { viewModel.startBaseline(routeId) } },
                    stop = viewModel::stopBaseline,
                )
            }
            composable("edit/{sessionId}", arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { entry ->
                val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
                CheckpointEditor(nav, viewModel, sessionId)
            }
            composable("countdown") {
                CountdownScreen(
                    onStart = viewModel::startAutoTimed,
                    onFinished = { nav.navigate("timing") { popUpTo("countdown") { inclusive = true } } },
                    onCancel = { viewModel.stopTimed(); nav.popBackStack() },
                )
            }
            composable("timing") {
                val route = routes.firstOrNull { it.id == runtime.routeId }
                TimingScreen(nav, route, routes, runtime, start = viewModel::startAutoTimed, stop = viewModel::stopTimed, switchRoute = viewModel::switchTimedRoute)
            }
            composable("ready/{routeId}", arguments = listOf(navArgument("routeId") { type = NavType.StringType })) { entry ->
                val routeId = entry.arguments?.getString("routeId") ?: return@composable
                val route = routes.firstOrNull { it.id == routeId }
                TimingScreen(nav, route, routes, runtime,
                    start = { withPermissions { viewModel.startTimed(routeId) } }, stop = viewModel::stopTimed, switchRoute = viewModel::switchTimedRoute)
            }
            composable("history") { HistoryScreen(nav, sessions, routes, viewModel) }
            composable(
                "detail/{sessionId}?source={source}",
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("source") { type = NavType.StringType; defaultValue = "history" },
                ),
            ) { entry ->
                val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
                SessionDetail(nav, viewModel, sessionId, routes, DetailOrigin.fromRoute(entry.arguments?.getString("source")))
            }
            composable("records") { TelemetryRecordsScreen(routes, leaderboard) { nav.navigate("detail/$it?source=records") } }
            composable("settings") { SettingsScreen(permissionGranted, withPermissions, viewModel, nav, runtime) }
            }
            }
    }
}

@Composable
private fun BottomNavigation(nav: NavController) {
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    if (route !in listOf("home", "history", "records", "settings")) return
    NavigationBar(containerColor = Color(0xFF0C1114)) {
        listOf(
            Triple("home", Icons.Default.Home, "首页"),
            Triple("history", Icons.Default.History, "历史"),
            Triple("records", Icons.Default.EmojiEvents, "纪录"),
            Triple("settings", Icons.Default.Settings, "设置"),
        ).forEach { (destination, icon, label) ->
            NavigationBarItem(
                selected = route == destination,
                onClick = {
                    nav.navigate(destination) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo("home") { saveState = true }
                    }
                },
                icon = { Icon(icon, null) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun PageHeader(title: String, nav: NavController? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (nav != null) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
internal fun HomeScreen(
    routes: List<RouteProfileEntity>, sessions: List<SessionListRow>, runtime: app.deltat.commute.location.RuntimeSnapshot, permissionGranted: Boolean,
    onRoutes: () -> Unit, onAutoPrepare: () -> Unit, onResume: () -> Unit, onPermission: () -> Unit,
    onRoute: (RouteProfileEntity) -> Unit = { onRoutes() },
) {
    val readyRoutes = routes.filter { it.activeCheckpointVersionId != null }
    val bounds = remember(sessions) { comparisonPeriodBounds(System.currentTimeMillis()) }
    val comparable = sessions.filter { row ->
        val rowRoute = routes.firstOrNull { it.id == row.routeProfileId }
        row.validity == "VALID" && row.qualityGrade in setOf("A", "B") &&
            row.checkpointVersionId == rowRoute?.activeCheckpointVersionId && row.totalDurationNanos != null
    }
    val thisWeek = comparable.filter { it.startedWallTimeMillis in bounds.weekStartMillis until bounds.endExclusiveMillis }
    val refreshedThisWeek = thisWeek.count { (it.weeklyDeltaNanos ?: 0L) < 0L }
    val active = runtime.mode != null && runtime.status != "IDLE" && runtime.engineStatus != EngineStatus.COMPLETED
    val displayRoutes = routes.sortedBy { if (it.directionType == "TO_WORK") 0 else if (it.directionType == "FROM_WORK") 1 else 2 }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Delta T", Modifier.weight(1f), fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.size(8.dp).background(if (permissionGranted) Green else Yellow, RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (permissionGranted) runtime.gnssHealth.displaySummary() else "定位已关闭",
                    color = if (permissionGranted && runtime.gnssHealth.locationEnabled) Green else Yellow,
                    fontSize = 13.sp,
                )
            }
        }
        if (!permissionGranted) item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF30230E))) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GpsOff, null, tint = Yellow)
                    Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                        Text("计时前需要精确定位和通知权限", fontWeight = FontWeight.SemiBold)
                        Text("不会申请后台自动定位", color = Muted, fontSize = 13.sp)
                    }
                    TextButton(onClick = onPermission) { Text("检查") }
                }
            }
        }
        item {
            val prepareAction = when { active -> onResume; readyRoutes.isEmpty() -> onRoutes; else -> onAutoPrepare }
            Card(
                onClick = prepareAction,
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("home-prepare"),
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("下一程", color = Muted, fontSize = 13.sp)
                            Text(when { active -> "返回计时"; readyRoutes.isEmpty() -> "建立路线"; else -> "准备出发" }, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text(if (active) runtime.routeName else "靠近起点后自动识别上班或下班路线", color = Muted, fontSize = 13.sp)
                        }
                        FilledIconButton(
                            onClick = prepareAction,
                            modifier = Modifier.size(58.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = RacingRed),
                        ) { Icon(if (active) Icons.Default.Timer else Icons.Default.PlayArrow, null, Modifier.size(30.dp)) }
                    }
                    HorizontalDivider(color = Color(0xFF292D32))
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        Text(when { active -> "返回计时"; readyRoutes.isEmpty() -> "先建立路线"; else -> "一键准备" }, color = RacingRed, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("通勤路线", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("点击路线可编辑计时点", color = Muted, fontSize = 13.sp)
            }
        }
        if (displayRoutes.isEmpty()) item { HomeEmptyRouteTile(Modifier.fillMaxWidth(), onRoutes) }
        else item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(20.dp)) {
                Column {
                    displayRoutes.take(2).forEachIndexed { index, route ->
                        val routeRows = comparable.filter { it.routeProfileId == route.id }
                        val weeklyBest = routeRows.filter { it.startedWallTimeMillis in bounds.weekStartMillis until bounds.endExclusiveMillis }
                            .minByOrNull { it.totalDurationNanos ?: Long.MAX_VALUE }
                        val latest = routeRows.maxByOrNull { it.startedWallTimeMillis }
                        HomeRouteRow(route, weeklyBest?.totalDurationNanos, latest?.totalDurationNanos) { onRoute(route) }
                        if (index < minOf(1, displayRoutes.lastIndex)) HorizontalDivider(color = Color(0xFF292D32))
                    }
                }
            }
        }
        item {
            Text("本周 ${thisWeek.size} 次通勤 · $refreshedThisWeek 次刷新纪录", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HomeRouteRow(route: RouteProfileEntity, weeklyBest: Long?, latest: Long?, onClick: () -> Unit) {
    val ready = route.activeCheckpointVersionId != null
    Column(
        Modifier.testTag("home-route-${route.id}").clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Route, null, tint = if (ready) TelemetryWhite else Muted, modifier = Modifier.size(22.dp))
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(route.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (ready) "已就绪" else "需要设置计时点", color = if (ready) Green else Yellow, fontSize = 12.sp)
            }
            Icon(Icons.Default.ChevronRight, "编辑${route.name}", tint = Muted)
        }
        Row(Modifier.fillMaxWidth().padding(start = 36.dp)) {
            Column(Modifier.weight(1f)) {
                Text("本周最快", color = Muted, fontSize = 12.sp)
                Text(weeklyBest?.let(::formatDuration) ?: "--", color = if (weeklyBest == null) Muted else Green, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("最近", color = Muted, fontSize = 12.sp)
                Text(latest?.let(::formatDuration) ?: "--", fontFamily = FontFamily.Monospace, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun HomeEmptyRouteTile(modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AddRoad, null, tint = Yellow)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text("建立第一条路线", fontWeight = FontWeight.Bold)
                Text("录制轨迹后设置 A、S1、S2、B", color = Muted, fontSize = 13.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable
private fun RouteCard(
    route: RouteProfileEntity,
    reference: Long?,
    best: Long?,
    latest: Long?,
    referenceTrack: List<Pair<Double, Double>>,
    onClick: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(route.name, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(if (route.directionType == "TO_WORK") "家 → 公司" else if (route.directionType == "FROM_WORK") "公司 → 家" else "自定义单程", color = Muted)
                }
                Text(reference?.let(::formatDuration) ?: "未建立", fontFamily = FontFamily.Monospace)
            }
            AbstractRoute(referenceTrack, Modifier.fillMaxWidth().height(88.dp), checkpointFractions = if (referenceTrack.size >= 2) listOf(0f, .33f, .66f, 1f) else emptyList())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("路线轨迹", reference); Stat("个人最佳", best); Stat("最近", latest)
            }
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(if (route.activeCheckpointVersionId == null) "录制路线轨迹" else "进入准备") }
        }
    }
}

@Composable private fun Stat(label: String, nanos: Long?) { Column { Text(label, color = Muted, fontSize = 12.sp); Text(nanos?.let(::formatDuration) ?: "--", fontFamily = FontFamily.Monospace) } }

@Composable
private fun RoutesScreen(
    nav: NavController,
    routes: List<RouteProfileEntity>,
    sessions: List<SessionListRow>,
    runtime: app.deltat.commute.location.RuntimeSnapshot,
    viewModel: AppViewModel,
    onNew: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<RouteProfileEntity?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        PageHeader("路线库", nav) { IconButton(onClick = onNew) { Icon(Icons.Default.Add, "新建") } }
        LazyColumn(
            modifier = Modifier.testTag("routes-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (routes.isEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AddRoad, null, tint = Muted)
                        Spacer(Modifier.height(10.dp))
                        Text("还没有路线", fontWeight = FontWeight.Bold)
                        Text("新建一个方向后开始录制真实轨迹", color = Muted, fontSize = 13.sp)
                    }
                }
            }
            items(routes) { route ->
                val recalculation by viewModel.repository.latestRecalculationJob(route.id).collectAsStateWithLifecycle(initialValue = null)
                val isActivelyRecording = runtime.routeId == route.id && runtime.sessionId != null && when (runtime.mode) {
                    "BASELINE_RECORDING" -> runtime.status != "路线轨迹已录制"
                    "TIMED" -> runtime.engineStatus != EngineStatus.COMPLETED
                    else -> false
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    shape = androidx.compose.foundation.shape.CutCornerShape(topEnd = 14.dp),
                    onClick = {
                        val destination = when {
                            route.activeCheckpointVersionId == null -> "baseline/${route.id}"
                            route.referenceSessionId != null -> "edit/${route.referenceSessionId}"
                            else -> "baseline/${route.id}"
                        }
                        nav.navigate(destination)
                    },
                ) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Route, null, tint = if (route.activeCheckpointVersionId == null) Muted else Green)
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(route.name, fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    isActivelyRecording -> "正在记录，不可删除"
                                    recalculation?.status == "PENDING" || recalculation?.status == "RUNNING" -> "历史成绩重算 ${recalculation?.completedSessions}/${recalculation?.totalSessions} · 旧计时点仍启用"
                                    recalculation?.status == "FAILED" -> "历史成绩重算失败 · 点击进入重试"
                                    recalculation?.status == "COMPLETED_WITH_FAILURES" -> "重算完成 · ${recalculation?.failedSessions} 条无法匹配新计时点"
                                    route.activeCheckpointVersionId == null -> "尚未建立计时点"
                                    else -> "A · S1 · S2 · B 已就绪 · 点击编辑"
                                },
                                color = if (isActivelyRecording || recalculation?.status in setOf("PENDING", "RUNNING", "FAILED", "COMPLETED_WITH_FAILURES")) Yellow else Muted,
                            )
                        }
                        IconButton(
                            onClick = { pendingDelete = route },
                            enabled = !isActivelyRecording && recalculation?.status !in setOf("PENDING", "RUNNING"),
                        ) { Icon(Icons.Default.DeleteOutline, "删除${route.name}") }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
            item { OutlinedButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("＋ 新建方向") } }
        }
    }

    pendingDelete?.let { route ->
        val historyCount = sessions.count { it.routeProfileId == route.id }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.DeleteOutline, null) },
            title = { Text("删除“${route.name}”？") },
            text = {
                Text(
                    "将同时删除 $historyCount 条历史记录、路线源轨迹、计时点版本和所有分段数据。\n\n此操作无法恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRoute(
                            route.id,
                            onDone = { pendingDelete = null },
                            onError = { message -> pendingDelete = null; deleteError = message },
                        )
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    deleteError?.let { message ->
        AlertDialog(
            onDismissRequest = { deleteError = null },
            title = { Text("无法删除") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { deleteError = null }) { Text("知道了") } },
        )
    }
}

@Composable
internal fun NewRouteScreen(nav: NavController, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageHeader("新建路线", nav)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            OutlinedTextField(name, { name = it.take(24) }, label = { Text("路线名称") }, placeholder = { Text("例如：周末回家") }, modifier = Modifier.fillMaxWidth())
            Text("Delta T 只验证 A → S1 → S2 → B 的经过顺序，计时点之间可以选择任何道路。", color = Muted)
            Button(onClick = { onCreate(name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("下一步：录制路线") }
        }
    }
}

@Composable
internal fun BaselineScreen(nav: NavController, route: RouteProfileEntity?, runtime: app.deltat.commute.location.RuntimeSnapshot, start: () -> Unit, stop: () -> Unit) {
    val active = runtime.mode == "BASELINE_RECORDING" && runtime.routeId == route?.id
    Column(Modifier.fillMaxSize()) {
        PageHeader("录制路线", nav)
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(route?.name ?: "路线", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            StatusPill(if (active) runtime.gnssHealth.displaySummary() else "准备开始", if (active && runtime.lastFix?.accuracyMeters ?: 999.0 <= 25) Green else Yellow)
            TelemetryRoute(
                points = runtime.track,
                currentFix = runtime.lastFix,
                cameraPolicy = MapCameraPolicy.STAGED_RECORDING,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Text(if (active) "正在记录完整轨迹 · ${formatDuration(runtime.elapsedNanos)}" else "出发前点击开始；驾驶过程中无需操作，到达并停车后再结束。", color = Muted)
            if (!active && runtime.status == "路线轨迹已录制" && runtime.sessionId != null) {
                Button(onClick = { nav.navigate("edit/${runtime.sessionId}") }, modifier = Modifier.fillMaxWidth()) { Text("标记 A / S1 / S2 / B") }
            } else if (active) {
                Button(onClick = stop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9B2C2C)), modifier = Modifier.fillMaxWidth()) { Text("我已停车，结束录制") }
            } else {
                Button(onClick = start, modifier = Modifier.fillMaxWidth()) { Text("开始记录路线") }
            }
            Text("请勿在驾驶中操作手机", color = Yellow, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CheckpointEditor(nav: NavController, viewModel: AppViewModel, sessionId: String) {
    val track by viewModel.repository.track(sessionId).collectAsStateWithLifecycle(initialValue = emptyList())
    val row by viewModel.repository.sessionRow(sessionId).collectAsStateWithLifecycle(initialValue = null)
    val checkpoints by produceState(initialValue = emptyList(), key1 = row?.routeProfileId) {
        value = row?.routeProfileId?.let { viewModel.repository.checkpointEntitiesForRoute(it) }.orEmpty()
    }
    var error by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var pendingSelections by remember { mutableStateOf<List<CheckpointSelection>?>(null) }
    var preview by remember { mutableStateOf<RecalculationPreview?>(null) }
    var requestedVersionId by remember { mutableStateOf<String?>(null) }
    val job by produceState<CheckpointRecalculationJobEntity?>(initialValue = null, key1 = row?.routeProfileId) {
        row?.routeProfileId?.let { routeId -> viewModel.repository.latestRecalculationJob(routeId).collect { value = it } }
    }
    val activeJob = job?.takeIf { requestedVersionId == null || it.checkpointVersionId == requestedVersionId }
    LaunchedEffect(activeJob?.status) {
        when (activeJob?.status) {
            "COMPLETED", "COMPLETED_WITH_FAILURES" -> if (requestedVersionId != null) nav.navigate("home") { popUpTo("home") { inclusive = true } }
            "FAILED" -> error = activeJob.errorMessage ?: "历史成绩重算失败，旧计时点仍保持启用"
        }
    }
    if (preview != null && pendingSelections != null) {
        val result = requireNotNull(preview)
        AlertDialog(
            onDismissRequest = { preview = null; pendingSelections = null },
            title = { Text("重算全部历史成绩？") },
            text = {
                Text("预计可重算 ${result.successfulSessions} 条，无法重算 ${result.failedSessions} 条。原始轨迹和旧成绩会完整保留。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val selections = requireNotNull(pendingSelections)
                    preview = null
                    pendingSelections = null
                    viewModel.saveCheckpointSelections(
                        sessionId,
                        selections,
                        { request -> requestedVersionId = request.checkpointVersionId },
                        { error = it },
                    )
                }) { Text("创建新版本并重算") }
            },
            dismissButton = { TextButton(onClick = { preview = null; pendingSelections = null }) { Text("取消") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("编辑计时点", nav)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("双指缩放、拖动画布；点击轨迹或拖动红色标记精确选点。", color = Muted, fontSize = 13.sp)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (previewing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在预演全部历史轨迹…", color = Muted, fontSize = 13.sp)
            }
            activeJob?.takeIf { it.status == "PENDING" || it.status == "RUNNING" }?.let { current ->
                LinearProgressIndicator(
                    progress = { if (current.totalSessions == 0) 0f else current.completedSessions.toFloat() / current.totalSessions },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("正在重算 ${current.completedSessions}/${current.totalSessions} · 成功 ${current.successfulSessions} · 无法重算 ${current.failedSessions}", color = Muted, fontSize = 13.sp)
            }
            activeJob?.takeIf { it.status == "FAILED" }?.let { failed ->
                OutlinedButton(onClick = { viewModel.retryRecalculation(failed.id) { error = it } }, modifier = Modifier.fillMaxWidth()) {
                    Text("重算失败，保留旧计时点 · 点击重试")
                }
            }
            ZoomableCheckpointEditor(
                track = track,
                initialPositions = initialCheckpointPositions(track, checkpoints),
                onSave = { selections ->
                    previewing = true
                    error = null
                    viewModel.previewCheckpointSelections(
                        sessionId,
                        selections,
                        { result -> previewing = false; pendingSelections = selections; preview = result },
                        { previewing = false; error = it },
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun CountdownScreen(
    onStart: () -> Unit,
    onFinished: () -> Unit,
    onCancel: () -> Unit,
    animationsEnabled: Boolean = android.animation.ValueAnimator.areAnimatorsEnabled(),
    stepMillis: Long = 720,
    readyMillis: Long = 650,
) {
    var phase by remember { mutableStateOf("3") }
    LaunchedEffect(Unit) {
        onStart()
        if (animationsEnabled) {
            listOf("3", "2", "1").forEach { value ->
                phase = value
                delay(stepMillis)
            }
        } else {
            phase = "READY"
            delay(350)
        }
        phase = "READY"
        delay(readyMillis)
        onFinished()
    }
    Surface(Modifier.fillMaxSize(), color = Background, contentColor = TelemetryWhite) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { Box(Modifier.size(18.dp).background(RacingRed, RoundedCornerShape(50))) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(phase, fontSize = if (phase == "READY") 66.sp else 112.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                TelemetryLabel("INITIALIZING GPS", RacingRed)
            }
            TextButton(onClick = onCancel) { Text("取消", color = Muted) }
        }
    }
}

@Composable
internal fun TimingScreen(
    nav: NavController, route: RouteProfileEntity?, routes: List<RouteProfileEntity>, runtime: app.deltat.commute.location.RuntimeSnapshot,
    start: () -> Unit, stop: () -> Unit, switchRoute: (String) -> Unit,
    elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    animateClock: Boolean = true,
) {
    val useScrollableAccessibilityLayout = LocalDensity.current.fontScale > 1.15f
    val accessibilityScrollState = rememberScrollState()
    val active = runtime.mode in setOf("AUTO_ARMING", "TIMED")
    val completed = runtime.engineStatus == EngineStatus.COMPLETED
    val detecting = runtime.mode == "AUTO_ARMING" && runtime.routeId == null
    val waitingAtA = runtime.engineStatus == EngineStatus.ARMED && runtime.routeId != null
    val beforeTiming = detecting || waitingAtA
    val displayElapsedNanos by produceState(
        runtime.elapsedNanos,
        runtime.startedAtElapsedNanos,
        runtime.elapsedNanos,
        completed,
    ) {
        val startNanos = runtime.startedAtElapsedNanos
        if (startNanos == null || completed || !animateClock) {
            value = projectedElapsedNanos(startNanos, runtime.elapsedNanos, elapsedRealtimeNanos(), completed)
        } else {
            while (true) {
                withFrameNanos {
                    value = projectedElapsedNanos(startNanos, runtime.elapsedNanos, elapsedRealtimeNanos(), completed = false)
                }
            }
        }
    }
    var confirmStop by remember { mutableStateOf(false) }
    var showRoutePicker by remember { mutableStateOf(false) }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(if (beforeTiming) "取消准备？" else "结束本次计时？") },
            text = { Text(if (beforeTiming) "尚未经过起点 A，不会产生计时成绩。" else "轨迹会保留，但本次不会计入个人纪录。请确认车辆已经停稳。") },
            confirmButton = { TextButton(onClick = { confirmStop = false; stop() }) { Text(if (beforeTiming) "确认取消" else "确认结束", color = Color(0xFFFF7777)) } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(if (beforeTiming) "继续等待" else "继续计时") } },
        )
    }
    if (showRoutePicker) {
        AlertDialog(
            onDismissRequest = { showRoutePicker = false },
            title = { Text("更换识别路线") },
            text = {
                Column {
                    routes.filter { it.activeCheckpointVersionId != null }.forEach { candidate ->
                        TextButton(
                            onClick = { showRoutePicker = false; switchRoute(candidate.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(candidate.name, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRoutePicker = false }) { Text("取消") } },
        )
    }
    if (detecting) {
        DetectingRouteScreen(nav, runtime) { confirmStop = true }
        return
    }
    if (waitingAtA) {
        WaitingAtStartScreen(nav, runtime, { showRoutePicker = true }) { confirmStop = true }
        return
    }
    Surface(Modifier.fillMaxSize(), color = Background, contentColor = TelemetryWhite) {
      Column(Modifier.fillMaxSize()) {
        PageHeader(if (completed) "COMPLETE" else if (runtime.mode == "AUTO_ARMING") "AUTO ROUTE" else "${route?.name ?: "计时"} · LIVE", nav)
        Column(
            Modifier.fillMaxSize()
                .then(if (useScrollableAccessibilityLayout) Modifier.verticalScroll(accessibilityScrollState) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(runtime.gnssHealth.displaySummary(), gnssStatusColor(runtime.gnssHealth.displayLabel()))
            }
            F1TimingBoard(runtime, route?.name ?: runtime.routeName.ifBlank { "计时" }, completed, displayElapsedNanos)
            TelemetryPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    TelemetryLabel("MAX")
                    Spacer(Modifier.width(10.dp))
                    Text(runtime.maxSpeedMps?.let { "%.1f".format(it * 3.6) } ?: "--", fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(" km/h", color = Muted)
                    Spacer(Modifier.weight(1f))
                    Text("过滤峰值", color = Muted, fontSize = 11.sp)
                }
                if (completed && runtime.maxSpeedWallTimeMillis != null) {
                    val relative = runtime.maxSpeedElapsedNanos?.let { peak -> runtime.startedAtElapsedNanos?.let { startTime -> (peak - startTime).coerceAtLeast(0) } }
                    Text(
                        "${formatClock(runtime.maxSpeedWallTimeMillis)}${relative?.let { " · 经过 A 后 ${formatDuration(it)}" }.orEmpty()}",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
            }
            TelemetryRoute(
                points = runtime.track,
                referencePoints = runtime.monthlyReferenceTrack.ifEmpty { runtime.referenceTrack },
                routeSourcePoints = runtime.routeSourceTrack,
                checkpoints = runtime.routeCheckpoints,
                currentFix = runtime.lastFix,
                referencePassElapsedNanos = runtime.monthlyReferencePassElapsedNanos.ifEmpty { runtime.checkpointPassElapsedNanos },
                actualPassElapsedNanos = runtime.actualPassElapsedNanos,
                sectorColors = (0..2).map { runtime.sectors.getOrNull(it)?.color ?: ResultColor.NEUTRAL },
                cameraPolicy = MapCameraPolicy.FULL_ROUTE_LOCKED,
                modifier = if (useScrollableAccessibilityLayout) {
                    Modifier.fillMaxWidth().aspectRatio(1.24f)
                } else {
                    Modifier.fillMaxWidth().weight(1f).heightIn(min = 230.dp, max = 340.dp)
                },
            )
            if (runtime.engineStatus == EngineStatus.ARMED && runtime.routeId != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已识别：${runtime.routeName} · 距离 A ${runtime.routeDetectionDistanceMeters?.let { "%.0f m".format(it) } ?: "--"}", color = Muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showRoutePicker = true }) { Text("更换路线") }
                }
            }
            when {
                completed && runtime.sessionId != null -> Button(onClick = { nav.navigate("detail/${runtime.sessionId}?source=timing") }, modifier = Modifier.fillMaxWidth()) { Text("查看本次详情") }
                active -> OutlinedButton(onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF7777))) { Text("结束本次（成绩无效）") }
                else -> Button(onClick = start, modifier = Modifier.fillMaxWidth()) { Text("一键准备") }
            }
            Text("可锁屏继续 · 驾驶中无需点击", color = Muted)
        }
      }
    }
}

internal fun projectedElapsedNanos(startedAtElapsedNanos: Long?, snapshotElapsedNanos: Long, nowElapsedNanos: Long, completed: Boolean): Long =
    if (startedAtElapsedNanos == null || completed) snapshotElapsedNanos
    else maxOf(snapshotElapsedNanos, (nowElapsedNanos - startedAtElapsedNanos).coerceAtLeast(0L))

internal enum class DeltaDisplayBasis(val label: String) { MONTH("月"), WEEK("周"), NONE("") }

internal data class AdaptiveDeltaDisplay(
    val basis: DeltaDisplayBasis,
    val deltaNanos: Long?,
    val resultLabel: String,
)

internal fun adaptiveDeltaDisplay(
    color: ResultColor,
    monthlyDeltaNanos: Long?,
    weeklyDeltaNanos: Long?,
    hasMonthlyReference: Boolean = monthlyDeltaNanos != null,
    hasWeeklyReference: Boolean = weeklyDeltaNanos != null,
): AdaptiveDeltaDisplay {
    val preferred = when (color) {
        ResultColor.PURPLE -> DeltaDisplayBasis.MONTH to monthlyDeltaNanos
        ResultColor.GREEN, ResultColor.YELLOW -> DeltaDisplayBasis.WEEK to weeklyDeltaNanos
        ResultColor.NEUTRAL -> if (weeklyDeltaNanos != null) DeltaDisplayBasis.WEEK to weeklyDeltaNanos else DeltaDisplayBasis.MONTH to monthlyDeltaNanos
    }
    val selected = when {
        preferred.second != null -> preferred
        weeklyDeltaNanos != null -> DeltaDisplayBasis.WEEK to weeklyDeltaNanos
        monthlyDeltaNanos != null -> DeltaDisplayBasis.MONTH to monthlyDeltaNanos
        else -> DeltaDisplayBasis.NONE to null
    }
    val result = when (color) {
        ResultColor.PURPLE -> "本月最佳"
        ResultColor.GREEN -> "本周最佳"
        ResultColor.YELLOW -> "慢于本周"
        ResultColor.NEUTRAL -> when {
            !hasMonthlyReference -> "本月首条记录"
            !hasWeeklyReference -> "本周首条记录"
            selected.second == null -> "等待实时匹配"
            else -> "持平"
        }
    }
    return AdaptiveDeltaDisplay(selected.first, selected.second, result)
}

internal fun adaptiveDeltaStatus(display: AdaptiveDeltaDisplay, confidence: DeltaConfidence): String = when (confidence) {
    DeltaConfidence.HELD -> "暂时无法匹配 · 保留值"
    DeltaConfidence.ESTIMATED -> "${display.resultLabel} · 实时估算"
    DeltaConfidence.CONFIRMED -> "${display.resultLabel} · 已确认"
    DeltaConfidence.UNAVAILABLE -> display.resultLabel
}

@Composable
private fun F1TimingBoard(
    runtime: app.deltat.commute.location.RuntimeSnapshot,
    routeName: String,
    completed: Boolean,
    displayElapsedNanos: Long,
) {
    val sectorHeight = 136.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.3f)
    val shape = RoundedCornerShape(18.dp)
    val stateLabel = if (completed) "已完成" else if (runtime.currentSector in 1..3) "实时 S${runtime.currentSector}" else "等待中"
    val hasMonthlyReference = runtime.monthlyReferenceTrack.isNotEmpty() || runtime.monthlyDeltaNanos != null
    val hasWeeklyReference = runtime.weeklyReferenceTrack.isNotEmpty() || runtime.weeklyDeltaNanos != null
    Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xFF0C0F14)).border(1.dp, Color(0xFF3A3F45), shape)) {
        Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().width(50.dp).background(RacingRed), contentAlignment = Alignment.Center) {
                Text(if (completed) "OK" else "S${runtime.currentSector.coerceIn(1, 3)}", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            Text(routeName, Modifier.padding(start = 14.dp).weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Black)
            TelemetryLabel(stateLabel, RacingRed)
            Spacer(Modifier.width(14.dp))
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(RacingRed))
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatDuration(displayElapsedNanos), Modifier.testTag("live-timer"), fontSize = 47.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, maxLines = 1)
            PeriodDeltaRow(
                label = "月",
                delta = runtime.monthlyDeltaNanos,
                bestColor = Purple,
                status = when {
                    !hasMonthlyReference -> "本月首条记录"
                    runtime.monthlyDeltaNanos != null && runtime.monthlyDeltaNanos < 0 -> if (completed) "本月最快 · 已确认" else "有望本月最快 · 实时估算"
                    else -> if (completed) "月比较 · 已确认" else "月比较 · 实时估算"
                },
            )
            PeriodDeltaRow(
                label = "周",
                delta = runtime.weeklyDeltaNanos,
                bestColor = Green,
                status = when {
                    !hasWeeklyReference -> "本周首条记录"
                    runtime.weeklyDeltaNanos != null && runtime.weeklyDeltaNanos < 0 -> if (completed) "本周最快 · 已确认" else "有望本周最佳 · 实时估算"
                    else -> if (completed) "周比较 · 已确认" else "周比较 · 实时估算"
                },
            )
            if (runtime.deltaConfidence == DeltaConfidence.HELD) Text("暂时无法匹配 · 保留最近确认值", color = Muted, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().height(sectorHeight), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (0..2).forEach { index ->
                val sector = runtime.sectors.getOrNull(index)
                val current = runtime.currentSector - 1 == index && !completed
                F1SectorCell(
                    index,
                    sector,
                    current,
                    runtime.currentSectorMonthlyDeltaNanos,
                    runtime.currentSectorWeeklyDeltaNanos,
                    runtime.currentSectorColor,
                    hasMonthlyReference,
                    hasWeeklyReference,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PeriodDeltaRow(label: String, delta: Long?, bestColor: Color, status: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label Δ ${delta?.let(::formatDelta) ?: "--"}", color = periodDeltaColor(delta, bestColor), fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.weight(1f))
        Text(status, color = Muted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun F1SectorCell(
    index: Int,
    sector: app.deltat.commute.location.RuntimeSector?,
    current: Boolean,
    liveMonthlyDeltaNanos: Long?,
    liveWeeklyDeltaNanos: Long?,
    liveColor: ResultColor,
    hasMonthlyReference: Boolean,
    hasWeeklyReference: Boolean,
    modifier: Modifier,
) {
    val resultColor = sector?.color
    val accent = when {
        resultColor != null -> colorFor(resultColor)
        current -> RacingRed
        else -> Color(0xFF4C5158)
    }
    val fill = Color(0xFF111419)
    val duration = sector?.durationNanos?.let(::formatDuration) ?: if (current) "LIVE" else "--"
    val monthDelta = sector?.monthlyDeltaNanos ?: sector?.deltaNanos ?: if (current) liveMonthlyDeltaNanos else null
    val weekDelta = sector?.weeklyDeltaNanos ?: if (current) liveWeeklyDeltaNanos else null
    val status = if (current) "计时中" else if (sector != null) "已完成" else "未开始"
    val cellShape = RoundedCornerShape(bottomStart = if (index == 0) 16.dp else 4.dp, bottomEnd = if (index == 2) 16.dp else 4.dp)
    Column(
        modifier.testTag("sector-card-S${index + 1}").fillMaxHeight().clip(cellShape).background(fill)
            .then(if (current) Modifier.border(1.5.dp, RacingRed, cellShape) else Modifier),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text("S${index + 1}", color = if (current) RacingRed else accent, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(duration, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            Text("月 ${monthDelta?.let(::formatDelta) ?: "--"}", color = periodDeltaColor(monthDelta, Purple), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
            Text("周 ${weekDelta?.let(::formatDelta) ?: "--"}", color = periodDeltaColor(weekDelta, Green), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
            Text(status, color = if (sector != null || current) TelemetryWhite.copy(alpha = .78f) else Muted, fontSize = 12.sp, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(if (sector != null || current) accent else Color(0xFF4C5158)))
    }
}

@Composable
private fun DetectingRouteScreen(nav: NavController, runtime: app.deltat.commute.location.RuntimeSnapshot, onCancel: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Background, contentColor = TelemetryWhite) {
        Column(Modifier.fillMaxSize()) {
            PageHeader("AUTO ROUTE", nav)
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                StatusPill(runtime.gnssHealth.displaySummary(), gnssStatusColor(runtime.gnssHealth.displayLabel()))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = RacingRed)
                    TelemetryLabel("AUTO ROUTE", RacingRed)
                    Text("正在识别最近路线", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("首个精度合格定位点到达后自动选择最近的起点 A", color = Muted, textAlign = TextAlign.Center)
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF7777))) { Text("取消准备") }
            }
        }
    }
}

@Composable
private fun WaitingAtStartScreen(
    nav: NavController,
    runtime: app.deltat.commute.location.RuntimeSnapshot,
    onSwitchRoute: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = Background, contentColor = TelemetryWhite) {
        Column(Modifier.fillMaxSize()) {
            PageHeader("AUTO ROUTE", nav)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(runtime.gnssHealth.displaySummary(), gnssStatusColor(runtime.gnssHealth.displayLabel()))
                    Spacer(Modifier.weight(1f))
                    TelemetryLabel("WAITING A", RacingRed)
                }
                TelemetryPanel(Modifier.fillMaxWidth()) {
                    TelemetryLabel("已识别路线", RacingRed)
                    Text(runtime.routeName, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    TelemetryLabel("距离起点 A")
                    Text(runtime.routeDetectionDistanceMeters?.let { "%.0f m".format(it) } ?: "--", fontSize = 46.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("驶过 A 后自动开始正式计时", color = Muted)
                }
                TelemetryRoute(
                    points = runtime.track,
                    referencePoints = runtime.referenceTrack,
                    routeSourcePoints = runtime.routeSourceTrack,
                    checkpoints = runtime.routeCheckpoints,
                    currentFix = runtime.lastFix,
                    referencePassElapsedNanos = runtime.checkpointPassElapsedNanos,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onSwitchRoute, modifier = Modifier.weight(1f)) { Text("更换路线") }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF7777))) { Text("取消准备") }
                }
                Text("计时尚未开始 · 可锁屏等待", color = Muted)
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    nav: NavController,
    sessions: List<SessionListRow>,
    routes: List<RouteProfileEntity>,
    viewModel: AppViewModel,
) {
    val historyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var periodNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) periodNowMillis = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val nextDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            delay((nextDay - now + 250L).coerceAtLeast(1_000L))
            periodNowMillis = System.currentTimeMillis()
        }
    }
    var pendingDelete by remember { mutableStateOf<SessionListRow?>(null) }
    pendingDelete?.let { session ->
        val isRouteSource = routes.firstOrNull { it.id == session.routeProfileId }?.referenceSessionId == session.id
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (isRouteSource) "无法单独删除" else "删除这条历史记录？") },
            text = {
                Text(if (isRouteSource) "这条轨迹用于定义当前路线和计时点。需要移除时，请在设置的路线管理中删除整条路线。" else "轨迹、分段和诊断数据将一起删除，无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!isRouteSource) viewModel.deleteSession(session.id)
                    pendingDelete = null
                }) {
                    Text(if (isRouteSource) "知道了" else "确认删除", color = if (isRouteSource) TelemetryWhite else Color(0xFFFF7777))
                }
            },
            dismissButton = if (isRouteSource) null else ({ TextButton(onClick = { pendingDelete = null }) { Text("取消") } }),
        )
    }
    val bounds = remember(sessions, periodNowMillis) { comparisonPeriodBounds(periodNowMillis) }
    val eligible = sessions.filter { row ->
        val route = routes.firstOrNull { it.id == row.routeProfileId }
        isCurrentRecordEligible(row, route?.activeCheckpointVersionId)
    }
    val monthBestIds = eligible.filter { it.startedWallTimeMillis in bounds.monthStartMillis until bounds.endExclusiveMillis }
        .groupBy { it.routeProfileId }.mapValues { (_, rows) -> rows.minWithOrNull(compareBy<SessionListRow> { it.totalDurationNanos }.thenBy { it.startedWallTimeMillis }.thenBy { it.id })?.id }
    val weekBestIds = eligible.filter { it.startedWallTimeMillis in bounds.weekStartMillis until bounds.endExclusiveMillis }
        .groupBy { it.routeProfileId }.mapValues { (_, rows) -> rows.minWithOrNull(compareBy<SessionListRow> { it.totalDurationNanos }.thenBy { it.startedWallTimeMillis }.thenBy { it.id })?.id }
    Column(Modifier.fillMaxSize()) {
        PageHeader("历史记录")
        if (sessions.isEmpty()) EmptyState("还没有行程", "完成路线录制或日常计时后会显示在这里")
        else LazyColumn(state = historyListState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions, key = { it.id }) { session ->
                val isMonthBest = monthBestIds[session.routeProfileId] == session.id
                val isWeekBest = weekBestIds[session.routeProfileId] == session.id
                val activeVersionId = routes.firstOrNull { it.id == session.routeProfileId }?.activeCheckpointVersionId
                val isEligible = isCurrentRecordEligible(session, activeVersionId)
                HistoryRecordCard(
                    session = session,
                    activeCheckpointVersionId = activeVersionId,
                    isMonthBest = isMonthBest,
                    isWeekBest = isWeekBest,
                    isEligible = isEligible,
                    onOpen = { nav.navigate("detail/${session.id}?source=history") },
                    onMenu = { pendingDelete = session },
                )
            }
        }
    }
}

internal fun isCurrentRecordEligible(session: SessionListRow, activeCheckpointVersionId: String?): Boolean =
    session.validity == "VALID" && session.qualityGrade in setOf("A", "B") &&
        session.totalDurationNanos != null && session.checkpointVersionId == activeCheckpointVersionId

@Composable
internal fun HistoryRecordCard(
    session: SessionListRow,
    activeCheckpointVersionId: String?,
    isMonthBest: Boolean,
    isWeekBest: Boolean,
    isEligible: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().testTag("history-card-${session.id}").clickable(onClick = onOpen)
                    .padding(start = 16.dp, top = 16.dp, end = 56.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(session.routeName, fontWeight = FontWeight.Bold)
                        Text(formatDate(session.startedWallTimeMillis), color = Muted, fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            session.totalDurationNanos?.let(::formatDuration) ?: statusText(session.status),
                            color = if (session.totalDurationNanos != null) TelemetryWhite else Muted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = if (session.totalDurationNanos == null) 13.sp else 19.sp,
                            maxLines = 1,
                        )
                        if (isEligible) Text("有效 · 质量 ${session.qualityGrade}", color = Muted, fontSize = 12.sp)
                    }
                }
                if (session.checkpointVersionId != activeCheckpointVersionId) Text("旧计时点", color = Yellow, fontSize = 12.sp)
                session.evaluationFailureReason?.let { reason -> Text(reason, color = Yellow, fontSize = 12.sp, modifier = Modifier.fillMaxWidth()) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isMonthBest) SmallPeriodTag("本月最快", Purple)
                    if (isWeekBest) SmallPeriodTag("本周最快", Green)
                    if (!isEligible) SmallPeriodTag(
                        when {
                            session.status == "INTERRUPTED" -> "已中断 · 不计纪录"
                            session.checkpointVersionId != activeCheckpointVersionId -> "原始${validityText(session.validity)} · 质量 ${session.qualityGrade} · 不计当前纪录"
                            else -> "${validityText(session.validity)} · 质量 ${session.qualityGrade}"
                        },
                        Muted,
                    )
                }
            }
            IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp).testTag("history-menu-${session.id}")) {
                Icon(Icons.Default.MoreVert, "记录菜单", tint = Muted)
            }
        }
    }
}

@Composable
private fun SmallPeriodTag(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, modifier = Modifier.background(color.copy(alpha = .13f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 3.dp))
}

private fun gnssStatusColor(label: String): Color = when {
    label.contains("良好") -> Green
    label.contains("一般") -> Yellow
    label.contains("弱") || label.contains("关闭") -> Color(0xFFFF7777)
    else -> Muted
}

@Composable
private fun SessionDetail(
    nav: NavController,
    viewModel: AppViewModel,
    sessionId: String,
    routes: List<RouteProfileEntity>,
    origin: DetailOrigin,
) {
    val sessionLoadState by produceState<DetailSessionLoadState>(DetailSessionLoadState.Loading, key1 = sessionId) {
        viewModel.repository.sessionRow(sessionId).collect { row -> value = DetailSessionLoadState.Loaded(row) }
    }
    val track by viewModel.repository.track(sessionId).collectAsStateWithLifecycle(initialValue = emptyList())
    val evaluations by viewModel.repository.evaluations(sessionId).collectAsStateWithLifecycle(initialValue = emptyList())
    val originalSession by produceState<SessionEntity?>(initialValue = null, key1 = sessionId) { value = viewModel.repository.session(sessionId) }
    val session = (sessionLoadState as? DetailSessionLoadState.Loaded)?.row
    var showOriginal by remember { mutableStateOf(false) }
    val activeEvaluation = evaluations.firstOrNull { it.id == session?.evaluationId }
    val originalEvaluation = evaluations.firstOrNull { it.checkpointVersionId == originalSession?.checkpointVersionId }
    val displayedEvaluation = if (showOriginal) originalEvaluation else activeEvaluation
    val passes by produceState(initialValue = emptyList(), key1 = displayedEvaluation?.id, key2 = showOriginal) {
        value = displayedEvaluation?.let { viewModel.repository.evaluationPassRows(it.id) }
            ?: viewModel.repository.passes(sessionId).first()
    }
    val sectors by produceState(initialValue = emptyList(), key1 = displayedEvaluation?.id, key2 = showOriginal) {
        value = displayedEvaluation?.let { viewModel.repository.evaluationSectorRows(it.id) }
            ?: viewModel.repository.sectors(sessionId).first()
    }
    val displayTotal = displayedEvaluation?.totalDurationNanos ?: if (showOriginal) originalSession?.totalDurationNanos else session?.totalDurationNanos
    val displayMonthlyDelta = displayedEvaluation?.monthlyDeltaNanos ?: if (showOriginal) originalSession?.monthlyDeltaNanos else session?.monthlyDeltaNanos
    val displayWeeklyDelta = displayedEvaluation?.weeklyDeltaNanos ?: if (showOriginal) originalSession?.weeklyDeltaNanos else session?.weeklyDeltaNanos
    val displayQuality: String = displayedEvaluation?.qualityGrade ?: (if (showOriginal) originalSession?.qualityGrade else session?.qualityGrade) ?: "C"
    val displayMissingMask = displayedEvaluation?.missingCheckpointMask
        ?: if (showOriginal) originalSession?.missingCheckpointMask ?: 0 else session?.missingCheckpointMask ?: 0
    val displayValidity = if (displayedEvaluation != null) if (displayedEvaluation.status == "VALID") "VALID" else "INVALID" else if (showOriginal) originalSession?.validity ?: "PENDING" else session?.validity ?: "PENDING"
    val currentRoute = routes.firstOrNull { it.id == session?.routeProfileId }
    val routeSourceTrack by produceState(initialValue = emptyList<LocationFix>(), key1 = currentRoute?.id) {
        value = currentRoute?.id?.let { routeId -> viewModel.repository.routeSourceTrack(routeId).map(::trackPointToFix) }.orEmpty()
    }
    val activeVersionForSession = currentRoute?.activeCheckpointVersionId
    val legacyOldWithoutEvaluation = activeEvaluation == null && session?.checkpointVersionId != activeVersionForSession
    val displayCheckpointVersionId = displayedEvaluation?.checkpointVersionId
        ?: if (showOriginal || legacyOldWithoutEvaluation) originalSession?.checkpointVersionId else activeVersionForSession
    val mapCheckpoints by produceState(
        initialValue = emptyList<app.deltat.commute.domain.Checkpoint>(),
        key1 = displayCheckpointVersionId,
    ) {
        value = displayCheckpointVersionId?.let { viewModel.repository.checkpointsForVersion(it) }.orEmpty()
    }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete && session != null) {
        val isRouteSource = routes.firstOrNull { it.id == session.routeProfileId }?.referenceSessionId == session.id
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (isRouteSource) "无法单独删除" else "删除这条历史记录？") },
            text = { Text(if (isRouteSource) "这条轨迹用于定义当前路线和计时点。请在设置的路线管理中删除整条路线。" else "所有相关轨迹和分段数据都会删除，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (!isRouteSource) viewModel.deleteSession(session.id) {
                        if (!nav.popBackStack()) nav.navigate(origin.fallbackRoute) { launchSingleTop = true }
                    }
                }) { Text(if (isRouteSource) "知道了" else "确认删除", color = if (isRouteSource) TelemetryWhite else Color(0xFFFF7777)) }
            },
            dismissButton = if (isRouteSource) null else ({ TextButton(onClick = { confirmDelete = false }) { Text("取消") } }),
        )
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("单次详情", nav) {
            if (session != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.DeleteOutline, "删除记录", tint = Color(0xFFFF7777)) }
        }
        when {
            sessionLoadState is DetailSessionLoadState.Loading -> EmptyState("正在加载记录…", "正在读取轨迹和计时点数据")
            session == null -> EmptyState("记录不存在", "它可能已被删除")
            else -> LazyColumn(
            modifier = Modifier.testTag("session-detail-list"),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (activeEvaluation != null && originalEvaluation != null && activeEvaluation.id != originalEvaluation.id) item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !showOriginal, onClick = { showOriginal = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("当前计时点重算") }
                    SegmentedButton(selected = showOriginal, onClick = { showOriginal = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("原始成绩") }
                }
            }
            item {
                Text(session.routeName, color = Muted)
                Text(
                    displayTotal?.let(::formatDuration) ?: displayedEvaluation?.failureReason ?: statusText(session.status),
                    color = if (displayTotal == null) Muted else TelemetryWhite,
                    fontSize = if (displayTotal == null) 20.sp else 43.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (displayMonthlyDelta != null && displayMonthlyDelta < 0) SmallPeriodTag("本月最快", Purple)
                    if (displayWeeklyDelta != null && displayWeeklyDelta < 0) SmallPeriodTag("本周最快", Green)
                }
                Text("月 Δ ${displayMonthlyDelta?.let(::formatDelta) ?: "--"}", color = periodDeltaColor(displayMonthlyDelta, Purple), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("周 Δ ${displayWeeklyDelta?.let(::formatDelta) ?: "--"}", color = periodDeltaColor(displayWeeklyDelta, Green), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("${formatDate(session.startedWallTimeMillis)} · ${validityText(displayValidity)} · 质量 $displayQuality", color = Muted)
                Text(
                    when {
                        legacyOldWithoutEvaluation -> "旧计时点版本 · 不参与当前纪录"
                        showOriginal -> "原始计时点版本 · 保留记录"
                        else -> "当前计时点版本 · 算法 v${displayedEvaluation?.timingEngineVersion ?: 1}"
                    },
                    color = if (showOriginal || legacyOldWithoutEvaluation) Yellow else Muted,
                    fontSize = 12.sp,
                )
                if (displayMissingMask != 0) {
                    val missing = (1..2).filter { displayMissingMask and (1 shl it) != 0 }.joinToString("、") { "S$it" }
                    Text("总程有效 · 缺失 $missing，相邻分段显示 --", color = Yellow, fontSize = 12.sp)
                }
                val failedEvaluation = displayedEvaluation?.takeIf { !showOriginal && it.status != "VALID" }
                if (failedEvaluation != null) {
                    Text(failedEvaluation.failureReason ?: "无法按当前计时点重算", color = Yellow, fontSize = 12.sp)
                } else if (showOriginal && originalSession?.checkpointVersionId != activeVersionForSession) {
                    Text("不参与当前版本纪录", color = Yellow, fontSize = 12.sp)
                }
            }
            item {
                TelemetryRoute(
                    points = track.map(::trackPointToFix),
                    routeSourcePoints = routeSourceTrack,
                    checkpoints = mapCheckpoints,
                    currentFix = track.lastOrNull()?.let(::trackPointToFix),
                    actualPassElapsedNanos = passes.map { it.passElapsedNanos },
                    sectorColors = sectors.map { row -> runCatching { ResultColor.valueOf(row.resultColorAtCompletion) }.getOrDefault(ResultColor.NEUTRAL) },
                    cameraPolicy = MapCameraPolicy.INTERACTIVE_DETAIL,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
                    Column(Modifier.padding(16.dp)) {
                        (0..2).forEach { index ->
                            val sector = sectors.firstOrNull { it.sectorIndex == index }
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("S${index + 1}", Modifier.width(44.dp))
                                Text(sector?.durationNanos?.let(::formatDuration) ?: "--", Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("月 ${sector?.monthlyDeltaNanos?.let(::formatDelta) ?: "--"}", color = periodDeltaColor(sector?.monthlyDeltaNanos, Purple), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                    Text("周 ${sector?.weeklyDeltaNanos?.let(::formatDelta) ?: "--"}", color = periodDeltaColor(sector?.weeklyDeltaNanos, Green), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            if (session.maxSpeedMps != null) item {
                TelemetryPanel(Modifier.fillMaxWidth()) {
                    TelemetryLabel("MAX SPEED", RacingRed)
                    Text("%.1f km/h".format(session.maxSpeedMps * 3.6), fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(
                        buildString {
                            append(session.maxSpeedWallTimeMillis?.let { "发生于 ${formatClock(it)}" } ?: "峰值时刻不可用")
                            val start = passes.firstOrNull()?.passElapsedNanos
                            val peak = session.maxSpeedElapsedNanos
                            if (start != null && peak != null && peak >= start) append(" · 经过 A 后 ${formatDuration(peak - start)}")
                        },
                        color = Muted,
                    )
                }
            }
            if (session.status == "RECORDED") item {
                Button(onClick = { nav.navigate("edit/${session.id}") }, modifier = Modifier.fillMaxWidth()) { Text("继续标记 A / S1 / S2 / B") }
            }
            item { Text("轨迹点 ${track.size} · 原始坐标仅保存在应用私有数据库", color = Muted, fontSize = 13.sp) }
        }
        }
    }
}

private sealed interface DetailSessionLoadState {
    data object Loading : DetailSessionLoadState
    data class Loaded(val row: SessionListRow?) : DetailSessionLoadState
}

private enum class DetailOrigin(val fallbackRoute: String) {
    HISTORY("history"),
    RECORDS("records"),
    TIMING("history");

    companion object {
        fun fromRoute(value: String?): DetailOrigin = when (value) {
            "records" -> RECORDS
            "timing" -> TIMING
            else -> HISTORY
        }
    }
}

@Composable
private fun SettingsScreen(
    permissionGranted: Boolean,
    withPermissions: (() -> Unit) -> Unit,
    viewModel: AppViewModel,
    nav: NavController,
    runtime: app.deltat.commute.location.RuntimeSnapshot,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var deviceRevision by remember { mutableIntStateOf(0) }
    var pendingContent by remember { mutableStateOf("") }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) deviceRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val systemSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        deviceRevision++
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingContent) }
    }
    val fineGranted = remember(deviceRevision, permissionGranted) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val notificationGranted = remember(deviceRevision, permissionGranted) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val locationEnabled = remember(deviceRevision) {
        context.getSystemService(LocationManager::class.java).isLocationEnabled
    }
    val batteryExempt = remember(deviceRevision) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val recentExit = remember(deviceRevision) {
        (context.applicationContext as? app.deltat.commute.DeltaTApplication)?.recentExitSummary() ?: "暂无可用记录"
    }
    fun openAppDetails() {
        systemSettingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    fun requestBatteryExemption() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        systemSettingsLauncher.launch(if (request.resolveActivity(context.packageManager) != null) request else fallback)
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("设置")
        LazyColumn(
            modifier = Modifier.testTag("settings-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingCard(Icons.Default.Route, "路线管理", "编辑上班、下班和自定义路线", TelemetryWhite) { nav.navigate("routes") }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = if (fineGranted && notificationGranted && locationEnabled) Color(0xFF10261A) else Color(0xFF30230E))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("锁屏继续计时检查", fontWeight = FontWeight.Bold)
                        Text(
                            if (fineGranted && notificationGranted && locationEnabled) "核心条件已满足；建议再允许电池优化豁免。" else "请逐项完成下面的系统授权。",
                            color = if (fineGranted && notificationGranted && locationEnabled) Green else Yellow,
                        )
                        Text("Delta T 由你在前台主动开始 location 前台服务，锁屏后继续，因此不申请后台自动定位权限。", color = Muted, fontSize = 13.sp)
                    }
                }
            }
            item {
                SettingCard(Icons.Default.MyLocation, "精确定位权限", if (fineGranted) "已允许" else "未允许 · 点击请求", if (fineGranted) Green else Yellow) {
                    if (fineGranted) openAppDetails() else withPermissions { deviceRevision++ }
                }
            }
            item {
                SettingCard(Icons.Default.NotificationsActive, "常驻通知权限", if (notificationGranted) "已允许" else "未允许 · 点击请求", if (notificationGranted) Green else Yellow) {
                    if (notificationGranted) openAppDetails() else withPermissions { deviceRevision++ }
                }
            }
            item {
                SettingCard(Icons.Default.LocationOn, "系统定位开关", if (locationEnabled) "已开启" else "已关闭 · 点击前往开启", if (locationEnabled) Green else Yellow) {
                    systemSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            item {
                SettingCard(Icons.Default.BatterySaver, "电池优化限制", if (batteryExempt) "已允许不受限制" else "可能限制锁屏定位 · 点击申请豁免", if (batteryExempt) Green else Yellow) {
                    if (batteryExempt) openAppDetails() else requestBatteryExemption()
                }
            }
            item {
                SettingCard(Icons.Default.Tune, "ColorOS 后台运行设置", "打开应用详情，检查耗电管理与通知渠道", Muted) { openAppDetails() }
            }
            item { SettingCard(Icons.Default.Timer, "时间显示", "0.01 秒 · 完成成绩严格比较；实时估算防闪烁", Muted) {} }
            item { SettingCard(Icons.Default.Vibration, "节点提示", "短振动 · 声音关闭", Muted) {} }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("运行诊断", fontWeight = FontWeight.Bold)
                        Text("上次进程退出：$recentExit", color = Muted, fontSize = 13.sp)
                        Text("定位队列：待处理 ${runtime.pendingFixCount} · 本次丢弃 ${runtime.droppedFixCount}", color = Muted, fontSize = 13.sp)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("数据导出", fontWeight = FontWeight.Bold)
                        Text("导出文件可能包含家庭和公司附近的敏感轨迹信息，请妥善保管。", color = Muted, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { scope.launch { pendingContent = viewModel.exportJson(); createDocument.launch("delta-t-diagnostics.json") } }) { Text("JSON 诊断包") }
                            OutlinedButton(onClick = { scope.launch { pendingContent = viewModel.exportCsv(); createDocument.launch("delta-t-sessions.csv") } }) { Text("CSV") }
                        }
                    }
                }
            }
            item { Text("Delta T ${BuildConfig.VERSION_NAME}\n个人侧载测试版 · ${BuildConfig.APPLICATION_ID}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun SettingCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Surface)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color)
            Column(Modifier.padding(start = 14.dp).weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Muted, fontSize = 13.sp) }
            Icon(Icons.Default.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(Modifier.background(color.copy(alpha = .13f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50))); Spacer(Modifier.width(8.dp)); Text(text, color = color)
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Route, null, Modifier.size(48.dp), tint = Muted); Spacer(Modifier.height(14.dp)); Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted)
    }
}

@Composable
fun AbstractRoute(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    checkpointFractions: List<Float> = emptyList(),
    completedCheckpoint: Int = 0,
    referencePoints: List<Pair<Double, Double>> = emptyList(),
) {
    val hasTrack = points.size >= 2 || referencePoints.size >= 2
    Box(
        modifier = modifier.background(Color(0xFF0A0F12), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (!hasTrack) {
            Text("尚无轨迹 · 录制后自动绘制", color = Muted, fontSize = 13.sp)
        } else {
            Canvas(Modifier.fillMaxSize().padding(14.dp)) {
                val allPoints = referencePoints + points
                val bounds = projectionBounds(allPoints)
                val referencePathPoints = if (referencePoints.size >= 2) project(referencePoints, size, bounds) else emptyList()
                val actualPathPoints = if (points.size >= 2) project(points, size, bounds) else emptyList()

                fun drawRoute(pathPoints: List<Offset>, color: Color, widthDp: Float) {
                    if (pathPoints.size < 2) return
                    val path = Path().apply {
                        moveTo(pathPoints.first().x, pathPoints.first().y)
                        pathPoints.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, color = color, style = Stroke(widthDp.dp.toPx(), cap = StrokeCap.Round))
                }

                drawRoute(referencePathPoints, Color(0xFF59636A), 2f)
                drawRoute(actualPathPoints, Color(0xFFDCE4E8), 3f)

                val checkpointPath = if (referencePathPoints.isNotEmpty()) referencePathPoints else actualPathPoints
                checkpointFractions.forEachIndexed { index, fraction ->
                    val point = checkpointPath[((checkpointPath.lastIndex) * fraction.coerceIn(0f, 1f)).toInt()]
                    drawCircle(if (index <= completedCheckpoint) Purple else Color(0xFF7E8890), 7.dp.toPx(), point)
                    drawCircle(Color.White, 7.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}

private data class ProjectionBounds(val latMin: Double, val latRange: Double, val lonMin: Double, val lonRange: Double)

private fun projectionBounds(points: List<Pair<Double, Double>>): ProjectionBounds {
    val latMin = points.minOf { it.first }
    val latMax = points.maxOf { it.first }
    val lonMin = points.minOf { it.second }
    val lonMax = points.maxOf { it.second }
    return ProjectionBounds(
        latMin = latMin,
        latRange = (latMax - latMin).takeIf { it > 1e-8 } ?: 1e-8,
        lonMin = lonMin,
        lonRange = (lonMax - lonMin).takeIf { it > 1e-8 } ?: 1e-8,
    )
}

private fun project(points: List<Pair<Double, Double>>, size: Size, bounds: ProjectionBounds): List<Offset> {
    return points.mapIndexedNotNull { index, point ->
        if (points.size > 500 && index % (points.size / 500) != 0 && index != points.lastIndex) null else Offset(
            ((point.second - bounds.lonMin) / bounds.lonRange).toFloat() * size.width * .88f + size.width * .06f,
            size.height * .94f - ((point.first - bounds.latMin) / bounds.latRange).toFloat() * size.height * .88f,
        )
    }
}

private fun formatDuration(nanos: Long): String {
    val hundredths = nanos.coerceAtLeast(0) / 10_000_000
    val hours = hundredths / 360_000
    val minutes = (hundredths / 6_000) % 60
    val seconds = (hundredths / 100) % 60
    val fraction = hundredths % 100
    return if (hours > 0) {
        "%d:%02d:%02d.%02d".format(hours, minutes, seconds, fraction)
    } else {
        "%02d:%02d.%02d".format(minutes, seconds, fraction)
    }
}
private fun formatDelta(nanos: Long) = (if (nanos < 0) "−" else "+") + formatDuration(kotlin.math.abs(nanos))
private fun formatDate(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
private fun formatClock(millis: Long) = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(millis))
private fun statusText(status: String) = when (status) { "INTERRUPTED" -> "已中断"; "CANCELLED" -> "已结束"; "RECORDED" -> "待标点"; else -> status }
private fun validityText(validity: String) = when (validity) { "VALID" -> "有效"; "INVALID" -> "不计纪录"; else -> "待完成" }
private fun qualityColor(grade: String) = when (grade) { "A" -> Green; "B" -> Yellow; else -> Muted }
private fun colorFor(color: ResultColor) = when (color) { ResultColor.PURPLE -> Purple; ResultColor.GREEN -> Green; ResultColor.YELLOW -> Yellow; ResultColor.NEUTRAL -> Muted }
private fun colorReason(color: ResultColor) = when (color) { ResultColor.PURPLE -> "纪录"; ResultColor.GREEN -> "快于基准"; ResultColor.YELLOW -> "慢于基准"; ResultColor.NEUTRAL -> "" }
private fun periodColorReason(color: ResultColor) = when (color) { ResultColor.PURPLE -> "本月最快"; ResultColor.GREEN -> "本周最快"; ResultColor.YELLOW -> "慢于本周最快"; ResultColor.NEUTRAL -> "持平 / 建立基准" }
private fun deltaColor(deltaNanos: Long?) = when { deltaNanos == null -> Muted; deltaNanos < 0 -> Green; else -> Yellow }
private fun deltaComparison(deltaNanos: Long?) = when { deltaNanos == null -> "对比"; deltaNanos < 0 -> "快于"; else -> "慢于" }
private fun periodDeltaColor(deltaNanos: Long?, bestColor: Color) = when {
    deltaNanos == null || deltaNanos == 0L -> Muted
    deltaNanos < 0 -> bestColor
    else -> Yellow
}
private fun periodPrimaryColor(monthlyDeltaNanos: Long?, weeklyDeltaNanos: Long?, qualityGrade: String): ResultColor = when {
    qualityGrade !in setOf("A", "B") -> ResultColor.NEUTRAL
    monthlyDeltaNanos != null && monthlyDeltaNanos < 0 -> ResultColor.PURPLE
    weeklyDeltaNanos != null && weeklyDeltaNanos < 0 -> ResultColor.GREEN
    (weeklyDeltaNanos ?: monthlyDeltaNanos)?.let { it > 0 } == true -> ResultColor.YELLOW
    else -> ResultColor.NEUTRAL
}

private fun trackPointToFix(point: TrackPointEntity) = LocationFix(
    latitude = point.latitude,
    longitude = point.longitude,
    elapsedRealtimeNanos = point.elapsedRealtimeNanos,
    wallTimeMillis = point.wallTimeMillis,
    accuracyMeters = point.accuracyMeters,
    speedMetersPerSecond = point.speedMps,
    bearingDegrees = point.bearingDegrees,
    bearingAccuracyDegrees = point.bearingAccuracy,
    provider = point.provider,
    isMock = point.isMock,
    speedAccuracyMetersPerSecond = point.speedAccuracyMps,
)
