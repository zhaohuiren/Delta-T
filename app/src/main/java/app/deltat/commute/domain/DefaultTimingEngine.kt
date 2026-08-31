package app.deltat.commute.domain

import kotlin.math.max

class DefaultTimingEngine : TimingEngine {
    override fun restore(snapshot: EngineState): EngineState = snapshot

    override fun cancel(state: EngineState, reason: String): EngineTransition = EngineTransition(
        newState = state.copy(status = EngineStatus.CANCELLED, candidate = null, progressLineCandidates = emptyList()),
        emittedEvents = listOf(EngineEvent.FixRejected(reason)),
        persistenceUrgent = true,
    )

    override fun processFix(state: EngineState, fix: LocationFix, checkpoints: List<Checkpoint>): EngineTransition {
        if (state.status in setOf(EngineStatus.COMPLETED, EngineStatus.CANCELLED, EngineStatus.INTERRUPTED)) return EngineTransition(state)
        val ordered = checkpoints.sortedBy { it.orderIndex }
        if (ordered.size != 4 || ordered.map { it.orderIndex } != listOf(0, 1, 2, 3)) {
            return reject(state, fix, "计时点必须是 A、S1、S2、B")
        }
        if (fix.accuracyMeters > 50.0) return reject(state, fix, "精度低于门槛")
        val previous = state.previousFix
        if (previous != null && fix.elapsedRealtimeNanos <= previous.elapsedRealtimeNanos) {
            return reject(state, fix, "单调时间无效", keepPrevious = true)
        }
        if (previous != null) {
            val seconds = (fix.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
            val distance = Geometry.distanceMeters(previous.latitude, previous.longitude, fix.latitude, fix.longitude)
            if (seconds > 0 && distance / seconds > 80.0) return reject(state, fix, "位置跳跃", keepPrevious = true)
        }

        if (state.nextCheckpointIndex == 0) return processEndpoint(state, fix, ordered.first(), emptyList())
        if (previous == null) return EngineTransition(state.copy(previousFix = fix))

        val routeBearing = Geometry.bearingDegrees(
            ordered.first().latitude, ordered.first().longitude,
            ordered.last().latitude, ordered.last().longitude,
        )
        var working = state.copy(candidate = null)
        val events = mutableListOf<EngineEvent>()

        val updatedCandidates = working.progressLineCandidates.mapNotNull { candidate ->
            val depth = Geometry.signedProgressMeters(fix.latitude, fix.longitude, candidate.checkpoint, routeBearing)
            if (depth < 0.0) null else candidate.copy(
                consecutiveBeyondFixes = candidate.consecutiveBeyondFixes + 1,
                maximumDepthMeters = max(candidate.maximumDepthMeters, depth),
            )
        }.toMutableList()

        ordered.asSequence()
            .filter { it.orderIndex in maxOf(1, working.nextCheckpointIndex)..2 }
            .filterNot { checkpoint -> updatedCandidates.any { it.checkpoint.orderIndex == checkpoint.orderIndex } }
            .mapNotNull { checkpoint ->
                Geometry.progressLineCrossing(previous, fix, checkpoint, routeBearing)?.let { checkpoint to it }
            }
            .sortedBy { it.second.ratio }
            .forEach { (checkpoint, crossing) ->
                val crossingFix = Geometry.interpolate(previous, fix, crossing.ratio)
                val depth = Geometry.signedProgressMeters(fix.latitude, fix.longitude, checkpoint, routeBearing)
                updatedCandidates += ProgressLineCandidate(checkpoint, crossingFix, crossing.crossTrackMeters, 1, depth)
            }

        working = working.copy(progressLineCandidates = updatedCandidates)
        updatedCandidates
            .filter { it.consecutiveBeyondFixes >= REQUIRED_BEYOND_FIXES || it.maximumDepthMeters >= REQUIRED_DEPTH_METERS }
            .sortedWith(compareBy<ProgressLineCandidate> { it.crossingFix.elapsedRealtimeNanos }.thenBy { it.checkpoint.orderIndex })
            .forEach { candidate ->
                if (candidate.checkpoint.orderIndex < working.nextCheckpointIndex) return@forEach
                val skipped = ordered.filter {
                    it.orderIndex in working.nextCheckpointIndex until candidate.checkpoint.orderIndex && it.orderIndex in 1..2
                }
                val result = confirmPass(
                    working, candidate.checkpoint, candidate.crossingFix,
                    TriggerMode.PROGRESS_LINE, candidate.crossTrackMeters, skipped,
                )
                working = result.first
                events += result.second
            }
        working = working.copy(
            progressLineCandidates = working.progressLineCandidates.filter { it.checkpoint.orderIndex >= working.nextCheckpointIndex },
        )

        if (working.nextCheckpointIndex <= 3) {
            val finishResult = processEndpoint(
                working.copy(previousFix = previous),
                fix,
                ordered.last(),
                ordered.filter { it.orderIndex in working.nextCheckpointIndex until 3 && it.orderIndex in 1..2 },
            )
            if (finishResult.emittedEvents.any { it is EngineEvent.PassConfirmed }) {
                return EngineTransition(finishResult.newState, events + finishResult.emittedEvents, true)
            }
            working = finishResult.newState
        }

        return EngineTransition(
            newState = working.copy(previousFix = fix, status = waitingStatus(working.nextCheckpointIndex)),
            emittedEvents = events,
            persistenceUrgent = events.isNotEmpty(),
        )
    }

    private fun processEndpoint(
        state: EngineState,
        fix: LocationFix,
        checkpoint: Checkpoint,
        markSkipped: List<Checkpoint>,
    ): EngineTransition {
        if (checkpoint.orderIndex < state.nextCheckpointIndex) return EngineTransition(state.copy(previousFix = fix))
        val previous = state.previousFix
        val distance = Geometry.distanceMeters(fix.latitude, fix.longitude, checkpoint.latitude, checkpoint.longitude)
        if (checkpoint.orderIndex == 0) {
            val requiresExit = state.startRequiresExit ?: (distance <= checkpoint.radiusMeters)
            if (requiresExit && distance <= checkpoint.radiusMeters * 1.2) {
                return EngineTransition(state.copy(previousFix = fix, startRequiresExit = true))
            }
            if (requiresExit && distance > checkpoint.radiusMeters * 1.2) {
                return EngineTransition(state.copy(previousFix = fix, startRequiresExit = false, candidate = null))
            }
        }
        if (previous != null) {
            Geometry.crossing(previous, fix, checkpoint)?.let { crossing ->
                val passFix = Geometry.interpolate(previous, fix, crossing.ratio)
                val (newState, events) = confirmPass(
                    state, checkpoint, passFix, TriggerMode.LINE_CROSSING, crossing.crossTrackMeters, markSkipped,
                )
                return EngineTransition(newState.copy(previousFix = fix), events, true)
            }
        }
        val candidate = state.candidate
        if (distance <= checkpoint.radiusMeters) {
            val nextCandidate = if (candidate == null || distance < candidate.bestDistanceMeters) {
                Candidate(fix, distance, candidate?.bestDistanceMeters ?: Double.POSITIVE_INFINITY)
            } else candidate.copy(previousDistanceMeters = distance)
            return EngineTransition(
                state.copy(
                    status = candidateStatus(checkpoint.orderIndex), previousFix = fix,
                    candidate = nextCandidate, startRequiresExit = false,
                ),
            )
        }
        if (candidate != null && distance > checkpoint.radiusMeters && distance > candidate.bestDistanceMeters + 8.0) {
            val (newState, events) = confirmPass(
                state, checkpoint, candidate.bestFix, TriggerMode.CLOSEST_APPROACH, candidate.bestDistanceMeters, markSkipped,
            )
            return EngineTransition(newState.copy(previousFix = fix), events, true)
        }
        return EngineTransition(
            state.copy(
                previousFix = fix, candidate = null, status = waitingStatus(state.nextCheckpointIndex),
                startRequiresExit = if (checkpoint.orderIndex == 0) state.startRequiresExit ?: false else state.startRequiresExit,
            ),
        )
    }

    private fun confirmPass(
        state: EngineState,
        checkpoint: Checkpoint,
        passFix: LocationFix,
        mode: TriggerMode,
        distance: Double,
        skipped: List<Checkpoint>,
    ): Pair<EngineState, List<EngineEvent>> {
        val missed = skipped.filterNot { it.orderIndex in state.missedCheckpointIndices }
        val uncertainty = ((max(passFix.accuracyMeters, 1.0) / max(passFix.speedMetersPerSecond ?: 5.0, 2.0)) * 1_000).toLong()
        val pass = CheckpointPass(
            checkpoint, passFix.elapsedRealtimeNanos, passFix.latitude, passFix.longitude,
            mode, distance, uncertainty,
        )
        val previousPass = state.passes.maxByOrNull { it.checkpoint.orderIndex }
        val passes = (state.passes + pass).distinctBy { it.checkpoint.orderIndex }.sortedBy { it.checkpoint.orderIndex }
        val events = mutableListOf<EngineEvent>()
        events += missed.map { EngineEvent.CheckpointMissed(it) }
        events += EngineEvent.PassConfirmed(pass)
        if (previousPass != null && previousPass.checkpoint.orderIndex + 1 == checkpoint.orderIndex) {
            events += EngineEvent.SectorCompleted(
                previousPass.checkpoint.orderIndex,
                pass.elapsedRealtimeNanos - previousPass.elapsedRealtimeNanos,
            )
        }
        val next = checkpoint.orderIndex + 1
        if (checkpoint.orderIndex == 3) events += EngineEvent.Finished
        return state.copy(
            status = if (checkpoint.orderIndex == 3) EngineStatus.COMPLETED else waitingStatus(next),
            nextCheckpointIndex = next,
            passes = passes,
            candidate = null,
            progressLineCandidates = state.progressLineCandidates.filterNot { it.checkpoint.orderIndex <= checkpoint.orderIndex },
            missedCheckpointIndices = state.missedCheckpointIndices + missed.map { it.orderIndex },
            startRequiresExit = false,
        ) to events
    }

    private fun reject(state: EngineState, fix: LocationFix, reason: String, keepPrevious: Boolean = false) = EngineTransition(
        newState = state.copy(previousFix = if (keepPrevious) state.previousFix else fix, rejectedFixes = state.rejectedFixes + 1),
        emittedEvents = listOf(EngineEvent.FixRejected(reason)),
    )

    private fun waitingStatus(index: Int) = when (index) {
        0 -> EngineStatus.ARMED
        1 -> EngineStatus.SECTOR_1
        2 -> EngineStatus.SECTOR_2
        3 -> EngineStatus.SECTOR_3
        else -> EngineStatus.COMPLETED
    }

    private fun candidateStatus(index: Int) = when (index) {
        0 -> EngineStatus.START_CANDIDATE
        3 -> EngineStatus.FINISH_CANDIDATE
        else -> waitingStatus(index)
    }

    companion object {
        const val TIMING_ENGINE_VERSION = 2
        private const val REQUIRED_BEYOND_FIXES = 3
        private const val REQUIRED_DEPTH_METERS = 25.0
    }
}
