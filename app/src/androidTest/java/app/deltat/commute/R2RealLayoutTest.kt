package app.deltat.commute

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.domain.*
import app.deltat.commute.location.*
import app.deltat.commute.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File

class R2RealLayoutTest {
    @get:Rule val compose=createComposeRule()
    @Test fun realRecordsAndTimingRemainReadableAt360AndLargeFont() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DeltaTApplication
        Thread.sleep(2600)
        val source=RealRedesignData(File(app.getExternalFilesDir(null),"redesign-input.json"))
        runBlocking(Dispatchers.IO){source.importInto(app)}
        val routes=runBlocking{app.repository.routes.first()}
        val rows=runBlocking{app.repository.leaderboard.first()}
        val sample=source.sample();val track=source.fixes(sample)
        val ev=sample.getJSONArray("evaluations").objects().first{it.getString("checkpointVersionId")==sample.getString("checkpointVersionId")}
        val sectors=ev.getJSONArray("sectors").objects().map{row->
            val month=row.longOrNull("monthlyDeltaNanos");val week=row.longOrNull("weeklyDeltaNanos")
            RuntimeSector(row.getInt("index"),row.getLong("durationNanos"),color=when{(month?:0)<0->ResultColor.PURPLE;(week?:0)<0->ResultColor.GREEN;(week?:month?:0)>0->ResultColor.YELLOW;else->ResultColor.NEUTRAL},monthlyDeltaNanos=month,weeklyDeltaNanos=week)
        }
        val cp=runBlocking{app.repository.checkpointsForRoute("import-work")}
        val completed=RuntimeSnapshot(sessionId=sample.getString("id"),routeId="import-work",routeName="上班",mode="TIMED",engineStatus=EngineStatus.COMPLETED,currentSector=3,track=track,lastFix=track.last(),routeSourceTrack=track,routeCheckpoints=cp,elapsedNanos=sample.getLong("durationNanos"),sectors=sectors,monthlyDeltaNanos=ev.longOrNull("monthlyDeltaNanos"),weeklyDeltaNanos=ev.longOrNull("weeklyDeltaNanos"),deltaConfidence=DeltaConfidence.CONFIRMED)
        val runtime=mutableStateOf(completed)
        val page=mutableStateOf("records")
        val config=mutableStateOf(360 to 1f)
        compose.setContent {
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,config.value.second),LocalMapFeatureConfig provides MapFeatureConfig(true,true,false)) {
                DeltaTTheme { Box(Modifier.fillMaxSize()) { Box(Modifier.width(config.value.first.dp).fillMaxHeight().safeDrawingPadding()) {
                    key(page.value) { if(page.value=="records") TelemetryRecordsScreen(routes,rows,{})
                    else TimingScreen(rememberNavController(),routes.first{it.id=="import-work"},routes,runtime.value,{},{},{},elapsedRealtimeNanos={runtime.value.elapsedNanos},animateClock=false) }
                } } }
            }
        }
        for(width in listOf(360,411))for(font in listOf(1f,1.3f)) {
            compose.runOnIdle{config.value=width to font;page.value="records"}
            compose.onNodeWithText("月统计").performClick()
            compose.onNodeWithTag("stats-median").assertTextEquals("29:21.04")
            shot("r2-real-stats-$width-$font.png")
            noOverflow("stats-median");noOverflow("gap-axis-3")
            compose.onNodeWithTag("trip-bar-${sample.getString("id")}").performScrollTo().performClick()
            compose.onNodeWithText("查看完整行程").assertIsDisplayed()
            shot("r2-real-sheet-$width-$font.png")
            compose.runOnIdle{page.value="timing";runtime.value=completed}
            noOverflow("live-timer")
            (1..3).forEach{compose.onNodeWithTag("sector-card-S$it").assertExists()}
            shot("r2-real-complete-$width-$font.png")
            // Fault state uses the same real trip's retained values and removes match confidence.
            compose.runOnIdle{runtime.value=completed.copy(engineStatus=EngineStatus.SECTOR_3,deltaConfidence=DeltaConfidence.HELD,sectors=sectors.take(2))}
            compose.onNodeWithText("暂时无法匹配 · 保留最近确认值").assertExists()
            if(font>1.15f)compose.onNodeWithText("结束本次（成绩无效）").performScrollTo()
            compose.onNodeWithText("结束本次（成绩无效）").assertIsDisplayed()
            shot("r2-real-held-$width-$font.png")
            // Sparse results are an explicit fault injection of missing rows, never invented times.
            compose.runOnIdle{runtime.value=completed.copy(sectors=sectors.filter{it.index==2})}
            compose.onNodeWithTag("sector-card-S1").assert(hasAnyDescendant(hasText("—",substring=true)))
            compose.onNodeWithTag("sector-card-S3").assert(hasAnyDescendant(hasText(formatRecordDuration(sectors[2].durationNanos),substring=true)))
        }
    }
    private fun shot(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun noOverflow(tag:String){compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult){ action->
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();action(layouts)
        assertTrue("$tag must fit: ${layouts.map{"${it.layoutInput.text.text} size=${it.size} overflowW=${it.didOverflowWidth} overflowH=${it.didOverflowHeight} lines=${it.lineCount}"}}",layouts.isNotEmpty()&&layouts.all{!it.hasVisualOverflow})
    }}
}
