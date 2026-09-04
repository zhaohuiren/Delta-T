package app.deltat.commute.domain

import org.junit.Assert.*
import org.junit.Test

class ModelCoverageTest {
    @Test fun diagnosticModelsRetainAllFields() {
        val health = GnssHealth(10, 8, 12, 9, 5, 3, "GPS 良好")
        assertEquals(8, health.usedGps)
        assertEquals("GPS 良好", health.copy().label)
        assertEquals("GPS 良好", health.displayLabel())
        assertEquals("GPS 搜星中", GnssHealth().displayLabel())
        assertEquals("定位已关闭", GnssHealth(locationEnabled = false).displayLabel())
        assertTrue(health.toString().contains("Beidou", ignoreCase = true))

        val fix = LocationFix(31.0, 121.0, 1, 2, 5.0, 10.0, 90.0, 2.0, "gps", false)
        val candidate = Candidate(fix, 2.0, 3.0)
        assertEquals(2.0, candidate.bestDistanceMeters, 0.0)
        assertEquals(candidate, candidate.copy())

        val checkpoint = Checkpoint("A", 0, "A", 31.0, 121.0)
        val pass = CheckpointPass(checkpoint, 10, 31.0, 121.0, TriggerMode.LINE_CROSSING, 1.0, 200)
        assertEquals(200, pass.copy().uncertaintyMillis)

        val event1: EngineEvent = EngineEvent.PassConfirmed(pass)
        val event2: EngineEvent = EngineEvent.SectorCompleted(0, 10)
        val event3: EngineEvent = EngineEvent.FixRejected("bad")
        assertTrue(event1.toString().isNotBlank() && event2.toString().isNotBlank() && event3.toString().isNotBlank())

        val transition = EngineTransition(EngineState(), listOf(event1, event2, event3), true)
        assertTrue(transition.copy().persistenceUrgent)
    }

    @Test fun gnssDisplayRequiresStableRecoveryAndPromptlyReportsDegradation() {
        val stabilizer = GnssDisplayStabilizer(worsenSamples = 2, recoverSamples = 3)
        fun health(used: Int, visible: Int = 20) = GnssHealth(visibleGps = visible, usedGps = used)

        assertEquals("GPS 良好", stabilizer.update(health(10)))
        assertEquals("GPS 良好", stabilizer.update(health(3)))
        assertEquals("GPS 弱", stabilizer.update(health(3)))
        assertEquals("GPS 弱", stabilizer.update(health(9)))
        assertEquals("GPS 弱", stabilizer.update(health(9)))
        assertEquals("GPS 良好", stabilizer.update(health(9)))
        assertEquals("定位已关闭", stabilizer.update(GnssHealth(locationEnabled = false)))
    }
}
