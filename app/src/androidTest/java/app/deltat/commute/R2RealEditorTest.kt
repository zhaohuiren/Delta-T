package app.deltat.commute

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import app.deltat.commute.data.TrackPointEntity
import app.deltat.commute.domain.CheckpointSelection
import app.deltat.commute.ui.DeltaTTheme
import app.deltat.commute.ui.ZoomableCheckpointEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class R2RealEditorTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun editorSupportsStepSelectionZoomPanDragNudgeAndSave() {
        var saved: List<CheckpointSelection>? = null
        composeRule.setContent {
            DeltaTTheme {
                ZoomableCheckpointEditor(
                    track = track(), initialPositions = emptyList(), onSave = { saved = it },
                )
            }
        }

        listOf("A", "S1", "S2", "B").forEach { composeRule.onNodeWithTag("checkpoint-marker-$it").assertExists() }
        composeRule.onNodeWithText("当前 A").assertExists()
        composeRule.onNodeWithText("1.0×").assertExists()

        composeRule.onNodeWithContentDescription("放大").performClick()
        composeRule.onNodeWithText("1.5×").assertExists()
        composeRule.onNodeWithContentDescription("适应全图").performClick()
        composeRule.onNodeWithText("1.0×").assertExists()

        composeRule.onNodeWithTag("checkpoint-map").performTouchInput {
            pinch(
                start0 = center - Offset(60f, 0f), start1 = center + Offset(60f, 0f),
                end0 = center - Offset(130f, 0f), end1 = center + Offset(130f, 0f),
            )
        }
        composeRule.onNodeWithText("1.0×").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("适应全图").performClick()

        composeRule.onAllNodes(hasText("S1") and hasClickAction())[0].performClick()
        composeRule.onNodeWithText("当前 S1").assertExists()
        composeRule.onNodeWithText("5m ▶").performClick()
        composeRule.onNodeWithTag("checkpoint-marker-S1").performTouchInput { swipe(center, center + Offset(18f, -8f), 250) }
        composeRule.onNodeWithTag("checkpoint-map").performTouchInput { click(center) }
        composeRule.saveScreenshot("r2-editor-real-gestures.png")

        composeRule.onNodeWithText("下一步").performClick()
        composeRule.onNodeWithText("当前 S2").assertExists()
        composeRule.onNodeWithText("下一步").performClick()
        composeRule.onNodeWithText("当前 B").assertExists()
        composeRule.onNodeWithText("保存新版本").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(4, saved?.size)
            assertTrue(requireNotNull(saved).zipWithNext().all { it.first.segmentStartPointId <= it.second.segmentStartPointId })
        }
    }

    private fun track(): List<TrackPointEntity> {
        val app=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DeltaTApplication
        val source=RealRedesignData(java.io.File(app.getExternalFilesDir(null),"redesign-input.json"))
        return source.track(source.sample()).mapIndexed { index,p -> TrackPointEntity(
            id=index+1L,sessionId="real-editor-replay",sequence=p.getLong("sequence"),latitude=p.getDouble("latitude"),longitude=p.getDouble("longitude"),
            elapsedRealtimeNanos=p.getLong("elapsedRealtimeNanos"),wallTimeMillis=0,accuracyMeters=p.getDouble("accuracyMeters"),speedMps=p.doubleOrNull("speedMps"),
            bearingDegrees=null,bearingAccuracy=null,provider="imported-recording",isMock=false,usedForTiming=p.getBoolean("usedForTiming")) }
    }
}
