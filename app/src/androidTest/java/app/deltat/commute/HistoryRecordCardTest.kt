package app.deltat.commute

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.deltat.commute.data.SessionListRow
import app.deltat.commute.ui.DeltaTTheme
import app.deltat.commute.ui.HistoryRecordCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HistoryRecordCardTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun longFailureReasonAppearsOnceAndMenuDoesNotOpenDetails() {
        val reason = "原行程已取消，当前计时点版本无法生成兼容成绩，请保留本条诊断记录"
        val row = SessionListRow(
            id = "cancelled", routeProfileId = "work", routeName = "上班",
            startedWallTimeMillis = 1_788_275_200_000,
            totalDurationNanos = null, status = "CANCELLED", validity = "INVALID", qualityGrade = "C",
            referenceSessionIdSnapshot = null, checkpointVersionId = "old-version",
            maxSpeedMps = null, maxSpeedElapsedNanos = null, maxSpeedWallTimeMillis = null,
            evaluationFailureReason = reason,
        )
        var openCount = 0
        var menuCount = 0
        composeRule.setContent {
            DeltaTTheme {
                HistoryRecordCard(row, "current-version", false, false, false, { openCount++ }, { menuCount++ })
            }
        }

        composeRule.onAllNodesWithText(reason, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithTag("history-menu-cancelled", useUnmergedTree = true).performClick()
        composeRule.runOnIdle {
            assertEquals(0, openCount)
            assertEquals(1, menuCount)
        }
        composeRule.onNodeWithTag("history-card-cancelled").performClick()
        composeRule.runOnIdle { assertEquals(1, openCount) }
    }
}
