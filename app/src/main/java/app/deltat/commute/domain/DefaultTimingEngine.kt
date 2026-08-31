package app.deltat.commute.domain

import kotlin.math.max

class DefaultTimingEngine : TimingEngine {
    override fun restore(snapshot: EngineState): EngineState = snapshot

    override fun cancel(state: EngineState, reason: String): EngineTransition = EngineTransition(
        newState = state.copy(status = EngineStatus.CANCELLED, candidate = null),
        emittedEvents = listOf(EngineEvent.FixRejected(reason)),
        persistenceUrgent = true,
    )

    override fun processFix(
        state: EngineState,
        fix: LocationFix,
        checkpoints: List<Checkpoint>,
    ): EngineTransition {
        if (state.status in setOf(EngineStatus.COMPLETED, EngineStatus.CANCELLED, EngineStatus.INTERRUPTED)) {
            return EngineTransition(state)
        }
        if (checkpoints.size != 4) return reject(state, fix, "计时点数量不是 4")
        val previous = state.previousFix
        if (fix.accuracyMeters > 50.0) return reject(state, fix, "精度低于门槛")
        if (previous != null && fix.elapsedRealtimeNanos <= previous.elapsedRealtimeNanos) {
            return reject(state, fix, "单调时间无效", keepPrevious = true)
        }
        if (previous != null) {
            val dt = (fix.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
            val distance = Geometry.distanceMeters(previous.latitude, previous.longitude, fix.latitude, fix.longitude)
            if (dt > 0 && distance / dt > 80.0) return reject(state, fix, "位置跳跃", keepPrevious = true)
        }

        val checkpoint = checkpoints[state.nextCheckpointIndex]
        val distance = Geometry.distanceMeters(fix.latitude, fix.longitude, checkpoint.latitude, checkpoint.longitude)

        if (state.nextCheckpointIndex == 0) {
            val requiresExit = state.startRequiresExit ?: (distance <= checkpoint.radiusMeters)
            if (requiresExit && distance <= checkpoint.radiusMeters * 1.2) {
                return EngineTransition(state.copy(previousFix = fix, startRequiresExit = true))
            }
            if (requiresExit && distance > checkpoint.radiusMeters * 1.2) {
                return EngineTransition(state.copy(previousFix = fix, startRequiresExit = false, candidate = null))
            }
        }

        if (previous != null) {
            val crossing = Geometry.crossing(previous, fix, checkpoint)
            if (crossing != null) {
                val interpolated = Geometry.interpolate(previous, fix, crossing.ratio)
                return confirm(state, fix, checkpoint, interpolated, TriggerMode.LINE_CROSSING, crossing.crossTrackMeters)
            }
        }

        val candidate = state.candidate
        if (distance <= checkpoint.radiusMeters) {
            val nextCandidate = if (candidate == null || distance < candidate.bestDistanceMeters) {
                Candidate(fix, distance, candidate?.bestDistanceMeters ?: Double.POSITIVE_INFINITY)
            } else candidate.copy(previousDistanceMeters = distance)
            return EngineTransition(
                state.copy(
                    status = candidateStatus(state.nextCheckpointIndex),
                    previousFix = fix,
                    candidate = nextCandidate,
                    startRequiresExit = false,
                ),
            )
        }

        if (candidate != null && distance > checkpoint.radiusMeters && distance > candidate.bestDistanceMeters + 8.0) {
            return confirm(
                state,
                fix,
                checkpoint,
                candidate.bestFix,
                TriggerMode.CLOSEST_APPROACH,
                candidate.bestDistanceMeters,
            )
        }

        return EngineTransition(
            state.copy(
                previousFix = fix,
                candidate = null,
                status = waitingStatus(state.nextCheckpointIndex),
                startRequiresExit = if (state.nextCheckpointIndex == 0) state.startRequiresExit ?: false else state.startRequiresExit,
            ),
        )
    }

    private fun reject(state: EngineState, fix: LocationFix, reason: String, keepPrevious: Boolean = false) = EngineTransition(
        newState = state.copy(
            previousFix = if (keepPrevious) state.previousFix else fix,
            rejectedFixes = state.rejectedFixes + 1,
        ),
        emittedEvents = listOf(EngineEvent.FixRejected(reason)),
    )

    private fun confirm(
        state: EngineState,
        currentFix: LocationFix,
        checkpoint: Checkpoint,
        passFix: LocationFix,
        mode: TriggerMode,
        distance: Double,
    ): EngineTransition {
        val uncertainty = ((max(passFix.accuracyMeters, 1.0) / max(passFix.speedMetersPerSecond ?: 5.0, 2.0)) * 1_000).toLong()
        val pass = CheckpointPass(
            checkpoint = checkpoint,
            elapsedRealtimeNanos = passFix.elapsedRealtimeNanos,
            latitude = passFix.latitude,
            longitude = passFix.longitude,
            triggerMode = mode,
            closestDistanceMeters = distance,
            uncertaintyMillis = uncertainty,
        )
        val passes = state.passes + pass
        val next = state.nextCheckpointIndex + 1
        val events = mutableListOf<EngineEvent>(EngineEvent.PassConfirmed(pass))
        if (passes.size >= 2) {
            events += EngineEvent.SectorCompleted(passes.size - 2, passes.last().elapsedRealtimeNanos - passes[passes.lastIndex - 1].elapsedRealtimeNanos)
        }
        if (next == 4) events += EngineEvent.Finished
        return EngineTransition(
            newState = state.copy(
                status = if (next == 4) EngineStatus.COMPLETED else waitingStatus(next),
                nextCheckpointIndex = next.coerceAtMost(3),
                passes = passes,
                previousFix = currentFix,
                candidate = null,
                startRequiresExit = false,
            ),
            emittedEvents = events,
            persistenceUrgent = true,
        )
    }

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
}
