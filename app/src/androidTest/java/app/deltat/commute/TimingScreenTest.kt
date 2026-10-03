package app.deltat.commute

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.domain.DeltaConfidence
import app.deltat.commute.domain.DeltaReferenceType
import app.deltat.commute.domain.EngineStatus
import app.deltat.commute.domain.GnssHealth
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.ResultColor
import app.deltat.commute.domain.Checkpoint
import app.deltat.commute.location.RuntimeSector
import app.deltat.commute.location.RuntimeSnapshot
import app.deltat.commute.ui.DeltaTTheme
import app.deltat.commute.ui.TimingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File

class TimingScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun timingUiRendersWaitingLiveHeldAndCompletedStates() {
        val route = RouteProfileEntity(
            id = "work", name = "上班", directionType = "TO_WORK",
            activeCheckpointVersionId = "v-work", createdAtMillis = 1, updatedAtMillis = 1,
        )
        val otherRoute = RouteProfileEntity(
            id = "home", name = "下班", directionType = "FROM_WORK",
            activeCheckpointVersionId = "v-home", createdAtMillis = 2, updatedAtMillis = 2,
        )
        val runtime = mutableStateOf(detectingSnapshot())
        var fakeElapsedRealtimeNanos = 0L

        composeRule.setContent {
            DeltaTTheme {
                TimingScreen(
                    nav = rememberNavController(), route = route, routes = listOf(route, otherRoute),
                    runtime = runtime.value, start = {}, stop = {}, switchRoute = {},
                    elapsedRealtimeNanos = { fakeElapsedRealtimeNanos },
                    animateClock = false,
                )
            }
        }

        composeRule.onNodeWithText("等待有效定位").assertExists()
        composeRule.onNodeWithText("获得有效定位后，自动识别最近路线").assertExists()
        saveScreenshot("timing-mock-detecting.png")

        composeRule.runOnIdle { runtime.value = waitingSnapshot() }
        composeRule.onNodeWithText("等待驶过起点 A").assertExists()
        composeRule.onNodeWithText("确认驶过 A 后自动开始计时").assertExists()
        composeRule.onNodeWithText("128 m").assertExists()
        composeRule.onNodeWithText("更换路线").assertExists()
        composeRule.onNodeWithText("取消准备").performClick()
        composeRule.onNodeWithText("取消准备？").assertExists()
        composeRule.onNodeWithText("继续等待").performClick()
        composeRule.onNodeWithText("更换路线").performClick()
        composeRule.onNodeWithText("更换识别路线").assertExists()
        composeRule.onNodeWithText("取消").performClick()
        saveScreenshot("timing-mock-waiting-a.png")

        composeRule.runOnIdle {
            runtime.value = liveSnapshot()
            fakeElapsedRealtimeNanos = requireNotNull(runtime.value.startedAtElapsedNanos) + runtime.value.elapsedNanos
        }
        composeRule.onNodeWithText("上班 · LIVE").assertExists()
        composeRule.onNodeWithText("18:42.60").assertExists()
        composeRule.onNodeWithText("周 Δ −00:08.40").assertExists()
        composeRule.onNodeWithText("月 Δ +00:02.40").assertExists()
        composeRule.onNodeWithText("有望本周最佳 · 实时估算").assertExists()
        composeRule.onNodeWithText("GPS 良好 · 11/20").assertExists()
        composeRule.onNodeWithTag("abstract-route-map").assertExists()
        composeRule.onNodeWithTag("sector-card-S1").assertExists()
        composeRule.onNodeWithTag("sector-card-S2").assertExists()
        composeRule.onNodeWithTag("sector-card-S3").assertExists()
        composeRule.onNodeWithText("未开始").assertExists()
        val liveStatusBounds = composeRule.onNodeWithText("计时中").fetchSemanticsNode().boundsInRoot
        val liveSectorBounds = composeRule.onNodeWithTag("sector-card-S2").fetchSemanticsNode().boundsInRoot
        assertTrue("分段状态文字必须完整位于卡片内", liveStatusBounds.bottom < liveSectorBounds.bottom)
        composeRule.onNodeWithText("82.4").assertExists()
        composeRule.onNodeWithText("结束本次（成绩无效）").performClick()
        composeRule.onNodeWithText("结束本次计时？").assertExists()
        composeRule.onNodeWithText("继续计时").performClick()
        saveScreenshot("timing-mock-live-s2.png")

        composeRule.runOnIdle {
            runtime.value = liveSnapshot().copy(
                liveDeltaNanos = -3_100_000_000,
                deltaConfidence = DeltaConfidence.HELD,
                comparisonMessage = "暂时无法匹配 · 保留最近确认值",
            )
        }
        composeRule.onNodeWithText("暂时无法匹配 · 保留最近确认值").assertExists()

        composeRule.runOnIdle { runtime.value = completedSnapshot() }
        composeRule.onNodeWithText("COMPLETE").assertExists()
        composeRule.onAllNodesWithText("已完成").assertCountEquals(4)
        composeRule.onNodeWithText("27:58.10").assertExists()
        composeRule.onNodeWithText("月 Δ −00:16.20").assertExists()
        composeRule.onNodeWithText("周 Δ −00:12.30").assertExists()
        composeRule.onNodeWithText("本月最快 · 已确认").assertExists()
        composeRule.onNodeWithText("本周最快 · 已确认").assertExists()
        composeRule.onNodeWithText("84.2").assertExists()
        composeRule.onNodeWithText("查看本次详情").assertExists()
        saveScreenshot("timing-mock-completed.png")
    }

    @Test fun longTrackKeepsTimingAndMapResponsiveAtDisplayWindowLimit() {
        val route = RouteProfileEntity(
            id = "work", name = "上班", directionType = "TO_WORK",
            activeCheckpointVersionId = "v-work", createdAtMillis = 1, updatedAtMillis = 1,
        )
        val points = track(pointCount = 4_000)
        val runtime = mutableStateOf(liveSnapshot().copy(
            track = points.take(100),
            routeSourceTrack = points,
            referenceTrack = points.map { it.copy(latitude = it.latitude + .00003) },
            lastFix = points[99],
        ))

        composeRule.setContent {
            DeltaTTheme {
                TimingScreen(
                    nav = rememberNavController(), route = route, routes = listOf(route),
                    runtime = runtime.value, start = {}, stop = {}, switchRoute = {},
                    elapsedRealtimeNanos = {
                        requireNotNull(runtime.value.startedAtElapsedNanos) + runtime.value.elapsedNanos
                    },
                    animateClock = false,
                )
            }
        }

        (200..4_000 step 100).forEachIndexed { index, count ->
            composeRule.runOnIdle {
                runtime.value = runtime.value.copy(
                    track = points.take(count),
                    lastFix = points[count - 1],
                    elapsedNanos = 1_122_600_000_000L + (index + 1) * 1_000_000_000L,
                )
            }
        }

        composeRule.onNodeWithText("19:21.60").assertExists()
        composeRule.onNodeWithTag("abstract-route-map").assertExists()
        composeRule.onNodeWithTag("sector-card-S2").assertExists()
        saveScreenshot("timing-mock-long-track-4000.png")
    }

    @Test fun largeFontKeepsSectorStatusAndMapInsideReadableLayout() {
        val route = RouteProfileEntity(
            id = "work", name = "上班", directionType = "TO_WORK",
            activeCheckpointVersionId = "v-work", createdAtMillis = 1, updatedAtMillis = 1,
        )
        val runtime = liveSnapshot()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                DeltaTTheme {
                    TimingScreen(
                        nav = rememberNavController(), route = route, routes = listOf(route),
                        runtime = runtime, start = {}, stop = {}, switchRoute = {},
                        elapsedRealtimeNanos = { requireNotNull(runtime.startedAtElapsedNanos) + runtime.elapsedNanos },
                        animateClock = false,
                    )
                }
            }
        }

        composeRule.onNodeWithText("计时中").assertIsDisplayed()
        val mapBounds = composeRule.onNodeWithTag("abstract-route-map").fetchSemanticsNode().boundsInRoot
        val aspectRatio = mapBounds.width / mapBounds.height
        assertTrue("地图不能再次退化为横向扁条", aspectRatio in 1.00f..1.03f)
    }

    @Test fun gpsCountsAreVisibleAndRefreshThroughDetectingWaitingAndLiveStates() {
        val route = RouteProfileEntity("gps-ui", "上班", "TO_WORK", createdAtMillis = 1, updatedAtMillis = 1)
        val state = mutableStateOf(detectingSnapshot().copy(gnssHealth = GnssHealth()))
        composeRule.setContent { DeltaTTheme {
            TimingScreen(rememberNavController(), route, listOf(route), state.value, {}, {}, {}, animateClock = false)
        } }
        composeRule.onNodeWithText("GPS 搜星中 · --/--").assertExists()
        composeRule.runOnIdle { state.value = waitingSnapshot().copy(gnssHealth = GnssHealth(12, 9)) }
        composeRule.onNodeWithText("GPS 良好 · 9/12").assertExists()
        composeRule.runOnIdle { state.value = liveSnapshot().copy(gnssHealth = GnssHealth(12, 9)) }
        composeRule.onNodeWithText("GPS 良好 · 9/12").assertExists()
        composeRule.runOnIdle { state.value = state.value.copy(gnssHealth = GnssHealth(128, 99)) }
        val counts = composeRule.onNodeWithText("GPS 良好 · 99/128").fetchSemanticsNode().boundsInRoot
        assertTrue(counts.right <= composeRule.onRoot().fetchSemanticsNode().boundsInRoot.right)
        composeRule.onNodeWithText("GPS 良好 · 9/12").assertDoesNotExist()
        composeRule.runOnIdle { state.value = state.value.copy(gnssHealth = GnssHealth(satelliteStatusReceived = true)) }
        composeRule.onNodeWithText("GPS 搜星中 · 0/0").assertExists()
        composeRule.runOnIdle { state.value = state.value.copy(gnssHealth = GnssHealth(12, 9, locationEnabled = false)) }
        composeRule.onNodeWithText("定位已关闭").assertExists()
        composeRule.onNodeWithText("GPS 良好 · 9/12").assertDoesNotExist()
    }

    private fun detectingSnapshot() = RuntimeSnapshot(
        mode = "AUTO_ARMING", status = "正在识别最近路线",
        gnssHealth = goodGnss(),
    )

    private fun waitingSnapshot() = RuntimeSnapshot(
        sessionId = "waiting", routeId = "work", routeName = "上班",
        routeDetectionDistanceMeters = 128.0, mode = "AUTO_ARMING", status = "等待驶过起点 A",
        engineStatus = EngineStatus.ARMED, gnssHealth = goodGnss(), track = track().take(3),
        routeSourceTrack = track(), routeCheckpoints = checkpoints(), lastFix = track().first(),
    )

    private fun liveSnapshot(): RuntimeSnapshot {
        val points = track()
        return RuntimeSnapshot(
            sessionId = "live", routeId = "work", routeName = "上班", mode = "TIMED",
            status = "正在计时", engineStatus = EngineStatus.SECTOR_2,
            startedAtElapsedNanos = points.first().elapsedRealtimeNanos,
            elapsedNanos = 1_122_600_000_000,
            currentSector = 2,
            sectors = listOf(RuntimeSector(0, 511_200_000_000, 1_000_000_000, ResultColor.GREEN, 1_000_000_000, -3_100_000_000)),
            liveDeltaNanos = -8_400_000_000,
            monthlyDeltaNanos = 2_400_000_000,
            weeklyDeltaNanos = -8_400_000_000,
            currentSectorMonthlyDeltaNanos = 1_500_000_000,
            currentSectorWeeklyDeltaNanos = -2_100_000_000,
            liveColor = ResultColor.GREEN,
            currentSectorColor = ResultColor.GREEN,
            deltaConfidence = DeltaConfidence.ESTIMATED,
            deltaReferenceType = DeltaReferenceType.WEEKLY_BEST,
            maxSpeedMps = 82.4 / 3.6,
            maxSpeedElapsedNanos = points[18].elapsedRealtimeNanos,
            referenceTrack = points.map { it.copy(latitude = it.latitude + .00003) },
            routeSourceTrack = points,
            routeCheckpoints = checkpoints(),
            checkpointPassElapsedNanos = listOf(0, 13, 26, 39).map { points[it].elapsedRealtimeNanos },
            actualPassElapsedNanos = listOf(points[0].elapsedRealtimeNanos, points[13].elapsedRealtimeNanos),
            gnssHealth = goodGnss(), track = points.take(27), lastFix = points[26],
            comparisonMessage = "实时估算 · 每秒同位置对比",
        )
    }

    private fun completedSnapshot(): RuntimeSnapshot {
        val points = track()
        return liveSnapshot().copy(
            sessionId = "complete", status = "完成", engineStatus = EngineStatus.COMPLETED,
            elapsedNanos = 1_678_100_000_000, currentSector = 3,
            sectors = listOf(
                RuntimeSector(0, 501_400_000_000, -5_100_000_000, ResultColor.PURPLE, -5_100_000_000, -4_000_000_000),
                RuntimeSector(1, 573_700_000_000, -8_200_000_000, ResultColor.GREEN, 2_000_000_000, -8_200_000_000),
                RuntimeSector(2, 603_000_000_000, -2_900_000_000, ResultColor.GREEN, 1_000_000_000, -2_900_000_000),
            ),
            liveDeltaNanos = -16_200_000_000,
            monthlyDeltaNanos = -16_200_000_000,
            weeklyDeltaNanos = -12_300_000_000,
            liveColor = ResultColor.PURPLE,
            deltaConfidence = DeltaConfidence.CONFIRMED,
            comparisonMessage = "确认差值 · 已通过计时点",
            maxSpeedMps = 84.2 / 3.6,
            maxSpeedElapsedNanos = points[18].elapsedRealtimeNanos,
            maxSpeedWallTimeMillis = 1_752_890_792_000,
            actualPassElapsedNanos = listOf(0, 13, 26, 39).map { points[it].elapsedRealtimeNanos },
            track = points, lastFix = points.last(),
        )
    }

    private fun goodGnss() = GnssHealth(visibleGps = 12, usedGps = 6, visibleBeidou = 8, usedBeidou = 5, label = "GPS 良好")

    private fun checkpoints() = listOf(0, 13, 26, 39).mapIndexed { order, pointIndex ->
        val point = track()[pointIndex]
        Checkpoint("cp$order", order, listOf("A", "S1", "S2", "B")[order], point.latitude, point.longitude)
    }

    private fun track(pointCount: Int = 40): List<LocationFix> = (0 until pointCount).map { index ->
        LocationFix(
            latitude = 31.20 + index * .00022 + kotlin.math.sin(index / 5.0) * .00008,
            longitude = 121.40 + index * .00028,
            elapsedRealtimeNanos = 8_000_000_000 + index * 43_000_000_000L,
            wallTimeMillis = 1_752_890_000_000 + index * 43_000,
            accuracyMeters = 6.0,
            speedMetersPerSecond = 18.0,
        )
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), name)
        file.outputStream().use { output ->
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/$name").close()
    }
}
