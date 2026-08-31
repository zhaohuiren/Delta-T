package app.deltat.commute.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "route_profiles")
data class RouteProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val directionType: String,
    val activeCheckpointVersionId: String? = null,
    val referenceSessionId: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val archived: Boolean = false,
)

@Entity(tableName = "checkpoint_versions", indices = [Index("routeProfileId")])
data class CheckpointVersionEntity(
    @PrimaryKey val id: String,
    val routeProfileId: String,
    val versionNumber: Int,
    val createdFromSessionId: String?,
    val createdAtMillis: Long,
    val active: Boolean,
    val changeReason: String,
)

@Entity(tableName = "checkpoints", indices = [Index(value = ["checkpointVersionId", "orderIndex"], unique = true)])
data class CheckpointEntity(
    @PrimaryKey val id: String,
    val checkpointVersionId: String,
    val orderIndex: Int,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val preferredBearing: Double?,
    val bearingTolerance: Double,
    val gateHalfWidthMeters: Double,
    val sourceTrackPointId: Long?,
)

@Entity(tableName = "sessions", indices = [Index("routeProfileId"), Index("status"), Index("startedWallTimeMillis")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val routeProfileId: String,
    val checkpointVersionId: String?,
    val mode: String,
    val status: String,
    val referenceSessionIdSnapshot: String?,
    val startedWallTimeMillis: Long,
    val endedWallTimeMillis: Long? = null,
    val bootCount: Int,
    val startElapsedNanos: Long? = null,
    val finishElapsedNanos: Long? = null,
    val totalDurationNanos: Long? = null,
    val validity: String = "PENDING",
    val qualityGrade: String = "C",
    val invalidReason: String? = null,
    val appVersion: String,
    val deviceModel: String,
    val androidVersion: String,
    val isMockDetected: Boolean = false,
    val maxSpeedMps: Double? = null,
    val maxSpeedElapsedNanos: Long? = null,
    val maxSpeedWallTimeMillis: Long? = null,
    val monthlyReferenceSessionIdSnapshot: String? = null,
    val weeklyReferenceSessionIdSnapshot: String? = null,
    val monthlyBestTotalNanos: Long? = null,
    val weeklyBestTotalNanos: Long? = null,
    val monthlyDeltaNanos: Long? = null,
    val weeklyDeltaNanos: Long? = null,
    val totalResultColor: String = "NEUTRAL",
    val comparisonRulesVersion: Int = 3,
    val missingCheckpointMask: Int = 0,
    val timingEngineVersion: Int = 1,
    val createdAtMillis: Long,
)

@Entity(tableName = "track_points", indices = [Index(value = ["sessionId", "sequence"], unique = true), Index(value = ["sessionId", "elapsedRealtimeNanos"])])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val sequence: Long,
    val latitude: Double,
    val longitude: Double,
    val elapsedRealtimeNanos: Long,
    val wallTimeMillis: Long,
    val accuracyMeters: Double,
    val speedMps: Double?,
    val speedAccuracyMps: Double? = null,
    val bearingDegrees: Double?,
    val bearingAccuracy: Double?,
    val provider: String,
    val isMock: Boolean,
    val usedForTiming: Boolean,
)

@Entity(tableName = "checkpoint_passes", indices = [Index(value = ["sessionId", "orderIndex"], unique = true)])
data class CheckpointPassEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val checkpointId: String,
    val orderIndex: Int,
    val passElapsedNanos: Long,
    val interpolatedLatitude: Double,
    val interpolatedLongitude: Double,
    val triggerMode: String,
    val closestDistanceMeters: Double,
    val estimatedUncertaintyMillis: Long,
    val confidence: String,
)

@Entity(tableName = "sector_results", primaryKeys = ["sessionId", "sectorIndex"], indices = [Index("sessionId")])
data class SectorResultEntity(
    val sessionId: String,
    val sectorIndex: Int,
    val durationNanos: Long,
    val referenceDurationNanos: Long?,
    val previousBestDurationNanos: Long?,
    val deltaToReferenceNanos: Long?,
    val resultColorAtCompletion: String,
    val recordQualified: Boolean,
    val monthlyBestDurationNanos: Long? = null,
    val weeklyBestDurationNanos: Long? = null,
    val monthlyDeltaNanos: Long? = null,
    val weeklyDeltaNanos: Long? = null,
    val comparisonRulesVersion: Int = 3,
)

@Entity(
    tableName = "session_evaluations",
    indices = [
        Index(value = ["sessionId", "checkpointVersionId"], unique = true),
        Index("checkpointVersionId"),
        Index("status"),
    ],
)
data class SessionEvaluationEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val checkpointVersionId: String,
    val status: String,
    val failureReason: String? = null,
    val startElapsedNanos: Long? = null,
    val finishElapsedNanos: Long? = null,
    val totalDurationNanos: Long? = null,
    val qualityGrade: String = "C",
    val recordQualified: Boolean = false,
    val monthlyReferenceSessionId: String? = null,
    val weeklyReferenceSessionId: String? = null,
    val monthlyBestTotalNanos: Long? = null,
    val weeklyBestTotalNanos: Long? = null,
    val monthlyDeltaNanos: Long? = null,
    val weeklyDeltaNanos: Long? = null,
    val totalResultColor: String = "NEUTRAL",
    val comparisonRulesVersion: Int = 3,
    val timingEngineVersion: Int = 1,
    val missingCheckpointMask: Int = 0,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "evaluation_passes",
    indices = [Index(value = ["evaluationId", "orderIndex"], unique = true)],
)
data class EvaluationPassEntity(
    @PrimaryKey val id: String,
    val evaluationId: String,
    val checkpointId: String,
    val orderIndex: Int,
    val passElapsedNanos: Long,
    val interpolatedLatitude: Double,
    val interpolatedLongitude: Double,
    val triggerMode: String,
    val closestDistanceMeters: Double,
    val estimatedUncertaintyMillis: Long,
    val confidence: String,
)

@Entity(
    tableName = "evaluation_sectors",
    primaryKeys = ["evaluationId", "sectorIndex"],
    indices = [Index("evaluationId")],
)
data class EvaluationSectorEntity(
    val evaluationId: String,
    val sectorIndex: Int,
    val durationNanos: Long,
    val monthlyBestDurationNanos: Long? = null,
    val weeklyBestDurationNanos: Long? = null,
    val monthlyDeltaNanos: Long? = null,
    val weeklyDeltaNanos: Long? = null,
    val resultColorAtCompletion: String = "NEUTRAL",
    val recordQualified: Boolean = false,
    val comparisonRulesVersion: Int = 3,
)

@Entity(
    tableName = "checkpoint_recalculation_jobs",
    indices = [Index(value = ["checkpointVersionId"], unique = true), Index("status")],
)
data class CheckpointRecalculationJobEntity(
    @PrimaryKey val id: String,
    val routeProfileId: String,
    val checkpointVersionId: String,
    val sourceSessionId: String,
    val status: String,
    val totalSessions: Int,
    val completedSessions: Int = 0,
    val successfulSessions: Int = 0,
    val failedSessions: Int = 0,
    val errorMessage: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val finishedAtMillis: Long? = null,
)

@Entity(tableName = "gnss_snapshots", indices = [Index("sessionId")])
data class GnssSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val elapsedRealtimeNanos: Long,
    val visibleGps: Int,
    val usedGps: Int,
    val visibleBeidou: Int,
    val usedBeidou: Int,
    val visibleOther: Int,
    val usedOther: Int,
)

data class SessionListRow(
    val id: String,
    val routeProfileId: String,
    val routeName: String,
    val startedWallTimeMillis: Long,
    val totalDurationNanos: Long?,
    val status: String,
    val validity: String,
    val qualityGrade: String,
    val referenceSessionIdSnapshot: String?,
    val checkpointVersionId: String?,
    val maxSpeedMps: Double?,
    val maxSpeedElapsedNanos: Long?,
    val maxSpeedWallTimeMillis: Long?,
    val monthlyBestTotalNanos: Long? = null,
    val weeklyBestTotalNanos: Long? = null,
    val monthlyDeltaNanos: Long? = null,
    val weeklyDeltaNanos: Long? = null,
    val totalResultColor: String = "NEUTRAL",
    val evaluationId: String? = null,
    val evaluationStatus: String? = null,
    val evaluationFailureReason: String? = null,
    val evaluationCreatedAtMillis: Long? = null,
    val evaluationEngineVersion: Int? = null,
    val missingCheckpointMask: Int = 0,
    val timingEngineVersion: Int = 1,
    val isCurrentReference: Boolean = false,
)

data class BestRow(val sectorIndex: Int, val durationNanos: Long, val sessionId: String)
data class SectorBestRow(val sectorIndex: Int, val durationNanos: Long)

data class LeaderboardRow(
    val sessionId: String,
    val routeProfileId: String,
    val routeName: String,
    val startedWallTimeMillis: Long,
    val totalDurationNanos: Long,
    val qualityGrade: String,
    val sector1Nanos: Long?,
    val sector2Nanos: Long?,
    val sector3Nanos: Long?,
    val timingEngineVersion: Int = 1,
    val missingCheckpointMask: Int = 0,
)
