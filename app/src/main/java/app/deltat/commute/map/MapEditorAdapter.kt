package app.deltat.commute.map

import android.content.Context
import android.view.View
import app.deltat.commute.data.TrackPointEntity

data class CheckpointMapPoint(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
)

interface MapEditorAdapter {
    val available: Boolean
    fun createView(context: Context): View?
    fun showRecordedTrack(points: List<TrackPointEntity>)
    fun showRoutes(points: List<Pair<Double, Double>>, referencePoints: List<Pair<Double, Double>> = emptyList())
    fun showCheckpoints(checkpoints: List<CheckpointMapPoint>)
    fun onResume()
    fun onPause()
    fun release()
}
