package app.deltat.commute.domain

import kotlin.math.*

object Geometry {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val h = sin(dLat / 2).pow(2) + cos(p1) * cos(p2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun toLocalMeters(lat: Double, lon: Double, originLat: Double, originLon: Double): Pair<Double, Double> {
        val y = Math.toRadians(lat - originLat) * EARTH_RADIUS_METERS
        val x = Math.toRadians(lon - originLon) * EARTH_RADIUS_METERS * cos(Math.toRadians(originLat))
        return x to y
    }

    fun bearingDegrees(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLon = Math.toRadians(bLon - aLon)
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angularDifference(a: Double, b: Double): Double = abs((a - b + 540.0) % 360.0 - 180.0)

    fun progressLineAxis(aLat: Double, aLon: Double, bLat: Double, bLon: Double): ProgressLineAxis {
        val (eastMeters, northMeters) = toLocalMeters(bLat, bLon, aLat, aLon)
        return if (abs(northMeters) >= abs(eastMeters)) ProgressLineAxis.EAST_WEST else ProgressLineAxis.NORTH_SOUTH
    }

    /**
     * Snaps A→B to its dominant compass axis. The timing plane perpendicular to this
     * bearing is therefore always horizontal or vertical on a north-up map.
     */
    fun axisAlignedRouteBearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val (eastMeters, northMeters) = toLocalMeters(bLat, bLon, aLat, aLon)
        return if (abs(northMeters) >= abs(eastMeters)) {
            if (northMeters >= 0.0) 0.0 else 180.0
        } else {
            if (eastMeters >= 0.0) 90.0 else 270.0
        }
    }

    data class Crossing(val ratio: Double, val crossTrackMeters: Double)

    fun signedProgressMeters(latitude: Double, longitude: Double, checkpoint: Checkpoint, routeBearing: Double): Double {
        val (x, y) = toLocalMeters(latitude, longitude, checkpoint.latitude, checkpoint.longitude)
        val radians = Math.toRadians(routeBearing)
        return x * sin(radians) + y * cos(radians)
    }

    fun progressLineCrossing(
        previous: LocationFix,
        current: LocationFix,
        checkpoint: Checkpoint,
        routeBearing: Double,
    ): Crossing? {
        val previousProgress = signedProgressMeters(previous.latitude, previous.longitude, checkpoint, routeBearing)
        val currentProgress = signedProgressMeters(current.latitude, current.longitude, checkpoint, routeBearing)
        if (previousProgress > 0.0 || currentProgress < 0.0 || currentProgress == previousProgress) return null
        val ratio = (-previousProgress / (currentProgress - previousProgress)).coerceIn(0.0, 1.0)
        val (px, py) = toLocalMeters(previous.latitude, previous.longitude, checkpoint.latitude, checkpoint.longitude)
        val (cx, cy) = toLocalMeters(current.latitude, current.longitude, checkpoint.latitude, checkpoint.longitude)
        val radians = Math.toRadians(routeBearing)
        val dirX = sin(radians)
        val dirY = cos(radians)
        val ix = px + ratio * (cx - px)
        val iy = py + ratio * (cy - py)
        return Crossing(ratio, abs(ix * -dirY + iy * dirX))
    }

    fun crossing(
        previous: LocationFix,
        current: LocationFix,
        checkpoint: Checkpoint,
    ): Crossing? {
        val bearing = checkpoint.preferredBearing ?: return null
        val (px, py) = toLocalMeters(previous.latitude, previous.longitude, checkpoint.latitude, checkpoint.longitude)
        val (cx, cy) = toLocalMeters(current.latitude, current.longitude, checkpoint.latitude, checkpoint.longitude)
        val rad = Math.toRadians(bearing)
        val dirX = sin(rad)
        val dirY = cos(rad)
        val prevAlong = px * dirX + py * dirY
        val currAlong = cx * dirX + cy * dirY
        if (prevAlong > 0.0 || currAlong < 0.0 || currAlong == prevAlong) return null
        val ratio = (-prevAlong / (currAlong - prevAlong)).coerceIn(0.0, 1.0)
        val ix = px + ratio * (cx - px)
        val iy = py + ratio * (cy - py)
        val cross = abs(ix * -dirY + iy * dirX)
        if (cross > checkpoint.gateHalfWidthMeters) return null
        current.bearingDegrees?.let {
            if (angularDifference(it, bearing) > checkpoint.bearingTolerance) return null
        }
        return Crossing(ratio, cross)
    }

    fun interpolate(previous: LocationFix, current: LocationFix, ratio: Double): LocationFix = LocationFix(
        latitude = previous.latitude + (current.latitude - previous.latitude) * ratio,
        longitude = previous.longitude + (current.longitude - previous.longitude) * ratio,
        elapsedRealtimeNanos = previous.elapsedRealtimeNanos + ((current.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) * ratio).toLong(),
        wallTimeMillis = previous.wallTimeMillis + ((current.wallTimeMillis - previous.wallTimeMillis) * ratio).toLong(),
        accuracyMeters = max(previous.accuracyMeters, current.accuracyMeters),
        speedMetersPerSecond = current.speedMetersPerSecond,
        speedAccuracyMetersPerSecond = current.speedAccuracyMetersPerSecond,
        bearingDegrees = current.bearingDegrees,
        bearingAccuracyDegrees = current.bearingAccuracyDegrees,
        provider = current.provider,
        isMock = previous.isMock || current.isMock,
    )
}
