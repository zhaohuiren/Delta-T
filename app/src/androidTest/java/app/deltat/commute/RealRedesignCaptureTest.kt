package app.deltat.commute

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.domain.*
import app.deltat.commute.location.*
import app.deltat.commute.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import java.io.File

class RealRedesignCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as DeltaTApplication
    private val out get() = File(app.getExternalFilesDir(null), "redesign-real-20261022").apply { mkdirs() }
    private fun shot(id: String, state: String) {
        compose.waitForIdle(); Thread.sleep(350)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(out, "$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        File(out, "$id.txt").writeText(compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString())
        File(out, "captures.jsonl").appendText(JSONObject().put("id", id).put("state", state)
            .put("capturedAt", java.time.OffsetDateTime.now().toString()).put("source", "diagnostics-v2 f1dfa83f; original dates/durations/tracks")
            .put("config_limit", "Route config reconstructed from nearest raw point to exported passes; timing oracle not available")
            .put("version", BuildConfig.VERSION_NAME).toString()+"\n")
    }
    private fun click(t: String) = compose.onNodeWithText(t).performClick()
    private fun back() = compose.onNodeWithContentDescription("返回").performClick()
    @Test fun importAndCaptureActualApp() {
        Thread.sleep(2500)
        val data = RealRedesignData(File(app.getExternalFilesDir(null), "redesign-input.json"))
        runBlocking { withContext(Dispatchers.IO) { data.importInto(app) } }
        RuntimeBus.reset()
        compose.waitUntil(15000) { compose.onAllNodesWithText("一键准备").fetchSemanticsNodes().isNotEmpty() }
        val routes = runBlocking { app.repository.routes.first() }
        val rows = runBlocking { app.repository.sessions.first() }
        Assert.assertEquals(92, rows.size)
        val lb = runBlocking { app.repository.leaderboard.first() }
        File(out, "import-summary.json").writeText(JSONObject().put("sessionCount", rows.size).put("eligibleLeaderboardCount", lb.size)
            .put("routeCount", routes.size).put("trackCount", 170433).put("datesShifted", false)
            .put("limitations", "Export omits route source identity, exact checkpoint geometry, per-point wall time and GNSS counts; route config reconstructed only for visualization; per-point wall time=0 unknown; evaluation quality inherited from exported session quality").toString(2))
        shot("real-home", "92 imported trips; no current-month trips; actual home")
        click("历史"); shot("real-history", "History newest first; actual interrupted and cancelled records visible")
        val chosen = data.sample(); val id = chosen.getString("id")
        compose.onNodeWithTag("history-card-$id").performScrollTo().performClick()
        if (compose.onAllNodesWithText("继续简洁模式").fetchSemanticsNodes().isNotEmpty()) click("继续简洁模式")
        shot("real-detail", "session=$id; imported real duration and raw track; fallback map")
        compose.onNodeWithTag("session-detail-list").performScrollToIndex(4)
        shot("real-detail-bottom", "session=$id; detail lower section")
        back(); click("纪录"); click("全部")
        shot("real-ranking", "work route; all dates; $id source sample; actual eligible records")
        click("月统计"); shot("real-month", "work; latest populated month September 2026; chronological five-trip viewport")
        compose.onNodeWithTag("records-list").performScrollToIndex(2)
        shot("real-month-detail", "Same September period; selected trip detail lower section")
        compose.onNodeWithTag("records-list").performScrollToIndex(0)
        click("周统计"); shot("real-week", "work; latest populated week; original dates")
        compose.onNodeWithTag("route-option-import-home").performClick()
        shot("real-week-home", "home route; original data; no direction mixing")
        click("设置"); shot("real-settings", "Real imported data; settings top")
        click("路线管理"); shot("real-routes", "Two actual route names; config reconstruction caveat recorded")
        compose.onAllNodes(hasText("上班") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("编辑计时点").fetchSemanticsNodes().isNotEmpty() }
        shot("real-editor", "Real source track selected for viewing; reconstructed config, not original exact checkpoint positions")
        val track = data.fixes(chosen)
        val ps = data.passes(chosen).map { it.getLong("elapsedRealtimeNanos") }
        val cp = runBlocking { app.repository.checkpointsForRoute("import-work") }
        val slice = track.takeWhile { it.elapsedRealtimeNanos <= ps[2] }.ifEmpty { track.take(1) }
        val live = RuntimeSnapshot(sessionId=id, routeId="import-work",routeName="上班",mode="TIMED",status="正在计时",
            engineStatus=EngineStatus.SECTOR_2,currentSector=2,startedAtElapsedNanos=ps[0],elapsedNanos=slice.last().elapsedRealtimeNanos-ps[0],
            track=slice,lastFix=slice.last(),routeSourceTrack=track,routeCheckpoints=cp,actualPassElapsedNanos=ps.take(2),
            comparisonMessage="历史轨迹回放 · 不作为实时定位证明",maxSpeedMps=chosen.doubleOrNull("maxSpeedMps"))
        val runtime = mutableStateOf(live)
        val online = mutableStateOf(true)
        compose.activityRule.scenario.onActivity { it.setContent {
            DeltaTTheme { CompositionLocalProvider(LocalMapFeatureConfig provides MapFeatureConfig(true, true, online.value)) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    TimingScreen(rememberNavController(), routes.first { r -> r.id=="import-work" }, routes, runtime.value, {}, {}, {},
                        elapsedRealtimeNanos={ (runtime.value.startedAtElapsedNanos?:0)+runtime.value.elapsedNanos },animateClock=false)
                }
            } }
        } }
        Thread.sleep(5000); shot("real-map-live", "session=$id; original track replay frozen at S2; real roads; GNSS counts unavailable")
        compose.runOnIdle { runtime.value=live.copy(engineStatus=EngineStatus.ARMED,mode="AUTO_ARMING",elapsedNanos=0,track=track.takeWhile { it.elapsedRealtimeNanos<=ps[0] },lastFix=track.minBy { kotlin.math.abs(it.elapsedRealtimeNanos-ps[0]) },actualPassElapsedNanos=emptyList(),routeDetectionDistanceMeters=0.0) }
        Thread.sleep(2000); shot("real-map-waiting", "session=$id; replay at exported A pass; waiting UI; A coincidence")
        compose.runOnIdle { runtime.value=live.copy(engineStatus=EngineStatus.COMPLETED,currentSector=3,elapsedNanos=chosen.getLong("durationNanos"),track=track,lastFix=track.last(),actualPassElapsedNanos=ps) }
        Thread.sleep(2000); shot("real-map-completed", "session=$id; actual total and complete track")
        compose.runOnIdle { online.value=false }
        shot("real-map-offline", "Same real completed track; offline renderer")
    }
}
