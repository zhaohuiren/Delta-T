package app.deltat.commute.map

import android.content.Context
import android.view.View
import app.deltat.commute.data.TrackPointEntity

object MapAdapterFactory {
    fun create(): MapEditorAdapter = object : MapEditorAdapter {
        override val available = false
        override fun createView(context: Context): View? = null
        override fun showRecordedTrack(points: List<TrackPointEntity>) = Unit
        override fun showRoutes(points: List<Pair<Double, Double>>, referencePoints: List<Pair<Double, Double>>) = Unit
        override fun showCheckpoints(checkpoints: List<CheckpointMapPoint>) = Unit
        override fun onResume() = Unit
        override fun onPause() = Unit
        override fun release() = Unit
    }
}
