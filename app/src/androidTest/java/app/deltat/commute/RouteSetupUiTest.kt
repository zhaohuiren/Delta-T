package app.deltat.commute

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.domain.GnssHealth
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.location.RuntimeSnapshot
import app.deltat.commute.ui.BaselineScreen
import app.deltat.commute.ui.DeltaTTheme
import app.deltat.commute.ui.NewRouteScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RouteSetupUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun newRouteRequiresNameAndSubmitsEnteredName() {
        var created = ""
        composeRule.setContent {
            DeltaTTheme { NewRouteScreen(rememberNavController()) { created = it } }
        }
        composeRule.onNodeWithText("下一步：录制路线").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("周末回家")
        composeRule.saveScreenshot("route-mock-new.png")
        composeRule.onNodeWithText("下一步：录制路线").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("周末回家", created) }
    }

    @Test fun baselineScreenCoversIdleRecordingAndRecordedActions() {
        val route = RouteProfileEntity("work", "上班", "TO_WORK", createdAtMillis = 1, updatedAtMillis = 1)
        val runtime = mutableStateOf(RuntimeSnapshot())
        var startClicks = 0
        var stopClicks = 0
        composeRule.setContent {
            DeltaTTheme {
                BaselineScreen(
                    rememberNavController(), route, runtime.value,
                    start = { startClicks++ }, stop = { stopClicks++ },
                )
            }
        }

        composeRule.onNodeWithText("开始记录路线").performClick()
        composeRule.runOnIdle { assertEquals(1, startClicks) }

        composeRule.runOnIdle {
            runtime.value = RuntimeSnapshot(
                sessionId = "baseline", routeId = "work", routeName = "上班",
                mode = "BASELINE_RECORDING", status = "正在录制", elapsedNanos = 65_400_000_000,
                gnssHealth = GnssHealth(usedGps = 6, usedBeidou = 5, label = "GNSS 良好"),
                lastFix = fix(10), track = (0..10).map(::fix),
            )
        }
        composeRule.onNodeWithText("正在记录完整轨迹 · 01:05.40").assertExists()
        composeRule.saveScreenshot("route-mock-baseline-recording.png")
        composeRule.onNodeWithText("我已停车，结束录制").performClick()
        composeRule.runOnIdle { assertEquals(1, stopClicks) }

        composeRule.runOnIdle {
            runtime.value = runtime.value.copy(mode = null, status = "路线轨迹已录制")
        }
        composeRule.onNodeWithText("标记 A / S1 / S2 / B").assertExists()
        composeRule.saveScreenshot("route-mock-baseline-finished.png")
    }

    private fun fix(index: Int) = LocationFix(
        latitude = 31.2 + index * .0002,
        longitude = 121.4 + index * .0002,
        elapsedRealtimeNanos = index * 6_000_000_000L,
        wallTimeMillis = 1_752_890_000_000 + index * 6_000,
        accuracyMeters = 6.0,
    )
}
