package app.deltat.commute.location

import app.deltat.commute.domain.EngineStatus
import app.deltat.commute.domain.DeltaConfidence
import app.deltat.commute.domain.DeltaReferenceType
import app.deltat.commute.domain.GnssHealth
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.ResultColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RuntimeSector(
    val index: Int,
    val durationNanos: Long,
    val deltaNanos: Long? = null,
    val color: ResultColor = ResultColor.NEUTRAL,
    val monthlyDeltaNanos: Long? = deltaNanos,
    val weeklyDeltaNanos: Long? = null,
)

data class RuntimeSnapshot(
    val sessionId: String? = null,
    val routeId: String? = null,
    val routeName: String = "",
    val routeDetectionDistanceMeters: Double? = null,
    val mode: String? = null,
    val status: String = "IDLE",
    val engineStatus: EngineStatus? = null,
    val startedAtElapsedNanos: Long? = null,
    val elapsedNanos: Long = 0,
    val currentSector: Int = 0,
    val sectors: List<RuntimeSector> = emptyList(),
    val liveDeltaNanos: Long? = null,
    val monthlyDeltaNanos: Long? = null,
    val weeklyDeltaNanos: Long? = null,
    val currentSectorMonthlyDeltaNanos: Long? = null,
    val currentSectorWeeklyDeltaNanos: Long? = null,
    val liveColor: ResultColor = ResultColor.NEUTRAL,
    val currentSectorColor: ResultColor = ResultColor.NEUTRAL,
    val comparisonMessage: String = "",
    val deltaConfidence: DeltaConfidence = DeltaConfidence.UNAVAILABLE,
    val deltaReferenceType: DeltaReferenceType = DeltaReferenceType.NONE,
    val maxSpeedMps: Double? = null,
    val maxSpeedElapsedNanos: Long? = null,
    val maxSpeedWallTimeMillis: Long? = null,
    val referenceTrack: List<LocationFix> = emptyList(),
    val checkpointPassElapsedNanos: List<Long> = emptyList(),
    val monthlyReferenceTrack: List<LocationFix> = emptyList(),
    val monthlyReferencePassElapsedNanos: List<Long> = emptyList(),
    val weeklyReferenceTrack: List<LocationFix> = emptyList(),
    val weeklyReferencePassElapsedNanos: List<Long> = emptyList(),
    val actualPassElapsedNanos: List<Long> = emptyList(),
    val actualPassOrderIndices: List<Int> = emptyList(),
    val missedCheckpointIndices: Set<Int> = emptySet(),
    val gnssHealth: GnssHealth = GnssHealth(),
    val lastFix: LocationFix? = null,
    val track: List<LocationFix> = emptyList(),
    val error: String? = null,
)

object RuntimeBus {
    private val mutable = MutableStateFlow(RuntimeSnapshot())
    val state = mutable.asStateFlow()
    fun update(transform: (RuntimeSnapshot) -> RuntimeSnapshot) { mutable.value = transform(mutable.value) }
    fun reset(error: String? = null) { mutable.value = RuntimeSnapshot(error = error) }
}
