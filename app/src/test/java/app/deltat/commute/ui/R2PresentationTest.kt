package app.deltat.commute.ui

import app.deltat.commute.location.RuntimeSnapshot
import app.deltat.commute.domain.*
import app.deltat.commute.map.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.math.hypot

class R2PresentationTest {
    @Test fun preparingQualityAndStartGateHaveExplicitPriority() {
        val fix = LocationFix(31.0,121.0,1_000_000_000,1,5.0,0.0)
        val waiting = RuntimeSnapshot(routeId="work", lastFix=fix, engineStatus=EngineStatus.START_CANDIDATE)
        assertEquals(FixPresentation.MISSING, fixPresentation(waiting.copy(lastFix=null), 1))
        assertEquals(FixPresentation.FRESH, fixPresentation(waiting, 11_000_000_000))
        assertEquals(FixPresentation.STALE, fixPresentation(waiting, 11_000_000_001))
        assertEquals(FixPresentation.INACCURATE, fixPresentation(waiting.copy(lastFix=fix.copy(accuracyMeters=51.0)), 1))
        assertEquals(FixPresentation.INACCURATE, fixPresentation(waiting.copy(lastFix=fix.copy(accuracyMeters=Double.NaN)), 1))
        assertEquals("正在确认通过 A", preparingMessage(waiting, FixPresentation.FRESH).title)
        assertEquals("等待重新经过 A", preparingMessage(waiting.copy(startRequiresExit=true), FixPresentation.FRESH).title)
        assertEquals("等待更新定位", preparingMessage(waiting.copy(startRequiresExit=true), FixPresentation.STALE).title)
    }
    @Test fun labelsKeepTrueAnchorsAndAvoidArrowAtEdgesAndClusters() {
        for (width in listOf(300f,360f,411f)) for (height in listOf(280f,360f,420f)) {
            for (point in listOf(MapPixel(30f,30f),MapPixel(width/2,height/2),MapPixel(width-30,height-50))) {
                val anchors=(0..3).associateWith { point }
                val layout=layoutMapLabels(anchors,point,width,height,1f)
                layout.forEach { assertEquals(point,it.anchor); assertTrue(it.label.x in 20f..width-20); assertTrue(it.label.y in 20f..height-48); assertTrue(hypot(it.label.x-point.x,it.label.y-point.y)>=39f) }
                layout.forEachIndexed { i,a -> layout.drop(i+1).forEach { b -> assertTrue(hypot(a.label.x-b.label.x,a.label.y-b.label.y)>=34f) } }
                assertEquals(layout,layoutMapLabels(anchors,point,width,height,1f,layout.associate{it.order to it.slot}))
            }
        }
    }
    @Test fun axisCoversZeroSubsecondHoursAndLongMaximumWithoutOverflow() {
        for (gap in listOf(0L,1L,30_000_001L,999_999_999L,151_100_000_000L,86_400_000_000_000L,Long.MAX_VALUE)) {
            val axis=gapAxis(gap)
            assertTrue(axis.topNanos>=gap)
            assertTrue(axis.tick(1)>0)
            assertTrue(axis.tick(2)>axis.tick(1))
            assertTrue(axis.tick(3)>axis.tick(2))
            assertEquals("0",axis.label(0))
        }
        assertEquals("+30", gapAxis(86_400_000_000_000L).label(3))
        assertEquals("+10", gapAxis(86_400_000_000_000L).label(1))
        assertEquals(LocalDate.of(2025,12,29),statisticsPeriodStart(LocalDate.of(2026,1,1),false))
        assertEquals(LocalDate.of(2026,1,1),statisticsPeriodStart(LocalDate.of(2026,1,31),true))
    }
}
