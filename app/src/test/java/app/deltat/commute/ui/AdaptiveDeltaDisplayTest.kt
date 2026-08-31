package app.deltat.commute.ui

import app.deltat.commute.domain.DeltaConfidence
import app.deltat.commute.domain.ResultColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveDeltaDisplayTest {
    @Test fun purpleUsesOnlyMonthlyDelta() {
        val display = adaptiveDeltaDisplay(ResultColor.PURPLE, -2_000_000_000L, -3_000_000_000L)
        assertEquals(DeltaDisplayBasis.MONTH, display.basis)
        assertEquals(-2_000_000_000L, display.deltaNanos)
        assertEquals("本月最佳", display.resultLabel)
    }

    @Test fun greenAndYellowUseOnlyWeeklyDelta() {
        val green = adaptiveDeltaDisplay(ResultColor.GREEN, 2_000_000_000L, -1_000_000_000L)
        val yellow = adaptiveDeltaDisplay(ResultColor.YELLOW, 2_000_000_000L, 1_000_000_000L)
        assertEquals(DeltaDisplayBasis.WEEK, green.basis)
        assertEquals(-1_000_000_000L, green.deltaNanos)
        assertEquals("本周最佳", green.resultLabel)
        assertEquals(DeltaDisplayBasis.WEEK, yellow.basis)
        assertEquals("慢于本周", yellow.resultLabel)
    }

    @Test fun neutralPrefersWeekThenMonthAndExplainsMissingBenchmarks() {
        val week = adaptiveDeltaDisplay(ResultColor.NEUTRAL, 200L, 100L)
        val month = adaptiveDeltaDisplay(ResultColor.NEUTRAL, 200L, null, hasMonthlyReference = true, hasWeeklyReference = false)
        val none = adaptiveDeltaDisplay(ResultColor.NEUTRAL, null, null, hasMonthlyReference = false, hasWeeklyReference = false)
        assertEquals(DeltaDisplayBasis.WEEK, week.basis)
        assertEquals("持平", week.resultLabel)
        assertEquals(DeltaDisplayBasis.MONTH, month.basis)
        assertEquals("本周首条记录", month.resultLabel)
        assertEquals(DeltaDisplayBasis.NONE, none.basis)
        assertNull(none.deltaNanos)
        assertEquals("本月首条记录", none.resultLabel)
    }

    @Test fun confidenceStatusMakesEstimatedHeldAndConfirmedExplicit() {
        val display = adaptiveDeltaDisplay(ResultColor.GREEN, 2L, -1L)
        assertEquals("本周最佳 · 实时估算", adaptiveDeltaStatus(display, DeltaConfidence.ESTIMATED))
        assertEquals("暂时无法匹配 · 保留值", adaptiveDeltaStatus(display, DeltaConfidence.HELD))
        assertEquals("本周最佳 · 已确认", adaptiveDeltaStatus(display, DeltaConfidence.CONFIRMED))
    }
}
