package app.deltat.commute

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.*

/** All chart rows come from the user's local diagnostic export; subsets exercise empty/single/deletion. */
class R2StatisticsStateTest {
    @get:Rule val compose = createComposeRule()
    @Test fun realMedianSortingPagingDetailsDeletionAndRestoration() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DeltaTApplication
        Thread.sleep(2600)
        val source=RealRedesignData(File(app.getExternalFilesDir(null),"redesign-input.json"))
        runBlocking(Dispatchers.IO) { source.importInto(app) }
        val original=runBlocking { app.repository.leaderboard.first() }
        val routes=runBlocking { app.repository.routes.first() }
        val data=mutableStateOf(original)
        var opened:String?=null
        val restore=StateRestorationTester(compose)
        restore.setContent { DeltaTTheme { TelemetryRecordsScreen(routes,data.value,{opened=it}) } }
        compose.onNodeWithText("月统计").performClick()
        compose.onNodeWithTag("stats-median").assertTextEquals("29:21.04")
        compose.onNodeWithTag("stats-window").assertTextContains("第 8–12 次／共 12 次",substring=true)
        val axis=compose.onNodeWithTag("gap-axis-3").fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        val september=original.filter { it.routeProfileId=="import-work" && Instant.ofEpochMilli(it.startedWallTimeMillis).atZone(ZoneId.systemDefault()).monthValue==9 }
        val fastest=september.sortedWith(compareBy<app.deltat.commute.data.LeaderboardRow>{it.totalDurationNanos}.thenBy{it.startedWallTimeMillis}.thenBy{it.sessionId})
        compose.onNodeWithTag("stats-sort").performClick()
        compose.onNodeWithText("用时从快到慢").performClick()
        fastest.take(5).forEach { compose.onNodeWithTag("trip-bar-${it.sessionId}").assertExists() }
        compose.onNodeWithTag("stats-median").assertTextEquals("29:21.04")
        compose.onNodeWithTag("gap-axis-3").assertTextEquals(axis)
        compose.onNodeWithTag("trip-bar-${fastest.first().sessionId}").performClick()
        compose.onNodeWithText("查看完整行程").assertExists()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("查看完整行程").assertExists().performClick()
        compose.runOnIdle { assertEquals(fastest.first().sessionId,opened) }
        compose.onNodeWithTag("stats-later").performScrollTo().performClick()
        fastest.drop(5).take(5).forEach { compose.onNodeWithTag("trip-bar-${it.sessionId}").assertExists() }
        compose.onNodeWithTag("trip-gap-chart").performTouchInput { swipeLeft() }
        fastest.takeLast(5).forEach { compose.onNodeWithTag("trip-bar-${it.sessionId}").assertExists() }
        compose.onNodeWithTag("stats-sort").performScrollTo().performClick()
        compose.onNodeWithText("用时从慢到快").performClick()
        fastest.takeLast(5).forEach { compose.onNodeWithTag("trip-bar-${it.sessionId}").assertExists() }
        val selected=fastest.last()
        compose.onNodeWithTag("trip-bar-${selected.sessionId}").performClick()
        pressBack()
        compose.runOnIdle { data.value=original.filterNot{it.sessionId==selected.sessionId} }
        compose.onNodeWithTag("trip-bar-${fastest[fastest.lastIndex-1].sessionId}").assertIsSelected()
        compose.onNodeWithTag("stats-previous-period").performScrollTo().performClick()
        compose.onNodeWithTag("stats-period").assertTextEquals("2026年8月")
        compose.onNodeWithTag("records-list").performScrollToIndex(0)
        compose.onNodeWithText("周统计").performClick()
        compose.onNodeWithText("月统计").performClick()
        compose.onNodeWithTag("stats-period").assertTextEquals("2026年8月")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("stats-period").assertTextEquals("2026年8月")
        compose.onNodeWithTag("stats-next-period").performClick()
        compose.onNodeWithTag("stats-next-period").performClick()
        compose.onNodeWithText("这个周期还没有有效行程").assertExists()
        compose.onNodeWithTag("stats-next-period").assertIsNotEnabled()
        compose.onNodeWithTag("stats-previous-period").performClick()
        val single=fastest.first()
        compose.runOnIdle { data.value=listOf(single) }
        compose.onNodeWithTag("stats-median").assertTextEquals(formatRecordDuration(single.totalDurationNanos))
        compose.onNodeWithTag("trip-bar-${single.sessionId}").assertExists().performClick()
        compose.onAllNodesWithText("与中位数持平").assertCountEquals(2)
        compose.runOnIdle { data.value=emptyList() }
        compose.onNodeWithText("这个周期还没有有效行程").assertExists()
        compose.onNodeWithText("查看完整行程").assertDoesNotExist()
        compose.runOnIdle { data.value=original }
        compose.onNodeWithTag("records-list").performScrollToIndex(0)
        compose.onNodeWithTag("route-option-import-home").performClick()
        compose.onNodeWithTag("stats-median").assertTextEquals("32:38.18")
        compose.onNodeWithTag("route-option-import-work").performClick()
        compose.onNodeWithTag("stats-median").assertTextEquals("29:21.04")
        assertEquals(92,runBlocking { app.repository.sessions.first().size })
        compose.saveScreenshot("r2-real-statistics-state.png")
    }
}
