package app.deltat.commute

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import app.deltat.commute.data.LeaderboardRow
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.ui.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class R2StatisticsStateTest {
    @get:Rule val compose = createComposeRule()
    @Test fun independentPeriodsZeroTiesPagingSelectionDeletionAndRestoration() {
        val start=LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val routes=listOf(RouteProfileEntity("r","上班","TO_WORK",createdAtMillis=1,updatedAtMillis=1))
        val data=mutableStateOf((0..11).map { i -> LeaderboardRow("trip-${i.toString().padStart(2,'0')}","r","上班",start+i*60_000,
            if(i<2) 60_000_000_000L else 60_000_000_000L+i*1_000_000_000L,"A",20_000_000_000L,null,20_000_000_000L,missingCheckpointMask=2) })
        val restore=StateRestorationTester(compose)
        restore.setContent { DeltaTTheme { TelemetryRecordsScreen(routes,data.value,{error("柱子不应跳页")}) } }
        compose.onNodeWithText("月统计").performClick()
        compose.onNodeWithTag("stats-window").assertTextEquals("第 8–12 次／共 12 次")
        compose.onNodeWithTag("stats-gap-baseline").assertTextEquals("相对本月最快 01:00.00 · 秒")
        compose.onNodeWithTag("stats-earlier").performScrollTo().performClick()
        compose.onNodeWithTag("stats-earlier").performClick()
        compose.onNodeWithTag("stats-window").assertTextEquals("第 1–5 次／共 12 次")
        compose.onAllNodesWithContentDescription("GAP 0",substring=true).assertCountEquals(2)
        compose.onNodeWithTag("trip-bar-trip-02").performScrollTo().performClick().assertIsSelected()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("trip-bar-trip-02").assertIsSelected()
        compose.onNodeWithTag("records-list").performScrollToIndex(0)
        compose.onNodeWithText("周统计").performScrollTo().performClick()
        compose.onNodeWithText("月统计").performScrollTo().performClick()
        compose.onNodeWithTag("trip-bar-trip-02").assertIsSelected()
        compose.runOnIdle { data.value=data.value.filterNot{it.sessionId=="trip-02"} }
        compose.onNodeWithTag("trip-bar-trip-03").assertIsSelected()
        compose.onNodeWithTag("stats-previous-period").performScrollTo().performClick()
        compose.onNodeWithText("这个周期还没有有效行程").assertExists()
        compose.onNodeWithTag("stats-next-period").performClick()
        compose.onNodeWithTag("stats-window").assertTextEquals("第 7–11 次／共 11 次")
        compose.saveScreenshot("r2-statistics-restored.png")
    }
}
