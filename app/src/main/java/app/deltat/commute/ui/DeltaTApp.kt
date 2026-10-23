@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
                Text("启用后会加载高德路网底图。原始通勤轨迹仍只保存在本机；高德地图 SDK 会处理提供地图所需的设备与网络信息。不同意时仍可使用简洁轨迹图。")
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
                        else nav.navigate("timing") { launchSingleTop = true }
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
    NavigationBar(containerColor = Surface) {
        listOf(
            Triple("home", Icons.Default.Home, "首页"),
            Triple("history", Icons.Default.History, "历史"),
            Triple("records", Icons.Default.BarChart, "纪录"),
            Triple("settings", Icons.Default.Settings, "设置"),
        ).forEach { (destination, icon, label) ->
            NavigationBarItem(
                selected = route == destination,
                colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF35424E), selectedIconColor = TelemetryWhite, selectedTextColor = TelemetryWhite, unselectedIconColor = Muted, unselectedTextColor = Muted),
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
    TopAppBar(title = { Text(title) }, navigationIcon = {
        if (nav != null) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = { action?.invoke() }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Background), windowInsets = WindowInsets(0, 0, 0, 0))
}

@Composable
internal fun HomeScreen(
    routes: List<RouteProfileEntity>, sessions: List<SessionListRow>, runtime: app.deltat.commute.location.RuntimeSnapshot, permissionGranted: Boolean,
    onRoutes: () -> Unit, onAutoPrepare: () -> Unit, onResume: () -> Unit, onPermission: () -> Unit,
    onRoute: (RouteProfileEntity) -> Unit = { onRoutes() },
) {
    val readyRoutes = remember(routes) { routes.filter { it.activeCheckpointVersionId != null } }
    val periodNow = rememberCalendarNowMillis()
    val bounds = remember(sessions, periodNow) { comparisonPeriodBounds(maxOf(periodNow, System.currentTimeMillis())) }
    val routesById = remember(routes) { routes.associateBy { it.id } }
    val comparable = remember(sessions, routesById) { sessions.filter { row ->
        val rowRoute = routesById[row.routeProfileId]
        row.validity == "VALID" && row.qualityGrade in setOf("A", "B") &&
            row.checkpointVersionId == rowRoute?.activeCheckpointVersionId && row.totalDurationNanos != null
    } }
    val thisWeek = remember(comparable, bounds) { comparable.filter { it.startedWallTimeMillis in bounds.weekStartMillis until bounds.endExclusiveMillis } }
    val refreshedThisWeek = remember(thisWeek) { thisWeek.count { (it.weeklyDeltaNanos ?: 0L) < 0L } }
    val routeGroups = remember(comparable) { comparable.groupBy { it.routeProfileId } }
    val weeklyBests = remember(thisWeek) { thisWeek.groupBy { it.routeProfileId }.mapValues { (_, rows) -> rows.minByOrNull { it.totalDurationNanos ?: Long.MAX_VALUE } } }
    val latestRows = remember(routeGroups) { routeGroups.mapValues { (_, rows) -> rows.maxByOrNull { it.startedWallTimeMillis } } }
    val active = runtime.mode != null && runtime.status != "IDLE" && runtime.engineStatus != EngineStatus.COMPLETED
    val displayRoutes = remember(routes) { routes.sortedBy { if (it.directionType == "TO_WORK") 0 else if (it.directionType == "FROM_WORK") 1 else 2 } }
    var gpsDetails by remember { mutableStateOf(false) }
    if (gpsDetails) AlertDialog(onDismissRequest = { gpsDetails = false }, title = { Text("定位状态") },
        text = { Text(if (permissionGranted) runtime.gnssHealth.displaySummary() else "计时前需要精确定位和通知权限") },
        confirmButton = { TextButton(onClick = { gpsDetails = false; if (!permissionGranted) onPermission() }) { Text(if (permissionGranted) "知道了" else "检查权限") } })
    Column(Modifier.fillMaxSize()) {
        PageHeader("Delta T", action = { IconButton(onClick = { gpsDetails = true }) { Icon(Icons.Default.GpsFixed, "定位状态", tint = if (permissionGranted) Muted else Yellow) } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (!permissionGranted) item { DesignPanel {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.LocationOff, null, tint = Yellow); Spacer(Modifier.width(12.dp)); Text("计时前需要精确定位和通知权限", Modifier.weight(1f)) }
                OutlinedButton(onClick = onPermission) { Text("检查权限") }
            } }
            item {
                val prepareAction = when { active -> onResume; readyRoutes.isEmpty() -> onRoutes; else -> onAutoPrepare }
                DesignPanel(Modifier.testTag("home-prepare").clickable(onClick = prepareAction)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.width(3.dp).height(16.dp).background(RacingRed)); Spacer(Modifier.width(8.dp)); Text("下一程", style = MaterialTheme.typography.bodySmall, color = Muted) }
                    Text(when { active -> "计时正在继续"; readyRoutes.isEmpty() -> "建立路线"; else -> "准备出发" }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (active) "${runtime.routeName} · 锁屏后继续记录" else "靠近起点后自动识别路线", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(4.dp))
                    DesignAction(when { active -> "返回计时"; readyRoutes.isEmpty() -> "先建立路线"; else -> "一键准备" }, prepareAction)
                }
            }
            item { DesignSection("通勤路线") }
            if (displayRoutes.isEmpty()) item { HomeEmptyRouteTile(Modifier.fillMaxWidth(), onRoutes) }
            else item {
                Surface(color = Surface, shape = RoundedCornerShape(8.dp)) { Column {
                    displayRoutes.take(2).forEachIndexed { index, route ->
                        HomeRouteRow(route, weeklyBests[route.id]?.totalDurationNanos, latestRows[route.id]?.totalDurationNanos) { onRoute(route) }
                        if (index < minOf(1, displayRoutes.lastIndex)) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Outline.copy(alpha = .45f))
                    }
                } }
            }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.History, null, Modifier.size(16.dp), tint = Muted); Spacer(Modifier.width(8.dp)); Text(if (thisWeek.isEmpty()) "本周暂无通勤记录" else "本周 ${thisWeek.size} 次通勤 · $refreshedThisWeek 次刷新纪录", color = Muted, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable
private fun HomeRouteRow(route: RouteProfileEntity, weeklyBest: Long?, latest: Long?, onClick: () -> Unit) {
    val ready = route.activeCheckpointVersionId != null
    Column(Modifier.testTag("home-route-${route.id}").clickable(onClick = onClick)) {
        ListItem(headlineContent = { Text(route.name, fontWeight = FontWeight.Medium) }, supportingContent = { Text(if (ready) "已就绪 · 点击编辑计时点" else "需要设置计时点") },
            leadingContent = { Icon(Icons.Default.Route, null, tint = Muted) }, trailingContent = { Icon(Icons.Default.ChevronRight, "编辑${route.name}", tint = Muted) }, colors = ListItemDefaults.colors(containerColor = Surface))
        FlowRow(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("本周最快  ${weeklyBest?.let(::formatDuration) ?: "—"}", color = if (weeklyBest == null) Muted else Green, style = MaterialTheme.typography.bodySmall)
            Text("最近 ${latest?.let(::formatDuration) ?: "—"}", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HomeEmptyRouteTile(modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(12.dp)) {
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
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(12.dp)) {
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
                    shape = RoundedCornerShape(12.dp),
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
            item { DesignAction("新建路线", onNew, icon = Icons.Default.Add) }
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
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageHeader("新建路线", nav)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("1 命名   →   2 录制   →   3 标记计时点", style = MaterialTheme.typography.bodySmall, color = Muted)
            OutlinedTextField(name, { name = it.take(24) }, label = { Text("路线名称") }, placeholder = { Text("例如：周末回家") }, singleLine = true, supportingText = { Text("${name.length}/24") }, modifier = Modifier.fillMaxWidth())
            DesignPanel { Icon(Icons.Default.Route, null, tint = ChartBlue); Text("一次录制，之后自动计时", style = MaterialTheme.typography.titleMedium); Text("出发前开始录制，到达并停车后标记四个计时点。点与点之间可选择任何道路。", color = Muted, style = MaterialTheme.typography.bodySmall) }
            DesignAction("下一步：录制路线", { onCreate(name) }, enabled = name.isNotBlank(), icon = null)
        }
    }
}

@Composable
internal fun BaselineScreen(nav: NavController, route: RouteProfileEntity?, runtime: app.deltat.commute.location.RuntimeSnapshot, start: () -> Unit, stop: () -> Unit) {
    val active = runtime.mode == "BASELINE_RECORDING" && runtime.routeId == route?.id
    val finished = !active && runtime.status == "路线轨迹已录制" && runtime.sessionId != null
    Column(Modifier.fillMaxSize()) {
        PageHeader("录制路线", nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${route?.name ?: "路线"} · ${if (active) "正在记录完整轨迹" else if (finished) "轨迹已保存" else "准备录制"}", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (!active && !finished) DesignPanel { Icon(Icons.Default.Route, null, tint = ChartBlue); Text("准备好再出发", style = MaterialTheme.typography.headlineSmall); Text("驾驶过程中无需操作，到达并停车后结束录制。", color = Muted, style = MaterialTheme.typography.bodySmall) }
            else { Text(formatRecordDuration(runtime.elapsedNanos), fontFamily = FontFamily.Monospace, fontSize = 40.sp); if (active) CompactGps(runtime) }
            TelemetryRoute(points = runtime.track, currentFix = runtime.lastFix, cameraPolicy = MapCameraPolicy.STAGED_RECORDING, modifier = Modifier.fillMaxWidth().height(390.dp))
            when {
                finished -> DesignAction("标记 A / S1 / S2 / B", { nav.navigate("edit/${runtime.sessionId}") }, icon = Icons.Default.EditLocationAlt)
                active -> DesignAction("我已停车，结束录制", stop, icon = Icons.Default.StopCircle)
                else -> DesignAction("开始记录路线", start, icon = Icons.Default.RadioButtonChecked)
            }
            Text("请勿在驾驶中操作手机", color = Muted, style = MaterialTheme.typography.bodySmall)
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
    val waitingAtA = runtime.engineStatus in setOf(EngineStatus.ARMED, EngineStatus.START_CANDIDATE) && runtime.routeId != null
    val beforeTiming = detecting || waitingAtA
    val fixState = rememberFixPresentation(runtime, elapsedRealtimeNanos)
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
    if (beforeTiming) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(if (detecting) "准备出发" else route?.name ?: runtime.routeName, nav)
            PreparedTimingContent(runtime, fixState, { showRoutePicker = true }) { confirmStop = true }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(route?.name ?: runtime.routeName.ifBlank { if (completed) "本次完成" else "计时" }, nav)
        val scrollable = useScrollableAccessibilityLayout
        Column(Modifier.fillMaxSize().then(if (scrollable) Modifier.verticalScroll(accessibilityScrollState) else Modifier).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TimingStatusRow(runtime)
            F1TimingBoard(runtime, route?.name ?: runtime.routeName, completed) { LiveTimer(runtime, completed, elapsedRealtimeNanos, animateClock) }
            TimingMap(runtime, fixState, if (completed) app.deltat.commute.map.MapPhase.COMPLETE else app.deltat.commute.map.MapPhase.TIMING,
                Modifier.fillMaxWidth().then(if (scrollable) Modifier.height(330.dp) else Modifier.weight(1f)))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("最高 ${runtime.maxSpeedMps?.let { "%.1f".format(it * 3.6) } ?: "—"} km/h", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text("过滤后峰值", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            when {
                completed && runtime.sessionId != null -> DesignAction("查看本次详情", { nav.navigate("detail/${runtime.sessionId}?source=timing") }, icon = Icons.Default.Route)
                active -> OutlinedButton(onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.StopCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("结束本次（成绩无效）") }
                else -> DesignAction("一键准备", start)
            }
            if (!completed) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center) { Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = Muted); Spacer(Modifier.width(6.dp)); Text("可锁屏继续 · 驾驶中无需点击", color = Muted, style = MaterialTheme.typography.bodySmall) }
            else Spacer(Modifier.height(8.dp))
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
private fun F1TimingBoard(runtime: app.deltat.commute.location.RuntimeSnapshot, routeName: String, completed: Boolean, timer: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DesignPanel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (completed) "本次总用时" else "总用时", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(if (completed) "COMPLETE" else "LIVE", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            timer()
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TimingDelta("月", runtime.monthlyDeltaNanos, Purple, runtime.deltaConfidence)
                TimingDelta("周", runtime.weeklyDeltaNanos, Green, runtime.deltaConfidence)
            }
            if (runtime.deltaConfidence == DeltaConfidence.HELD) Text("暂时无法匹配 · 保留最近确认值", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (runtime.deltaConfidence == DeltaConfidence.ESTIMATED) Text("实时估算 · 过点后确认", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Surface(color = Surface, shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text("分段", Modifier.width(36.dp), color = Muted, fontSize = 11.sp)
                    listOf("用时", "月 Δ", "周 Δ").forEach { Text(it, Modifier.weight(1f), color = Muted, fontSize = 11.sp, textAlign = TextAlign.End) }
                }
                (0..2).forEach { index ->
                    val sector = runtime.sectors.firstOrNull { it.index == index }
                    val current = !completed && runtime.currentSector - 1 == index
                    val month = sector?.monthlyDeltaNanos ?: sector?.deltaNanos ?: if (current) runtime.currentSectorMonthlyDeltaNanos else null
                    val week = sector?.weeklyDeltaNanos ?: if (current) runtime.currentSectorWeeklyDeltaNanos else null
                    val color = sector?.color?.let(::colorFor) ?: if (current) RacingRed else Muted
                    val confidence = if (sector != null) DeltaConfidence.CONFIRMED else runtime.deltaConfidence
                    Row(Modifier.fillMaxWidth().heightIn(min = 35.dp).testTag("sector-card-S${index + 1}"), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(2.dp).height(16.dp).background(if (current) RacingRed else Color.Transparent)); Spacer(Modifier.width(6.dp))
                        Text("S${index + 1}", Modifier.width(28.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
                        FittedTimingValue(sector?.durationNanos?.let(::formatRecordDuration) ?: if (current) "计时中" else "—", Modifier.weight(1f), if (sector != null) color else TelemetryWhite, 13f)
                        FittedTimingValue(month?.let(::formatDelta) ?: "—", Modifier.weight(1f), if (confidence == DeltaConfidence.HELD) Muted else periodDeltaColor(month, Purple), 12f)
                        FittedTimingValue(week?.let(::formatDelta) ?: "—", Modifier.weight(1f), if (confidence == DeltaConfidence.HELD) Muted else periodDeltaColor(week, Green), 12f)
                    }
                    if (index < 2) HorizontalDivider(color = Outline.copy(alpha = .3f))
                }
            }
        }
    }
}

@Composable
private fun TimingDelta(label: String, delta: Long?, bestColor: Color, confidence: DeltaConfidence) {
    Text("$label Δ ${delta?.let(::formatDelta) ?: "—"}", color = if (confidence == DeltaConfidence.HELD) Muted else periodDeltaColor(delta, bestColor), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

@Composable
private fun FittedTimingValue(text: String, modifier: Modifier, color: Color, maximum: Float) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterEnd) {
        val scale = LocalDensity.current.fontScale
        val ems = text.sumOf { if (it.code > 255) 1.0 else .61 }.coerceAtLeast(1.0)
        Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End, color = color, fontSize = minOf(maximum, ((maxWidth.value - 2f) / ems / scale).toFloat()).sp, fontFamily = FontFamily.Monospace, maxLines = 1)
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
    val periodNowMillis = rememberCalendarNowMillis()
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
    val bounds = remember(sessions, periodNowMillis) { comparisonPeriodBounds(maxOf(periodNowMillis, System.currentTimeMillis())) }
    val routesById = remember(routes) { routes.associateBy { it.id } }
    val eligible = remember(sessions, routesById) { sessions.filter { row ->
        isCurrentRecordEligible(row, routesById[row.routeProfileId]?.activeCheckpointVersionId)
    } }
    val monthBestIds = remember(eligible, bounds) { eligible.filter { it.startedWallTimeMillis in bounds.monthStartMillis until bounds.endExclusiveMillis }
        .groupBy { it.routeProfileId }.mapValues { (_, rows) -> rows.minWithOrNull(compareBy<SessionListRow> { it.totalDurationNanos }.thenBy { it.startedWallTimeMillis }.thenBy { it.id })?.id } }
    val weekBestIds = remember(eligible, bounds) { eligible.filter { it.startedWallTimeMillis in bounds.weekStartMillis until bounds.endExclusiveMillis }
        .groupBy { it.routeProfileId }.mapValues { (_, rows) -> rows.minWithOrNull(compareBy<SessionListRow> { it.totalDurationNanos }.thenBy { it.startedWallTimeMillis }.thenBy { it.id })?.id } }
    Column(Modifier.fillMaxSize()) {
        PageHeader("历史记录")
        if (sessions.isEmpty()) EmptyState("还没有行程", "完成路线录制或日常计时后会显示在这里")
        else LazyColumn(state = historyListState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            items(sessions, key = { it.id }) { session ->
                val isMonthBest = monthBestIds[session.routeProfileId] == session.id
                val isWeekBest = weekBestIds[session.routeProfileId] == session.id
                val activeVersionId = routesById[session.routeProfileId]?.activeCheckpointVersionId
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
internal fun HistoryRecordCard(session: SessionListRow, activeCheckpointVersionId: String?, isMonthBest: Boolean, isWeekBest: Boolean, isEligible: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    Column(Modifier.background(Surface)) {
        ListItem(modifier = Modifier.testTag("history-card-${session.id}").clickable(onClick = onOpen),
            headlineContent = { Text(session.routeName, fontWeight = FontWeight.Medium) },
            overlineContent = { Text(formatDate(session.startedWallTimeMillis)) },
            supportingContent = { Column {
                Text(if (isEligible) "有效 · 质量 ${session.qualityGrade}" else when {
                    session.status == "INTERRUPTED" -> "已中断 · 不计纪录"
                    session.checkpointVersionId != activeCheckpointVersionId -> "旧计时点 · 原始${validityText(session.validity)} · 质量 ${session.qualityGrade} · 不计当前纪录"
                    else -> "${validityText(session.validity)} · 质量 ${session.qualityGrade}"
                })
                session.evaluationFailureReason?.let { Text(it, color = Yellow) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { if (isMonthBest) SmallPeriodTag("本月最快", Purple); if (isWeekBest) SmallPeriodTag("本周最快", Green) }
            } },
            leadingContent = { Icon(if (isEligible) Icons.Default.Route else Icons.Default.Info, null, tint = Muted) },
            trailingContent = { Column(horizontalAlignment = Alignment.End) {
                Text(session.totalDurationNanos?.let(::formatRecordDuration) ?: "—", fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onMenu, modifier = Modifier.testTag("history-menu-${session.id}")) { Icon(Icons.Default.MoreHoriz, "记录菜单", tint = Muted) }
            } }, colors = ListItemDefaults.colors(containerColor = Surface))
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Outline.copy(alpha = .4f))
    }
}

@Composable
private fun SmallPeriodTag(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, modifier = Modifier.background(color.copy(alpha = .13f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 3.dp))
}

internal fun gnssStatusColor(label: String): Color = when {
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
                Text("${session.routeName} · ${formatDate(session.startedWallTimeMillis)}", color = Muted)
                Spacer(Modifier.height(12.dp))
                Text(
                    displayTotal?.let(::formatDuration) ?: displayedEvaluation?.failureReason ?: statusText(session.status),
                    color = if (displayTotal == null) Muted else TelemetryWhite,
                    fontSize = if (displayTotal == null) 20.sp else 44.sp,
                    lineHeight = if (displayTotal == null) 28.sp else 52.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(12.dp))
                Text("${validityText(displayValidity)} · 质量 $displayQuality  ${when {
                    legacyOldWithoutEvaluation -> "旧计时点版本 · 不参与当前纪录"
                    showOriginal -> "原始计时点版本 · 保留记录"
                    else -> "当前计时点 · 算法 v${displayedEvaluation?.timingEngineVersion ?: 1}"
                }}", color = if (showOriginal || legacyOldWithoutEvaluation) Yellow else Muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("月 Δ ${displayMonthlyDelta?.let(::formatDelta) ?: "--"}", color = periodDeltaColor(displayMonthlyDelta, Purple), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("周 Δ ${displayWeeklyDelta?.let(::formatDelta) ?: "--"}", color = periodDeltaColor(displayWeeklyDelta, Green), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (displayMonthlyDelta != null && displayMonthlyDelta < 0) SmallPeriodTag("本月最快", Purple)
                    if (displayWeeklyDelta != null && displayWeeklyDelta < 0) SmallPeriodTag("本周最快", Green)
                }
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
                    sectorColors = (0..2).map { index -> sectors.firstOrNull { it.sectorIndex == index }?.let { row -> runCatching { ResultColor.valueOf(row.resultColorAtCompletion) }.getOrDefault(ResultColor.NEUTRAL) } ?: ResultColor.NEUTRAL },
                    cameraPolicy = MapCameraPolicy.INTERACTIVE_DETAIL,
                    modifier = Modifier.fillMaxWidth().height(250.dp),
                )
            }
            item {
                DesignSection("分段成绩")
                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(12.dp)) {
                    (0..2).forEach { index ->
                        val sector = sectors.firstOrNull { it.sectorIndex == index }
                        ListItem(
                            headlineContent = { Text(listOf("A → S1", "S1 → S2", "S2 → B")[index]) },
                            supportingContent = { FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("月 ${sector?.monthlyDeltaNanos?.let(::formatDelta) ?: "--"}", color = periodDeltaColor(sector?.monthlyDeltaNanos, Purple), fontSize = 12.sp)
                                Text("周 ${sector?.weeklyDeltaNanos?.let(::formatDelta) ?: "--"}", color = periodDeltaColor(sector?.weeklyDeltaNanos, Green), fontSize = 12.sp)
                            } },
                            leadingContent = { Text("0${index+1}", color = Muted, fontFamily = FontFamily.Monospace) },
                            trailingContent = { Text(sector?.durationNanos?.let(::formatDuration) ?: "--", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = sector?.resultColorAtCompletion?.let { runCatching { colorFor(ResultColor.valueOf(it)) }.getOrNull() } ?: Muted) },
                            colors = ListItemDefaults.colors(containerColor = Surface),
                        )
                        if (index < 2) HorizontalDivider(color = Outline.copy(alpha = .25f))
                    }
                }
            }
            if (session.maxSpeedMps != null) item {
                ListItem(
                    headlineContent = { Text("过滤后最高速度") },
                    supportingContent = { Text(buildString {
                        append(session.maxSpeedWallTimeMillis?.let { "峰值时刻 ${formatClock(it)}" } ?: "峰值时刻不可用")
                        val start = passes.firstOrNull()?.passElapsedNanos
                        val peak = session.maxSpeedElapsedNanos
                        if (start != null && peak != null && peak >= start) append(" · 经过 A 后 ${formatDuration(peak - start)}")
                    }) },
                    leadingContent = { Icon(Icons.Default.Speed, null, tint = Muted) },
                    trailingContent = { Text("%.1f km/h".format(session.maxSpeedMps * 3.6), fontFamily = FontFamily.Monospace, color = Muted) },
                    colors = ListItemDefaults.colors(containerColor = Surface), modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                )
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
    var diagnostics by remember { mutableStateOf(false) }
    if (diagnostics) AlertDialog(onDismissRequest = { diagnostics = false }, title = { Text("运行诊断") }, text = { Text("上次进程退出：$recentExit\n定位队列：待处理 ${runtime.pendingFixCount} · 本次丢弃 ${runtime.droppedFixCount}") }, confirmButton = { TextButton(onClick = { diagnostics = false }) { Text("知道了") } })
    Column(Modifier.fillMaxSize()) {
        PageHeader("设置")
        LazyColumn(modifier = Modifier.testTag("settings-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Surface(color = Surface, shape = RoundedCornerShape(8.dp)) { Column { DesignSetting("路线管理", "上班、下班和自定义路线", Icons.Default.Route, true) { nav.navigate("routes") } } } }
            item { DesignSection("锁屏继续计时"); Text("开始前检查以下系统设置", color = Muted, style = MaterialTheme.typography.bodySmall) }
            item { Surface(color = Surface, shape = RoundedCornerShape(8.dp)) { Column {
                DesignSetting("精确定位权限", if (fineGranted) "已允许" else "未允许 · 点击请求", Icons.Default.LocationOn) { if (fineGranted) openAppDetails() else withPermissions { deviceRevision++ } }
                DesignSetting("常驻通知权限", if (notificationGranted) "已允许" else "未允许 · 点击请求", Icons.Default.NotificationsActive) { if (notificationGranted) openAppDetails() else withPermissions { deviceRevision++ } }
                DesignSetting("系统定位", if (locationEnabled) "已开启" else "已关闭 · 点击前往开启", Icons.Default.GpsFixed) { systemSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                DesignSetting("电池优化限制", if (batteryExempt) "已允许不受限制" else "建议允许不受限制", Icons.Default.BatterySaver) { if (batteryExempt) openAppDetails() else requestBatteryExemption() }
                DesignSetting("ColorOS 后台运行", "前往系统应用设置", Icons.Default.Settings, true) { openAppDetails() }
            } } }
            item { DesignSection("计时与数据") }
            item { Surface(color = Surface, shape = RoundedCornerShape(8.dp)) { Column {
                DesignSetting("时间显示", "0.01 秒 · 完成成绩严格比较；实时估算防闪烁", Icons.Default.Timer)
                DesignSetting("节点提示", "短振动 · 声音关闭", Icons.Default.Vibration, true)
            } } }
            item { DesignSection("数据与诊断") }
            item { DesignPanel {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.FileDownload, null, tint = Muted); Spacer(Modifier.width(12.dp)); Text("数据导出", style = MaterialTheme.typography.titleMedium) }
                Text("导出包含敏感轨迹信息，请妥善保管", color = Muted, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { pendingContent = viewModel.exportJson(); createDocument.launch("delta-t-diagnostics.json") } }) { Text("JSON 诊断包") }
                    OutlinedButton(onClick = { scope.launch { pendingContent = viewModel.exportCsv(); createDocument.launch("delta-t-sessions.csv") } }) { Text("CSV") }
                }
            } }
            item { Surface(color = Surface, shape = RoundedCornerShape(8.dp)) { Column { DesignSetting("运行诊断", "查看进程退出、定位队列和丢弃记录", Icons.Default.MonitorHeart, true) { diagnostics = true } } } }
            item { DesignSection("关于"); Text("Delta T ${BuildConfig.VERSION_NAME}\n个人侧载测试版", color = Muted, style = MaterialTheme.typography.bodySmall) }
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
