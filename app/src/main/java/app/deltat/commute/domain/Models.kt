package app.deltat.commute.domain

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val elapsedRealtimeNanos: Long,
    val wallTimeMillis: Long,
    val accuracyMeters: Double,
    val speedMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
    val bearingAccuracyDegrees: Double? = null,
    val provider: String = "gps",
    val isMock: Boolean = false,
    val speedAccuracyMetersPerSecond: Double? = null,
)

data class Checkpoint(
    val id: String,
    val orderIndex: Int,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double = 35.0,
    val preferredBearing: Double? = null,
    val bearingTolerance: Double = 100.0,
    val gateHalfWidthMeters: Double = 50.0,
)

enum class EngineStatus {
    ARMED, START_CANDIDATE, SECTOR_1, SECTOR_2, SECTOR_3, FINISH_CANDIDATE,
    COMPLETED, CANCELLED, INTERRUPTED, INCOMPLETE,
}

enum class TriggerMode { LINE_CROSSING, PROGRESS_LINE, CLOSEST_APPROACH, MANUAL_REVIEW }
enum class ProgressLineAxis { EAST_WEST, NORTH_SOUTH }
enum class QualityGrade { A, B, C }
enum class ResultColor { PURPLE, GREEN, YELLOW, NEUTRAL }
enum class PeriodComparisonStatus { FASTER, SLOWER, TIED, UNAVAILABLE, INELIGIBLE }

data class PeriodComparisonResult(
    val monthly: PeriodComparisonStatus,
    val weekly: PeriodComparisonStatus,
)

enum class DeltaConfidence { UNAVAILABLE, ESTIMATED, CONFIRMED, HELD }

enum class DeltaReferenceType { WEEKLY_BEST, MONTHLY_BEST, NONE }

data class RouteCandidate(
    val routeId: String,
    val routeName: String,
    val createdAtMillis: Long,
    val checkpoints: List<Checkpoint>,
)

data class CheckpointSelection(
    val segmentStartPointId: Long,
    val segmentRatio: Double,
)

data class SpeedPeak(
    val metersPerSecond: Double,
    val elapsedRealtimeNanos: Long,
    val wallTimeMillis: Long,
)

data class LiveDelta(
    val nanos: Long?,
    val confidence: DeltaConfidence,
    val referenceType: DeltaReferenceType,
    val crossTrackMeters: Double? = null,
)

data class CheckpointPass(
    val checkpoint: Checkpoint,
    val elapsedRealtimeNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val triggerMode: TriggerMode,
    val closestDistanceMeters: Double,
    val uncertaintyMillis: Long,
)

data class Candidate(
    val bestFix: LocationFix,
    val bestDistanceMeters: Double,
    val previousDistanceMeters: Double,
)

data class ProgressLineCandidate(
    val checkpoint: Checkpoint,
    val crossingFix: LocationFix,
    val crossTrackMeters: Double,
    val consecutiveBeyondFixes: Int,
    val maximumDepthMeters: Double,
)

data class EngineState(
    val status: EngineStatus = EngineStatus.ARMED,
    val nextCheckpointIndex: Int = 0,
    val passes: List<CheckpointPass> = emptyList(),
    val previousFix: LocationFix? = null,
    val candidate: Candidate? = null,
    val progressLineCandidates: List<ProgressLineCandidate> = emptyList(),
    val missedCheckpointIndices: Set<Int> = emptySet(),
    val startRequiresExit: Boolean? = null,
    val rejectedFixes: Int = 0,
)

sealed interface EngineEvent {
    data class PassConfirmed(val pass: CheckpointPass) : EngineEvent
    data class CheckpointMissed(val checkpoint: Checkpoint) : EngineEvent
    data class SectorCompleted(val sectorIndex: Int, val durationNanos: Long) : EngineEvent
    data object Finished : EngineEvent
    data class FixRejected(val reason: String) : EngineEvent
}

data class EngineTransition(
    val newState: EngineState,
    val emittedEvents: List<EngineEvent> = emptyList(),
    val persistenceUrgent: Boolean = false,
)

interface TimingEngine {
    fun restore(snapshot: EngineState): EngineState
    fun processFix(state: EngineState, fix: LocationFix, checkpoints: List<Checkpoint>): EngineTransition
    fun cancel(state: EngineState, reason: String): EngineTransition
}

interface LocationSource {
    fun start(onFix: (LocationFix) -> Unit, onGnss: (GnssHealth) -> Unit)
    fun stop()
}

data class GnssHealth(
    val visibleGps: Int = 0,
    val usedGps: Int = 0,
    val visibleBeidou: Int = 0,
    val usedBeidou: Int = 0,
    val visibleOther: Int = 0,
    val usedOther: Int = 0,
    val label: String = "正在定位",
    val locationEnabled: Boolean = true,
    val satelliteStatusReceived: Boolean = false,
) {
    val visibleTotal: Int get() = visibleGps + visibleBeidou + visibleOther
    val usedTotal: Int get() = usedGps + usedBeidou + usedOther
    fun displayLabel(): String = when {
        !locationEnabled -> "定位已关闭"
        label != "正在定位" -> label
        visibleTotal == 0 || usedTotal == 0 -> "GPS 搜星中"
        usedTotal >= 8 -> "GPS 良好"
        usedTotal >= 4 -> "GPS 一般"
        else -> "GPS 弱"
    }

    /** Status hysteresis is independent of the latest physical-satellite counts. */
    fun displaySummary(): String {
        if (!locationEnabled) return displayLabel()
        val known = satelliteStatusReceived || visibleTotal > 0 || usedTotal > 0
        val valid = visibleTotal >= 0 && usedTotal in 0..visibleTotal
        val counts = if (known && valid) "$usedTotal/$visibleTotal" else "--/--"
        return "${displayLabel()} · $counts"
    }
}

enum class GnssDisplayQuality(val label: String) {
    SEARCHING("GPS 搜星中"),
    WEAK("GPS 弱"),
    FAIR("GPS 一般"),
    GOOD("GPS 良好"),
    DISABLED("定位已关闭"),
}

/** Keeps noisy satellite membership changes from rewriting the driving UI every callback. */
class GnssDisplayStabilizer(
    private val worsenSamples: Int = 2,
    private val recoverSamples: Int = 3,
) {
    private var current: GnssDisplayQuality? = null
    private var candidate: GnssDisplayQuality? = null
    private var candidateSamples = 0

    fun update(health: GnssHealth): String {
        val next = qualityFor(health)
        val active = current
        if (active == null || next == GnssDisplayQuality.DISABLED || active == GnssDisplayQuality.DISABLED) {
            current = next
            candidate = null
            candidateSamples = 0
            return next.label
        }
        if (next == active) {
            candidate = null
            candidateSamples = 0
            return active.label
        }
        if (candidate != next) {
            candidate = next
            candidateSamples = 1
        } else {
            candidateSamples++
        }
        val required = if (next.ordinal < active.ordinal) worsenSamples else recoverSamples
        if (candidateSamples >= required) {
            current = next
            candidate = null
            candidateSamples = 0
        }
        return requireNotNull(current).label
    }

    fun reset() {
        current = null
        candidate = null
        candidateSamples = 0
    }

    companion object {
        fun qualityFor(health: GnssHealth): GnssDisplayQuality = when {
            !health.locationEnabled -> GnssDisplayQuality.DISABLED
            health.visibleTotal == 0 || health.usedTotal == 0 -> GnssDisplayQuality.SEARCHING
            health.usedTotal >= 8 -> GnssDisplayQuality.GOOD
            health.usedTotal >= 4 -> GnssDisplayQuality.FAIR
            else -> GnssDisplayQuality.WEAK
        }
    }
}
