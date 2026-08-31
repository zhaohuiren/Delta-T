package app.deltat.commute

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun homeScreenShowsBrandAndDirections() {
        composeRule.onNodeWithText("Delta T").assertExists()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("上班")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("下班").assertExists()
        composeRule.onNodeWithText("先建立路线").assertExists()
    }
}
