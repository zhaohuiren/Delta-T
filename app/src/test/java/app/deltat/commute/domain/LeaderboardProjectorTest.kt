package app.deltat.commute.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LeaderboardProjectorTest {
    @Test fun ranksByDurationThenDateThenIdAndCalculatesGap() {
        val rows = listOf(
            LeaderboardLap("b", 100, listOf(30, 30, 40), 2),
            LeaderboardLap("a", 100, listOf(29, 31, 40), 2),
            LeaderboardLap("late", 110, listOf(35, 35, 40), 3),
        )
        val ranked = LeaderboardProjector.rank(rows)
        assertEquals(listOf("a", "b", "late"), ranked.map { it.lap.id })
        assertEquals(listOf(0L, 0L, 10L), ranked.map { it.gapToLeaderNanos })
    }

    @Test fun theoreticalBestUsesIndependentAllTimeSectors() {
        val rows = listOf(
            LeaderboardLap("one", 100, listOf(20, 40, 40), 1),
            LeaderboardLap("two", 100, listOf(30, 25, 45), 2),
            LeaderboardLap("three", 100, listOf(35, 35, 30), 3),
        )
        assertEquals(75L, LeaderboardProjector.theoreticalBestNanos(rows))
    }
}
