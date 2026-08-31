package app.deltat.commute.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

object RouteRecognizer {
    fun selectNearest(fix: LocationFix, candidates: List<RouteCandidate>): RouteCandidate? = candidates
        .asSequence()
        .filter { it.checkpoints.size == 4 }
        .map { candidate ->
            val start = candidate.checkpoints.first()
            candidate to Geometry.distanceMeters(fix.latitude, fix.longitude, start.latitude, start.longitude)
        }
        .sortedWith(compareBy<Pair<RouteCandidate, Double>> { it.second }
            .thenBy { it.first.createdAtMillis }
            .thenBy { it.first.routeId })
        .firstOrNull()
        ?.first
}

class SpeedPeakTracker {
    private val window = ArrayDeque<LocationFix>(3)
    var peak: SpeedPeak? = null
        private set

    fun process(fix: LocationFix, timingActive: Boolean): SpeedPeak? {
        if (!timingActive || !isUsable(fix)) {
            window.clear()
            return peak
        }
        if (window.size == 3) window.removeFirst()
        window.addLast(fix)
        if (window.size < 3) return peak
        val points = window.toList()
        val median = points.mapNotNull { it.speedMetersPerSecond }.sorted()[1]
        val sample = points[1]
        if (peak == null || median > peak!!.metersPerSecond) {
            peak = SpeedPeak(median, sample.elapsedRealtimeNanos, sample.wallTimeMillis)
        }
        return peak
    }

    private fun isUsable(fix: LocationFix): Boolean =
        !fix.isMock &&
            fix.accuracyMeters <= 25.0 &&
            fix.speedMetersPerSecond != null &&
            fix.speedMetersPerSecond >= 0.0 &&
            (fix.speedAccuracyMetersPerSecond == null || fix.speedAccuracyMetersPerSecond <= 3.0)
}

data class ProgressMatch(
    val expectedElapsedSinceStartNanos: Long,
    val crossTrackMeters: Double,
)

object ReferenceProgressMatcher {
    fun match(
        fix: LocationFix,
        referenceTrack: List<LocationFix>,
        referencePassElapsedNanos: List<Long>,
        currentSectorIndex: Int,
        minimumExpectedElapsedNanos: Long = 0L,
        maximumExpectedElapsedNanos: Long = Long.MAX_VALUE,
        maxCrossTrackMeters: Double = (fix.accuracyMeters * 2.0).coerceIn(30.0, 80.0),
    ): ProgressMatch? {
        if (fix.accuracyMeters > 50.0 || referenceTrack.size < 2 || referencePassElapsedNanos.size != 4) return null
        val sector = currentSectorIndex.coerceIn(0, 2)
        val sectorStart = referencePassElapsedNanos[sector]
        val sectorEnd = referencePassElapsedNanos[sector + 1]
        val start = referencePassElapsedNanos.first()
        var bestDistance = Double.POSITIVE_INFINITY
        var bestElapsed = 0L

        referenceTrack.zipWithNext().forEach { (a, b) ->
            if (b.elapsedRealtimeNanos < sectorStart || a.elapsedRealtimeNanos > sectorEnd) return@forEach
            val segmentStartProgress = a.elapsedRealtimeNanos - start
            val segmentEndProgress = b.elapsedRealtimeNanos - start
            if (segmentEndProgress < minimumExpectedElapsedNanos - 2_000_000_000L || segmentStartProgress > maximumExpectedElapsedNanos) return@forEach
            val fixBearing = fix.bearingDegrees
            if (fixBearing != null && (fix.speedMetersPerSecond ?: 0.0) >= 2.0 && (fix.bearingAccuracyDegrees ?: 180.0) <= 45.0) {
                val segmentBearing = Geometry.bearingDegrees(a.latitude, a.longitude, b.latitude, b.longitude)
                val difference = kotlin.math.abs(((fixBearing - segmentBearing + 540.0) % 360.0) - 180.0)
                if (difference > 90.0) return@forEach
            }
            val originLat = fix.latitude
            val originLon = fix.longitude
            val (ax, ay) = Geometry.toLocalMeters(a.latitude, a.longitude, originLat, originLon)
            val (bx, by) = Geometry.toLocalMeters(b.latitude, b.longitude, originLat, originLon)
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val ratio = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val px = ax + ratio * dx
            val py = ay + ratio * dy
            val distance = kotlin.math.hypot(px, py)
            if (distance < bestDistance) {
                bestDistance = distance
                bestElapsed = a.elapsedRealtimeNanos + ((b.elapsedRealtimeNanos - a.elapsedRealtimeNanos) * ratio).roundToLong()
            }
        }

        if (!bestDistance.isFinite() || bestDistance > maxCrossTrackMeters) return null
        val expected = (bestElapsed - start).coerceIn(minimumExpectedElapsedNanos, maximumExpectedElapsedNanos)
        return ProgressMatch(expected, bestDistance)
    }
}

data class TimedResultSummary(
    val sessionId: String,
    val startedWallTimeMillis: Long,
    val durationNanos: Long,
)

data class CalendarDaySummary(
    val date: LocalDate,
    val fastestNanos: Long,
    val count: Int,
    val sessionIds: List<String>,
)

data class WeeklyRunPoint(
    val sessionId: String,
    val startedWallTimeMillis: Long,
    val durationNanos: Long,
    val deltaToWeekBestNanos: Long,
    val isWeekBest: Boolean,
)

data class WeeklyStats(
    val weekStart: LocalDate,
    val bestNanos: Long?,
    val averageNanos: Long?,
    val runs: List<WeeklyRunPoint>,
    val days: List<CalendarDaySummary>,
)

object CalendarAggregator {
    fun weekStart(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun weekly(
        results: List<TimedResultSummary>,
        weekStart: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): WeeklyStats {
        val weekEnd = weekStart.plusDays(7)
        val inWeek = results.filter {
            val date = Instant.ofEpochMilli(it.startedWallTimeMillis).atZone(zoneId).toLocalDate()
            !date.isBefore(weekStart) && date.isBefore(weekEnd)
        }.sortedBy { it.startedWallTimeMillis }
        val best = inWeek.minOfOrNull { it.durationNanos }
        val average = inWeek.takeIf { it.isNotEmpty() }?.map { it.durationNanos }?.average()?.roundToLong()
        val runs = inWeek.map {
            WeeklyRunPoint(it.sessionId, it.startedWallTimeMillis, it.durationNanos, best?.let { b -> it.durationNanos - b } ?: 0L, it.durationNanos == best)
        }
        return WeeklyStats(weekStart, best, average, runs, daily(inWeek, zoneId))
    }

    fun daily(results: List<TimedResultSummary>, zoneId: ZoneId = ZoneId.systemDefault()): List<CalendarDaySummary> =
        results.groupBy { Instant.ofEpochMilli(it.startedWallTimeMillis).atZone(zoneId).toLocalDate() }
            .map { (date, rows) ->
                CalendarDaySummary(date, rows.minOf { it.durationNanos }, rows.size, rows.sortedBy { it.startedWallTimeMillis }.map { it.sessionId })
            }
            .sortedBy { it.date }
}
