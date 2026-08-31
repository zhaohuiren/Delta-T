package app.deltat.commute.domain

import org.junit.Assert.*
import org.junit.Test

class TimingEngineTest {
    private val engine = DefaultTimingEngine()
    private val checkpoints = (0..3).map { index ->
        Checkpoint("cp$index", index, listOf("A", "S1", "S2", "B")[index], 31.0, 121.0 + index * .001, preferredBearing = 90.0)
    }

    @Test fun fourCrossingsCompleteInOrder() {
        val result = run(track())
        assertEquals(EngineStatus.COMPLETED, result.status)
        assertEquals(listOf(0, 1, 2, 3), result.passes.map { it.checkpoint.orderIndex })
        assertEquals(3, result.passes.zipWithNext().size)
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

    @Test fun inaccurateFixIsRetainedAsRejectedNotPassed() {
        val state = engine.processFix(EngineState(), fix(121.0, 1, accuracy = 80.0), checkpoints).newState
        assertEquals(1, state.rejectedFixes)
        assertTrue(state.passes.isEmpty())
    }

    @Test fun duplicateMonotonicTimeIsRejected() {
        var state = engine.processFix(EngineState(), fix(120.9998, 1), checkpoints).newState
        state = engine.processFix(state, fix(121.0002, 1), checkpoints).newState
        assertEquals(1, state.rejectedFixes)
    }

    @Test fun cancelIsTerminal() {
        val transition = engine.cancel(EngineState(), "用户结束")
        assertEquals(EngineStatus.CANCELLED, transition.newState.status)
        assertTrue(transition.persistenceUrgent)
    }

    @Test fun closestApproachFallbackConfirmsCheckpoint() {
        val noGate = checkpoints.map { it.copy(preferredBearing = null) }
        var state = EngineState()
        listOf(
            fix(120.9994, 1),
            fix(120.9998, 3),
            fix(121.0, 4),
            fix(121.0002, 5),
            fix(121.0006, 8),
        ).forEach { state = engine.processFix(state, it, noGate).newState }
        assertEquals(1, state.passes.size)
        assertEquals(TriggerMode.CLOSEST_APPROACH, state.passes.single().triggerMode)
    }

    @Test fun invalidConfigurationAndImpossibleJumpAreRejected() {
        val invalid = engine.processFix(EngineState(), fix(120.9995, 1), checkpoints.take(3))
        assertEquals(1, invalid.newState.rejectedFixes)
        var state = engine.processFix(EngineState(), fix(120.9995, 1), checkpoints).newState
        state = engine.processFix(state, fix(121.01, 2), checkpoints).newState
        assertEquals(1, state.rejectedFixes)
    }

    @Test fun restoreAndTerminalStatesAreStable() {
        val complete = EngineState(status = EngineStatus.COMPLETED)
        assertSame(complete, engine.restore(complete))
        assertSame(complete, engine.processFix(complete, fix(121.0, 2), checkpoints).newState)
    }

    @Test fun closestApproachCanCompleteAllFourCheckpoints() {
        val noGate = checkpoints.map { it.copy(preferredBearing = null) }
        var state = EngineState()
        var time = 1L
        noGate.forEach { cp ->
            listOf(cp.longitude - .0006, cp.longitude - .0002, cp.longitude, cp.longitude + .0002, cp.longitude + .0006).forEach { lon ->
                state = engine.processFix(state, fix(lon, time++) , noGate).newState
            }
        }
        assertEquals(EngineStatus.COMPLETED, state.status)
        assertTrue(state.passes.all { it.triggerMode == TriggerMode.CLOSEST_APPROACH })
    }

    private fun run(fixes: List<LocationFix>): EngineState = fixes.fold(EngineState()) { state, fix -> engine.processFix(state, fix, checkpoints).newState }
    private fun track(latOffset: Double = 0.0): List<LocationFix> = buildList {
        var t = 1L
        checkpoints.forEach { cp ->
            add(LocationFix(cp.latitude + latOffset, cp.longitude - .0005, t++ * 1_000_000_000, t * 1_000, 5.0, 15.0, 90.0))
            add(LocationFix(cp.latitude + latOffset, cp.longitude + .0002, t++ * 1_000_000_000, t * 1_000, 5.0, 15.0, 90.0))
            t += 4
        }
    }
    private fun fix(lon: Double, seconds: Long, accuracy: Double = 5.0) = LocationFix(31.0, lon, seconds * 1_000_000_000, seconds * 1_000, accuracy, 15.0, 90.0)
}
