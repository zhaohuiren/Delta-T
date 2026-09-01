package app.deltat.commute.map

import app.deltat.commute.domain.Checkpoint
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.ResultColor

enum class MapCameraPolicy { FULL_ROUTE_LOCKED, INTERACTIVE_DETAIL, STAGED_RECORDING }

enum class MapRenderState { LOADING, AVAILABLE, PRIVACY_REQUIRED, KEY_MISSING, OFFLINE, FAILED }

data class MapCheckpoint(
    val label: String,
    val orderIndex: Int,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val showProgressLine: Boolean,
) {
    companion object {
        fun from(checkpoint: Checkpoint) = MapCheckpoint(
            label = checkpoint.label,
            orderIndex = checkpoint.orderIndex,
            latitude = checkpoint.latitude,
            longitude = checkpoint.longitude,
            radiusMeters = checkpoint.radiusMeters,
            showProgressLine = checkpoint.orderIndex in 1..2,
        )
    }
}

data class MapScene(
    val routeSourceTrack: List<LocationFix> = emptyList(),
    val actualTrack: List<LocationFix> = emptyList(),
    val checkpoints: List<MapCheckpoint> = emptyList(),
    val currentFix: LocationFix? = actualTrack.lastOrNull(),
    val actualPassElapsedNanos: List<Long> = emptyList(),
    val sectorColors: List<ResultColor> = emptyList(),
    val cameraPolicy: MapCameraPolicy = MapCameraPolicy.FULL_ROUTE_LOCKED,
)
