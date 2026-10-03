package app.deltat.commute

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.deltat.commute.data.LeaderboardRow
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.ui.DeltaTTheme
import app.deltat.commute.ui.TelemetryRecordsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RecordsLayoutMatrixTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tenLongRoutesAndDenseExtremeGapRemainReachableAcrossWidthAndFontMatrix() {
        val routes = (1..10).map { RouteProfileEntity("matrix-$it", "路线$it·跨城区长名称", "CUSTOM", createdAtMillis = it.toLong(), updatedAtMillis = 1) }
        val start = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val rows = (0..199).map { i -> LeaderboardRow(
            sessionId = "matrix-trip-$i", routeProfileId = "matrix-1", routeName = routes.first().name,
            startedWallTimeMillis = start + (i / 20) * 60_000L,
            totalDurationNanos = if (i == 199) 86_399_990_000_000L else 1_600_000_000_000L + i * 100_000_000L,
            qualityGrade = "A", sector1Nanos = 500_000_000_000, sector2Nanos = 500_000_000_000, sector3Nanos = 600_000_000_000,
            timingEngineVersion = app.deltat.commute.domain.DefaultTimingEngine.TIMING_ENGINE_VERSION, missingCheckpointMask = 0,
        ) }
        val config = mutableStateOf(360 to 1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, config.value.second)) {
                Box(Modifier.width(config.value.first.dp)) { DeltaTTheme { TelemetryRecordsScreen(routes, rows, {}) } }
            }
        }
        for (width in listOf(320, 360, 393, 411)) for (font in listOf(1f, 1.3f)) {
            compose.runOnIdle { config.value = width to font }
            compose.onNodeWithTag("route-option-matrix-10").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("route-option-matrix-1").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("月统计").performScrollTo().performClick()
            compose.onNodeWithTag("trip-gap-chart").performScrollTo().assertIsDisplayed()
            val chart = compose.onNodeWithTag("trip-gap-chart").fetchSemanticsNode()
            compose.onNodeWithTag("stats-window").assertTextEquals("第 196–200 次／共 200 次")
            compose.onAllNodes(hasTestTag("trip-bar-matrix-trip-199")).assertCountEquals(1)
            compose.onNodeWithTag("stats-earlier").performScrollTo().performClick()
            compose.onNodeWithTag("stats-window").assertTextEquals("第 191–195 次／共 200 次")
            compose.onNodeWithTag("stats-later").performClick()
            compose.saveScreenshot("records-before-axis-check-${width}-${font}.png")
            for (tick in 0..3) {
                compose.onNodeWithTag("gap-axis-$tick").onChild().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    action(layouts)
                    assertTrue("轴刻度不能折行或截断 width=$width font=$font tick=$tick layouts=${layouts.map { "${it.layoutInput.text.text}: lines=${it.lineCount}, size=${it.size}, overflowW=${it.didOverflowWidth}, overflowH=${it.didOverflowHeight}" }}", layouts.isNotEmpty() && layouts.all { it.lineCount == 1 && !it.hasVisualOverflow })
                }
            }
            assertTrue(chart.boundsInRoot.width > 100)
            assertTrue(chart.boundsInRoot.height > 100)
            compose.saveScreenshot("records-matrix-${width}-${font}.png")
            compose.onNodeWithTag("records-list").performScrollToIndex(0)
            compose.onNodeWithText("排行榜").performScrollTo().performClick()
        }
    }
}
