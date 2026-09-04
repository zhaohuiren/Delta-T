package app.deltat.commute

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.data.SessionListRow
import app.deltat.commute.data.LeaderboardRow
import app.deltat.commute.ui.DeltaTTheme
import app.deltat.commute.ui.TelemetryRecordsScreen
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.io.File

class TelemetryRecordsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun populatedRecordsKeepCommuteOrderAndRenderMonthlyF1Ranking() {
        val monthStart = LocalDate.now().withDayOfMonth(1)
        val routes = listOf(
            route("home", "下班", "FROM_WORK", "v-home"),
            route("work", "上班", "TO_WORK", "v-work"),
        )
        val sessions = listOf(
            session("run-1", monthStart, 0, 28 * 60 + 42.7),
            session("run-2", monthStart, 0, 28 * 60 + 14.3),
            session("run-3", monthStart, 0, 27 * 60 + 58.1),
        )
        val leaderboard = sessions.map { row ->
            LeaderboardRow(
                sessionId = row.id,
                routeProfileId = row.routeProfileId,
                routeName = row.routeName,
                startedWallTimeMillis = row.startedWallTimeMillis,
                totalDurationNanos = requireNotNull(row.totalDurationNanos),
                qualityGrade = row.qualityGrade,
                sector1Nanos = null,
                sector2Nanos = null,
                sector3Nanos = null,
                timingEngineVersion = row.timingEngineVersion,
                missingCheckpointMask = row.missingCheckpointMask,
            )
        }

        var openedSession: String? = null
        composeRule.setContent {
            DeltaTTheme { TelemetryRecordsScreen(routes, leaderboard, onOpenSession = { openedSession = it }) }
        }

        val workLeft = composeRule.onNodeWithText("上班").fetchSemanticsNode().boundsInRoot.center.x
        val homeLeft = composeRule.onNodeWithText("下班").fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("上班应位于下班左侧", workLeft < homeLeft)
        composeRule.onNodeWithText("本月").assertExists()
        composeRule.onNodeWithText("排行榜").assertExists()
        composeRule.onNodeWithTag("route-option-work").assertIsSelected()
        composeRule.onNodeWithTag("route-option-home").assertIsNotSelected()
        composeRule.onNodeWithTag("records-tab-LEADERBOARD").assertIsSelected()
        composeRule.onNodeWithTag("period-本月").assertIsSelected()
        composeRule.onNodeWithText("P1").assertExists()
        composeRule.onNodeWithTag("leaderboard-P1").assertTextContains("27:58.10")
        composeRule.onNodeWithTag("leaderboard-P1").assertTextContains("PB", substring = true)
        composeRule.onNodeWithTag("leaderboard-P1").assertTextContains("本月最快", substring = true)
        composeRule.onNodeWithText("有效行程").assertExists()
        composeRule.onNodeWithText("月统计").performClick()
        composeRule.onNodeWithText("最近月平均").assertExists()
        composeRule.onNodeWithText("行程 GAP 趋势").assertExists()
        composeRule.onNodeWithText("纵轴 GAP（相对当前范围最快） · 横轴日期").assertExists()
        composeRule.onNodeWithTag("trip-gap-chart").assertExists()
        composeRule.onNodeWithContentDescription("GAP 0", substring = true).assertExists()
        saveScreenshot("records-monthly-gap-trend-v3.png")
        composeRule.onNodeWithText("周统计").performClick()
        composeRule.onNodeWithText("最近周平均").assertExists()
        composeRule.onNodeWithText("排行榜").performClick()
        saveScreenshot("records-leaderboard-v3.png")
        composeRule.onNodeWithTag("leaderboard-P1").performClick()
        composeRule.runOnIdle { assertEquals("run-3", openedSession) }
    }

    @Test fun allRoutesRemainSelectableWhenThereAreMoreThanFour() {
        val routes = (1..6).map { index -> route("route-$index", "路线 $index", "CUSTOM", "v-$index") }
        composeRule.setContent {
            DeltaTTheme { TelemetryRecordsScreen(routes, emptyList(), onOpenSession = {}) }
        }

        composeRule.onNodeWithTag("route-option-route-6").assertExists().performScrollTo().performClick()
        composeRule.onNodeWithTag("route-option-route-6").assertIsSelected()
    }

    @Test fun selectedRouteAndTabSurviveSavedStateRestoration() {
        val tester = StateRestorationTester(composeRule)
        val routes = listOf(
            route("work", "上班", "TO_WORK", "v-work"),
            route("home", "下班", "FROM_WORK", "v-home"),
        )
        tester.setContent {
            DeltaTTheme { TelemetryRecordsScreen(routes, emptyList(), onOpenSession = {}) }
        }

        composeRule.onNodeWithTag("route-option-home").performClick()
        composeRule.onNodeWithText("周统计").performClick()
        tester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("route-option-home").assertIsSelected()
        composeRule.onNodeWithTag("records-tab-WEEK").assertIsSelected()
    }

    @Test fun nullLeaderboardShowsLoadingInsteadOfProjectingIncompleteSessions() {
        val routes = listOf(route("work", "上班", "TO_WORK", "v-work"))
        composeRule.setContent {
            DeltaTTheme { TelemetryRecordsScreen(routes, null, onOpenSession = {}) }
        }

        composeRule.onNodeWithText("正在加载有效成绩…").assertExists()
        composeRule.onAllNodesWithText("P1").assertCountEquals(0)
    }

    private fun route(id: String, name: String, direction: String, version: String) = RouteProfileEntity(
        id = id,
        name = name,
        directionType = direction,
        activeCheckpointVersionId = version,
        createdAtMillis = 1,
        updatedAtMillis = 1,
    )

    private fun session(id: String, date: LocalDate, hour: Int, seconds: Double) = SessionListRow(
        id = id,
        routeProfileId = "work",
        routeName = "上班",
        startedWallTimeMillis = date.atTime(hour, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        totalDurationNanos = (seconds * 1_000_000_000L).toLong(),
        status = "COMPLETED",
        validity = "VALID",
        qualityGrade = "A",
        referenceSessionIdSnapshot = null,
        checkpointVersionId = "v-work",
        maxSpeedMps = 23.4,
        maxSpeedElapsedNanos = 740_000_000_000,
        maxSpeedWallTimeMillis = null,
    )

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(requireNotNull(context.getExternalFilesDir(null)), name)
        file.outputStream().use { output ->
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cp ${file.absolutePath} /sdcard/Download/$name")
            .close()
    }
}
