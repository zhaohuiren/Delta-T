package app.deltat.commute

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.data.LeaderboardRow
import app.deltat.commute.domain.DeltaConfidence
import app.deltat.commute.location.RuntimeBus
import app.deltat.commute.location.RuntimeSnapshot
import app.deltat.commute.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import java.io.File

/** Design evidence only. Runs production screens with existing deterministic fixtures,
 * in an isolated emulator. Never packaged in the application APK. */
class RedesignBaselineCaptureTest {
    private val fixture = FullAppUiFlowTest()
    @get:Rule val compose = fixture.composeRule
    private val app get() = compose.activity.application as DeltaTApplication
    private val output get() = File(app.getExternalFilesDir(null), "redesign-20261022").apply { mkdirs() }

    @Before fun seed() = fixture.seedRichUiState()
    @After fun restore() = fixture.restoreDefaults()
    private fun click(text: String) = compose.onNodeWithText(text).performClick()
    private fun back() = compose.onNodeWithContentDescription("返回").performClick()
    private fun shot(id: String, state: String) {
        compose.waitForIdle()
        Thread.sleep(250)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "$id.txt").writeText(compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString())
        File(output, "captures.jsonl").appendText(JSONObject().put("id", id).put("state", state)
            .put("timestamp", java.time.OffsetDateTime.now().toString())
            .put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
            .put("fixture", "FullAppUiFlowTest.seedRichData + TimingScreenTest snapshots; synthetic only")
            .put("density", app.resources.displayMetrics.density).put("fontScale", app.resources.configuration.fontScale)
            .toString() + "\n")
    }
    private fun dismissPrivacy() {
        if (compose.onAllNodesWithText("继续简洁模式").fetchSemanticsNodes().isNotEmpty()) {
            shot("privacy-dialog", "Actual navigation into map page; privacy not accepted")
            click("继续简洁模式")
        }
    }

    @Test fun populatedNavigation() {
        shot("home", "Full app; work and home routes; no active session")
        click("设置"); shot("settings-top", "Full app settings; actual emulator permission state")
        compose.onNodeWithTag("settings-list").performScrollToIndex(7)
        shot("settings-bottom", "Settings scrolled to display, cues, diagnostics and export")
        compose.onNodeWithTag("settings-list").performScrollToIndex(0)
        click("路线管理"); shot("routes", "Routes work/home")
        compose.onNodeWithContentDescription("删除上班").performClick()
        shot("route-delete-dialog", "Delete confirmation only; no deletion")
        click("取消")
        compose.onNodeWithContentDescription("新建").performClick()
        shot("new-route-empty", "New route; empty name; primary action disabled")
        compose.onNode(hasSetTextAction()).performTextInput("周末回家")
        compose.activity.runOnUiThread { }
        shot("new-route-filled", "New route; entered fixture name; no save")
        back()
        compose.onAllNodes(hasText("上班") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("编辑计时点").fetchSemanticsNodes().isNotEmpty() }
        shot("editor-a", "work-best source track; checkpoint A selected")
        compose.onAllNodes(hasText("S1") and hasClickAction()).onFirst().performClick()
        shot("editor-s1", "Same source track; S1 selected")
        back(); back()
        click("历史"); shot("history", "Full app; work-best, work-older, work-old and home-run")
        compose.onAllNodes(hasText("27:40.00") and hasClickAction()).onFirst().performClick()
        dismissPrivacy(); shot("detail-old", "work-old; old checkpoint version")
        back()
        compose.onAllNodes(hasText("27:58.10") and hasClickAction()).onFirst().performClick()
        dismissPrivacy(); shot("detail", "work-best; current checkpoint version; fallback map")
        compose.onNodeWithTag("session-detail-list").performScrollToIndex(4)
        shot("detail-bottom", "work-best; metrics, checkpoint version and raw track section")
        compose.onNodeWithContentDescription("删除记录").performClick()
        shot("detail-protected", "Source session deletion guard")
        click("知道了"); back()
        click("纪录"); shot("records-ranking", "work route; default monthly ranking")
        click("月统计"); shot("records-month", "work route; monthly; all eligible fixture sessions")
        compose.onNodeWithTag("records-list").performScrollToIndex(2)
        shot("records-month-bottom", "Monthly lower section")
        compose.onNodeWithTag("records-list").performScrollToIndex(0)
        click("周统计"); shot("records-week", "work route; weekly statistics")
        click("排行榜"); click("自定义")
        shot("date-range", "Custom date selection dialog")
        click("取消")
    }

    @Test fun emptyNavigation() {
        runBlocking { withContext(Dispatchers.IO) { app.database.clearAllTables() } }
        RuntimeBus.reset()
        compose.waitUntil(5000) { compose.onAllNodesWithText("先建立路线").fetchSemanticsNodes().isNotEmpty() }
        shot("home-empty", "Empty database, no routes")
        click("历史"); shot("history-empty", "Empty database")
        click("纪录"); shot("records-empty", "No route or valid results")
        click("设置"); click("路线管理"); shot("routes-empty", "No routes")
    }

    private fun snapshot(name: String): RuntimeSnapshot = TimingScreenTest::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(TimingScreenTest()) as RuntimeSnapshot

    @Test fun timingStates() {
        val route = RouteProfileEntity("work", "上班", "TO_WORK", "v-work", createdAtMillis = 1, updatedAtMillis = 1)
        val home = RouteProfileEntity("home", "下班", "FROM_WORK", "v-home", createdAtMillis = 2, updatedAtMillis = 2)
        val state = mutableStateOf(snapshot("detectingSnapshot"))
        val online = mutableStateOf(false)
        compose.activityRule.scenario.onActivity { it.setContent {
            DeltaTTheme { CompositionLocalProvider(LocalMapFeatureConfig provides MapFeatureConfig(true, online.value, online.value)) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    TimingScreen(rememberNavController(), route, listOf(route, home), state.value, {}, {}, {},
                        elapsedRealtimeNanos = { (state.value.startedAtElapsedNanos ?: 0) + state.value.elapsedNanos }, animateClock = false)
                }
            } }
        } }
        shot("timing-detecting", "Production TimingScreen harness; deterministic detecting snapshot; fallback")
        compose.runOnIdle { state.value = snapshot("waitingSnapshot") }
        shot("timing-waiting", "Waiting A; distance 128m; arrow at A; fallback")
        click("更换路线"); shot("timing-route-dialog", "Waiting route selection"); click("取消")
        click("取消准备"); shot("timing-cancel-dialog", "Cancel preparation confirmation"); click("继续等待")
        compose.runOnIdle { state.value = snapshot("liveSnapshot") }
        shot("timing-live", "S2; frozen elapsed 18:42.60; fallback")
        click("结束本次（成绩无效）"); shot("timing-stop-dialog", "Stop active timing confirmation"); click("继续计时")
        compose.runOnIdle { state.value = state.value.copy(deltaConfidence = DeltaConfidence.HELD, comparisonMessage = "暂时无法匹配 · 保留最近确认值") }
        shot("timing-held", "S2; held comparison; fallback")
        compose.runOnIdle { state.value = snapshot("completedSnapshot") }
        shot("timing-completed", "Completed; 27:58.10; fallback")
        compose.runOnIdle { online.value = true; state.value = snapshot("waitingSnapshot") }
        Thread.sleep(5000); shot("map-online-waiting", "Production AMap renderer; synthetic coordinates; privacy enabled in harness; A coincidence")
        compose.runOnIdle { state.value = snapshot("liveSnapshot") }
        Thread.sleep(2000); shot("map-online-live", "Production AMap renderer; S2 coincidence; frozen 18:42.60")
        compose.runOnIdle { state.value = snapshot("completedSnapshot") }
        Thread.sleep(2000); shot("map-online-completed", "Production AMap renderer; completed; B coincidence")
    }

    @Test fun recordingStates() {
        val route = RouteProfileEntity("work", "上班", "TO_WORK", createdAtMillis = 1, updatedAtMillis = 1)
        val state = mutableStateOf(RuntimeSnapshot())
        compose.activityRule.scenario.onActivity { it.setContent {
            DeltaTTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                BaselineScreen(rememberNavController(), route, state.value, {}, {})
            } }
        } }
        shot("record-idle", "Production BaselineScreen; idle")
        compose.runOnIdle { state.value = snapshot("liveSnapshot").copy(mode = "BASELINE_RECORDING", elapsedNanos = 65_400_000_000) }
        shot("record-active", "Production BaselineScreen; synthetic track; recording 01:05.40")
        compose.runOnIdle { state.value = state.value.copy(mode = null, status = "路线轨迹已录制") }
        shot("record-finished", "Production BaselineScreen; recording finished; mark checkpoints entry")
    }

    @Test fun countdownStates() {
        compose.mainClock.autoAdvance = false
        compose.activityRule.scenario.onActivity { it.setContent {
            DeltaTTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                CountdownScreen({}, {}, {}, animationsEnabled = true, stepMillis = 720, readyMillis = 650)
            } }
        } }
        shot("countdown-3", "Production countdown; virtual clock frozen")
        compose.mainClock.advanceTimeBy(721); shot("countdown-2", "Virtual clock 721ms")
        compose.mainClock.advanceTimeBy(721); shot("countdown-1", "Virtual clock 1442ms")
        compose.mainClock.advanceTimeBy(721); shot("countdown-ready", "Virtual clock 2163ms")
        compose.mainClock.autoAdvance = true
    }

    @Test fun statisticsRichStates() {
        val route = RouteProfileEntity("work", "上班", "TO_WORK", "v-work", createdAtMillis = 1, updatedAtMillis = 1)
        val home = route.copy(id = "home", name = "下班", directionType = "FROM_WORK")
        val rows = mutableStateOf<List<LeaderboardRow>?>(redesignStatisticsRows())
        compose.activityRule.scenario.onActivity { it.setContent {
            DeltaTTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                TelemetryRecordsScreen(listOf(route, home), rows.value, {})
            } }
        } }
        shot("ranking-rich", "Production records harness; redesignStatisticsRows v1; 8 work records, fixed October 2026")
        click("月统计"); shot("stats-rich", "Same eight rows; October monthly; newest five visible")
        compose.onNodeWithTag("records-list").performScrollToIndex(2)
        shot("stats-rich-detail", "Same rows; lower chart and selected trip")
        compose.runOnIdle { rows.value = redesignStatisticsRows().take(1) }
        compose.onNodeWithTag("records-list").performScrollToIndex(0)
        shot("stats-single", "One valid trip; existing mean and fastest GAP")
        compose.runOnIdle { rows.value = emptyList() }
        shot("stats-no-results", "Route exists; no eligible results")
        compose.runOnIdle { rows.value = null }
        shot("stats-loading", "Records loading")
    }
}

internal fun redesignStatisticsRows(): List<LeaderboardRow> {
    val times = listOf(1678.1, 1694.3, 1740.0, 1800.0, 1650.0, 1725.0, 1860.0, 1710.0)
    return times.mapIndexed { i, seconds ->
        LeaderboardRow(sessionId = "design-trip-$i", routeProfileId = "work", routeName = "上班",
            startedWallTimeMillis = java.time.LocalDate.of(2026, 10, 12 + i).atTime(8, 30).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
            totalDurationNanos = (seconds * 1_000_000_000L).toLong(), qualityGrade = "A",
            sector1Nanos = 501_400_000_000, sector2Nanos = 573_700_000_000,
            sector3Nanos = (seconds * 1_000_000_000L).toLong() - 1_075_100_000_000,
            timingEngineVersion = 4, missingCheckpointMask = 0)
    }
}
