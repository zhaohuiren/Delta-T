package app.deltat.commute.data

import androidx.room.withTransaction
import app.deltat.commute.domain.Checkpoint
import app.deltat.commute.domain.CheckpointPass
import app.deltat.commute.domain.CheckpointSelection
import app.deltat.commute.domain.DefaultTimingEngine
import app.deltat.commute.domain.EngineState
import app.deltat.commute.domain.EngineStatus
import app.deltat.commute.domain.Geometry
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.QualityGrade
import app.deltat.commute.domain.RecordJudge
import app.deltat.commute.domain.ResultColor
import app.deltat.commute.domain.TriggerMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import java.util.UUID

data class RecalculationPreview(
    val totalSessions: Int,
    val successfulSessions: Int,
    val failedSessions: Int,
)

data class CheckpointRecalculationRequest(
    val jobId: String,
    val checkpointVersionId: String,
)

class HistoricalRecalculator(private val database: DeltaTDatabase) {
    private val dao = database.dao()
    private val runMutex = Mutex()

    suspend fun preview(
        sourceSessionId: String,
        selections: List<CheckpointSelection>,
        radiusMeters: Double = 35.0,
    ): RecalculationPreview {
        val prepared = prepare(sourceSessionId, selections, radiusMeters)
        val sessions = dao.recalculableSessions(prepared.session.routeProfileId)
        var successful = 0
        sessions.forEach { session ->
            val result = if (session.id == sourceSessionId) {
                ReplayResult.Success(manualPasses(prepared.checkpoints, prepared.positions), QualityGrade.B, 0)
            } else {
                replay(session, prepared.checkpoints.map { it.toDomain() })
            }
            if (result is ReplayResult.Success) successful++
        }
        return RecalculationPreview(sessions.size, successful, sessions.size - successful)
    }

    suspend fun create(
        sourceSessionId: String,
        selections: List<CheckpointSelection>,
        radiusMeters: Double = 35.0,
    ): CheckpointRecalculationRequest = database.withTransaction {
        val prepared = prepare(sourceSessionId, selections, radiusMeters)
        require(dao.activeRecalculationJob(prepared.session.routeProfileId) == null) { "该路线正在重算，请等待完成后再修改计时点" }
        val now = System.currentTimeMillis()
        val versionId = UUID.randomUUID().toString()
        val checkpoints = prepared.checkpoints.map { it.copy(checkpointVersionId = versionId) }
        dao.insertVersion(
            CheckpointVersionEntity(
                id = versionId,
                routeProfileId = prepared.session.routeProfileId,
                versionNumber = dao.maxVersion(prepared.session.routeProfileId) + 1,
                createdFromSessionId = sourceSessionId,
                createdAtMillis = now,
                active = false,
                changeReason = "移动计时点并重算全部历史成绩",
            ),
        )
        dao.insertCheckpoints(checkpoints)
        persistEvaluation(
            session = prepared.session,
            versionId = versionId,
            checkpoints = checkpoints,
            result = ReplayResult.Success(manualPasses(checkpoints, prepared.positions), QualityGrade.B, 0),
            createdAtMillis = now,
        )
        val sessions = dao.recalculableSessions(prepared.session.routeProfileId)
        val job = CheckpointRecalculationJobEntity(
            id = UUID.randomUUID().toString(),
            routeProfileId = prepared.session.routeProfileId,
            checkpointVersionId = versionId,
            sourceSessionId = sourceSessionId,
            status = "PENDING",
            totalSessions = sessions.size,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        dao.insertRecalculationJob(job)
        CheckpointRecalculationRequest(job.id, versionId)
    }

    suspend fun createEngineUpgrade(routeId: String): String? = database.withTransaction {
        val route = dao.route(routeId) ?: return@withTransaction null
        if (dao.activeRecalculationJob(routeId) != null) return@withTransaction null
        val currentVersionId = route.activeCheckpointVersionId ?: return@withTransaction null
        val currentVersion = dao.checkpointVersion(currentVersionId) ?: return@withTransaction null
        if (currentVersion.changeReason.startsWith(ENGINE_V3_REASON)) return@withTransaction null
        val currentCheckpoints = dao.checkpoints(currentVersionId)
        if (currentCheckpoints.size != 4) return@withTransaction null
        val sourceSessionId = route.referenceSessionId ?: return@withTransaction null
        val now = System.currentTimeMillis()
        val versionId = UUID.randomUUID().toString()
        val checkpoints = currentCheckpoints.map { checkpoint ->
            checkpoint.copy(
                id = UUID.randomUUID().toString(),
                checkpointVersionId = versionId,
            )
        }
        dao.insertVersion(
            CheckpointVersionEntity(
                id = versionId,
                routeProfileId = routeId,
                versionNumber = dao.maxVersion(routeId) + 1,
                createdFromSessionId = sourceSessionId,
                createdAtMillis = now,
                active = false,
                changeReason = ENGINE_V3_REASON,
            ),
        )
        dao.insertCheckpoints(checkpoints)
        val job = CheckpointRecalculationJobEntity(
            id = UUID.randomUUID().toString(),
            routeProfileId = routeId,
            checkpointVersionId = versionId,
            sourceSessionId = sourceSessionId,
            status = "PENDING",
            totalSessions = dao.recalculableSessions(routeId).size,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        dao.insertRecalculationJob(job)
        job.id
    }

    suspend fun runPending(jobId: String? = null) = runMutex.withLock {
        val jobs = if (jobId == null) dao.pendingRecalculationJobs() else listOfNotNull(dao.recalculationJob(jobId))
        jobs.filter { it.status == "PENDING" || it.status == "RUNNING" }.forEach { runJob(it) }
    }

    suspend fun retry(jobId: String) {
        val job = requireNotNull(dao.recalculationJob(jobId)) { "重算任务不存在" }
        require(job.status == "FAILED") { "只有失败的重算任务可以重试" }
        dao.updateRecalculationJob(job.copy(status = "PENDING", errorMessage = null, finishedAtMillis = null, updatedAtMillis = System.currentTimeMillis()))
        runPending(jobId)
    }

    private suspend fun runJob(initial: CheckpointRecalculationJobEntity) {
        var job = initial.copy(status = "RUNNING", errorMessage = null, updatedAtMillis = System.currentTimeMillis())
        dao.updateRecalculationJob(job)
        try {
            val sessions = dao.recalculableSessions(job.routeProfileId)
            val checkpoints = dao.checkpoints(job.checkpointVersionId)
            require(checkpoints.size == 4) { "新计时点版本不完整" }
            var successful = 0
            var failed = 0
            sessions.forEachIndexed { index, session ->
                var evaluation = dao.evaluation(session.id, job.checkpointVersionId)
                if (evaluation == null) {
                    val result = if (session.id == job.sourceSessionId) {
                        replayRouteSource(session, checkpoints) ?: replay(session, checkpoints.map { it.toDomain() })
                    } else {
                        replay(session, checkpoints.map { it.toDomain() })
                    }
                    database.withTransaction {
                        persistEvaluation(session, job.checkpointVersionId, checkpoints, result, System.currentTimeMillis())
                    }
                    evaluation = dao.evaluation(session.id, job.checkpointVersionId)
                }
                if (evaluation?.status == "VALID") successful++ else failed++
                job = job.copy(
                    totalSessions = sessions.size,
                    completedSessions = index + 1,
                    successfulSessions = successful,
                    failedSessions = failed,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                dao.updateRecalculationJob(job)
            }
            rebuildComparisons(job.routeProfileId, job.checkpointVersionId)
            val finishedAt = System.currentTimeMillis()
            database.withTransaction {
                val route = requireNotNull(dao.route(job.routeProfileId))
                dao.deactivateVersions(job.routeProfileId)
                dao.setCheckpointVersionActive(job.checkpointVersionId, true)
                dao.updateRoute(
                    route.copy(
                        activeCheckpointVersionId = job.checkpointVersionId,
                        referenceSessionId = job.sourceSessionId,
                        updatedAtMillis = finishedAt,
                    ),
                )
                job = job.copy(
                    status = if (failed == 0) "COMPLETED" else "COMPLETED_WITH_FAILURES",
                    completedSessions = sessions.size,
                    successfulSessions = successful,
                    failedSessions = failed,
                    updatedAtMillis = finishedAt,
                    finishedAtMillis = finishedAt,
                )
                dao.updateRecalculationJob(job)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            dao.updateRecalculationJob(
                job.copy(
                    status = "FAILED",
                    errorMessage = error.message ?: "历史成绩重算失败",
                    updatedAtMillis = System.currentTimeMillis(),
                    finishedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun replay(session: SessionEntity, checkpoints: List<Checkpoint>): ReplayResult {
        if (session.status == "CANCELLED") return ReplayResult.Failure("原行程已取消，不参与当前版本纪录")
        if (session.status == "INTERRUPTED") return ReplayResult.Failure("原行程已中断，不参与当前版本纪录")
        if (session.status !in setOf("COMPLETED", "RECORDED")) return ReplayResult.Failure("原行程未完成，不参与当前版本纪录")
        val track = dao.track(session.id)
        if (track.size < 4) return ReplayResult.Failure("没有完整历史轨迹")
        if (session.isMockDetected || track.any { it.isMock }) return ReplayResult.Failure("历史轨迹包含模拟定位", "LOW_QUALITY")
        val engine = DefaultTimingEngine()
        var state = EngineState()
        track.forEach { point ->
            if (state.status == EngineStatus.COMPLETED) return@forEach
            state = engine.processFix(state, point.toFix(), checkpoints).newState
        }
        if (state.status != EngineStatus.COMPLETED || state.passes.size != 4) {
            val missing = checkpoints.getOrNull(state.passes.size)?.label ?: "B"
            val usable = track.count { it.accuracyMeters <= 50.0 }
            val reason = if (usable < 4) "GPS 精度不足，无法确认 $missing" else "历史轨迹未经过新计时点 $missing"
            return ReplayResult.Failure(reason)
        }
        val grade = if (state.passes.any { it.triggerMode == TriggerMode.CLOSEST_APPROACH } || state.rejectedFixes > 10) QualityGrade.B else QualityGrade.A
        return ReplayResult.Success(state.passes, grade, state.rejectedFixes)
    }

    private suspend fun persistEvaluation(
        session: SessionEntity,
        versionId: String,
        checkpoints: List<CheckpointEntity>,
        result: ReplayResult,
        createdAtMillis: Long,
    ) {
        val existing = dao.evaluation(session.id, versionId)
        if (existing != null) {
            dao.deleteEvaluationPasses(existing.id)
            dao.deleteEvaluationSectors(existing.id)
        }
        val evaluationId = existing?.id ?: UUID.randomUUID().toString()
        when (result) {
            is ReplayResult.Failure -> dao.insertEvaluation(
                SessionEvaluationEntity(
                    id = evaluationId,
                    sessionId = session.id,
                    checkpointVersionId = versionId,
                    status = result.status,
                    failureReason = result.reason,
                    qualityGrade = "C",
                    recordQualified = false,
                    timingEngineVersion = DefaultTimingEngine.TIMING_ENGINE_VERSION,
                    createdAtMillis = existing?.createdAtMillis ?: createdAtMillis,
                    updatedAtMillis = createdAtMillis,
                ),
            )
            is ReplayResult.Success -> {
                val start = result.passes.first().elapsedRealtimeNanos
                val finish = result.passes.last().elapsedRealtimeNanos
                dao.insertEvaluation(
                    SessionEvaluationEntity(
                        id = evaluationId,
                        sessionId = session.id,
                        checkpointVersionId = versionId,
                        status = "VALID",
                        startElapsedNanos = start,
                        finishElapsedNanos = finish,
                        totalDurationNanos = finish - start,
                        qualityGrade = result.grade.name,
                        recordQualified = result.grade != QualityGrade.C,
                        timingEngineVersion = DefaultTimingEngine.TIMING_ENGINE_VERSION,
                        createdAtMillis = existing?.createdAtMillis ?: createdAtMillis,
                        updatedAtMillis = createdAtMillis,
                    ),
                )
                result.passes.forEach { pass ->
                    dao.insertEvaluationPass(
                        EvaluationPassEntity(
                            id = UUID.randomUUID().toString(),
                            evaluationId = evaluationId,
                            checkpointId = pass.checkpoint.id,
                            orderIndex = pass.checkpoint.orderIndex,
                            passElapsedNanos = pass.elapsedRealtimeNanos,
                            interpolatedLatitude = pass.latitude,
                            interpolatedLongitude = pass.longitude,
                            triggerMode = pass.triggerMode.name,
                            closestDistanceMeters = pass.closestDistanceMeters,
                            estimatedUncertaintyMillis = pass.uncertaintyMillis,
                            confidence = if (pass.triggerMode == TriggerMode.LINE_CROSSING) "A" else "B",
                        ),
                    )
                }
                result.passes.zipWithNext().forEachIndexed { sectorIndex, pair ->
                    dao.insertEvaluationSector(
                        EvaluationSectorEntity(
                            evaluationId = evaluationId,
                            sectorIndex = sectorIndex,
                            durationNanos = pair.second.elapsedRealtimeNanos - pair.first.elapsedRealtimeNanos,
                            recordQualified = result.grade != QualityGrade.C,
                        ),
                    )
                }
            }
        }
    }

    suspend fun rebuildComparisons(routeId: String, versionId: String) {
        val chronological = dao.evaluationsChronological(routeId, versionId)
        val previous = mutableListOf<Pair<SessionEntity, SessionEvaluationEntity>>()
        chronological.forEach { evaluation ->
            val session = dao.session(evaluation.sessionId) ?: return@forEach
            if (evaluation.status != "VALID" || !evaluation.recordQualified || evaluation.totalDurationNanos == null) return@forEach
            val bounds = comparisonPeriodBounds(session.startedWallTimeMillis)
            val monthCandidates = previous.filter { it.first.startedWallTimeMillis >= bounds.monthStartMillis }
            val weekCandidates = previous.filter { it.first.startedWallTimeMillis >= bounds.weekStartMillis }
            val month = monthCandidates.minByOrNull { it.second.totalDurationNanos ?: Long.MAX_VALUE }
            val week = weekCandidates.minByOrNull { it.second.totalDurationNanos ?: Long.MAX_VALUE }
            val passes = dao.evaluationPasses(evaluation.id)
            val uncertainty = passes.maxOfOrNull { it.estimatedUncertaintyMillis } ?: 500L
            val grade = runCatching { QualityGrade.valueOf(evaluation.qualityGrade) }.getOrDefault(QualityGrade.C)
            val total = evaluation.totalDurationNanos
            val monthTotal = month?.second?.totalDurationNanos
            val weekTotal = week?.second?.totalDurationNanos
            dao.updateEvaluation(
                evaluation.copy(
                    monthlyReferenceSessionId = month?.first?.id,
                    weeklyReferenceSessionId = week?.first?.id,
                    monthlyBestTotalNanos = monthTotal,
                    weeklyBestTotalNanos = weekTotal,
                    monthlyDeltaNanos = monthTotal?.let { total - it },
                    weeklyDeltaNanos = weekTotal?.let { total - it },
                    totalResultColor = RecordJudge.periodColor(total, monthTotal, weekTotal, grade, uncertainty).name,
                    comparisonRulesVersion = 3,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            val monthSectors = monthCandidates.map { it.second.id to dao.evaluationSectors(it.second.id) }
            val weekSectors = weekCandidates.map { it.second.id to dao.evaluationSectors(it.second.id) }
            dao.evaluationSectors(evaluation.id).forEach { sector ->
                val monthBest = monthSectors.mapNotNull { it.second.firstOrNull { row -> row.sectorIndex == sector.sectorIndex }?.durationNanos }.minOrNull()
                val weekBest = weekSectors.mapNotNull { it.second.firstOrNull { row -> row.sectorIndex == sector.sectorIndex }?.durationNanos }.minOrNull()
                dao.insertEvaluationSector(
                    sector.copy(
                        monthlyBestDurationNanos = monthBest,
                        weeklyBestDurationNanos = weekBest,
                        monthlyDeltaNanos = monthBest?.let { sector.durationNanos - it },
                        weeklyDeltaNanos = weekBest?.let { sector.durationNanos - it },
                        resultColorAtCompletion = RecordJudge.periodColor(sector.durationNanos, monthBest, weekBest, grade, uncertainty).name,
                        comparisonRulesVersion = 3,
                    ),
                )
            }
            previous += session to evaluation
        }
    }

    private suspend fun prepare(
        sourceSessionId: String,
        selections: List<CheckpointSelection>,
        radiusMeters: Double,
    ): PreparedEdit {
        require(selections.size == 4) { "必须设置 A、S1、S2、B 四个计时点" }
        val session = requireNotNull(dao.session(sourceSessionId)) { "历史轨迹不存在" }
        val track = dao.track(sourceSessionId)
        require(track.size >= 4) { "轨迹点不足，至少需要 4 个" }
        val positions = selections.map { selection ->
            val index = track.indexOfFirst { it.id == selection.segmentStartPointId }
            require(index in 0 until track.lastIndex) { "选点已不在当前轨迹中" }
            val ratio = selection.segmentRatio.coerceIn(0.0, 1.0)
            val a = track[index]
            val b = track[index + 1]
            SelectedTrackPosition(
                sourcePointId = a.id,
                pathPosition = index + ratio,
                latitude = a.latitude + (b.latitude - a.latitude) * ratio,
                longitude = a.longitude + (b.longitude - a.longitude) * ratio,
                elapsedRealtimeNanos = a.elapsedRealtimeNanos + ((b.elapsedRealtimeNanos - a.elapsedRealtimeNanos) * ratio).toLong(),
                preferredBearing = Geometry.bearingDegrees(a.latitude, a.longitude, b.latitude, b.longitude),
            )
        }
        require(positions.zipWithNext().all { it.first.pathPosition < it.second.pathPosition }) { "计时点必须沿轨迹按 A、S1、S2、B 排列" }
        val routeBearing = Geometry.bearingDegrees(
            positions.first().latitude, positions.first().longitude,
            positions.last().latitude, positions.last().longitude,
        )
        val projectionOrigin = Checkpoint("axis", 0, "A", positions.first().latitude, positions.first().longitude)
        val projections = positions.map { point ->
            Geometry.signedProgressMeters(point.latitude, point.longitude, projectionOrigin, routeBearing)
        }
        require(projections.zipWithNext().all { (a, b) -> b - a >= 100.0 }) {
            "计时点沿 A→B 方向必须依次排列，且相邻进度线至少间隔 100 米"
        }
        val labels = listOf("A", "S1", "S2", "B")
        val draftVersion = "preview"
        val checkpoints = positions.mapIndexed { index, point ->
            CheckpointEntity(
                id = UUID.randomUUID().toString(),
                checkpointVersionId = draftVersion,
                orderIndex = index,
                label = labels[index],
                latitude = point.latitude,
                longitude = point.longitude,
                radiusMeters = radiusMeters.coerceIn(20.0, 80.0),
                preferredBearing = routeBearing,
                bearingTolerance = 100.0,
                gateHalfWidthMeters = maxOf(radiusMeters, 50.0),
                sourceTrackPointId = point.sourcePointId,
            )
        }
        return PreparedEdit(session, positions, checkpoints)
    }

    private fun manualPasses(checkpoints: List<CheckpointEntity>, positions: List<SelectedTrackPosition>): List<CheckpointPass> =
        checkpoints.mapIndexed { index, checkpoint ->
            val point = positions[index]
            CheckpointPass(
                checkpoint = checkpoint.toDomain(),
                elapsedRealtimeNanos = point.elapsedRealtimeNanos,
                latitude = point.latitude,
                longitude = point.longitude,
                triggerMode = TriggerMode.MANUAL_REVIEW,
                closestDistanceMeters = 0.0,
                uncertaintyMillis = 500L,
            )
        }

    private suspend fun replayRouteSource(
        session: SessionEntity,
        checkpoints: List<CheckpointEntity>,
    ): ReplayResult.Success? {
        val pointsById = dao.track(session.id).associateBy { it.id }
        val passes = checkpoints.map { checkpoint ->
            val point = checkpoint.sourceTrackPointId?.let(pointsById::get) ?: return null
            CheckpointPass(
                checkpoint = checkpoint.toDomain(),
                elapsedRealtimeNanos = point.elapsedRealtimeNanos,
                latitude = checkpoint.latitude,
                longitude = checkpoint.longitude,
                triggerMode = TriggerMode.MANUAL_REVIEW,
                closestDistanceMeters = 0.0,
                uncertaintyMillis = 500L,
            )
        }
        return ReplayResult.Success(passes, QualityGrade.B, 0)
    }

    private fun TrackPointEntity.toFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        wallTimeMillis = wallTimeMillis,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMps,
        bearingDegrees = bearingDegrees,
        bearingAccuracyDegrees = bearingAccuracy,
        provider = provider,
        isMock = isMock,
        speedAccuracyMetersPerSecond = speedAccuracyMps,
    )

    private fun CheckpointEntity.toDomain() = Checkpoint(
        id, orderIndex, label, latitude, longitude, radiusMeters, preferredBearing, bearingTolerance, gateHalfWidthMeters,
    )

    private data class PreparedEdit(
        val session: SessionEntity,
        val positions: List<SelectedTrackPosition>,
        val checkpoints: List<CheckpointEntity>,
    )

    private data class SelectedTrackPosition(
        val sourcePointId: Long,
        val pathPosition: Double,
        val latitude: Double,
        val longitude: Double,
        val elapsedRealtimeNanos: Long,
        val preferredBearing: Double,
    )

    private sealed interface ReplayResult {
        data class Success(val passes: List<CheckpointPass>, val grade: QualityGrade, val rejectedFixes: Int) : ReplayResult
        data class Failure(val reason: String, val status: String = "INCOMPLETE") : ReplayResult
    }

    private companion object {
        const val ENGINE_V3_REASON = "计时引擎 v3：S1/S2 轴向无限参考线"
    }
}
