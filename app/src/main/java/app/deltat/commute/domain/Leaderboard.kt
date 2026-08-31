package app.deltat.commute.domain

data class LeaderboardLap(
    val id: String,
    val totalDurationNanos: Long,
    val sectorNanos: List<Long?>,
    val startedWallTimeMillis: Long,
)

data class RankedLap(
    val position: Int,
    val lap: LeaderboardLap,
    val gapToLeaderNanos: Long,
)

object LeaderboardProjector {
    fun rank(laps: List<LeaderboardLap>): List<RankedLap> {
        val sorted = laps.sortedWith(
            compareBy<LeaderboardLap> { it.totalDurationNanos }
                .thenBy { it.startedWallTimeMillis }
                .thenBy { it.id },
        )
        val leader = sorted.firstOrNull()?.totalDurationNanos ?: return emptyList()
        return sorted.mapIndexed { index, lap -> RankedLap(index + 1, lap, lap.totalDurationNanos - leader) }
    }

    fun theoreticalBestNanos(laps: List<LeaderboardLap>, sectorCount: Int = 3): Long? {
        val best = (0 until sectorCount).map { index -> laps.mapNotNull { it.sectorNanos.getOrNull(index) }.minOrNull() }
        return best.takeIf { it.all { value -> value != null } }?.sumOf { requireNotNull(it) }
    }
}
