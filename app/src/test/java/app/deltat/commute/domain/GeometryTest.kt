package app.deltat.commute.domain

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun distanceIsSymmetricAndMetric() {
        val a = Geometry.distanceMeters(31.0, 121.0, 31.001, 121.0)
        val b = Geometry.distanceMeters(31.001, 121.0, 31.0, 121.0)
        assertEquals(a, b, 0.0001)
        assertTrue(a in 110.0..112.5)
    }

    @Test fun crossingInterpolatesMonotonicTime() {
        val cp = Checkpoint("A", 0, "A", 31.0, 121.0, preferredBearing = 90.0)
        val prev = fix(31.0, 120.9998, 1)
        val next = fix(31.0, 121.0002, 3)
        val crossing = requireNotNull(Geometry.crossing(prev, next, cp))
        assertEquals(.5, crossing.ratio, .02)
        assertTrue(kotlin.math.abs(2_000_000_000L - Geometry.interpolate(prev, next, crossing.ratio).elapsedRealtimeNanos) < 5_000_000L)
    }

    @Test fun reverseCrossingIsRejected() {
        val cp = Checkpoint("A", 0, "A", 31.0, 121.0, preferredBearing = 90.0)
        assertNull(Geometry.crossing(fix(31.0, 121.0002, 1), fix(31.0, 120.9998, 3), cp))
    }

    @Test fun localProjectionBearingAndAnglesAreStable() {
        val (x, y) = Geometry.toLocalMeters(31.001, 121.001, 31.0, 121.0)
        assertTrue(x > 90 && y > 110)
        assertTrue(Geometry.bearingDegrees(31.0, 121.0, 31.0, 121.001) in 89.0..91.0)
        assertEquals(20.0, Geometry.angularDifference(350.0, 10.0), .001)
    }

    @Test fun crossingGuardBranchesRejectBadGeometryAndDirection() {
        val base = Checkpoint("A", 0, "A", 31.0, 121.0, preferredBearing = 90.0)
        assertNull(Geometry.crossing(fix(31.0, 120.9998, 1), fix(31.0, 120.9997, 2), base))
        assertNull(Geometry.crossing(fix(31.0, 121.0, 1), fix(31.0, 121.0, 2), base))
        assertNull(Geometry.crossing(fix(31.0001, 120.9998, 1), fix(31.0001, 121.0002, 2), base.copy(gateHalfWidthMeters = 2.0)))
        assertNull(Geometry.crossing(fix(31.0, 120.9998, 1), fix(31.0, 121.0002, 2).copy(bearingDegrees = 270.0), base.copy(bearingTolerance = 20.0)))
        assertNotNull(Geometry.crossing(fix(31.0, 120.9998, 1), fix(31.0, 121.0002, 2).copy(bearingDegrees = null), base))
    }

    @Test fun interpolationPropagatesMockFlagFromEitherFix() {
        val normal = fix(31.0, 121.0, 1)
        assertTrue(Geometry.interpolate(normal.copy(isMock = true), normal, .5).isMock)
        assertTrue(Geometry.interpolate(normal, normal.copy(isMock = true), .5).isMock)
    }

    private fun fix(lat: Double, lon: Double, seconds: Long) = LocationFix(lat, lon, seconds * 1_000_000_000, seconds * 1_000, 5.0, 15.0, 90.0)
}
