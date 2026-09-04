package app.deltat.commute.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingEngineTest {
    @Test fun mockFixNeverAdvancesTimingState() {
        val engine = DefaultTimingEngine()
        val mock = fix(checkpoints.first().longitude, 1).copy(isMock = true)

        val transition = engine.processFix(EngineState(), mock, checkpoints)

        assertEquals(EngineStatus.ARMED, transition.newState.status)
        assertEquals(0, transition.newState.nextCheckpointIndex)
        assertTrue(transition.emittedEvents.filterIsInstance<EngineEvent.FixRejected>().single().reason.contains("模拟定位"))
    }
    private val engine = DefaultTimingEngine()
    private val checkpoints = (0..3).map { index ->
        Checkpoint(
            id = "cp$index",
            orderIndex = index,
            label = listOf("A", "S1", "S2", "B")[index],
            latitude = 31.0,
            longitude = 121.0 + index * .001,
            preferredBearing = 90.0,
        )
    }

    @Test fun completeFourPointRunUsesProgressLinesForIntermediatePoints() {
        val result = run(
            listOf(
                fix(120.9996, 1), fix(121.0001, 3),
                fix(121.0008, 5), fix(121.0011, 7), fix(121.0014, 9),
                fix(121.0018, 11), fix(121.0021, 13), fix(121.0024, 15),
                fix(121.0028, 17), fix(121.0031, 19),
            ),
        )

        assertEquals(EngineStatus.COMPLETED, result.status)
        assertEquals(listOf(0, 1, 2, 3), result.passes.map { it.checkpoint.orderIndex })
        assertEquals(TriggerMode.PROGRESS_LINE, result.passes[1].triggerMode)
        assertEquals(TriggerMode.PROGRESS_LINE, result.passes[2].triggerMode)
        assertTrue(result.missedCheckpointIndices.isEmpty())
    }

    @Test fun hittingS2WhileWaitingForS1MarksS1MissedAndContinues() {
        var state = EngineState(
            status = EngineStatus.SECTOR_1,
            nextCheckpointIndex = 1,
            passes = listOf(pass(0, 2)),
            previousFix = fix(121.0016, 5),
        )
        val emitted = mutableListOf<EngineEvent>()
        listOf(fix(121.0021, 7), fix(121.0024, 9)).forEach { sample ->
            val transition = engine.processFix(state, sample, checkpoints)
            state = transition.newState
            emitted += transition.emittedEvents
        }

        assertEquals(listOf(0, 2), state.passes.map { it.checkpoint.orderIndex })
        assertEquals(setOf(1), state.missedCheckpointIndices)
        assertEquals(3, state.nextCheckpointIndex)
        assertTrue(emitted.any { it is EngineEvent.CheckpointMissed && it.checkpoint.orderIndex == 1 })
        assertFalse(emitted.any { it is EngineEvent.SectorCompleted })
    }

    @Test fun reachingBMarksAnyRemainingIntermediatePointMissedAndCompletes() {
        val state = EngineState(
            status = EngineStatus.SECTOR_2,
            nextCheckpointIndex = 2,
            passes = listOf(pass(0, 2), pass(1, 6)),
            previousFix = fix(121.0028, 10),
        )
        val transition = engine.processFix(state, fix(121.0031, 12), checkpoints)

        assertEquals(EngineStatus.COMPLETED, transition.newState.status)
        assertEquals(listOf(0, 1, 3), transition.newState.passes.map { it.checkpoint.orderIndex })
        assertEquals(setOf(2), transition.newState.missedCheckpointIndices)
        assertFalse(transition.emittedEvents.any { it is EngineEvent.SectorCompleted })
    }

    @Test fun oneGnssSegmentCanConfirmMultipleLinesInIntersectionOrder() {
        val state = EngineState(
            status = EngineStatus.SECTOR_1,
            nextCheckpointIndex = 1,
            passes = listOf(pass(0, 2)),
            previousFix = fix(121.0008, 5),
        )
        val transition = engine.processFix(state, fix(121.0024, 10), checkpoints)
        val intermediate = transition.newState.passes.filter { it.checkpoint.orderIndex in 1..2 }

        assertEquals(listOf(1, 2), intermediate.map { it.checkpoint.orderIndex })
        assertTrue(intermediate[0].elapsedRealtimeNanos < intermediate[1].elapsedRealtimeNanos)
        assertEquals(3, transition.newState.nextCheckpointIndex)
    }

    @Test fun reverseCrossingDoesNotCreateAProgressLineCandidate() {
        val state = EngineState(
            status = EngineStatus.SECTOR_1,
            nextCheckpointIndex = 1,
            passes = listOf(pass(0, 2)),
            previousFix = fix(121.0012, 5, bearing = 270.0),
        )
        val transition = engine.processFix(state, fix(121.0008, 7, bearing = 270.0), checkpoints)

        assertEquals(listOf(0), transition.newState.passes.map { it.checkpoint.orderIndex })
        assertTrue(transition.newState.progressLineCandidates.isEmpty())
    }

    @Test fun backtrackingCancelsCandidateAndLaterForwardCrossingCanConfirm() {
        var state = EngineState(
            status = EngineStatus.SECTOR_1,
            nextCheckpointIndex = 1,
            passes = listOf(pass(0, 2)),
            previousFix = fix(121.0009, 5),
        )
        state = engine.processFix(state, fix(121.00105, 7), checkpoints).newState
        assertEquals(1, state.progressLineCandidates.size)
        state = engine.processFix(state, fix(121.0009, 9, bearing = 270.0), checkpoints).newState
        assertTrue(state.progressLineCandidates.isEmpty())

        listOf(fix(121.00105, 11), fix(121.00115, 13), fix(121.00130, 15)).forEach {
            state = engine.processFix(state, it, checkpoints).newState
        }
        assertEquals(listOf(0, 1), state.passes.map { it.checkpoint.orderIndex })
    }

    @Test fun progressLineStillCountsOnAParallelRouteFarFromCheckpoint() {
        val farNorth = 31.01
        var state = EngineState(
            status = EngineStatus.SECTOR_1,
            nextCheckpointIndex = 1,
            passes = listOf(pass(0, 2)),
            previousFix = fix(121.0008, 5, latitude = farNorth),
        )
        listOf(
            fix(121.0011, 7, latitude = farNorth),
            fix(121.0014, 9, latitude = farNorth),
        ).forEach { state = engine.processFix(state, it, checkpoints).newState }

        assertEquals(listOf(0, 1), state.passes.map { it.checkpoint.orderIndex })
        assertTrue(state.passes.last().closestDistanceMeters > 1_000.0)
    }

    @Test fun startingInsideARequiresExitAndReentry() {
        var state = EngineState()
        state = engine.processFix(state, fix(121.0, 1), checkpoints).newState
        state = engine.processFix(state, fix(121.0001, 2), checkpoints).newState
        assertTrue(state.passes.isEmpty())
        state = engine.processFix(state, fix(120.9995, 5), checkpoints).newState
        state = engine.processFix(state, fix(121.0001, 8), checkpoints).newState
        assertEquals(1, state.passes.size)
    }

    @Test fun invalidFixConfigurationAndTerminalStatesAreSafe() {
        val inaccurate = engine.processFix(EngineState(), fix(121.0, 1, accuracy = 80.0), checkpoints)
        assertEquals(1, inaccurate.newState.rejectedFixes)
        val invalid = engine.processFix(EngineState(), fix(120.9995, 1), checkpoints.take(3))
        assertEquals(1, invalid.newState.rejectedFixes)
        var state = engine.processFix(EngineState(), fix(120.9995, 1), checkpoints).newState
        state = engine.processFix(state, fix(121.01, 2), checkpoints).newState
        assertEquals(1, state.rejectedFixes)
        val complete = EngineState(status = EngineStatus.COMPLETED)
        assertSame(complete, engine.restore(complete))
        assertSame(complete, engine.processFix(complete, fix(121.0, 2), checkpoints).newState)
    }

    @Test fun cancelIsTerminal() {
        val transition = engine.cancel(EngineState(), "用户结束")
        assertEquals(EngineStatus.CANCELLED, transition.newState.status)
        assertTrue(transition.persistenceUrgent)
    }

    @Test fun legacyEngineStillReplaysStraightHistoricalFourPointTracks() {
        val legacy = LegacyTimingEngine()
        val historicalPoints = (0..8).map { index -> fix(121.0 + index * .001, index * 8L + 1) }
        val historicalCheckpoints = listOf(1, 3, 5, 7).mapIndexed { order, pointIndex ->
            checkpoints[order].copy(longitude = 121.0 + pointIndex * .001, preferredBearing = 90.0)
        }
        val result = historicalPoints.fold(EngineState()) { state, point ->
            legacy.processFix(state, point, historicalCheckpoints).newState
        }
        assertEquals(EngineStatus.COMPLETED, result.status)
        assertEquals(listOf(0, 1, 2, 3), result.passes.map { it.checkpoint.orderIndex })
    }

    private fun run(fixes: List<LocationFix>): EngineState = fixes.fold(EngineState()) { state, fix ->
        engine.processFix(state, fix, checkpoints).newState
    }

    private fun pass(order: Int, seconds: Long) = CheckpointPass(
        checkpoint = checkpoints[order],
        elapsedRealtimeNanos = seconds * 1_000_000_000,
        latitude = checkpoints[order].latitude,
        longitude = checkpoints[order].longitude,
        triggerMode = if (order in 1..2) TriggerMode.PROGRESS_LINE else TriggerMode.LINE_CROSSING,
        closestDistanceMeters = 0.0,
        uncertaintyMillis = 100,
    )

    private fun fix(
        longitude: Double,
        seconds: Long,
        accuracy: Double = 5.0,
        bearing: Double = 90.0,
        latitude: Double = 31.0,
    ) = LocationFix(
        latitude = latitude,
        longitude = longitude,
        elapsedRealtimeNanos = seconds * 1_000_000_000,
        wallTimeMillis = seconds * 1_000,
        accuracyMeters = accuracy,
        speedMetersPerSecond = 15.0,
        bearingDegrees = bearing,
    )
}
