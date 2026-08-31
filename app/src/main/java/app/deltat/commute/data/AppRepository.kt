package app.deltat.commute.data

import android.os.Build
import androidx.room.withTransaction
import app.deltat.commute.BuildConfig
import app.deltat.commute.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class ReferenceRunData(
    val session: SessionEntity,
    val track: List<TrackPointEntity>,
    val passes: List<CheckpointPassEntity>,
    val sectors: List<SectorResultEntity>,
    val type: DeltaReferenceType,
)

data class ComparisonPeriodBounds(
    val monthStartMillis: Long,
    val weekStartMillis: Long,
    val endExclusiveMillis: Long,
)

data class TimingBenchmarks(
    val monthlyTotal: ReferenceRunData?,
    val weeklyTotal: ReferenceRunData?,
    val monthlySectorBestNanos: List<Long?>,
    val weeklySectorBestNanos: List<Long?>,
    val monthlySectorReferences: List<ReferenceRunData?> = List(3) { null },
    val weeklySectorReferences: List<ReferenceRunData?> = List(3) { null },
    val bounds: ComparisonPeriodBounds,
)

fun comparisonPeriodBounds(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): ComparisonPeriodBounds {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val monthStartDate = now.toLocalDate().withDayOfMonth(1)
    val naturalWeekStart = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val clippedWeekStart = maxOf(monthStartDate, naturalWeekStart)
    return ComparisonPeriodBounds(
        monthStartDate.atStartOfDay(zone).toInstant().toEpochMilli(),
        clippedWeekStart.atStartOfDay(zone).toInstant().toEpochMilli(),
        nowMillis,
    )
}

class AppRepository(private val database: DeltaTDatabase) {
    private val dao = database.dao()
    private val historicalRecalculator = HistoricalRecalculator(database)
    val routes: Flow<List<RouteProfileEntity>> = dao.observeRoutes()
    val sessions: Flow<List<SessionListRow>> = dao.observeSessionList()
    val leaderboard: Flow<List<LeaderboardRow>> = dao.observeLeaderboard()

    suspend fun seedDefaults() {
        if (dao.routeCount() != 0) return
        val now = System.currentTimeMillis()
        dao.insertRoute(RouteProfileEntity(UUID.randomUUID().toString(), "上班", "TO_WORK", createdAtMillis = now, updatedAtMillis = now))
        dao.insertRoute(RouteProfileEntity(UUID.randomUUID().toString(), "下班", "FROM_WORK", createdAtMillis = now + 1, updatedAtMillis = now + 1))
    }

    suspend fun createRoute(name: String, directionType: String = "CUSTOM"): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertRoute(RouteProfileEntity(id, name.trim().ifBlank { "自定义方向" }, directionType, createdAtMillis = now, updatedAtMillis = now))
        return id
    }

    suspend fun route(id: String) = dao.route(id)
    suspend fun session(id: String) = dao.session(id)
    fun track(sessionId: String) = dao.observeTrack(sessionId)
    fun sectors(sessionId: String) = dao.observeSectors(sessionId)
    fun passes(sessionId: String) = dao.observePasses(sessionId)
    suspend fun sectorRows(sessionId: String) = dao.sectors(sessionId)
    fun sessionRow(sessionId: String) = dao.observeSessionRow(sessionId)
    fun evaluations(sessionId: String) = dao.observeEvaluations(sessionId)
    fun evaluationPasses(evaluationId: String) = dao.observeEvaluationPasses(evaluationId)
    fun evaluationSectors(evaluationId: String) = dao.observeEvaluationSectors(evaluationId)
    fun latestRecalculationJob(routeId: String) = dao.observeLatestRecalculationJob(routeId)
    suspend fun evaluationPassRows(evaluationId: String): List<CheckpointPassEntity> = dao.evaluationPasses(evaluationId).map { pass ->
        CheckpointPassEntity(
            id = pass.id, sessionId = "", checkpointId = pass.checkpointId, orderIndex = pass.orderIndex,
            passElapsedNanos = pass.passElapsedNanos, interpolatedLatitude = pass.interpolatedLatitude,
            interpolatedLongitude = pass.interpolatedLongitude, triggerMode = pass.triggerMode,
            closestDistanceMeters = pass.closestDistanceMeters, estimatedUncertaintyMillis = pass.estimatedUncertaintyMillis,
            confidence = pass.confidence,
        )
    }
    suspend fun evaluationSectorRows(evaluationId: String): List<SectorResultEntity> = dao.evaluationSectors(evaluationId).map { sector ->
        SectorResultEntity(
            sessionId = "", sectorIndex = sector.sectorIndex, durationNanos = sector.durationNanos,
            referenceDurationNanos = sector.monthlyBestDurationNanos, previousBestDurationNanos = sector.weeklyBestDurationNanos,
            deltaToReferenceNanos = sector.monthlyDeltaNanos, resultColorAtCompletion = sector.resultColorAtCompletion,
            recordQualified = sector.recordQualified, monthlyBestDurationNanos = sector.monthlyBestDurationNanos,
            weeklyBestDurationNanos = sector.weeklyBestDurationNanos, monthlyDeltaNanos = sector.monthlyDeltaNanos,
            weeklyDeltaNanos = sector.weeklyDeltaNanos, comparisonRulesVersion = sector.comparisonRulesVersion,
        )
    }

    suspend fun checkpointsForRoute(routeId: String): List<Checkpoint> {
        val route = dao.route(routeId) ?: return emptyList()
        val version = route.activeCheckpointVersionId ?: return emptyList()
        return dao.checkpoints(version).map { it.toDomain() }
    }

    suspend fun checkpointEntitiesForRoute(routeId: String): List<CheckpointEntity> {
        val route = dao.route(routeId) ?: return emptyList()
        return route.activeCheckpointVersionId?.let { dao.checkpoints(it) }.orEmpty()
    }

    suspend fun readyRouteCandidates(): List<RouteCandidate> = routes.first().mapNotNull { route ->
        val checkpoints = checkpointsForRoute(route.id)
        checkpoints.takeIf { it.size == 4 }?.let { RouteCandidate(route.id, route.name, route.createdAtMillis, it) }
    }

    suspend fun comparisonBenchmarksForTiming(routeId: String, nowMillis: Long = System.currentTimeMillis()): TimingBenchmarks? {
        val route = dao.route(routeId) ?: return null
        val version = route.activeCheckpointVersionId ?: return null
        val bounds = comparisonPeriodBounds(nowMillis)
        suspend fun referenceData(evaluation: SessionEvaluationEntity, type: DeltaReferenceType): ReferenceRunData? {
            val original = dao.session(evaluation.sessionId) ?: return null
            val session = original.copy(
                checkpointVersionId = version,
                startElapsedNanos = evaluation.startElapsedNanos,
                finishElapsedNanos = evaluation.finishElapsedNanos,
                totalDurationNanos = evaluation.totalDurationNanos,
                validity = "VALID",
                qualityGrade = evaluation.qualityGrade,
                monthlyBestTotalNanos = evaluation.monthlyBestTotalNanos,
                weeklyBestTotalNanos = evaluation.weeklyBestTotalNanos,
                monthlyDeltaNanos = evaluation.monthlyDeltaNanos,
                weeklyDeltaNanos = evaluation.weeklyDeltaNanos,
                totalResultColor = evaluation.totalResultColor,
            )
            val passes = dao.evaluationPasses(evaluation.id).map { pass ->
                CheckpointPassEntity(
                    id = pass.id, sessionId = session.id, checkpointId = pass.checkpointId, orderIndex = pass.orderIndex,
                    passElapsedNanos = pass.passElapsedNanos, interpolatedLatitude = pass.interpolatedLatitude,
                    interpolatedLongitude = pass.interpolatedLongitude, triggerMode = pass.triggerMode,
                    closestDistanceMeters = pass.closestDistanceMeters, estimatedUncertaintyMillis = pass.estimatedUncertaintyMillis,
                    confidence = pass.confidence,
                )
            }
            val sectors = dao.evaluationSectors(evaluation.id).map { sector ->
                SectorResultEntity(
                    sessionId = session.id, sectorIndex = sector.sectorIndex, durationNanos = sector.durationNanos,
                    referenceDurationNanos = sector.monthlyBestDurationNanos, previousBestDurationNanos = sector.weeklyBestDurationNanos,
                    deltaToReferenceNanos = sector.monthlyDeltaNanos, resultColorAtCompletion = sector.resultColorAtCompletion,
                    recordQualified = sector.recordQualified, monthlyBestDurationNanos = sector.monthlyBestDurationNanos,
                    weeklyBestDurationNanos = sector.weeklyBestDurationNanos, monthlyDeltaNanos = sector.monthlyDeltaNanos,
                    weeklyDeltaNanos = sector.weeklyDeltaNanos, comparisonRulesVersion = sector.comparisonRulesVersion,
                )
            }
            return ReferenceRunData(session, dao.track(session.id), passes, sectors, type)
        }
        suspend fun candidates(fromMillis: Long) = dao.validEvaluationsInPeriod(routeId, version, fromMillis, bounds.endExclusiveMillis)
        suspend fun reference(fromMillis: Long, type: DeltaReferenceType): ReferenceRunData? =
            candidates(fromMillis).firstOrNull()?.let { referenceData(it, type) }
        suspend fun sectorReferences(fromMillis: Long, type: DeltaReferenceType): List<ReferenceRunData?> {
            val evaluations = candidates(fromMillis)
            val sectorRows = evaluations.associateWith { dao.evaluationSectors(it.id) }
            val startedBySession = evaluations.associate { evaluation ->
                evaluation.sessionId to (dao.session(evaluation.sessionId)?.startedWallTimeMillis ?: Long.MAX_VALUE)
            }
            return (0..2).map { index ->
                sectorRows.entries.minWithOrNull(
                    compareBy<Map.Entry<SessionEvaluationEntity, List<EvaluationSectorEntity>>> {
                        it.value.firstOrNull { sector -> sector.sectorIndex == index }?.durationNanos ?: Long.MAX_VALUE
                    }.thenBy { startedBySession[it.key.sessionId] ?: Long.MAX_VALUE }
                        .thenBy { it.key.sessionId },
                )?.takeIf { entry -> entry.value.any { it.sectorIndex == index } }?.key?.let { referenceData(it, type) }
            }
        }
        fun sectorDurations(rows: List<SectorBestRow>): List<Long?> = (0..2).map { index -> rows.firstOrNull { it.sectorIndex == index }?.durationNanos }
        return TimingBenchmarks(
            monthlyTotal = reference(bounds.monthStartMillis, DeltaReferenceType.MONTHLY_BEST),
            weeklyTotal = reference(bounds.weekStartMillis, DeltaReferenceType.WEEKLY_BEST),
            monthlySectorBestNanos = sectorDurations(dao.evaluationSectorBestsInPeriod(routeId, version, bounds.monthStartMillis, bounds.endExclusiveMillis)),
            weeklySectorBestNanos = sectorDurations(dao.evaluationSectorBestsInPeriod(routeId, version, bounds.weekStartMillis, bounds.endExclusiveMillis)),
            monthlySectorReferences = sectorReferences(bounds.monthStartMillis, DeltaReferenceType.MONTHLY_BEST),
            weeklySectorReferences = sectorReferences(bounds.weekStartMillis, DeltaReferenceType.WEEKLY_BEST),
            bounds = bounds,
        )
    }

    @Deprecated("Use comparisonBenchmarksForTiming")
    suspend fun referenceForTiming(routeId: String, nowMillis: Long = System.currentTimeMillis()): ReferenceRunData? =
        comparisonBenchmarksForTiming(routeId, nowMillis)?.monthlyTotal

    suspend fun startSession(routeId: String, mode: String, benchmarks: TimingBenchmarks? = null): SessionEntity = database.withTransaction {
        dao.activeSession()?.let { dao.markInterrupted(it.id, System.currentTimeMillis(), "新的记录已开始") }
        val route = requireNotNull(dao.route(routeId))
        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            routeProfileId = routeId,
            checkpointVersionId = route.activeCheckpointVersionId,
            mode = mode,
            status = if (mode == "BASELINE_RECORDING") "RECORDING" else "ARMED",
            referenceSessionIdSnapshot = benchmarks?.monthlyTotal?.session?.id ?: route.referenceSessionId,
            monthlyReferenceSessionIdSnapshot = benchmarks?.monthlyTotal?.session?.id,
            weeklyReferenceSessionIdSnapshot = benchmarks?.weeklyTotal?.session?.id,
            monthlyBestTotalNanos = benchmarks?.monthlyTotal?.session?.totalDurationNanos,
            weeklyBestTotalNanos = benchmarks?.weeklyTotal?.session?.totalDurationNanos,
            startedWallTimeMillis = System.currentTimeMillis(),
            bootCount = 0,
            appVersion = BuildConfig.VERSION_NAME,
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            createdAtMillis = System.currentTimeMillis(),
        )
        dao.insertSession(session)
        session
    }

    suspend fun activeSession() = dao.activeSession()

    suspend fun appendFix(sessionId: String, fix: LocationFix, usedForTiming: Boolean): Long {
        val sequence = dao.nextSequence(sessionId)
        return dao.insertTrackPoint(
            TrackPointEntity(
                sessionId = sessionId,
                sequence = sequence,
                latitude = fix.latitude,
                longitude = fix.longitude,
                elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
                wallTimeMillis = fix.wallTimeMillis,
                accuracyMeters = fix.accuracyMeters,
                speedMps = fix.speedMetersPerSecond,
                speedAccuracyMps = fix.speedAccuracyMetersPerSecond,
                bearingDegrees = fix.bearingDegrees,
                bearingAccuracy = fix.bearingAccuracyDegrees,
                provider = fix.provider,
                isMock = fix.isMock,
                usedForTiming = usedForTiming,
            ),
        )
    }

    suspend fun updateRuntimeStatus(sessionId: String, status: EngineStatus) {
        dao.session(sessionId)?.let { dao.updateSession(it.copy(status = status.name)) }
    }

    suspend fun finishBaseline(sessionId: String) {
        val session = dao.session(sessionId) ?: return
        dao.updateSession(session.copy(status = "RECORDED", endedWallTimeMillis = System.currentTimeMillis(), validity = "PENDING"))
    }

    suspend fun saveBaselineCheckpoints(sessionId: String, fractions: List<Float>, radiusMeters: Double = 35.0) {
        val track = dao.track(sessionId)
        require(track.size >= 4) { "轨迹点不足，至少需要 4 个" }
        val selections = fractions.map { fraction ->
            val position = (fraction.coerceIn(0f, 1f) * (track.size - 1))
            val index = position.toInt().coerceAtMost(track.lastIndex - 1)
            CheckpointSelection(track[index].id, (position - index).toDouble())
        }
        val request = saveCheckpointSelections(sessionId, selections, radiusMeters)
        runPendingRecalculations(request.jobId)
    }

    suspend fun previewCheckpointSelections(sessionId: String, selections: List<CheckpointSelection>, radiusMeters: Double = 35.0): RecalculationPreview =
        historicalRecalculator.preview(sessionId, selections, radiusMeters)

    suspend fun saveCheckpointSelections(sessionId: String, selections: List<CheckpointSelection>, radiusMeters: Double = 35.0): CheckpointRecalculationRequest =
        historicalRecalculator.create(sessionId, selections, radiusMeters)

    suspend fun runPendingRecalculations(jobId: String? = null) = historicalRecalculator.runPending(jobId)
    suspend fun retryRecalculation(jobId: String) = historicalRecalculator.retry(jobId)

    suspend fun persistTransition(sessionId: String, transition: EngineTransition, benchmarks: TimingBenchmarks? = null) = database.withTransaction {
        val session = dao.session(sessionId) ?: return@withTransaction
        transition.emittedEvents.filterIsInstance<EngineEvent.PassConfirmed>().forEach { event ->
            val pass = event.pass
            dao.insertPass(
                CheckpointPassEntity(
                    id = UUID.randomUUID().toString(), sessionId = sessionId, checkpointId = pass.checkpoint.id,
                    orderIndex = pass.checkpoint.orderIndex, passElapsedNanos = pass.elapsedRealtimeNanos,
                    interpolatedLatitude = pass.latitude, interpolatedLongitude = pass.longitude,
                    triggerMode = pass.triggerMode.name, closestDistanceMeters = pass.closestDistanceMeters,
                    estimatedUncertaintyMillis = pass.uncertaintyMillis,
                    confidence = if (pass.triggerMode == TriggerMode.LINE_CROSSING) "A" else "B",
                ),
            )
        }
        dao.updateSession(session.copy(status = transition.newState.status.name))
        if (transition.newState.status == EngineStatus.COMPLETED) finalizeTimedSession(sessionId, transition.newState, benchmarks)
    }

    private suspend fun finalizeTimedSession(sessionId: String, state: EngineState, suppliedBenchmarks: TimingBenchmarks?) {
        val session = dao.session(sessionId) ?: return
        val benchmarks = suppliedBenchmarks ?: comparisonBenchmarksForTiming(session.routeProfileId, session.startedWallTimeMillis) ?: return
        val versionId = session.checkpointVersionId ?: return
        val evaluationId = dao.evaluation(sessionId, versionId)?.id ?: UUID.randomUUID().toString()
        dao.deleteEvaluationPasses(evaluationId)
        dao.deleteEvaluationSectors(evaluationId)
        val grade = when {
            state.passes.any { it.triggerMode == TriggerMode.CLOSEST_APPROACH } -> QualityGrade.B
            state.rejectedFixes > 10 -> QualityGrade.B
            else -> QualityGrade.A
        }
        state.passes.zipWithNext().forEachIndexed { index, (a, b) ->
            val duration = b.elapsedRealtimeNanos - a.elapsedRealtimeNanos
            val monthlyBest = benchmarks.monthlySectorBestNanos.getOrNull(index)
            val weeklyBest = benchmarks.weeklySectorBestNanos.getOrNull(index)
            val uncertainty = maxOf(a.uncertaintyMillis, b.uncertaintyMillis)
            val color = RecordJudge.periodColor(duration, monthlyBest, weeklyBest, grade, uncertainty)
            dao.insertSector(
                SectorResultEntity(
                    sessionId = sessionId,
                    sectorIndex = index,
                    durationNanos = duration,
                    referenceDurationNanos = monthlyBest,
                    previousBestDurationNanos = weeklyBest,
                    deltaToReferenceNanos = monthlyBest?.let { duration - it },
                    resultColorAtCompletion = color.name,
                    recordQualified = grade != QualityGrade.C,
                    monthlyBestDurationNanos = monthlyBest,
                    weeklyBestDurationNanos = weeklyBest,
                    monthlyDeltaNanos = monthlyBest?.let { duration - it },
                    weeklyDeltaNanos = weeklyBest?.let { duration - it },
                ),
            )
            dao.insertEvaluationSector(
                EvaluationSectorEntity(
                    evaluationId = evaluationId,
                    sectorIndex = index,
                    durationNanos = duration,
                    monthlyBestDurationNanos = monthlyBest,
                    weeklyBestDurationNanos = weeklyBest,
                    monthlyDeltaNanos = monthlyBest?.let { duration - it },
                    weeklyDeltaNanos = weeklyBest?.let { duration - it },
                    resultColorAtCompletion = color.name,
                    recordQualified = grade != QualityGrade.C,
                ),
            )
        }
        val start = state.passes.first().elapsedRealtimeNanos
        val finish = state.passes.last().elapsedRealtimeNanos
        val total = finish - start
        val uncertainty = state.passes.maxOfOrNull { it.uncertaintyMillis } ?: 500L
        val monthlyBest = benchmarks.monthlyTotal?.session?.totalDurationNanos
        val weeklyBest = benchmarks.weeklyTotal?.session?.totalDurationNanos
        val totalColor = RecordJudge.periodColor(total, monthlyBest, weeklyBest, grade, uncertainty)
        val now = System.currentTimeMillis()
        dao.insertEvaluation(
            SessionEvaluationEntity(
                id = evaluationId,
                sessionId = sessionId,
                checkpointVersionId = versionId,
                status = "VALID",
                startElapsedNanos = start,
                finishElapsedNanos = finish,
                totalDurationNanos = total,
                qualityGrade = grade.name,
                recordQualified = grade != QualityGrade.C,
                monthlyReferenceSessionId = benchmarks.monthlyTotal?.session?.id,
                weeklyReferenceSessionId = benchmarks.weeklyTotal?.session?.id,
                monthlyBestTotalNanos = monthlyBest,
                weeklyBestTotalNanos = weeklyBest,
                monthlyDeltaNanos = monthlyBest?.let { total - it },
                weeklyDeltaNanos = weeklyBest?.let { total - it },
                totalResultColor = totalColor.name,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        state.passes.forEach { pass ->
            dao.insertEvaluationPass(
                EvaluationPassEntity(
                    id = UUID.randomUUID().toString(), evaluationId = evaluationId, checkpointId = pass.checkpoint.id,
                    orderIndex = pass.checkpoint.orderIndex, passElapsedNanos = pass.elapsedRealtimeNanos,
                    interpolatedLatitude = pass.latitude, interpolatedLongitude = pass.longitude,
                    triggerMode = pass.triggerMode.name, closestDistanceMeters = pass.closestDistanceMeters,
                    estimatedUncertaintyMillis = pass.uncertaintyMillis,
                    confidence = if (pass.triggerMode == TriggerMode.LINE_CROSSING) "A" else "B",
                ),
            )
        }
        dao.updateSession(
            session.copy(
                status = "COMPLETED", startElapsedNanos = start, finishElapsedNanos = finish,
                totalDurationNanos = total, validity = "VALID", qualityGrade = grade.name,
                endedWallTimeMillis = now, isMockDetected = session.isMockDetected,
                monthlyReferenceSessionIdSnapshot = benchmarks.monthlyTotal?.session?.id,
                weeklyReferenceSessionIdSnapshot = benchmarks.weeklyTotal?.session?.id,
                monthlyBestTotalNanos = monthlyBest,
                weeklyBestTotalNanos = weeklyBest,
                monthlyDeltaNanos = monthlyBest?.let { total - it },
                weeklyDeltaNanos = weeklyBest?.let { total - it },
                totalResultColor = totalColor.name,
                comparisonRulesVersion = 3,
            ),
        )
    }

    suspend fun rebuildComparisonHistory() = database.withTransaction {
        dao.allValidSessionsChronological().forEach { original ->
            val session = dao.session(original.id) ?: return@forEach
            val version = session.checkpointVersionId ?: return@forEach
            val bounds = comparisonPeriodBounds(session.startedWallTimeMillis)
            val monthCandidates = dao.validSessionsInPeriod(session.routeProfileId, version, bounds.monthStartMillis, session.startedWallTimeMillis)
            val weekCandidates = dao.validSessionsInPeriod(session.routeProfileId, version, bounds.weekStartMillis, session.startedWallTimeMillis)
            val monthReference = monthCandidates.firstOrNull()
            val weekReference = weekCandidates.firstOrNull()
            fun sectorDurations(rows: List<SectorBestRow>): List<Long?> = (0..2).map { index -> rows.firstOrNull { it.sectorIndex == index }?.durationNanos }
            val monthSectors = sectorDurations(dao.sectorBestsInPeriod(session.routeProfileId, version, bounds.monthStartMillis, session.startedWallTimeMillis))
            val weekSectors = sectorDurations(dao.sectorBestsInPeriod(session.routeProfileId, version, bounds.weekStartMillis, session.startedWallTimeMillis))
            val grade = runCatching { QualityGrade.valueOf(session.qualityGrade) }.getOrDefault(QualityGrade.C)
            val passRows = dao.passes(session.id)
            val uncertainty = passRows.maxOfOrNull { it.estimatedUncertaintyMillis } ?: 500L
            dao.sectors(session.id).forEach { sector ->
                val month = monthSectors.getOrNull(sector.sectorIndex)
                val week = weekSectors.getOrNull(sector.sectorIndex)
                dao.insertSector(
                    sector.copy(
                        referenceDurationNanos = month,
                        previousBestDurationNanos = week,
                        deltaToReferenceNanos = month?.let { sector.durationNanos - it },
                        resultColorAtCompletion = RecordJudge.periodColor(sector.durationNanos, month, week, grade, uncertainty).name,
                        monthlyBestDurationNanos = month,
                        weeklyBestDurationNanos = week,
                        monthlyDeltaNanos = month?.let { sector.durationNanos - it },
                        weeklyDeltaNanos = week?.let { sector.durationNanos - it },
                        comparisonRulesVersion = 3,
                    ),
                )
            }
            val total = session.totalDurationNanos ?: return@forEach
            val month = monthReference?.totalDurationNanos
            val week = weekReference?.totalDurationNanos
            dao.updateSession(
                session.copy(
                    monthlyReferenceSessionIdSnapshot = monthReference?.id,
                    weeklyReferenceSessionIdSnapshot = weekReference?.id,
                    monthlyBestTotalNanos = month,
                    weeklyBestTotalNanos = week,
                    monthlyDeltaNanos = month?.let { total - it },
                    weeklyDeltaNanos = week?.let { total - it },
                    totalResultColor = RecordJudge.periodColor(total, month, week, grade, uncertainty).name,
                    comparisonRulesVersion = 3,
                ),
            )
        }
    }

    suspend fun rebuildEvaluationComparisonHistory() {
        routes.first().forEach { route ->
            route.activeCheckpointVersionId?.let { version -> historicalRecalculator.rebuildComparisons(route.id, version) }
        }
    }

    suspend fun markInterrupted(sessionId: String, reason: String) = dao.markInterrupted(sessionId, System.currentTimeMillis(), reason)

    suspend fun saveGnss(sessionId: String, elapsedRealtimeNanos: Long, health: GnssHealth) {
        dao.insertGnss(
            GnssSnapshotEntity(
                sessionId = sessionId, elapsedRealtimeNanos = elapsedRealtimeNanos,
                visibleGps = health.visibleGps, usedGps = health.usedGps,
                visibleBeidou = health.visibleBeidou, usedBeidou = health.usedBeidou,
                visibleOther = health.visibleOther, usedOther = health.usedOther,
            ),
        )
    }

    suspend fun cancelSession(sessionId: String, reason: String) {
        dao.session(sessionId)?.let {
            dao.updateSession(
                it.copy(
                    status = "CANCELLED", validity = "INVALID", invalidReason = reason,
                    endedWallTimeMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun sectorBests(routeId: String): List<SectorBestRow> {
        val version = dao.route(routeId)?.activeCheckpointVersionId ?: return emptyList()
        return dao.evaluationSectorBests(routeId, version)
    }

    suspend fun comparableSessions(routeId: String): List<SessionEntity> {
        val version = dao.route(routeId)?.activeCheckpointVersionId ?: return emptyList()
        return dao.validEvaluations(routeId, version).mapNotNull { evaluation ->
            dao.session(evaluation.sessionId)?.copy(
                checkpointVersionId = version,
                totalDurationNanos = evaluation.totalDurationNanos,
                validity = "VALID",
                qualityGrade = evaluation.qualityGrade,
            )
        }
    }

    suspend fun saveSpeedPeak(sessionId: String, peak: SpeedPeak?) {
        if (peak == null) return
        dao.session(sessionId)?.let { session ->
            dao.updateSession(session.copy(maxSpeedMps = peak.metersPerSecond, maxSpeedElapsedNanos = peak.elapsedRealtimeNanos, maxSpeedWallTimeMillis = peak.wallTimeMillis))
        }
    }

    suspend fun deleteSession(sessionId: String) = database.withTransaction {
        val session = dao.session(sessionId)
        val route = session?.let { dao.route(it.routeProfileId) }
        require(route?.referenceSessionId != sessionId) { "该轨迹是当前路线的来源；请删除整条路线，或先用另一条轨迹重建路线" }
        dao.deleteEvaluationPassesForSession(sessionId); dao.deleteEvaluationSectorsForSession(sessionId); dao.deleteEvaluationsForSession(sessionId)
        dao.deleteTrack(sessionId); dao.deletePasses(sessionId); dao.deleteSectors(sessionId); dao.deleteGnss(sessionId); dao.deleteSession(sessionId)
    }

    suspend fun deleteRoute(routeId: String) = database.withTransaction {
        val route = requireNotNull(dao.route(routeId)) { "路线已不存在" }
        val active = dao.activeSession()
        require(active?.routeProfileId != route.id) { "这条路线正在记录，请先结束本次行程" }
        require(dao.activeRecalculationJob(route.id) == null) { "这条路线正在重算历史成绩，请等待完成后再删除" }

        dao.sessionIdsForRoute(route.id).forEach { sessionId ->
            dao.deleteEvaluationPassesForSession(sessionId)
            dao.deleteEvaluationSectorsForSession(sessionId)
            dao.deleteEvaluationsForSession(sessionId)
            dao.deleteTrack(sessionId)
            dao.deletePasses(sessionId)
            dao.deleteSectors(sessionId)
            dao.deleteGnss(sessionId)
        }
        dao.deleteRecalculationJobsForRoute(route.id)
        dao.deleteCheckpointsForRoute(route.id)
        dao.deleteCheckpointVersionsForRoute(route.id)
        dao.deleteSessionsForRoute(route.id)
        dao.deleteRoute(route.id)
    }

    suspend fun exportJson(): String {
        val rows = sessions.first()
        return buildString {
            append("{\"format\":\"delta-t-diagnostics-v2\",\"exportedAt\":").append(System.currentTimeMillis()).append(",\"sessions\":[")
            rows.forEachIndexed { index, row ->
                if (index > 0) append(',')
                append("{\"id\":\"").append(row.id).append("\",\"route\":\"").append(escape(row.routeName))
                    .append("\",\"startedAt\":").append(row.startedWallTimeMillis)
                    .append(",\"durationNanos\":").append(row.totalDurationNanos ?: "null")
                    .append(",\"status\":\"").append(row.status).append("\",\"quality\":\"").append(row.qualityGrade).append("\"")
                    .append(",\"checkpointVersionId\":").append(row.checkpointVersionId?.let { "\"$it\"" } ?: "null")
                    .append(",\"maxSpeedMps\":").append(row.maxSpeedMps ?: "null")
                    .append(",\"maxSpeedElapsedNanos\":").append(row.maxSpeedElapsedNanos ?: "null")
                    .append(",\"maxSpeedWallTimeMillis\":").append(row.maxSpeedWallTimeMillis ?: "null")
                append(",\"track\":[")
                dao.track(row.id).forEachIndexed { pointIndex, point ->
                    if (pointIndex > 0) append(',')
                    append("{\"sequence\":").append(point.sequence)
                        .append(",\"latitude\":").append(point.latitude)
                        .append(",\"longitude\":").append(point.longitude)
                        .append(",\"elapsedRealtimeNanos\":").append(point.elapsedRealtimeNanos)
                        .append(",\"accuracyMeters\":").append(point.accuracyMeters)
                        .append(",\"speedMps\":").append(point.speedMps ?: "null")
                        .append(",\"speedAccuracyMps\":").append(point.speedAccuracyMps ?: "null")
                        .append(",\"usedForTiming\":").append(point.usedForTiming).append('}')
                }
                append("],\"checkpointPasses\":[")
                dao.passes(row.id).forEachIndexed { passIndex, pass ->
                    if (passIndex > 0) append(',')
                    append("{\"order\":").append(pass.orderIndex)
                        .append(",\"elapsedRealtimeNanos\":").append(pass.passElapsedNanos)
                        .append(",\"triggerMode\":\"").append(pass.triggerMode).append("\"")
                        .append(",\"uncertaintyMillis\":").append(pass.estimatedUncertaintyMillis).append('}')
                }
                append("],\"evaluations\":[")
                dao.evaluations(row.id).forEachIndexed { evaluationIndex, evaluation ->
                    if (evaluationIndex > 0) append(',')
                    append("{\"id\":\"").append(evaluation.id)
                        .append("\",\"checkpointVersionId\":\"").append(evaluation.checkpointVersionId)
                        .append("\",\"status\":\"").append(evaluation.status)
                        .append("\",\"failureReason\":").append(evaluation.failureReason?.let { "\"${escape(it)}\"" } ?: "null")
                        .append(",\"durationNanos\":").append(evaluation.totalDurationNanos ?: "null")
                        .append(",\"monthlyDeltaNanos\":").append(evaluation.monthlyDeltaNanos ?: "null")
                        .append(",\"weeklyDeltaNanos\":").append(evaluation.weeklyDeltaNanos ?: "null")
                        .append(",\"color\":\"").append(evaluation.totalResultColor)
                        .append("\",\"timingEngineVersion\":").append(evaluation.timingEngineVersion)
                    append(",\"passes\":[")
                    dao.evaluationPasses(evaluation.id).forEachIndexed { passIndex, pass ->
                        if (passIndex > 0) append(',')
                        append("{\"order\":").append(pass.orderIndex)
                            .append(",\"elapsedRealtimeNanos\":").append(pass.passElapsedNanos)
                            .append(",\"triggerMode\":\"").append(pass.triggerMode).append("\"}")
                    }
                    append("],\"sectors\":[")
                    dao.evaluationSectors(evaluation.id).forEachIndexed { sectorIndex, sector ->
                        if (sectorIndex > 0) append(',')
                        append("{\"index\":").append(sector.sectorIndex)
                            .append(",\"durationNanos\":").append(sector.durationNanos)
                            .append(",\"monthlyDeltaNanos\":").append(sector.monthlyDeltaNanos ?: "null")
                            .append(",\"weeklyDeltaNanos\":").append(sector.weeklyDeltaNanos ?: "null").append('}')
                    }
                    append("]}")
                }
                append("]}")
            }
            append("]}")
        }
    }

    suspend fun exportCsv(): String = buildString {
        appendLine("id,route,startedAtMillis,durationNanos,status,validity,quality,checkpointVersionId,evaluationStatus,evaluationFailureReason,maxSpeedMps,maxSpeedWallTimeMillis")
        sessions.first().forEach { row ->
            append(row.id).append(',').append(csv(row.routeName)).append(',').append(row.startedWallTimeMillis).append(',')
                .append(row.totalDurationNanos ?: "").append(',').append(row.status).append(',').append(row.validity).append(',').append(row.qualityGrade).append(',')
                .append(row.checkpointVersionId ?: "").append(',').append(row.evaluationStatus ?: "").append(',')
                .append(csv(row.evaluationFailureReason ?: "")).append(',').append(row.maxSpeedMps ?: "").append(',').appendLine(row.maxSpeedWallTimeMillis ?: "")
        }
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""

    private fun CheckpointEntity.toDomain() = Checkpoint(
        id, orderIndex, label, latitude, longitude, radiusMeters, preferredBearing, bearingTolerance, gateHalfWidthMeters,
    )
}
