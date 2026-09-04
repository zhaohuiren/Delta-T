package app.deltat.commute.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeltaTDao {
    @Query("""
        SELECT * FROM route_profiles
        WHERE archived = 0
        ORDER BY CASE directionType
            WHEN 'TO_WORK' THEN 0
            WHEN 'FROM_WORK' THEN 2
            ELSE 1
        END, createdAtMillis
    """)
    fun observeRoutes(): Flow<List<RouteProfileEntity>>

    @Query("SELECT * FROM route_profiles WHERE id = :id")
    suspend fun route(id: String): RouteProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: RouteProfileEntity)

    @Update suspend fun updateRoute(route: RouteProfileEntity)

    @Query("SELECT COUNT(*) FROM route_profiles")
    suspend fun routeCount(): Int

    @Query("SELECT id FROM sessions WHERE routeProfileId = :routeId")
    suspend fun sessionIdsForRoute(routeId: String): List<String>

    @Insert suspend fun insertVersion(version: CheckpointVersionEntity)
    @Insert suspend fun insertCheckpoints(checkpoints: List<CheckpointEntity>)

    @Query("UPDATE checkpoint_versions SET active = 0 WHERE routeProfileId = :routeId")
    suspend fun deactivateVersions(routeId: String)

    @Query("SELECT COALESCE(MAX(versionNumber), 0) FROM checkpoint_versions WHERE routeProfileId = :routeId")
    suspend fun maxVersion(routeId: String): Int

    @Query("SELECT * FROM checkpoint_versions WHERE id = :id")
    suspend fun checkpointVersion(id: String): CheckpointVersionEntity?

    @Query("UPDATE checkpoint_versions SET active = :active WHERE id = :id")
    suspend fun setCheckpointVersionActive(id: String, active: Boolean)

    @Query("SELECT * FROM checkpoints WHERE checkpointVersionId = :versionId ORDER BY orderIndex")
    suspend fun checkpoints(versionId: String): List<CheckpointEntity>

    @Insert suspend fun insertSession(session: SessionEntity)
    @Update suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE status IN ('RECORDING','ARMED','START_CANDIDATE','SECTOR_1','SECTOR_2','SECTOR_3','FINISH_CANDIDATE') ORDER BY createdAtMillis DESC LIMIT 1")
    suspend fun activeSession(): SessionEntity?

    @Query("UPDATE sessions SET status = 'INTERRUPTED', validity = 'INVALID', invalidReason = :reason, endedWallTimeMillis = :now WHERE id = :sessionId")
    suspend fun markInterrupted(sessionId: String, now: Long, reason: String)

    @Insert suspend fun insertTrackPoint(point: TrackPointEntity): Long

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY sequence")
    fun observeTrack(sessionId: String): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY sequence")
    suspend fun track(sessionId: String): List<TrackPointEntity>

    @Query("SELECT COALESCE(MAX(sequence), -1) + 1 FROM track_points WHERE sessionId = :sessionId")
    suspend fun nextSequence(sessionId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPass(pass: CheckpointPassEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSector(sector: SectorResultEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvaluation(evaluation: SessionEvaluationEntity)

    @Update
    suspend fun updateEvaluation(evaluation: SessionEvaluationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvaluationPass(pass: EvaluationPassEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvaluationSector(sector: EvaluationSectorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecalculationJob(job: CheckpointRecalculationJobEntity)

    @Update
    suspend fun updateRecalculationJob(job: CheckpointRecalculationJobEntity)

    @Query("SELECT * FROM session_evaluations WHERE sessionId = :sessionId AND checkpointVersionId = :versionId LIMIT 1")
    suspend fun evaluation(sessionId: String, versionId: String): SessionEvaluationEntity?

    @Query("SELECT * FROM session_evaluations WHERE id = :evaluationId")
    suspend fun evaluationById(evaluationId: String): SessionEvaluationEntity?

    @Query("SELECT * FROM session_evaluations WHERE sessionId = :sessionId ORDER BY createdAtMillis DESC")
    fun observeEvaluations(sessionId: String): Flow<List<SessionEvaluationEntity>>

    @Query("SELECT * FROM session_evaluations WHERE sessionId = :sessionId ORDER BY createdAtMillis DESC")
    suspend fun evaluations(sessionId: String): List<SessionEvaluationEntity>

    @Query("SELECT * FROM evaluation_passes WHERE evaluationId = :evaluationId ORDER BY orderIndex")
    fun observeEvaluationPasses(evaluationId: String): Flow<List<EvaluationPassEntity>>

    @Query("SELECT * FROM evaluation_passes WHERE evaluationId = :evaluationId ORDER BY orderIndex")
    suspend fun evaluationPasses(evaluationId: String): List<EvaluationPassEntity>

    @Query("SELECT * FROM evaluation_sectors WHERE evaluationId = :evaluationId ORDER BY sectorIndex")
    fun observeEvaluationSectors(evaluationId: String): Flow<List<EvaluationSectorEntity>>

    @Query("SELECT * FROM evaluation_sectors WHERE evaluationId = :evaluationId ORDER BY sectorIndex")
    suspend fun evaluationSectors(evaluationId: String): List<EvaluationSectorEntity>

    @Query("SELECT * FROM checkpoint_recalculation_jobs WHERE checkpointVersionId = :versionId LIMIT 1")
    suspend fun recalculationJobForVersion(versionId: String): CheckpointRecalculationJobEntity?

    @Query("SELECT * FROM checkpoint_recalculation_jobs WHERE id = :jobId LIMIT 1")
    suspend fun recalculationJob(jobId: String): CheckpointRecalculationJobEntity?

    @Query("SELECT * FROM checkpoint_recalculation_jobs WHERE routeProfileId = :routeId ORDER BY createdAtMillis DESC LIMIT 1")
    fun observeLatestRecalculationJob(routeId: String): Flow<CheckpointRecalculationJobEntity?>

    @Query("SELECT * FROM checkpoint_recalculation_jobs WHERE routeProfileId = :routeId AND status IN ('PENDING','RUNNING') ORDER BY createdAtMillis DESC LIMIT 1")
    suspend fun activeRecalculationJob(routeId: String): CheckpointRecalculationJobEntity?

    @Query("SELECT * FROM checkpoint_recalculation_jobs WHERE status IN ('PENDING','RUNNING') ORDER BY createdAtMillis")
    suspend fun pendingRecalculationJobs(): List<CheckpointRecalculationJobEntity>

    @Query("SELECT * FROM checkpoint_passes WHERE sessionId = :sessionId ORDER BY orderIndex")
    suspend fun passes(sessionId: String): List<CheckpointPassEntity>

    @Query("SELECT * FROM checkpoint_passes WHERE sessionId = :sessionId ORDER BY orderIndex")
    fun observePasses(sessionId: String): Flow<List<CheckpointPassEntity>>

    @Query("SELECT * FROM sector_results WHERE sessionId = :sessionId ORDER BY sectorIndex")
    fun observeSectors(sessionId: String): Flow<List<SectorResultEntity>>

    @Query("SELECT * FROM sector_results WHERE sessionId = :sessionId ORDER BY sectorIndex")
    suspend fun sectors(sessionId: String): List<SectorResultEntity>

    @Insert suspend fun insertGnss(snapshot: GnssSnapshotEntity)

    @Query("""
        SELECT s.id, s.routeProfileId, r.name AS routeName, s.startedWallTimeMillis,
               CASE WHEN r.activeCheckpointVersionId IS NULL OR e.id IS NULL THEN s.totalDurationNanos ELSE e.totalDurationNanos END AS totalDurationNanos,
               s.status,
               CASE WHEN r.activeCheckpointVersionId IS NULL OR e.id IS NULL THEN s.validity WHEN e.status = 'VALID' THEN 'VALID' ELSE 'INVALID' END AS validity,
               COALESCE(e.qualityGrade, s.qualityGrade) AS qualityGrade, s.referenceSessionIdSnapshot,
               COALESCE(e.checkpointVersionId, s.checkpointVersionId) AS checkpointVersionId,
               s.maxSpeedMps, s.maxSpeedElapsedNanos, s.maxSpeedWallTimeMillis,
               CASE WHEN r.activeCheckpointVersionId IS NULL OR e.id IS NULL THEN s.monthlyBestTotalNanos ELSE e.monthlyBestTotalNanos END AS monthlyBestTotalNanos,
               CASE WHEN r.activeCheckpointVersionId IS NULL OR e.id IS NULL THEN s.weeklyBestTotalNanos ELSE e.weeklyBestTotalNanos END AS weeklyBestTotalNanos,
               CASE WHEN r.activeCheckpointVersionId IS NULL OR e.id IS NULL THEN s.monthlyDeltaNanos ELSE e.monthlyDeltaNanos END AS monthlyDeltaNanos,
               CASE WHEN r.activeCheckpointVersionId IS NULL OR e.id IS NULL THEN s.weeklyDeltaNanos ELSE e.weeklyDeltaNanos END AS weeklyDeltaNanos,
               COALESCE(e.totalResultColor, s.totalResultColor) AS totalResultColor,
               e.id AS evaluationId, e.status AS evaluationStatus, e.failureReason AS evaluationFailureReason,
               e.createdAtMillis AS evaluationCreatedAtMillis, e.timingEngineVersion AS evaluationEngineVersion,
               COALESCE(e.missingCheckpointMask, s.missingCheckpointMask) AS missingCheckpointMask,
               COALESCE(e.timingEngineVersion, s.timingEngineVersion) AS timingEngineVersion,
               CASE WHEN r.referenceSessionId = s.id THEN 1 ELSE 0 END AS isCurrentReference
        FROM sessions s JOIN route_profiles r ON r.id = s.routeProfileId
        LEFT JOIN session_evaluations e ON e.sessionId = s.id AND e.checkpointVersionId = r.activeCheckpointVersionId
        ORDER BY s.startedWallTimeMillis DESC, s.createdAtMillis DESC, s.id DESC
    """)
    fun observeSessionList(): Flow<List<SessionListRow>>

    @Query("""
        SELECT s.id AS sessionId, s.routeProfileId, r.name AS routeName,
               s.startedWallTimeMillis, COALESCE(e.totalDurationNanos, 0) AS totalDurationNanos,
               e.qualityGrade,
               MAX(CASE WHEN es.sectorIndex = 0 THEN es.durationNanos END) AS sector1Nanos,
               MAX(CASE WHEN es.sectorIndex = 1 THEN es.durationNanos END) AS sector2Nanos,
               MAX(CASE WHEN es.sectorIndex = 2 THEN es.durationNanos END) AS sector3Nanos,
               e.timingEngineVersion, e.missingCheckpointMask
        FROM session_evaluations e
        JOIN sessions s ON s.id = e.sessionId
        JOIN route_profiles r ON r.id = s.routeProfileId
        LEFT JOIN evaluation_sectors es ON es.evaluationId = e.id
        WHERE r.archived = 0
          AND e.checkpointVersionId = r.activeCheckpointVersionId
          AND e.status = 'VALID'
          AND e.recordQualified = 1
          AND e.qualityGrade IN ('A','B')
          AND e.totalDurationNanos IS NOT NULL
        GROUP BY e.id
        ORDER BY s.routeProfileId, e.totalDurationNanos ASC, s.startedWallTimeMillis ASC, s.id ASC
    """)
    fun observeLeaderboard(): Flow<List<LeaderboardRow>>

    @Query("""
        SELECT s.id, s.routeProfileId, r.name AS routeName, s.startedWallTimeMillis,
               CASE WHEN r.activeCheckpointVersionId IS NULL OR e.id IS NULL THEN s.totalDurationNanos ELSE e.totalDurationNanos END AS totalDurationNanos,
               s.status,
               CASE WHEN r.activeCheckpointVersionId IS NULL OR e.id IS NULL THEN s.validity WHEN e.status = 'VALID' THEN 'VALID' ELSE 'INVALID' END AS validity,
               COALESCE(e.qualityGrade, s.qualityGrade) AS qualityGrade, s.referenceSessionIdSnapshot,
               COALESCE(e.checkpointVersionId, s.checkpointVersionId) AS checkpointVersionId,
               s.maxSpeedMps, s.maxSpeedElapsedNanos, s.maxSpeedWallTimeMillis,
               CASE WHEN r.activeCheckpointVersionId IS NULL OR e.id IS NULL THEN s.monthlyBestTotalNanos ELSE e.monthlyBestTotalNanos END AS monthlyBestTotalNanos,
               CASE WHEN r.activeCheckpointVersionId IS NULL OR e.id IS NULL THEN s.weeklyBestTotalNanos ELSE e.weeklyBestTotalNanos END AS weeklyBestTotalNanos,
               CASE WHEN r.activeCheckpointVersionId IS NULL OR e.id IS NULL THEN s.monthlyDeltaNanos ELSE e.monthlyDeltaNanos END AS monthlyDeltaNanos,
               CASE WHEN r.activeCheckpointVersionId IS NULL OR e.id IS NULL THEN s.weeklyDeltaNanos ELSE e.weeklyDeltaNanos END AS weeklyDeltaNanos,
               COALESCE(e.totalResultColor, s.totalResultColor) AS totalResultColor,
               e.id AS evaluationId, e.status AS evaluationStatus, e.failureReason AS evaluationFailureReason,
               e.createdAtMillis AS evaluationCreatedAtMillis, e.timingEngineVersion AS evaluationEngineVersion,
               COALESCE(e.missingCheckpointMask, s.missingCheckpointMask) AS missingCheckpointMask,
               COALESCE(e.timingEngineVersion, s.timingEngineVersion) AS timingEngineVersion,
               CASE WHEN r.referenceSessionId = s.id THEN 1 ELSE 0 END AS isCurrentReference
        FROM sessions s JOIN route_profiles r ON r.id = s.routeProfileId
        LEFT JOIN session_evaluations e ON e.sessionId = s.id AND e.checkpointVersionId = r.activeCheckpointVersionId
        WHERE s.id = :sessionId
    """)
    fun observeSessionRow(sessionId: String): Flow<SessionListRow?>

    @Query("SELECT MIN(totalDurationNanos) FROM sessions WHERE routeProfileId = :routeId AND validity = 'VALID' AND totalDurationNanos IS NOT NULL AND id != :excludeId")
    suspend fun previousTotalBest(routeId: String, excludeId: String): Long?

    @Query("SELECT MIN(durationNanos) FROM sector_results sr JOIN sessions s ON s.id = sr.sessionId WHERE s.routeProfileId = :routeId AND s.checkpointVersionId = :checkpointVersionId AND s.validity = 'VALID' AND sr.sectorIndex = :sectorIndex AND s.id != :excludeId")
    suspend fun previousSectorBest(routeId: String, checkpointVersionId: String, sectorIndex: Int, excludeId: String): Long?

    @Query("SELECT * FROM sessions WHERE routeProfileId = :routeId AND checkpointVersionId = :checkpointVersionId AND validity = 'VALID' ORDER BY totalDurationNanos ASC")
    suspend fun validSessions(routeId: String, checkpointVersionId: String): List<SessionEntity>

    @Query("SELECT sr.sectorIndex AS sectorIndex, MIN(sr.durationNanos) AS durationNanos FROM sector_results sr JOIN sessions s ON s.id = sr.sessionId WHERE s.routeProfileId = :routeId AND s.checkpointVersionId = :checkpointVersionId AND s.validity = 'VALID' GROUP BY sr.sectorIndex ORDER BY sr.sectorIndex")
    suspend fun sectorBests(routeId: String, checkpointVersionId: String): List<SectorBestRow>

    @Query("SELECT * FROM sessions WHERE routeProfileId = :routeId AND checkpointVersionId = :checkpointVersionId AND validity = 'VALID' AND totalDurationNanos IS NOT NULL AND startedWallTimeMillis >= :fromMillis AND startedWallTimeMillis < :toMillis ORDER BY totalDurationNanos ASC")
    suspend fun validSessionsInPeriod(routeId: String, checkpointVersionId: String, fromMillis: Long, toMillis: Long): List<SessionEntity>

    @Query("SELECT sr.sectorIndex AS sectorIndex, MIN(sr.durationNanos) AS durationNanos FROM sector_results sr JOIN sessions s ON s.id = sr.sessionId WHERE s.routeProfileId = :routeId AND s.checkpointVersionId = :checkpointVersionId AND s.validity = 'VALID' AND s.startedWallTimeMillis >= :fromMillis AND s.startedWallTimeMillis < :toMillis GROUP BY sr.sectorIndex ORDER BY sr.sectorIndex")
    suspend fun sectorBestsInPeriod(routeId: String, checkpointVersionId: String, fromMillis: Long, toMillis: Long): List<SectorBestRow>

    @Query("SELECT * FROM sessions WHERE validity = 'VALID' AND totalDurationNanos IS NOT NULL ORDER BY startedWallTimeMillis ASC, createdAtMillis ASC")
    suspend fun allValidSessionsChronological(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE routeProfileId = :routeId ORDER BY startedWallTimeMillis ASC, createdAtMillis ASC, id ASC")
    suspend fun recalculableSessions(routeId: String): List<SessionEntity>

    @Query("SELECT e.* FROM session_evaluations e JOIN sessions s ON s.id = e.sessionId WHERE s.routeProfileId = :routeId AND e.checkpointVersionId = :versionId AND e.status = 'VALID' AND e.recordQualified = 1 AND e.totalDurationNanos IS NOT NULL ORDER BY e.totalDurationNanos ASC")
    suspend fun validEvaluations(routeId: String, versionId: String): List<SessionEvaluationEntity>

    @Query("SELECT e.* FROM session_evaluations e JOIN sessions s ON s.id = e.sessionId WHERE s.routeProfileId = :routeId AND e.checkpointVersionId = :versionId AND e.status = 'VALID' AND e.recordQualified = 1 AND e.totalDurationNanos IS NOT NULL AND s.startedWallTimeMillis >= :fromMillis AND s.startedWallTimeMillis < :toMillis ORDER BY e.totalDurationNanos ASC")
    suspend fun validEvaluationsInPeriod(routeId: String, versionId: String, fromMillis: Long, toMillis: Long): List<SessionEvaluationEntity>

    @Query("SELECT es.sectorIndex AS sectorIndex, MIN(es.durationNanos) AS durationNanos FROM evaluation_sectors es JOIN session_evaluations e ON e.id = es.evaluationId JOIN sessions s ON s.id = e.sessionId WHERE s.routeProfileId = :routeId AND e.checkpointVersionId = :versionId AND e.status = 'VALID' AND e.recordQualified = 1 AND e.timingEngineVersion = :engineVersion GROUP BY es.sectorIndex ORDER BY es.sectorIndex")
    suspend fun evaluationSectorBests(routeId: String, versionId: String, engineVersion: Int): List<SectorBestRow>

    @Query("SELECT es.sectorIndex AS sectorIndex, MIN(es.durationNanos) AS durationNanos FROM evaluation_sectors es JOIN session_evaluations e ON e.id = es.evaluationId JOIN sessions s ON s.id = e.sessionId WHERE s.routeProfileId = :routeId AND e.checkpointVersionId = :versionId AND e.status = 'VALID' AND e.recordQualified = 1 AND e.timingEngineVersion = :engineVersion AND s.startedWallTimeMillis >= :fromMillis AND s.startedWallTimeMillis < :toMillis GROUP BY es.sectorIndex ORDER BY es.sectorIndex")
    suspend fun evaluationSectorBestsInPeriod(routeId: String, versionId: String, engineVersion: Int, fromMillis: Long, toMillis: Long): List<SectorBestRow>

    @Query("SELECT e.* FROM session_evaluations e JOIN sessions s ON s.id = e.sessionId WHERE s.routeProfileId = :routeId AND e.checkpointVersionId = :versionId ORDER BY s.startedWallTimeMillis ASC, s.createdAtMillis ASC, s.id ASC")
    suspend fun evaluationsChronological(routeId: String, versionId: String): List<SessionEvaluationEntity>

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM track_points WHERE sessionId = :sessionId") suspend fun deleteTrack(sessionId: String)
    @Query("DELETE FROM checkpoint_passes WHERE sessionId = :sessionId") suspend fun deletePasses(sessionId: String)
    @Query("DELETE FROM sector_results WHERE sessionId = :sessionId") suspend fun deleteSectors(sessionId: String)
    @Query("DELETE FROM gnss_snapshots WHERE sessionId = :sessionId") suspend fun deleteGnss(sessionId: String)
    @Query("DELETE FROM evaluation_passes WHERE evaluationId IN (SELECT id FROM session_evaluations WHERE sessionId = :sessionId)") suspend fun deleteEvaluationPassesForSession(sessionId: String)
    @Query("DELETE FROM evaluation_sectors WHERE evaluationId IN (SELECT id FROM session_evaluations WHERE sessionId = :sessionId)") suspend fun deleteEvaluationSectorsForSession(sessionId: String)
    @Query("DELETE FROM session_evaluations WHERE sessionId = :sessionId") suspend fun deleteEvaluationsForSession(sessionId: String)
    @Query("DELETE FROM evaluation_passes WHERE evaluationId = :evaluationId") suspend fun deleteEvaluationPasses(evaluationId: String)
    @Query("DELETE FROM evaluation_sectors WHERE evaluationId = :evaluationId") suspend fun deleteEvaluationSectors(evaluationId: String)
    @Query("DELETE FROM checkpoint_recalculation_jobs WHERE routeProfileId = :routeId") suspend fun deleteRecalculationJobsForRoute(routeId: String)

    @Query("DELETE FROM checkpoints WHERE checkpointVersionId IN (SELECT id FROM checkpoint_versions WHERE routeProfileId = :routeId)")
    suspend fun deleteCheckpointsForRoute(routeId: String)

    @Query("DELETE FROM checkpoint_versions WHERE routeProfileId = :routeId")
    suspend fun deleteCheckpointVersionsForRoute(routeId: String)

    @Query("DELETE FROM sessions WHERE routeProfileId = :routeId")
    suspend fun deleteSessionsForRoute(routeId: String)

    @Query("DELETE FROM route_profiles WHERE id = :routeId")
    suspend fun deleteRoute(routeId: String)
}
