package app.deltat.commute

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.deltat.commute.location.RuntimeBus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File

class R2RealPreparationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun realRoutesPrepareAndCancelWithoutInventingJourney() {
        val app=compose.activity.application as DeltaTApplication
        Thread.sleep(2600)
        val source=RealRedesignData(File(app.getExternalFilesDir(null),"redesign-input.json"))
        runBlocking(Dispatchers.IO){source.importInto(app)};RuntimeBus.reset()
        compose.onNodeWithText("一键准备").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("取消准备").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("取消准备").performClick()
        compose.onNodeWithText("确认取消").assertExists()
        compose.onNodeWithText("继续等待").performClick()
        compose.onNodeWithText("取消准备").assertExists().performClick()
        compose.onNodeWithText("确认取消").performClick()
        compose.waitUntil(10000){RuntimeBus.state.value.mode!="AUTO_ARMING"}
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("一键准备").assertExists()
        assertEquals(92,runBlocking{app.repository.sessions.first().size})
        compose.onNodeWithText("一键准备").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("一键准备").assertExists()
        compose.waitUntil(10000){RuntimeBus.state.value.mode!="AUTO_ARMING"}
        assertEquals(92,runBlocking{app.repository.sessions.first().size})
    }
}
