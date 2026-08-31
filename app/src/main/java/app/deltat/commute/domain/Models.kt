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
) {
    val visibleTotal: Int get() = visibleGps + visibleBeidou + visibleOther
    val usedTotal: Int get() = usedGps + usedBeidou + usedOther
    fun displayLabel(): String = when {
        !locationEnabled -> "定位已关闭"
        visibleTotal == 0 -> "卫星 0/0 · 正在搜星"
        else -> "卫星 $usedTotal/$visibleTotal"
    }
}
