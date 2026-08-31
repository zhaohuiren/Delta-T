package app.deltat.commute

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.data.SessionListRow
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

        var openedSession: String? = null
        composeRule.setContent {
            DeltaTTheme { TelemetryRecordsScreen(routes, sessions, onOpenSession = { openedSession = it }) }
        }

        val workLeft = composeRule.onNodeWithText("上班").fetchSemanticsNode().boundsInRoot.center.x
        val homeLeft = composeRule.onNodeWithText("下班").fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("上班应位于下班左侧", workLeft < homeLeft)
        composeRule.onNodeWithText("本月").assertExists()
        composeRule.onNodeWithText("排行榜").assertExists()
        composeRule.onNodeWithText("P1").assertExists()
        composeRule.onNodeWithTag("leaderboard-P1").assertTextContains("27:58.10")
        composeRule.onNodeWithTag("leaderboard-P1").assertTextContains("PB", substring = true)
        composeRule.onNodeWithText("有效行程").assertExists()
        composeRule.onNodeWithText("月统计").performClick()
        composeRule.onNodeWithText("最近月平均").assertExists()
        composeRule.onNodeWithText("周统计").performClick()
        composeRule.onNodeWithText("最近周平均").assertExists()
        composeRule.onNodeWithText("排行榜").performClick()
        saveScreenshot("records-leaderboard-v3.png")
        composeRule.onNodeWithTag("leaderboard-P1").performClick()
        composeRule.runOnIdle { assertEquals("run-3", openedSession) }
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
