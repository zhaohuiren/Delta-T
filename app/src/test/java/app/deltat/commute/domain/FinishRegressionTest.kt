package app.deltat.commute.domain

import org.junit.Assert.*
import org.junit.Test

class FinishRegressionTest {
    private val engine = DefaultTimingEngine()
    private val checkpoints = (0..3).map { Checkpoint("p$it", it, "$it", 0.0, it * .003, preferredBearing = 90.0) }
    private fun fix(eastOfB: Double, seconds: Long, accuracy: Double = 3.0) = LocationFix(0.0, .009 + eastOfB / 111195.0, seconds * 1_000_000_000L, seconds * 1000, accuracy, speedMetersPerSecond = 10.0)
    private fun waiting() = EngineState(status = EngineStatus.SECTOR_3, nextCheckpointIndex = 3,
        passes = (0..2).map { CheckpointPass(checkpoints[it], it * 1_000_000_000L, 0.0, it * .003, TriggerMode.LINE_CROSSING, 0.0, 100) }, previousFix = fix(60.0, 10))

    @Test fun editorServiceValidationRejectsOrderThatOnlyWorksOnDiagonalBearing() {
        val points = listOf(0.0 to 0.0, 400.0 to 0.0, 300.0 to 600.0, 1000.0 to 800.0)
        val invalid = points.mapIndexed { index, (x, y) -> Checkpoint("$index", index, "$index", y / 111195.0, x / 111195.0) }
        assertFalse(validCheckpointSequence(invalid))
        assertTrue(validCheckpointSequence(checkpoints))
    }

    @Test fun finishWithoutLineCrossingUsesClosestPointAndFinishesOnlyOnce() {
        var state = waiting()
        listOf(34.0, 25.0, 30.0, 34.0).forEachIndexed { index, distance ->
            state = engine.processFix(state, fix(distance, 11L + index), checkpoints).newState
            assertEquals(EngineStatus.FINISH_CANDIDATE, state.status)
        }
        val finish = engine.processFix(state, fix(42.0, 15), checkpoints)
        assertEquals(EngineStatus.COMPLETED, finish.newState.status)
        assertEquals(12_000_000_000L, finish.newState.passes.last().elapsedRealtimeNanos)
        assertEquals(TriggerMode.CLOSEST_APPROACH, finish.newState.passes.last().triggerMode)
        assertTrue(engine.processFix(finish.newState, fix(60.0, 16), checkpoints).emittedEvents.isEmpty())
    }

    @Test fun candidateSurvivesLeavingRadiusBeforeEightMeterHysteresis() {
        var state = waiting()
        listOf(34.0, 36.0, 40.0, 44.0).forEachIndexed { index, distance -> state = engine.processFix(state, fix(distance, 11L + index), checkpoints).newState }
        assertEquals(EngineStatus.COMPLETED, state.status)
        assertEquals(11_000_000_000L, state.passes.last().elapsedRealtimeNanos)
    }

    @Test fun badPointBreaksCrossingAndCandidateContinuity() {
        val candidate = engine.processFix(waiting(), fix(25.0, 11), checkpoints).newState
        val rejected = engine.processFix(candidate, fix(-10.0, 12, 100.0), checkpoints).newState
        assertNull(rejected.previousFix)
        assertNull(rejected.candidate)
        val resumed = engine.processFix(rejected, fix(50.0, 13), checkpoints)
        assertTrue(resumed.emittedEvents.isEmpty())
        assertEquals(3, resumed.newState.passes.size)
    }

    @Test fun longGapAndCancellationCannotConfirmAnOldCandidate() {
        val candidate = engine.processFix(waiting(), fix(25.0, 11), checkpoints).newState
        val resumed = engine.processFix(candidate, fix(50.0, 30), checkpoints)
        assertTrue(resumed.emittedEvents.isEmpty())
        assertNull(resumed.newState.candidate)
        assertTrue(engine.processFix(engine.cancel(candidate, "test").newState, fix(50.0, 12), checkpoints).emittedEvents.isEmpty())
    }
}
