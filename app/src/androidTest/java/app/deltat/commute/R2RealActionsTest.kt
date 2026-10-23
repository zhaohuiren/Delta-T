package app.deltat.commute

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.deltat.commute.location.RuntimeBus
import app.deltat.commute.ui.formatRecordDuration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.*

/** Mutations only affect the isolated imported database, never the user's device or source export. */
class R2RealActionsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as DeltaTApplication
    private fun click(text:String)=compose.onNodeWithText(text).performClick()
    private fun back()=compose.onNodeWithContentDescription("返回").performClick()
    @Test fun realDeletionProtectionDatabaseLinkageAndOldEvaluation() {
        Thread.sleep(2600)
        val source=RealRedesignData(File(app.getExternalFilesDir(null),"redesign-input.json"))
        runBlocking(Dispatchers.IO){source.importInto(app)};RuntimeBus.reset()
        val all=runBlocking{app.repository.leaderboard.first()}
        val routes=runBlocking{app.repository.routes.first()}
        click("历史")
        val protected=source.sample().getString("id")
        compose.onNodeWithTag("history-menu-$protected").performScrollTo().performClick()
        compose.onNodeWithText("无法单独删除").assertExists();click("知道了")
        assertNotNull(runBlocking{app.repository.session(protected)})
        for(status in listOf("CANCELLED","INTERRUPTED")) {
            val row=source.sessions.first{it.getString("status")==status}
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("history-card-${row.getString("id")}"))
            compose.onNodeWithTag("history-card-${row.getString("id")}").performClick()
            compose.onNodeWithText("单次详情").assertExists()
            compose.saveScreenshot("r2-real-detail-$status.png");back()
        }
        click("纪录");click("月统计")
        val september=all.filter{it.routeProfileId=="import-work"&&Instant.ofEpochMilli(it.startedWallTimeMillis).atZone(ZoneId.systemDefault()).monthValue==9}
        val target=september.filter{it.sessionId!=protected}.minBy{it.totalDurationNanos}
        compose.onNodeWithTag("stats-sort").performClick();click("用时从快到慢")
        compose.onNodeWithTag("trip-bar-${target.sessionId}").performClick();click("查看完整行程")
        compose.onNodeWithContentDescription("删除记录").performClick()
        click("取消");assertNotNull(runBlocking{app.repository.session(target.sessionId)})
        compose.onNodeWithContentDescription("删除记录").performClick();click("确认删除")
        compose.waitUntil(10000){compose.onAllNodesWithTag("stats-median").fetchSemanticsNodes().isNotEmpty()}
        val remaining=september.filterNot{it.sessionId==target.sessionId}.map{it.totalDurationNanos}.sorted()
        compose.onNodeWithTag("stats-median").assertTextEquals(formatRecordDuration(remaining[remaining.size/2]))
        runBlocking {
            assertNull(app.repository.session(target.sessionId))
            assertTrue(app.database.dao().track(target.sessionId).isEmpty())
            assertEquals(91,app.repository.sessions.first().size)
            assertFalse(app.repository.leaderboard.first().any{it.sessionId==target.sessionId})
        }
        // Exercise a genuine historical evaluation by activating its real saved version locally.
        val old=source.sessions.first{it.getJSONArray("evaluations").objects().any{e->e.getString("checkpointVersionId")!=it.getString("checkpointVersionId")}}
        val route=routes.first{it.id==source.routeId(old.getString("route"))}
        val version=old.getJSONArray("evaluations").objects().first{it.getString("checkpointVersionId")!=old.getString("checkpointVersionId")}.getString("checkpointVersionId")
        runBlocking{app.database.dao().insertRoute(route.copy(activeCheckpointVersionId=version))}
        click("历史")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("history-card-${old.getString("id")}"))
        compose.onNodeWithTag("history-card-${old.getString("id")}").performClick()
        compose.onNodeWithText("原始成绩").performClick()
        compose.onNodeWithText("不参与当前版本纪录").assertExists()
        compose.saveScreenshot("r2-real-old-evaluation.png")
        compose.onNodeWithText("当前计时点重算").performClick()
        back();click("设置");click("路线管理")
        compose.onNodeWithContentDescription("删除${route.name}").performClick()
        compose.onNodeWithText("删除“${route.name}”？").assertExists();click("取消")
        assertEquals(2,runBlocking{app.repository.routes.first().size})
        runBlocking(Dispatchers.IO){source.importInto(app)}
        assertEquals(92,runBlocking{app.repository.sessions.first().size})
    }
}
