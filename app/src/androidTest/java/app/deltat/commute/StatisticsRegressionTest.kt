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

class StatisticsRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val routes = listOf("r1", "r2").map { RouteProfileEntity(it, it, "CUSTOM", createdAtMillis = 1, updatedAtMillis = 1) }
    private fun row(id: String, route: String, time: Long) = LeaderboardRow(id, route, route, time, 60_000_000_000L, "A", 20_000_000_000L, 20_000_000_000L, 20_000_000_000L)

    @Test fun plannedCountMatrixKeepsMonthAndWeekWindowsReachable() {
        val count = mutableStateOf(0)
        val now = System.currentTimeMillis()
        compose.setContent {
            androidx.compose.runtime.key(count.value) {
                val data = (0 until count.value).map { row("dense-$it", "r1", now - 2_001_000L + it * 1000L) }
                DeltaTTheme { TelemetryRecordsScreen(routes, data, {}) }
            }
        }
        for (size in listOf(0, 1, 5, 6, 12, 2000)) {
            compose.runOnIdle { count.value = size }
            for (tab in listOf("月统计", "周统计")) {
                compose.onNodeWithTag("records-list").performScrollToIndex(0)
                compose.onNodeWithText(tab).performClick()
                compose.onNodeWithTag("trip-gap-chart").performScrollTo().assertIsDisplayed()
                if (size == 0) {
                    compose.onNodeWithText("这个周期还没有有效行程").assertExists()
                } else {
                    val first = (size - 5).coerceAtLeast(0) + 1
                    compose.onNodeWithTag("stats-window").assertTextEquals("第 $first–$size 次／共 $size 次")
                    compose.onNodeWithTag("trip-bar-dense-${size - 1}").assertIsSelected()
                    if (size > 5) {
                        compose.onNodeWithTag("stats-earlier").performScrollTo().performClick()
                        val previous = (first - 6).coerceAtLeast(0)
                        compose.onNodeWithTag("stats-window").assertTextEquals("第 ${previous + 1}–${previous + 5} 次／共 $size 次")
                        compose.onNodeWithTag("stats-later").performClick()
                        compose.onNodeWithTag("stats-window").assertTextEquals("第 $first–$size 次／共 $size 次")
                    }
                }
            }
        }
        compose.saveScreenshot("statistics-count-matrix-2000.png")
    }

    @Test fun newTripAppearsWithoutResumingScreenAndFutureTripsStayExcluded() {
        val data = mutableStateOf(listOf(row("first", "r1", System.currentTimeMillis() - 2000)))
        compose.setContent { DeltaTTheme { TelemetryRecordsScreen(routes, data.value, {}) } }
        compose.onNodeWithTag("leaderboard-P1").assertExists()
        Thread.sleep(30)
        compose.runOnIdle { data.value += row("new", "r1", System.currentTimeMillis()) }
        compose.onNodeWithTag("leaderboard-P2").assertExists()
        compose.runOnIdle { data.value += row("future", "r1", System.currentTimeMillis() + 86_400_000) }
        compose.onNodeWithTag("leaderboard-P3").assertDoesNotExist()
        compose.onNodeWithText("月统计").performClick()
        compose.onNodeWithTag("stats-window").assertTextEquals("第 1–2 次／共 2 次")
    }

    @Test fun routesKeepSeparatePeriodsSelectionsAndRestoreAfterRecreation() {
        val now = System.currentTimeMillis()
        val data = mutableStateOf((0..11).map { row("trip-$it", "r1", now - 120_000 + it * 1000) } + row("only", "r2", now - 1000))
        val restore = StateRestorationTester(compose)
        restore.setContent { DeltaTTheme { TelemetryRecordsScreen(routes, data.value, {}) } }
        compose.onNodeWithText("月统计").performClick()
        compose.onNodeWithTag("stats-earlier").performScrollTo().performClick()
        compose.onNodeWithTag("trip-bar-trip-3").performScrollTo().performClick()
        compose.onNodeWithTag("records-list").performScrollToIndex(0)
        compose.onNodeWithTag("route-option-r2").performClick()
        compose.onNodeWithTag("stats-window").assertTextEquals("第 1–1 次／共 1 次")
        compose.onNodeWithTag("stats-previous-period").performClick()
        compose.onNodeWithText("这个周期还没有有效行程").assertExists()
        compose.onNodeWithTag("route-option-r1").performClick()
        compose.onNodeWithTag("trip-bar-trip-3").assertIsSelected()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("trip-bar-trip-3").assertIsSelected()
        compose.onNodeWithTag("records-list").performScrollToIndex(0)
        compose.onNodeWithTag("route-option-r2").performClick()
        compose.onNodeWithText("这个周期还没有有效行程").assertExists()
        compose.runOnIdle { data.value = emptyList() }
        compose.onNodeWithText("这个周期还没有有效行程").assertExists()
    }
}
