package app.deltat.commute

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.deltat.commute.data.CheckpointEntity
import app.deltat.commute.data.CheckpointPassEntity
import app.deltat.commute.data.CheckpointVersionEntity
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.data.SectorResultEntity
import app.deltat.commute.data.SessionEntity
import app.deltat.commute.data.SessionEvaluationEntity
import app.deltat.commute.data.EvaluationSectorEntity
import app.deltat.commute.data.TrackPointEntity
import app.deltat.commute.domain.EngineStatus
import app.deltat.commute.location.RuntimeBus
import app.deltat.commute.location.RuntimeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class FullAppUiFlowTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    private val application get() = composeRule.activity.application as DeltaTApplication

    @Before fun seedRichUiState() {
        runBlocking { withContext(Dispatchers.IO) { seedRichData() } }
        RuntimeBus.reset()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("一键准备").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun restoreDefaults() {
        RuntimeBus.reset()
        runBlocking {
            withContext(Dispatchers.IO) {
                application.database.clearAllTables()
                application.repository.seedDefaults()
            }
        }
    }

    @Test fun bottomNavigationHistoryDetailRecordsSettingsAndHome() {
        composeRule.onAllNodesWithText("27:58.10").assertCountEquals(1)

        composeRule.onNodeWithText("历史").performClick()
        composeRule.onNodeWithText("历史记录").assertExists()
        composeRule.onNodeWithText("旧计时点").assertExists()
        composeRule.saveScreenshot("history-mock-list.png")

        composeRule.onAllNodes(hasText("27:40.00") and hasClickAction()).onFirst().performClick()
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithText("继续简洁模式").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("继续简洁模式").performClick()
        }
        composeRule.onNodeWithText("旧计时点版本 · 不参与当前纪录").assertExists()
        composeRule.onNodeWithText("设为固定基准").assertDoesNotExist()
        composeRule.saveScreenshot("history-mock-old-detail.png")
        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onAllNodes(hasText("27:58.10") and hasClickAction()).onFirst().performClick()

        composeRule.onNodeWithText("单次详情").assertExists()
        composeRule.saveScreenshot("session-detail-current.png")
        composeRule.onNodeWithText("84.2 km/h").performScrollTo().assertExists()
        composeRule.onNodeWithText("设为固定基准").assertDoesNotExist()
        composeRule.onNodeWithTag("session-detail-list").performScrollToIndex(4)
        composeRule.onNode(hasText("轨迹点", substring = true) and hasText("原始坐标仅保存在应用私有数据库", substring = true))
            .assertExists()
        composeRule.onNodeWithContentDescription("删除记录").performClick()
        composeRule.onNodeWithText("无法单独删除").assertExists()
        composeRule.onNodeWithText("知道了").performClick()
        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onNodeWithText("纪录").performClick()
        composeRule.onNodeWithText("个人纪录").assertExists()
        composeRule.onNodeWithText("全部").performClick()
        composeRule.onNodeWithText("P1").assertExists()
        composeRule.onNodeWithText("筛选范围 PB").assertExists()
        composeRule.onNodeWithTag("leaderboard-P2").performScrollTo().performClick()
        composeRule.onNodeWithText("单次详情").assertExists()
        composeRule.onNodeWithContentDescription("删除记录").performClick()
        composeRule.onNodeWithText("确认删除").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("个人纪录").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("period-全部").assertIsSelected()
        composeRule.onNodeWithTag("records-tab-LEADERBOARD").assertIsSelected()

        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("锁屏继续计时检查").assertExists()
        composeRule.onNodeWithTag("settings-list").performScrollToIndex(10)
        composeRule.onNodeWithText("数据导出").assertExists()
        composeRule.onNodeWithText("JSON 诊断包").assertExists()
        composeRule.onNodeWithText("CSV").assertExists()
        composeRule.saveScreenshot("settings-mock-export.png")
        composeRule.onNodeWithTag("settings-list").performScrollToIndex(11)
        composeRule.onNode(hasText("Delta T ${BuildConfig.VERSION_NAME}", substring = true)).assertExists()

        composeRule.onNodeWithText("首页").performClick()
        composeRule.onNodeWithText("准备出发").assertExists()
    }

    @Test fun nativeMapRepeatedReturnPreservesRecordsAndReleasesRenderer() {
        composeRule.activityRule.scenario.onActivity { androidx.lifecycle.ViewModelProvider(it)[app.deltat.commute.ui.AppViewModel::class.java].agreeToAmapPrivacy() }
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("纪录").performClick()
        composeRule.onNodeWithText("全部").performClick()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val output = java.io.File(application.getExternalFilesDir(null), "native-return").apply { mkdirs() }
        fun gesture(cancel: Boolean) {
            val start = android.os.SystemClock.uptimeMillis()
            val positions = if (cancel) listOf(1f, 45f, 100f, 200f, 100f, 20f, 1f) else listOf(1f, 45f, 100f, 200f, 350f, 500f)
            positions.forEachIndexed { index, x ->
                val action = when (index) { 0 -> android.view.MotionEvent.ACTION_DOWN; positions.lastIndex -> if (cancel) android.view.MotionEvent.ACTION_CANCEL else android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                val event = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, x, 1000f, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle(); Thread.sleep(45)
            }
        }
        repeat(6) { round ->
            composeRule.onNodeWithTag("leaderboard-P2").performScrollTo().performClick()
            if (composeRule.onAllNodesWithText("同意并启用").fetchSemanticsNodes().isNotEmpty()) composeRule.onNodeWithText("同意并启用").performClick()
            composeRule.onNodeWithTag("route-map").assertExists()
            Thread.sleep(500)
            if (round == 4) {
                gesture(true)
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("route-map").assertExists()
            }
            if (round == 5) gesture(false)
            else if (round % 2 == 0) {
                val bounds = composeRule.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInWindow
                val now = android.os.SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                    android.view.MotionEvent.obtain(now, now + action * 20L, action, bounds.center.x, bounds.center.y, 0).also {
                        instrumentation.uiAutomation.injectInputEvent(it, true); it.recycle()
                    }
                }
            } else instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            repeat(5) { frame ->
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    java.io.File(output, "return-$round-$frame.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                Thread.sleep(40)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("period-全部").assertIsSelected()
            composeRule.onNodeWithTag("leaderboard-P2").assertExists()
            composeRule.onNodeWithTag("route-map").assertDoesNotExist()
        }
    }

    @Test fun activityRecreationPreservesRecordsFilterRouteAndDetailBackStack() {
        composeRule.onNodeWithText("纪录").performClick()
        composeRule.onNodeWithText("全部").performClick()
        composeRule.onNodeWithTag("period-全部").assertIsSelected()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("个人纪录").assertExists()
        composeRule.onNodeWithTag("period-全部").assertIsSelected()
        composeRule.onNodeWithTag("route-option-work").assertIsSelected()
        composeRule.onNodeWithTag("leaderboard-P2").performScrollTo().performClick()
        composeRule.onNodeWithText("单次详情").assertExists()
        composeRule.onNodeWithText("28:14.30").assertExists()
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("单次详情").assertExists()
        composeRule.onNodeWithText("28:14.30").assertExists()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onNodeWithTag("period-全部").assertIsSelected()
        composeRule.onNodeWithTag("leaderboard-P2").assertTextContains("28:14.30")
    }

    @Test fun routeLibraryDeleteGuardNewRouteAndEditorNavigation() {
        RuntimeBus.update {
            RuntimeSnapshot(
                sessionId = "active", routeId = "work", routeName = "上班",
                mode = "TIMED", status = "正在计时", engineStatus = EngineStatus.SECTOR_1,
            )
        }
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("路线管理").performClick()
        composeRule.onNodeWithText("路线库").assertExists()
        composeRule.onNodeWithText("正在记录，不可删除").assertExists()
        composeRule.saveScreenshot("routes-mock-library.png")
        composeRule.onNodeWithContentDescription("删除上班").assertIsNotEnabled()

        RuntimeBus.reset()
        composeRule.onNodeWithContentDescription("删除上班").assertIsEnabled().performClick()
        composeRule.onNodeWithText("删除“上班”？").assertExists()
        composeRule.onNode(hasText("将同时删除 3 条历史记录", substring = true)).assertExists()
        composeRule.onNodeWithText("取消").performClick()

        composeRule.onNodeWithContentDescription("新建").performClick()
        composeRule.onNodeWithText("新建路线").assertExists()
        composeRule.onNode(hasSetTextAction()).performTextInput("周末路线")
        composeRule.onNodeWithText("下一步：录制路线").assertIsEnabled()
        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onAllNodes(hasText("上班") and hasClickAction()).onFirst().performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("编辑计时点").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithContentDescription("放大").performClick()
        composeRule.onNodeWithText("1.5×").assertExists()
        composeRule.onNodeWithContentDescription("适应全图").performClick()
        composeRule.onNodeWithText("1.0×").assertExists()
        composeRule.onAllNodes(hasText("S1") and hasClickAction()).onFirst().performClick()
        composeRule.onNodeWithText("当前 S1").assertExists()
        composeRule.saveScreenshot("checkpoint-editor-current.png")
        composeRule.onNodeWithText("5m ▶").performClick()
    }

    @Test fun emptyRoutesHistoryAndRecordsHaveUsefulNextSteps() {
        runBlocking { withContext(Dispatchers.IO) { application.database.clearAllTables() } }
        RuntimeBus.reset()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("先建立路线").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("先建立路线").assertExists()
        composeRule.onNodeWithText("历史").performClick()
        composeRule.onNodeWithText("还没有行程").assertExists()
        composeRule.onNodeWithText("纪录").performClick()
        composeRule.onNodeWithText("请先建立路线并完成有效行程").assertExists()
        composeRule.onNodeWithText("首页").performClick()
        composeRule.onNodeWithText("先建立路线").assertExists()
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("路线管理").performClick()
        composeRule.onNodeWithText("路线库").assertExists()
        composeRule.onNodeWithText("还没有路线").assertExists()
    }

    private suspend fun seedRichData() {
        application.getSharedPreferences("bootstrap", 0).edit().putBoolean("defaults_seeded", true).commit()
        application.database.clearAllTables()
        val dao = application.database.dao()
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val workBestTime = millis(monday.plusDays(2), 18, 30)
        val workOlderTime = millis(monday.plusDays(2), 8, 30)
        val oldVersionTime = millis(monday.plusDays(3), 8, 30)
        val homeTime = millis(monday.plusDays(1), 18, 30)

        dao.insertRoute(RouteProfileEntity("work", "上班", "TO_WORK", "v-work-2", "work-best", 1, 2))
        dao.insertRoute(RouteProfileEntity("home", "下班", "FROM_WORK", "v-home", "home-run", 2, 2))
        dao.insertVersion(CheckpointVersionEntity("v-work-1", "work", 1, "work-old", 1, false, "旧版"))
        dao.insertVersion(CheckpointVersionEntity("v-work-2", "work", 2, "work-best", 2, true, "当前版"))
        dao.insertVersion(CheckpointVersionEntity("v-home", "home", 1, "home-run", 2, true, "当前版"))
        dao.insertCheckpoints(checkpoints("v-work-2", "work"))
        dao.insertCheckpoints(checkpoints("v-home", "home"))

        dao.insertSession(session("work-best", "work", "v-work-2", workBestTime, 1_678_100_000_000, 84.2 / 3.6))
        dao.insertSession(session("work-older", "work", "v-work-2", workOlderTime, 1_694_300_000_000, 82.4 / 3.6))
        dao.insertSession(session("work-old", "work", "v-work-1", oldVersionTime, 1_660_000_000_000, 80.0 / 3.6))
        dao.insertSession(session("home-run", "home", "v-home", homeTime, 1_800_000_000_000, 76.0 / 3.6))
        dao.insertEvaluation(evaluation("eval-work-best", "work-best", "v-work-2", workBestTime, 1_678_100_000_000, -16_200_000_000, -12_300_000_000))
        dao.insertEvaluation(evaluation("eval-work-older", "work-older", "v-work-2", workOlderTime, 1_694_300_000_000, 16_200_000_000, 16_200_000_000))
        dao.insertEvaluation(evaluation("eval-home-run", "home-run", "v-home", homeTime, 1_800_000_000_000, null, null))

        val elapsed = (0 until 40).map { 8_000_000_000 + it * 43_000_000_000L }
        (0 until 40).forEach { index -> dao.insertTrackPoint(trackPoint("work-best", index, workBestTime, elapsed[index])) }
        listOf(0, 13, 26, 39).forEachIndexed { order, pointIndex ->
            dao.insertPass(
                CheckpointPassEntity(
                    "pass-$order", "work-best", "work-$order", order, elapsed[pointIndex],
                    31.20 + pointIndex * .00022, 121.40 + pointIndex * .00028,
                    "LINE_CROSSING", 2.0, 120, "A",
                ),
            )
        }
        val sectors = listOf(
            SectorResultEntity("work-best", 0, 501_400_000_000, null, null, -5_100_000_000, "PURPLE", true),
            SectorResultEntity("work-best", 1, 573_700_000_000, null, null, -8_200_000_000, "GREEN", true),
            SectorResultEntity("work-best", 2, 603_000_000_000, null, null, -2_900_000_000, "GREEN", true),
        )
        for (sector in sectors) dao.insertSector(sector)
        sectors.forEach { sector ->
            dao.insertEvaluationSector(
                EvaluationSectorEntity(
                    evaluationId = "eval-work-best",
                    sectorIndex = sector.sectorIndex,
                    durationNanos = sector.durationNanos,
                    monthlyDeltaNanos = sector.monthlyDeltaNanos,
                    weeklyDeltaNanos = sector.weeklyDeltaNanos,
                    resultColorAtCompletion = sector.resultColorAtCompletion,
                    recordQualified = true,
                ),
            )
        }
    }

    private fun checkpoints(version: String, prefix: String) = listOf(0, 13, 26, 39).mapIndexed { order, point ->
        CheckpointEntity(
            "$prefix-$order", version, order, listOf("A", "S1", "S2", "B")[order],
            31.20 + point * .00022, 121.40 + point * .00028,
            35.0, 45.0, 100.0, 50.0, null,
        )
    }

    private fun session(id: String, route: String, version: String, started: Long, duration: Long, maxSpeed: Double) = SessionEntity(
        id = id, routeProfileId = route, checkpointVersionId = version, mode = "TIMED", status = "COMPLETED",
        referenceSessionIdSnapshot = null, startedWallTimeMillis = started, endedWallTimeMillis = started + duration / 1_000_000,
        bootCount = 1, startElapsedNanos = 8_000_000_000, finishElapsedNanos = 8_000_000_000 + duration,
        totalDurationNanos = duration, validity = "VALID", qualityGrade = "A",
        appVersion = "0.2.0-test", deviceModel = "UI fixture", androidVersion = "16",
        maxSpeedMps = maxSpeed, maxSpeedElapsedNanos = 782_000_000_000,
        maxSpeedWallTimeMillis = started + 774_000, createdAtMillis = started,
    )

    private fun evaluation(
        id: String,
        sessionId: String,
        versionId: String,
        createdAt: Long,
        duration: Long,
        monthlyDelta: Long?,
        weeklyDelta: Long?,
    ) = SessionEvaluationEntity(
        id = id,
        sessionId = sessionId,
        checkpointVersionId = versionId,
        status = "VALID",
        totalDurationNanos = duration,
        qualityGrade = "A",
        recordQualified = true,
        monthlyDeltaNanos = monthlyDelta,
        weeklyDeltaNanos = weeklyDelta,
        timingEngineVersion = app.deltat.commute.domain.DefaultTimingEngine.TIMING_ENGINE_VERSION,
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    )

    private fun trackPoint(session: String, index: Int, started: Long, elapsed: Long) = TrackPointEntity(
        sessionId = session, sequence = index.toLong(),
        latitude = 31.20 + index * .00022, longitude = 121.40 + index * .00028,
        elapsedRealtimeNanos = elapsed, wallTimeMillis = started + index * 43_000,
        accuracyMeters = 6.0, speedMps = 18.0, speedAccuracyMps = 1.0,
        bearingDegrees = 45.0, bearingAccuracy = 3.0, provider = "gps", isMock = false, usedForTiming = true,
    )

    private fun millis(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
