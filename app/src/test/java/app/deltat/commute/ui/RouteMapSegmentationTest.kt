package app.deltat.commute.ui

import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.ResultColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMapSegmentationTest {
    @Test fun fourThousandPointsProduceOnlyContiguousColorSegments() {
        val track = (0 until 4_000).map { index ->
            LocationFix(31.0, 121.0 + index * .00001, index.toLong(), index.toLong(), 5.0)
        }
        val segments = coloredTrackSegments(
            track,
            passes = listOf(0L, 1_000L, 2_000L, 3_000L),
            colors = listOf(ResultColor.PURPLE, ResultColor.GREEN, ResultColor.YELLOW),
        )

        assertTrue("overlay 数量应与颜色段有关而不是与点数有关", segments.size <= 4)
        assertEquals(track.first(), segments.first().points.first())
        assertEquals(track.last(), segments.last().points.last())
        segments.zipWithNext().forEach { (left, right) -> assertEquals(left.points.last(), right.points.first()) }
    }

    @Test fun emptyAndSinglePointTracksNeedNoPolyline() {
        assertTrue(coloredTrackSegments(emptyList(), emptyList(), emptyList()).isEmpty())
        val point = LocationFix(31.0, 121.0, 0, 0, 5.0)
        assertTrue(coloredTrackSegments(listOf(point), emptyList(), emptyList()).isEmpty())
    }
}
