package app.deltat.commute.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import app.deltat.commute.DeltaTApplication
import app.deltat.commute.MainActivity
import app.deltat.commute.R
import app.deltat.commute.data.AppRepository
import app.deltat.commute.data.ReferenceRunData
import app.deltat.commute.data.TimingBenchmarks
import app.deltat.commute.data.TrackPointEntity
import app.deltat.commute.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.max

class TimingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: AppRepository
    private lateinit var locationSource: LocationSource
    private val engine: TimingEngine = DefaultTimingEngine()
    private var engineState = EngineState()
    private var checkpoints: List<Checkpoint> = emptyList()
    private var candidates: List<RouteCandidate> = emptyList()
    private var benchmarks: TimingBenchmarks? = null
    private var sessionId: String? = null
    private var mode: String = ""
    private var routeName: String = ""
    private var locationStarted = false
    private val monthlyProgress = LiveReferenceProgress()
    private val weeklyProgress = LiveReferenceProgress()
    private val monthlySectorProgress = LiveReferenceProgress()
    private val weeklySectorProgress = LiveReferenceProgress()
    private val fixChannel = Channel<LocationFix>(Channel.UNLIMITED)
    private val speedPeakTracker = SpeedPeakTracker()
    private var lastNotificationAt = 0L
    private var lastGnssPersistAt = 0L

    override fun onCreate() {
        super.onCreate()
        repository = (application as DeltaTApplication).repository
        locationSource = AndroidGnssLocationSource(this)
        createChannel()
        scope.launch {
            for (fix in fixChannel) processIncomingFix(fix)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCurrent(false)
            ACTION_STOP_BASELINE -> stopCurrent(true)
            ACTION_START_BASELINE -> {
                startForeground(NOTIFICATION_ID, notification("正在启动定位…"))
                val routeId = requireNotNull(intent.getStringExtra(EXTRA_ROUTE_ID))
                scope.launch { startBaseline(routeId) }
            }
            ACTION_ARM -> {
                startForeground(NOTIFICATION_ID, notification("正在启动定位…"))
                val routeId = requireNotNull(intent.getStringExtra(EXTRA_ROUTE_ID))
                scope.launch { startExplicitTimed(routeId) }
            }
            ACTION_ARM_AUTO -> {
                startForeground(NOTIFICATION_ID, notification("正在识别路线…"))
                scope.launch { startAutomaticTimed() }
            }
            ACTION_SWITCH_ROUTE -> {
                val routeId = requireNotNull(intent.getStringExtra(EXTRA_ROUTE_ID))
                scope.launch { switchTimedRoute(routeId) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startBaseline(routeId: String) {
        if (sessionId != null || locationStarted) return
        val route = repository.route(routeId) ?: return stopWithError("路线不存在")
        mode = "BASELINE_RECORDING"
        routeName = route.name
        val session = repository.startSession(routeId, mode)
        sessionId = session.id
        RuntimeBus.update {
            RuntimeSnapshot(
                sessionId = session.id, routeId = routeId, routeName = routeName, mode = mode,
                status = "正在录制路线轨迹",
            )
        }
        startLocation()
    }

    private suspend fun startExplicitTimed(routeId: String) {
        if (sessionId != null || locationStarted) return
        val route = repository.route(routeId) ?: return stopWithError("路线不存在")
        val routeCheckpoints = repository.checkpointsForRoute(routeId)
        if (routeCheckpoints.size != 4) return stopWithError("请先录制并标记四个计时点")
        activateTimed(RouteCandidate(route.id, route.name, route.createdAtMillis, routeCheckpoints), null)
        startLocation()
    }

    private suspend fun startAutomaticTimed() {
        if (sessionId != null || locationStarted) return
        mode = "AUTO_ARMING"
        candidates = repository.readyRouteCandidates()
        if (candidates.isEmpty()) return stopWithError("还没有完成计时点设置的路线")
        RuntimeBus.update {
            RuntimeSnapshot(mode = mode, status = "正在识别最近路线", engineStatus = EngineStatus.ARMED)
        }
        startLocation()
    }

    private suspend fun startLocation() {
        if (!hasLocationPermission()) return stopWithError("未获得精确定位权限")
        withContext(Dispatchers.Main) {
            locationStarted = true
            locationSource.start(::onFix) { health ->
                RuntimeBus.update { it.copy(gnssHealth = health) }
                val now = SystemClock.elapsedRealtimeNanos()
                val id = sessionId
                if (id != null && now - lastGnssPersistAt >= 10_000_000_000L) {
                    lastGnssPersistAt = now
                    scope.launch { repository.saveGnss(id, now, health) }
                }
                updateNotification()
            }
        }
    }

    private suspend fun activateTimed(candidate: RouteCandidate, detectionFix: LocationFix?) {
        mode = "TIMED"
        routeName = candidate.routeName
        checkpoints = candidate.checkpoints
        benchmarks = repository.comparisonBenchmarksForTiming(candidate.routeId)
        val routeSourceTrack = repository.routeSourceTrack(candidate.routeId).map(::toFix)
        engineState = EngineState()
        monthlyProgress.reset()
        weeklyProgress.reset()
        monthlySectorProgress.reset()
        weeklySectorProgress.reset()
        val session = repository.startSession(candidate.routeId, mode, benchmarks)
        sessionId = session.id
        val distance = detectionFix?.let { fix ->
            val a = candidate.checkpoints.first()
            Geometry.distanceMeters(fix.latitude, fix.longitude, a.latitude, a.longitude)
        }
        RuntimeBus.update {
            RuntimeSnapshot(
                sessionId = session.id,
                routeId = candidate.routeId,
                routeName = candidate.routeName,
                routeDetectionDistanceMeters = distance,
                mode = mode,
                status = "等待驶过起点 A",
                engineStatus = EngineStatus.ARMED,
                gnssHealth = it.gnssHealth,
                lastFix = detectionFix,
                deltaReferenceType = if (benchmarks?.monthlyTotal != null) DeltaReferenceType.MONTHLY_BEST else DeltaReferenceType.NONE,
                referenceTrack = benchmarks?.monthlyTotal?.track?.map(::toFix).orEmpty(),
                routeSourceTrack = routeSourceTrack,
                routeCheckpoints = candidate.checkpoints.sortedBy { checkpoint -> checkpoint.orderIndex },
                checkpointPassElapsedNanos = benchmarks?.monthlyTotal?.passes?.map { pass -> pass.passElapsedNanos }.orEmpty(),
                monthlyReferenceTrack = benchmarks?.monthlyTotal?.track?.map(::toFix).orEmpty(),
                monthlyReferencePassElapsedNanos = benchmarks?.monthlyTotal?.passes?.map { pass -> pass.passElapsedNanos }.orEmpty(),
                weeklyReferenceTrack = benchmarks?.weeklyTotal?.track?.map(::toFix).orEmpty(),
                weeklyReferencePassElapsedNanos = benchmarks?.weeklyTotal?.passes?.map { pass -> pass.passElapsedNanos }.orEmpty(),
                comparisonMessage = when {
                    benchmarks?.monthlyTotal == null -> "本月首条记录"
                    benchmarks?.weeklyTotal == null -> "本周首条记录"
                    else -> "等待驶过起点 A"
                },
            )
        }
    }

    private suspend fun switchTimedRoute(routeId: String) {
        if (mode != "TIMED" || engineState.passes.isNotEmpty()) return
        val route = repository.route(routeId) ?: return
        val routeCheckpoints = repository.checkpointsForRoute(routeId)
        if (routeCheckpoints.size != 4) return
        sessionId?.let { repository.deleteSession(it) }
        sessionId = null
        val fix = RuntimeBus.state.value.lastFix
        activateTimed(RouteCandidate(route.id, route.name, route.createdAtMillis, routeCheckpoints), fix)
        if (fix != null) processTimedFix(fix)
    }

    private fun onFix(fix: LocationFix) {
        fixChannel.trySend(fix)
    }

    private suspend fun processIncomingFix(fix: LocationFix) {
        try {
            when (mode) {
                "AUTO_ARMING" -> {
                    RuntimeBus.update { it.copy(lastFix = fix) }
                    if (fix.accuracyMeters <= 50.0) {
                        val candidate = RouteRecognizer.selectNearest(fix, candidates)
                            ?: return stopWithError("无法识别可用路线")
                        activateTimed(candidate, fix)
                        processTimedFix(fix)
                    }
                }
                "BASELINE_RECORDING" -> processBaselineFix(fix)
                "TIMED" -> processTimedFix(fix)
            }
            updateNotification()
        } catch (t: Throwable) {
            RuntimeBus.update { it.copy(error = t.message ?: "定位处理失败") }
        }
    }

    private suspend fun processBaselineFix(fix: LocationFix) {
        val id = sessionId ?: return
        val track = (RuntimeBus.state.value.track + fix).takeLast(4_000)
        repository.appendFix(id, fix, usedForTiming = fix.accuracyMeters <= 50)
        RuntimeBus.update {
            it.copy(
                lastFix = fix,
                track = track,
                elapsedNanos = track.firstOrNull()?.let { first -> fix.elapsedRealtimeNanos - first.elapsedRealtimeNanos } ?: 0,
            )
        }
    }

    private suspend fun processTimedFix(fix: LocationFix) {
        val id = sessionId ?: return
        val track = (RuntimeBus.state.value.track + fix).takeLast(4_000)
        repository.appendFix(id, fix, usedForTiming = fix.accuracyMeters <= 50)
        val previousProcessedFix = engineState.previousFix
        val transition = engine.processFix(engineState, fix, checkpoints)
        engineState = transition.newState
        repository.persistTransition(id, transition, benchmarks)
        if (transition.emittedEvents.any { it is EngineEvent.PassConfirmed }) vibrateCheckpoint()

        val orderedPasses = engineState.passes.sortedBy { it.checkpoint.orderIndex }
        val firstPass = orderedPasses.firstOrNull { it.checkpoint.orderIndex == 0 }?.elapsedRealtimeNanos
        val currentSector = (engineState.nextCheckpointIndex - 1).coerceIn(0, 2)
        var completedSectors = orderedPasses.zipWithNext().mapNotNull { (a, b) ->
            if (a.checkpoint.orderIndex + 1 != b.checkpoint.orderIndex) return@mapNotNull null
            val index = a.checkpoint.orderIndex
            val duration = b.elapsedRealtimeNanos - a.elapsedRealtimeNanos
            val month = benchmarks?.monthlySectorBestNanos?.getOrNull(index)
            val week = benchmarks?.weeklySectorBestNanos?.getOrNull(index)
            val monthDelta = month?.let { duration - it }
            val weekDelta = week?.let { duration - it }
            val uncertainty = maxOf(a.uncertaintyMillis, b.uncertaintyMillis)
            RuntimeSector(index, duration, monthDelta, RecordJudge.periodColor(duration, month, week, QualityGrade.A, uncertainty), monthDelta, weekDelta)
        }
        if (engineState.status == EngineStatus.COMPLETED) {
            completedSectors = repository.sectorRows(id).map { row ->
                RuntimeSector(
                    row.sectorIndex,
                    row.durationNanos,
                    row.deltaToReferenceNanos,
                    runCatching { ResultColor.valueOf(row.resultColorAtCompletion) }.getOrDefault(ResultColor.NEUTRAL),
                    row.monthlyDeltaNanos,
                    row.weeklyDeltaNanos,
                )
            }
        }

        val justConfirmed = transition.emittedEvents.any { it is EngineEvent.PassConfirmed }
        val monthlyTotalLive = calculateLiveComparison(fix, previousProcessedFix, firstPass, currentSector, justConfirmed, benchmarks?.monthlyTotal, monthlyProgress)
        val weeklyTotalLive = calculateLiveComparison(fix, previousProcessedFix, firstPass, currentSector, justConfirmed, benchmarks?.weeklyTotal, weeklyProgress)
        val monthlySectorLive = calculateLiveComparison(
            fix, previousProcessedFix, firstPass, currentSector, justConfirmed,
            benchmarks?.monthlySectorReferences?.getOrNull(currentSector), monthlySectorProgress,
        )
        val weeklySectorLive = calculateLiveComparison(
            fix, previousProcessedFix, firstPass, currentSector, justConfirmed,
            benchmarks?.weeklySectorReferences?.getOrNull(currentSector), weeklySectorProgress,
        )
        val monthlyLive = LiveComparison(monthlyTotalLive.cumulativeDeltaNanos, monthlySectorLive.sectorDeltaNanos, monthlyTotalLive.matched || monthlySectorLive.matched)
        val weeklyLive = LiveComparison(weeklyTotalLive.cumulativeDeltaNanos, weeklySectorLive.sectorDeltaNanos, weeklyTotalLive.matched || weeklySectorLive.matched)
        val anyMatched = monthlyLive.matched || weeklyLive.matched
        var deltaConfidence = when {
            firstPass == null || (benchmarks?.monthlyTotal == null && benchmarks?.weeklyTotal == null) -> DeltaConfidence.UNAVAILABLE
            justConfirmed && orderedPasses.size >= 2 -> DeltaConfidence.CONFIRMED
            anyMatched -> DeltaConfidence.ESTIMATED
            monthlyLive.cumulativeDeltaNanos != null || weeklyLive.cumulativeDeltaNanos != null -> DeltaConfidence.HELD
            else -> DeltaConfidence.UNAVAILABLE
        }
        var liveColor = RecordJudge.livePeriodColorFromDeltas(
            monthlyLive.cumulativeDeltaNanos,
            weeklyLive.cumulativeDeltaNanos,
            QualityGrade.A,
            liveUncertaintyMillis(fix),
        )
        var currentSectorColor = RecordJudge.livePeriodColorFromDeltas(
            monthlyLive.sectorDeltaNanos,
            weeklyLive.sectorDeltaNanos,
            QualityGrade.A,
            liveUncertaintyMillis(fix),
        )
        if (engineState.status == EngineStatus.COMPLETED) {
            val completedSession = repository.session(id)
            liveColor = completedSession?.totalResultColor?.let { runCatching { ResultColor.valueOf(it) }.getOrNull() } ?: ResultColor.NEUTRAL
            currentSectorColor = ResultColor.NEUTRAL
            deltaConfidence = DeltaConfidence.CONFIRMED
        }

        val previousPeak = speedPeakTracker.peak
        val peak = speedPeakTracker.process(
            fix,
            timingActive = engineState.passes.isNotEmpty() && engineState.status != EngineStatus.COMPLETED,
        )
        if (peak != null && peak != previousPeak) repository.saveSpeedPeak(id, peak)

        val startCheckpoint = checkpoints.firstOrNull()
        val distanceToStart = if (engineState.passes.isEmpty() && startCheckpoint != null) {
            Geometry.distanceMeters(fix.latitude, fix.longitude, startCheckpoint.latitude, startCheckpoint.longitude)
        } else RuntimeBus.state.value.routeDetectionDistanceMeters

        RuntimeBus.update {
            it.copy(
                status = statusLabel(engineState),
                engineStatus = engineState.status,
                startedAtElapsedNanos = firstPass,
                elapsedNanos = firstPass?.let { start -> (fix.elapsedRealtimeNanos - start).coerceAtLeast(0) } ?: 0,
                currentSector = if (engineState.passes.isEmpty()) 0 else currentSector + 1,
                sectors = completedSectors,
                liveDeltaNanos = monthlyLive.cumulativeDeltaNanos,
                monthlyDeltaNanos = monthlyLive.cumulativeDeltaNanos,
                weeklyDeltaNanos = weeklyLive.cumulativeDeltaNanos,
                currentSectorMonthlyDeltaNanos = monthlyLive.sectorDeltaNanos,
                currentSectorWeeklyDeltaNanos = weeklyLive.sectorDeltaNanos,
                liveColor = liveColor,
                currentSectorColor = currentSectorColor,
                deltaConfidence = deltaConfidence,
                comparisonMessage = when {
                    benchmarks?.monthlyTotal == null -> "本月首条记录"
                    benchmarks?.weeklyTotal == null -> "本周首条记录"
                    deltaConfidence == DeltaConfidence.HELD -> "暂时无法匹配 · 保留最近确认值"
                    deltaConfidence == DeltaConfidence.CONFIRMED -> "确认差值 · 已通过计时点"
                    deltaConfidence == DeltaConfidence.ESTIMATED -> "实时估算 · 每秒同位置对比"
                    else -> statusLabel(engineState)
                },
                maxSpeedMps = peak?.metersPerSecond,
                maxSpeedElapsedNanos = peak?.elapsedRealtimeNanos,
                maxSpeedWallTimeMillis = peak?.wallTimeMillis,
                lastFix = fix,
                track = track,
                actualPassElapsedNanos = engineState.passes.map { pass -> pass.elapsedRealtimeNanos },
                actualPassOrderIndices = engineState.passes.map { pass -> pass.checkpoint.orderIndex },
                missedCheckpointIndices = engineState.missedCheckpointIndices,
                routeDetectionDistanceMeters = distanceToStart,
            )
        }

        if (engineState.status == EngineStatus.COMPLETED) {
            repository.saveSpeedPeak(id, speedPeakTracker.peak)
            updateNotification(force = true)
            withContext(Dispatchers.Main) {
                locationSource.stop()
                locationStarted = false
            }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private data class LiveComparison(val cumulativeDeltaNanos: Long?, val sectorDeltaNanos: Long?, val matched: Boolean)

    private class LiveReferenceProgress {
        var expectedElapsedNanos: Long = 0L
        var lastCumulativeDeltaNanos: Long? = null
        var lastSectorDeltaNanos: Long? = null
        val cumulativeSamples = ArrayDeque<Long>()
        val sectorSamples = ArrayDeque<Long>()
        fun reset() {
            expectedElapsedNanos = 0L
            lastCumulativeDeltaNanos = null
            lastSectorDeltaNanos = null
            cumulativeSamples.clear()
            sectorSamples.clear()
        }
    }

    private fun calculateLiveComparison(
        fix: LocationFix,
        previousFix: LocationFix?,
        firstPass: Long?,
        currentSector: Int,
        justConfirmed: Boolean,
        reference: ReferenceRunData?,
        progress: LiveReferenceProgress,
    ): LiveComparison {
        if (firstPass == null || reference == null) return LiveComparison(null, null, false)
        val referencePasses = reference.passes.map { it.passElapsedNanos }
        if (referencePasses.size != 4) return LiveComparison(null, null, false)
        val confirmedPass = engineState.passes.maxByOrNull { it.checkpoint.orderIndex }
        val confirmedPassIndex = confirmedPass?.checkpoint?.orderIndex ?: -1
        if (justConfirmed && confirmedPassIndex >= 1) {
            val expected = referencePasses[confirmedPassIndex] - referencePasses.first()
            val delta = (requireNotNull(confirmedPass).elapsedRealtimeNanos - firstPass) - expected
            progress.expectedElapsedNanos = expected
            progress.lastCumulativeDeltaNanos = delta
            progress.lastSectorDeltaNanos = if (engineState.status == EngineStatus.COMPLETED) null else 0L
            progress.cumulativeSamples.clear()
            progress.sectorSamples.clear()
            return LiveComparison(delta, progress.lastSectorDeltaNanos, true)
        }
        if (engineState.status == EngineStatus.COMPLETED) return LiveComparison(progress.lastCumulativeDeltaNanos, null, false)
        val actualGap = previousFix?.let { (fix.elapsedRealtimeNanos - it.elapsedRealtimeNanos).coerceAtLeast(0L) } ?: 1_000_000_000L
        val maximumExpected = progress.expectedElapsedNanos + max(5_000_000_000L, actualGap * 3)
        val match = ReferenceProgressMatcher.match(
            fix = fix,
            referenceTrack = reference.track.map(::toFix),
            referencePassElapsedNanos = referencePasses,
            currentSectorIndex = currentSector,
            minimumExpectedElapsedNanos = progress.expectedElapsedNanos,
            maximumExpectedElapsedNanos = maximumExpected,
        ) ?: return LiveComparison(progress.lastCumulativeDeltaNanos, progress.lastSectorDeltaNanos, false)
        progress.expectedElapsedNanos = match.expectedElapsedSinceStartNanos
        val rawCumulative = (fix.elapsedRealtimeNanos - firstPass) - match.expectedElapsedSinceStartNanos
        val actualSectorStart = engineState.passes.getOrNull(currentSector)?.elapsedRealtimeNanos ?: firstPass
        val referenceSectorStart = referencePasses[currentSector] - referencePasses.first()
        val rawSector = (fix.elapsedRealtimeNanos - actualSectorStart) - (match.expectedElapsedSinceStartNanos - referenceSectorStart)
        progress.lastCumulativeDeltaNanos = medianSample(progress.cumulativeSamples, rawCumulative)
        progress.lastSectorDeltaNanos = medianSample(progress.sectorSamples, rawSector)
        return LiveComparison(progress.lastCumulativeDeltaNanos, progress.lastSectorDeltaNanos, true)
    }

    private fun medianSample(samples: ArrayDeque<Long>, value: Long): Long {
        samples.addLast(value)
        while (samples.size > 3) samples.removeFirst()
        return samples.sorted()[samples.size / 2]
    }

    private fun liveUncertaintyMillis(fix: LocationFix): Long =
        ((fix.accuracyMeters / max(fix.speedMetersPerSecond ?: 2.0, 2.0)) * 1_000.0).toLong()

    private fun stopCurrent(baseline: Boolean) {
        val id = sessionId
        scope.launch {
            if (id != null) {
                if (baseline) repository.finishBaseline(id) else {
                    repository.saveSpeedPeak(id, speedPeakTracker.peak)
                    repository.cancelSession(id, "用户主动结束")
                }
            }
            withContext(Dispatchers.Main) {
                runCatching { locationSource.stop() }
                locationStarted = false
            }
            if (baseline && id != null) RuntimeBus.update { it.copy(status = "路线轨迹已录制") } else RuntimeBus.reset()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopWithError(message: String) {
        RuntimeBus.reset(message)
        if (locationStarted) runCatching { locationSource.stop() }
        locationStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun statusLabel(state: EngineState): String = when (state.status) {
        EngineStatus.ARMED, EngineStatus.START_CANDIDATE -> "等待驶过起点 A"
        EngineStatus.SECTOR_1 -> "S1 计时中"
        EngineStatus.SECTOR_2 -> "S2 计时中"
        EngineStatus.SECTOR_3, EngineStatus.FINISH_CANDIDATE -> "S3 计时中"
        EngineStatus.COMPLETED -> "行程完成"
        EngineStatus.CANCELLED -> "已结束"
        EngineStatus.INTERRUPTED -> "计时中断"
        EngineStatus.INCOMPLETE -> "行程不完整"
    }

    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < 1_000) return
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(RuntimeBus.state.value.status))
    }

    private fun notification(status: String): Notification {
        val snapshot = RuntimeBus.state.value
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val delta = snapshot.liveDeltaNanos
            ?.takeIf { snapshot.deltaConfidence in setOf(DeltaConfidence.CONFIRMED, DeltaConfidence.HELD) }
            ?.let { value -> " · Δ ${if (value < 0) "−" else "+"}${formatDuration(abs(value))}" }
            .orEmpty()
        val sector = if (snapshot.currentSector in 1..3) "S${snapshot.currentSector}" else "等待 A"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_delta_t_foreground)
            .setContentTitle(if (routeName.isBlank()) "Delta T · $status" else "$routeName · $sector")
            .setContentText("${formatDuration(snapshot.elapsedNanos)}$delta · ${snapshot.gnssHealth.displayLabel()}")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "打开并确认结束", openIntent)
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notification_channel_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun hasLocationPermission() = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun vibrateCheckpoint() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun formatDuration(nanos: Long): String {
        val hundredths = nanos / 10_000_000
        val minutes = hundredths / 6_000
        val seconds = (hundredths / 100) % 60
        return "%02d:%02d.%02d".format(minutes, seconds, hundredths % 100)
    }

    private fun toFix(point: TrackPointEntity) = LocationFix(
        latitude = point.latitude,
        longitude = point.longitude,
        elapsedRealtimeNanos = point.elapsedRealtimeNanos,
        wallTimeMillis = point.wallTimeMillis,
        accuracyMeters = point.accuracyMeters,
        speedMetersPerSecond = point.speedMps,
        bearingDegrees = point.bearingDegrees,
        bearingAccuracyDegrees = point.bearingAccuracy,
        provider = point.provider,
        isMock = point.isMock,
        speedAccuracyMetersPerSecond = point.speedAccuracyMps,
    )

    override fun onDestroy() {
        runCatching { locationSource.stop() }
        val id = sessionId
        if (id != null && engineState.status != EngineStatus.COMPLETED && RuntimeBus.state.value.status !in setOf("路线轨迹已录制", "IDLE")) {
            scope.launch { repository.markInterrupted(id, "前台定位服务已停止") }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_BASELINE = "app.deltat.action.START_BASELINE"
        const val ACTION_ARM = "app.deltat.action.ARM"
        const val ACTION_ARM_AUTO = "app.deltat.action.ARM_AUTO"
        const val ACTION_SWITCH_ROUTE = "app.deltat.action.SWITCH_ROUTE"
        const val ACTION_STOP = "app.deltat.action.STOP"
        const val ACTION_STOP_BASELINE = "app.deltat.action.STOP_BASELINE"
        const val EXTRA_ROUTE_ID = "route_id"
        private const val CHANNEL_ID = "delta_t_timing"
        private const val NOTIFICATION_ID = 4101
    }
}
