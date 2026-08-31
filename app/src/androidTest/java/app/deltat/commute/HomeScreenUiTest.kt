package app.deltat.commute

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.data.SessionListRow
import app.deltat.commute.domain.EngineStatus
import app.deltat.commute.location.RuntimeSnapshot
import app.deltat.commute.ui.DeltaTTheme
import app.deltat.commute.ui.HomeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class HomeScreenUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun homeRoutesPermissionPrepareAndResumeActionsAreConsistent() {
        val routes = mutableStateOf(emptyList<RouteProfileEntity>())
        val sessions = mutableStateOf(emptyList<SessionListRow>())
        val runtime = mutableStateOf(RuntimeSnapshot())
        val permission = mutableStateOf(false)
        var routesClicks = 0
        var prepareClicks = 0
        var resumeClicks = 0
        var permissionClicks = 0

        composeRule.setContent {
            DeltaTTheme {
                HomeScreen(
                    routes.value, sessions.value, runtime.value, permission.value,
                    onRoutes = { routesClicks++ }, onAutoPrepare = { prepareClicks++ },
                    onResume = { resumeClicks++ }, onPermission = { permissionClicks++ },
                )
            }
        }

        composeRule.onNodeWithText("检查").performClick()
        composeRule.onNodeWithText("先建立路线").performClick()
        composeRule.runOnIdle { assertEquals(1, permissionClicks); assertEquals(1, routesClicks) }

        val work = route("work", "上班", "TO_WORK")
        val home = route("home", "下班", "FROM_WORK")
        composeRule.runOnIdle {
            routes.value = listOf(home, work)
            sessions.value = records()
            permission.value = true
        }
        composeRule.onNodeWithText("一键准备").performClick()
        composeRule.runOnIdle { assertEquals(1, prepareClicks) }
        assertTrue(composeRule.onAllNodesWithText("28:42.70").fetchSemanticsNodes().isNotEmpty())
        val workY = composeRule.onNodeWithTag("home-route-work").fetchSemanticsNode().boundsInRoot.center.y
        val homeY = composeRule.onNodeWithTag("home-route-home").fetchSemanticsNode().boundsInRoot.center.y
        assertTrue("首页路线应为上班在上、下班在下", workY < homeY)
        composeRule.onNodeWithTag("home-route-work").performClick()
        composeRule.runOnIdle { assertEquals(2, routesClicks) }
        composeRule.saveScreenshot("home-mock-populated.png")

        composeRule.runOnIdle {
            runtime.value = RuntimeSnapshot(
                sessionId = "active", routeId = "work", routeName = "上班",
                mode = "TIMED", status = "正在计时", engineStatus = EngineStatus.SECTOR_1,
            )
        }
        composeRule.onNodeWithText("返回计时").performClick()
        composeRule.runOnIdle { assertEquals(1, resumeClicks) }
    }

    private fun route(id: String, name: String, direction: String) = RouteProfileEntity(
        id = id, name = name, directionType = direction,
        activeCheckpointVersionId = "v-$id", createdAtMillis = 1, updatedAtMillis = 1,
    )

    private fun records(): List<SessionListRow> {
        val monday = LocalDate.now().withDayOfMonth(1)
        return listOf(
            row("best", monday, 27 * 60 + 58.1, 7),
            row("latest", monday, 28 * 60 + 42.7, 8),
        )
    }

    private fun row(id: String, date: LocalDate, seconds: Double, hour: Int) = SessionListRow(
        id = id, routeProfileId = "work", routeName = "上班",
        startedWallTimeMillis = date.atTime(hour, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        totalDurationNanos = (seconds * 1_000_000_000).toLong(), status = "COMPLETED", validity = "VALID",
        qualityGrade = "A", referenceSessionIdSnapshot = null, checkpointVersionId = "v-work",
        maxSpeedMps = 20.0, maxSpeedElapsedNanos = null, maxSpeedWallTimeMillis = null,
    )
}
