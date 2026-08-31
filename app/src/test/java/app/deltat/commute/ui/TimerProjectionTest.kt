package app.deltat.commute.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerProjectionTest {
    @Test fun activeTimerAdvancesWithoutGpsAndNeverMovesBackward() {
        val start = 10_000_000_000L
        assertEquals(2_010_000_000L, projectedElapsedNanos(start, 2_000_000_000L, 12_010_000_000L, false))
        assertEquals(2_000_000_000L, projectedElapsedNanos(start, 2_000_000_000L, 11_000_000_000L, false))
    }

    @Test fun completedTimerFreezesAtConfirmedSnapshot() {
        assertEquals(3_210_000_000L, projectedElapsedNanos(10L, 3_210_000_000L, 99_000_000_000L, true))
    }
}
