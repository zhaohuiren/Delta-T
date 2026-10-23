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

class R2AcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as DeltaTApplication
    private val out get() = File(app.getExternalFilesDir(null), "r2-implementation-after").apply { mkdirs() }
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
        out.listFiles()?.forEach { it.delete() }
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
        click("自定义"); shot("real-date-range", "Native date range picker; imported real history retained")
        click("取消")
        click("月统计"); compose.onNodeWithTag("stats-median").assertTextEquals("29:21.04"); shot("real-month", "work; latest populated month September 2026; chronological five-trip viewport")
        compose.onNodeWithTag("stats-sort").performClick(); click("用时从快到慢")
        shot("real-month-sorted", "September sorted by duration; median and axis unchanged")
        compose.onNodeWithTag("stats-median").assertTextEquals("29:21.04")
        compose.onNodeWithTag("stats-sort").performClick(); click("时间先后")
        compose.onNodeWithTag("trip-bar-$id").performClick()
        shot("real-month-detail", "Real selected journey detail sheet")
        click("查看完整行程")
        compose.onNodeWithText("单次详情").assertExists()
        back()
        compose.onNodeWithTag("stats-median").assertTextEquals("29:21.04")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("stats-median").assertTextEquals("29:21.04")
        compose.onNodeWithTag("records-list").performScrollToIndex(0)
        click("周统计"); shot("real-week", "work; latest populated week; original dates")
        compose.onNodeWithTag("route-option-import-home").performClick()
        shot("real-week-home", "home route; original data; no direction mixing")
        click("月统计"); shot("real-month-home", "Home route; September median; independent from work")
        click("设置"); shot("real-settings", "Real imported data; settings top")
        click("路线管理"); shot("real-routes", "Two actual route names; config reconstruction caveat recorded")
        compose.onAllNodes(hasText("上班") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("编辑计时点").fetchSemanticsNodes().isNotEmpty() }
        shot("real-editor", "Real source track selected for viewing; reconstructed config, not original exact checkpoint positions")
        back()
        compose.onNodeWithText("新建路线").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("周末回家")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        shot("real-new-route", "Actual create-route form with entered name; real imported data retained")
        back()
        runBlocking { app.repository.createRoute("周末回家") }
        shot("real-routes-three", "Two imported real routes plus one explicit empty configuration named 周末回家; no artificial trips")
        back(); click("纪录")
        compose.onAllNodesWithText("周末回家").onFirst().performClick()
        shot("real-custom-empty", "Third custom route selected, no journeys; actual production records page")
        click("设置")
        compose.onNodeWithTag("settings-list").performScrollToIndex(5)
        shot("real-settings-bottom", "Actual settings lower section with real data")
        val track = data.fixes(chosen)
        val ps = data.passes(chosen).map { it.getLong("elapsedRealtimeNanos") }
        val cp = runBlocking { app.repository.checkpointsForRoute("import-work") }
        val slice = track.takeWhile { it.elapsedRealtimeNanos <= ps[2] }.ifEmpty { track.take(1) }
        val ev=chosen.getJSONArray("evaluations").objects().first{it.getString("checkpointVersionId")==chosen.getString("checkpointVersionId")}
        val realSectors=ev.getJSONArray("sectors").objects().map { row -> RuntimeSector(row.getInt("index"),row.getLong("durationNanos"),color=when { (row.longOrNull("monthlyDeltaNanos") ?: 0)<0 -> ResultColor.PURPLE; (row.longOrNull("weeklyDeltaNanos") ?: 0)<0 -> ResultColor.GREEN; (row.longOrNull("weeklyDeltaNanos") ?: row.longOrNull("monthlyDeltaNanos") ?: 0)>0 -> ResultColor.YELLOW; else -> ResultColor.NEUTRAL },monthlyDeltaNanos=row.longOrNull("monthlyDeltaNanos"),weeklyDeltaNanos=row.longOrNull("weeklyDeltaNanos")) }
        val live = RuntimeSnapshot(sessionId=id, routeId="import-work",routeName="上班",mode="TIMED",status="正在计时",
            engineStatus=EngineStatus.SECTOR_2,currentSector=2,startedAtElapsedNanos=ps[0],elapsedNanos=slice.last().elapsedRealtimeNanos-ps[0],
            track=slice,lastFix=slice.last(),routeSourceTrack=track,routeCheckpoints=cp,actualPassElapsedNanos=ps.take(2),
            sectors=realSectors.take(1), comparisonMessage="历史轨迹回放 · 不作为实时定位证明",maxSpeedMps=chosen.doubleOrNull("maxSpeedMps"))
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
        Thread.sleep(10000); shot("real-map-live", "session=$id; original track replay frozen at S2; real roads; GNSS counts unavailable")
        compose.runOnIdle { runtime.value=live.copy(engineStatus=EngineStatus.ARMED,mode="AUTO_ARMING",elapsedNanos=0,track=track.takeWhile { it.elapsedRealtimeNanos<=ps[0] },lastFix=track.minBy { kotlin.math.abs(it.elapsedRealtimeNanos-ps[0]) },actualPassElapsedNanos=emptyList(),routeDetectionDistanceMeters=0.0) }
        Thread.sleep(2000); shot("real-map-waiting", "session=$id; replay at exported A pass; waiting UI; A coincidence")
        compose.runOnIdle { online.value=false }
        shot("real-waiting-offline", "Same waiting state; fallback map")
        compose.runOnIdle { online.value=true; runtime.value=live.copy(engineStatus=EngineStatus.COMPLETED,currentSector=3,elapsedNanos=chosen.getLong("durationNanos"),track=track,lastFix=track.last(),actualPassElapsedNanos=ps,sectors=realSectors,monthlyDeltaNanos=ev.longOrNull("monthlyDeltaNanos"),weeklyDeltaNanos=ev.longOrNull("weeklyDeltaNanos"),deltaConfidence=DeltaConfidence.CONFIRMED) }
        Thread.sleep(2000); shot("real-map-completed", "session=$id; actual total and complete track")
        compose.runOnIdle { online.value=false }
        shot("real-map-offline", "Same real completed track; offline renderer")
        compose.runOnIdle { runtime.value=live }
        shot("real-timing-offline", "Same S2 replay; fallback map")
        compose.activityRule.scenario.onActivity { it.setContent {
            DeltaTTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                BaselineScreen(rememberNavController(), routes.first { r -> r.id=="import-work" }, runtime.value, {}, {})
            } }
        } }
        compose.runOnIdle { runtime.value=RuntimeSnapshot() }
        shot("real-record-idle", "Production recording screen; imported route; recording has not started")
        compose.runOnIdle { runtime.value=live.copy(mode="BASELINE_RECORDING") }
        shot("real-recording", "Production recording screen; real track replay frozen at S2; no live GNSS claim")
        compose.runOnIdle { runtime.value=live.copy(mode=null,status="路线轨迹已录制") }
        shot("real-record-finished", "Production recording screen; real sample session; completion UI replay")
    }
}
