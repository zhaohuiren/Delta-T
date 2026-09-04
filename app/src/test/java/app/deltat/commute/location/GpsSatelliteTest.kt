package app.deltat.commute.location

import app.deltat.commute.domain.GnssDisplayStabilizer
import app.deltat.commute.domain.GnssHealth
import org.junit.Assert.*
import org.junit.Test

class GpsSatelliteTest {
    @Test fun visibleSummaryKeepsCountsAndDistinguishesUnknownZeroAndDisabled() {
        assertEquals("GPS 搜星中 · --/--", GnssHealth().displaySummary())
        assertEquals("GPS 搜星中 · 0/0", satelliteHealth(emptyList()).displaySummary())
        assertEquals("GPS 搜星中 · 0/1", satelliteHealth(listOf(SatelliteSignal(1, 3, false))).displaySummary())
        val mixed = GnssHealth(12, 6, 8, 5, 3, 2)
        assertEquals("GPS 良好 · 13/23", mixed.displaySummary())
        assertEquals("定位已关闭", mixed.copy(locationEnabled = false).displaySummary())
        assertEquals("GPS 良好 · 99/128", GnssHealth(128, 99).displaySummary())
        assertEquals("GPS 良好 · --/--", GnssHealth(4, 9).displaySummary())
    }

    @Test fun qualityHysteresisDoesNotFreezeOrHideLatestSatelliteCounts() {
        val stabilizer = GnssDisplayStabilizer()
        val initial = GnssHealth(12, 9)
        assertEquals("GPS 良好 · 9/12", initial.copy(label = stabilizer.update(initial)).displaySummary())
        for (used in listOf(7, 9, 7, 9, 0)) {
            val health = GnssHealth(12, used, satelliteStatusReceived = true)
            assertEquals("GPS 良好 · $used/12", health.copy(label = stabilizer.update(health)).displaySummary())
        }
        val zero = satelliteHealth(emptyList())
        assertEquals("GPS 搜星中 · 0/0", zero.copy(label = stabilizer.update(zero)).displaySummary())
    }

    @Test fun duplicateFrequenciesCountOneSatelliteAndUsedIsUnion() {
        val health = satelliteHealth(listOf(
            SatelliteSignal(1, 3, false), SatelliteSignal(1, 3, true),
            SatelliteSignal(1, 4, false), SatelliteSignal(5, 3, true),
            SatelliteSignal(6, 3, true), SatelliteSignal(6, 3, true),
        ))
        assertEquals(2, health.visibleGps)
        assertEquals(1, health.usedGps)
        assertEquals(1, health.visibleBeidou)
        assertEquals(1, health.usedBeidou)
        assertEquals(1, health.visibleOther)
        assertEquals(1, health.usedOther)
        assertEquals(4, health.visibleTotal)
        assertEquals(3, health.usedTotal)
        assertTrue(health.usedTotal <= health.visibleTotal)
    }

    @Test fun emptyAndUnusedSignalsSearchWithoutClaimingAFix() {
        assertEquals("GPS 搜星中", satelliteHealth(emptyList()).displayLabel())
        assertEquals("GPS 搜星中", satelliteHealth(listOf(SatelliteSignal(1, 3, false))).displayLabel())
    }

    @Test fun thresholdNoiseDoesNotFlickerButSustainedLossAndRecoveryDoChange() {
        val display = GnssDisplayStabilizer()
        fun health(used: Int) = GnssHealth(visibleGps = 12, usedGps = used)
        assertEquals("GPS 良好", display.update(health(8)))
        repeat(120) {
            assertEquals("GPS 良好", display.update(health(if (it % 2 == 0) 7 else 9)))
        }
        assertEquals("GPS 良好", display.update(health(0)))
        assertEquals("GPS 搜星中", display.update(health(0)))
        repeat(2) { assertEquals("GPS 搜星中", display.update(health(8))) }
        assertEquals("GPS 良好", display.update(health(8)))
        assertEquals("定位已关闭", display.update(GnssHealth(locationEnabled = false)))
        assertEquals("GPS 搜星中", display.update(GnssHealth()))
        display.reset()
        assertEquals("GPS 弱", display.update(health(2)))
    }
}
