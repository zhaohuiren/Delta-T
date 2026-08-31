package app.deltat.commute

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.deltat.commute.ui.CountdownScreen
import app.deltat.commute.ui.DeltaTTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CountdownScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun countdownShowsThreeTwoOneReadyWithoutStartingTimingEarly() {
        composeRule.mainClock.autoAdvance = false
        var starts = 0
        var finishes = 0
        composeRule.setContent {
            DeltaTTheme {
                CountdownScreen(
                    onStart = { starts++ }, onFinished = { finishes++ }, onCancel = {},
                    animationsEnabled = true, stepMillis = 720, readyMillis = 650,
                )
            }
        }

        composeRule.onNodeWithText("3").assertExists()
        composeRule.saveScreenshot("countdown-mock-3.png")
        composeRule.runOnIdle { assertEquals(1, starts); assertEquals(0, finishes) }
        composeRule.mainClock.advanceTimeBy(721)
        composeRule.onNodeWithText("2").assertExists()
        composeRule.mainClock.advanceTimeBy(721)
        composeRule.onNodeWithText("1").assertExists()
        composeRule.mainClock.advanceTimeBy(721)
        composeRule.onNodeWithText("READY").assertExists()
        composeRule.saveScreenshot("countdown-mock-ready.png")
        composeRule.runOnIdle { assertEquals(0, finishes) }
        composeRule.mainClock.advanceTimeBy(651)
        composeRule.runOnIdle { assertEquals(1, finishes) }
    }

    @Test fun countdownCancelIsAvailableImmediately() {
        var cancelled = 0
        composeRule.setContent {
            DeltaTTheme {
                CountdownScreen(
                    onStart = {}, onFinished = {}, onCancel = { cancelled++ },
                    animationsEnabled = true,
                )
            }
        }
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle { assertEquals(1, cancelled) }
    }
}
