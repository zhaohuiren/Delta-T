package app.deltat.commute.map

import android.content.Context
import android.view.View
import app.deltat.commute.BuildConfig
import app.deltat.commute.data.TrackPointEntity
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.maps.CoordinateConverter

object MapAdapterFactory {
    fun create(): MapEditorAdapter = AMapEditorAdapter()
}

private class AMapEditorAdapter : MapEditorAdapter {
    private var mapView: MapView? = null
    private var map: AMap? = null
    private var context: Context? = null
    private var lastRouteSignature: Int? = null
    private var lastCheckpointSignature: Int? = null
    private val checkpointCircles = mutableListOf<Circle>()
    private val checkpointMarkers = mutableListOf<Marker>()
    override val available: Boolean get() = BuildConfig.AMAP_API_KEY.isNotBlank()

    override fun createView(context: Context): View? {
        if (!available) return null
        this.context = context
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        return MapView(context).also { view ->
            mapView = view
            view.onCreate(null)
            map = view.map.apply {
                uiSettings.isZoomControlsEnabled = false
                uiSettings.isScaleControlsEnabled = true
                uiSettings.isCompassEnabled = true
            }
        }
    }

    override fun showRecordedTrack(points: List<TrackPointEntity>) {
        showRoutes(points.map { it.latitude to it.longitude })
    }

    override fun showRoutes(points: List<Pair<Double, Double>>, referencePoints: List<Pair<Double, Double>>) {
        val signature = 31 * points.hashCode() + referencePoints.hashCode()
        if (lastRouteSignature == signature) return
        lastRouteSignature = signature
        val actual = points.map { toDisplay(it.first, it.second) }
        val reference = referencePoints.map { toDisplay(it.first, it.second) }
        map?.clear()
        checkpointCircles.clear()
        checkpointMarkers.clear()
        lastCheckpointSignature = null
        if (reference.size >= 2) {
            map?.addPolyline(
                PolylineOptions().addAll(reference).width(8f).color(0xAA59636A.toInt()).zIndex(1f),
            )
        }
        if (actual.size >= 2) {
            map?.addPolyline(
                PolylineOptions().addAll(actual).width(11f).color(0xFFDCE4E8.toInt()).zIndex(2f),
            )
        }
        val boundsPoints = reference + actual
        if (boundsPoints.isNotEmpty()) {
            mapView?.post {
                if (boundsPoints.size == 1) {
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(boundsPoints.first(), 17f))
                } else {
                    val bounds = LatLngBounds.builder().apply { boundsPoints.forEach(::include) }.build()
                    map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 72))
                }
            }
        }
    }

    override fun showCheckpoints(checkpoints: List<CheckpointMapPoint>) {
        val signature = checkpoints.hashCode()
        if (lastCheckpointSignature == signature) return
        lastCheckpointSignature = signature
        checkpointCircles.forEach { it.remove() }
        checkpointMarkers.forEach { it.remove() }
        checkpointCircles.clear()
        checkpointMarkers.clear()
        checkpoints.forEachIndexed { index, checkpoint ->
            val position = toDisplay(checkpoint.latitude, checkpoint.longitude)
            val color = when (index) {
                0 -> 0xFF38E86B.toInt()
                checkpoints.lastIndex -> 0xFFFF1E2D.toInt()
                else -> 0xFFB05CFF.toInt()
            }
            map?.addCircle(
                CircleOptions().center(position).radius(checkpoint.radiusMeters)
                    .strokeWidth(3f).strokeColor(color).fillColor(color and 0x22FFFFFF),
            )?.let(checkpointCircles::add)
            map?.addMarker(MarkerOptions().position(position).title(checkpoint.label).snippet("触发半径 ${checkpoint.radiusMeters.toInt()} m"))
                ?.let(checkpointMarkers::add)
        }
    }

    private fun toDisplay(latitude: Double, longitude: Double): LatLng {
        val source = LatLng(latitude, longitude)
        val ctx = context ?: return source
        return CoordinateConverter(ctx).from(CoordinateConverter.CoordType.GPS).coord(source).convert()
    }

    override fun onResume() {
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
    }

    override fun release() {
        mapView?.onDestroy()
        mapView = null
        map = null
        context = null
        lastRouteSignature = null
        lastCheckpointSignature = null
        checkpointCircles.clear()
        checkpointMarkers.clear()
    }
}
