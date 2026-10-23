package app.deltat.commute.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.deltat.commute.domain.Geometry
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.ProgressLineAxis
import app.deltat.commute.domain.ResultColor
import app.deltat.commute.map.MapCameraPolicy
import app.deltat.commute.map.MapCheckpoint
import app.deltat.commute.map.MapScene
import app.deltat.commute.map.MapPhase
import app.deltat.commute.map.displayCheckpoints
import app.deltat.commute.map.MapPixel
import app.deltat.commute.map.layoutMapLabels
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.maps.model.Polygon
import com.amap.api.maps.model.PolygonOptions
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

// Google Material Icons "Navigation", Apache License 2.0, normalized from the 24dp source.
private val MATERIAL_NAVIGATION_PATH = listOf(
    Offset(.50f, .08f),
    Offset(.19f, .84f),
    Offset(.22f, .87f),
    Offset(.50f, .75f),
    Offset(.78f, .87f),
    Offset(.81f, .84f),
)

internal const val CHECKPOINT_ICON_DP = 36f
internal const val CHECKPOINT_ICON_HEIGHT_DP = 28f

data class MapFeatureConfig(
    val sdkAvailable: Boolean = false,
    val privacyAgreed: Boolean = false,
    val networkAvailableOverride: Boolean? = null,
)

val LocalMapFeatureConfig = compositionLocalOf { MapFeatureConfig() }

@Composable
fun RouteMap(scene: MapScene, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val config = LocalMapFeatureConfig.current
    val online = config.networkAvailableOverride ?: rememberValidatedNetwork(context)
    var failed by remember(scene.cameraPolicy) { mutableStateOf(false) }
    LaunchedEffect(online) { if (online) failed = false }
    if (config.sdkAvailable && config.privacyAgreed && online && !failed) {
        Box(modifier) {
            AmapScene(scene, Modifier.fillMaxSize()) { failed = true }
            if (scene.currentFixStale && scene.currentFix != null) Text("上次定位",
                Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0xE6111419), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 4.dp),
                color = Color(0xFFB8C1CA), fontSize = 11.sp)
        }
    } else {
        Box(modifier) {
            AbstractMapScene(scene, Modifier.fillMaxSize())
            Text(if (scene.currentFixStale && scene.currentFix != null) "简洁地图 · 上次定位" else "简洁地图",
                Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0xE6111419), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 4.dp),
                color = Color(0xFFB8C1CA), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AmapScene(scene: MapScene, modifier: Modifier, onFailure: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { AmapSceneController(context.applicationContext, onFailure) }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                Lifecycle.Event.ON_DESTROY -> controller.release()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
        }
    }
    AndroidView(
        factory = { viewContext ->
            try {
                controller.createView(viewContext)
            } catch (error: Exception) {
                Log.e("RouteMap", "AMap MapView initialization failed", error)
                onFailure()
                View(viewContext)
            }
        },
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF303C47)).testTag("route-map"),
        update = {
            try {
                controller.update(scene)
            } catch (error: Exception) {
                Log.e("RouteMap", "AMap scene rendering failed", error)
                onFailure()
            }
        },
    )
}

private class AmapSceneController(
    private val appContext: Context,
    private val onFailure: () -> Unit,
) {
    private var mapView: TextureMapView? = null
    private var map: AMap? = null
    private var backgroundShade: Polygon? = null
    private var latestScene: MapScene? = null
    private var cameraSettled = false
    private val markerLayout = Runnable { if (cameraSettled) resolveMarkerCollisions() }
    private var lastCameraSignature: Int? = null
    private var recordingBounds: LatLngBounds? = null
    private var lastReliableBearing = 0.0
    private val coordinateCache = LinkedHashMap<String, LatLng>()
    private var routeCasing: Polyline? = null
    private var routeLine: Polyline? = null
    private val actualLines = mutableListOf<Polyline>()
    private val progressLines = mutableMapOf<Int, Polyline>()
    private val checkpointMarkers = mutableMapOf<Int, Marker>()
    private val checkpointCircles = mutableMapOf<Int, Circle>()
    private var positionMarker: Marker? = null
    private val checkpointIcons = mutableMapOf<String, BitmapDescriptor>()
    private var positionIcon: BitmapDescriptor? = null
    private var anchorIcon: BitmapDescriptor? = null
    private val anchorMarkers = mutableMapOf<Int, Marker>()
    private val labelLeaders = mutableMapOf<Int, Polyline>()
    private var labelSlots: Map<Int, Int> = emptyMap()
    private var lastRouteInput: List<LocationFix>? = null
    private var displayedRoute: List<LatLng> = emptyList()

    fun createView(context: Context): View {
        MapsInitializer.updatePrivacyShow(appContext, true, true)
        MapsInitializer.updatePrivacyAgree(appContext, true)
        return TextureMapView(context).also { view ->
            mapView = view
            view.onCreate(Bundle())
            map = view.map.apply {
                mapType = AMap.MAP_TYPE_NIGHT
                isTrafficEnabled = false
                showMapText(false)
                showBuildings(false)
                backgroundShade = addPolygon(
                    PolygonOptions().add(LatLng(5.0, 60.0), LatLng(60.0, 60.0), LatLng(60.0, 150.0), LatLng(5.0, 150.0))
                        .fillColor(AndroidColor.argb(64, 0, 0, 0)).strokeWidth(0f).zIndex(0f),
                )
                uiSettings.isZoomControlsEnabled = false
                uiSettings.isScaleControlsEnabled = true
                uiSettings.isCompassEnabled = false
                uiSettings.isMyLocationButtonEnabled = false
                setOnMapLoadedListener { mapView?.setBackgroundColor(AndroidColor.rgb(9, 12, 15)) }
                setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                    override fun onCameraChange(position: CameraPosition?) { cameraSettled = false }
                    override fun onCameraChangeFinish(position: CameraPosition?) {
                        cameraSettled = true
                        scheduleMarkerLayout()
                    }
                })
            }
            view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    lastCameraSignature = null
                    latestScene?.let { scene ->
                        updateCamera(scene, displayedRoute, scene.displayCheckpoints().map { display(it.latitude, it.longitude) })
                        scheduleMarkerLayout()
                    }
                }
            }
        }
    }

    fun update(scene: MapScene) {
        val amap = map ?: return
        val previous = latestScene
        if (previous == scene) return
        latestScene = scene
        configureGestures(amap, scene.cameraPolicy == MapCameraPolicy.INTERACTIVE_DETAIL)
        if (lastRouteInput != scene.routeSourceTrack) {
            lastRouteInput = scene.routeSourceTrack
            displayedRoute = scene.routeSourceTrack.map(::display)
            updateRoute(amap, displayedRoute)
        }
        val route = displayedRoute
        if (previous?.actualTrack != scene.actualTrack || previous.actualPassElapsedNanos != scene.actualPassElapsedNanos || previous.sectorColors != scene.sectorColors) updateActualTrack(amap, scene)
        val checkpoints = scene.displayCheckpoints().sortedBy { it.orderIndex }
        if (previous?.checkpoints != scene.checkpoints || previous.phase != scene.phase || previous.nextCheckpointIndex != scene.nextCheckpointIndex) {
            updateProgressLines(amap, checkpoints)
            updateCheckpoints(amap, checkpoints)
        }
        scene.currentFix?.let { fix ->
            val reliable = fix.bearingDegrees?.takeIf { (fix.bearingAccuracyDegrees ?: 0.0) <= 45.0 && (fix.speedMetersPerSecond ?: 0.0) >= 2.0 }
            if (reliable != null) lastReliableBearing = reliable
            val marker = positionMarker ?: amap.addMarker(
                MarkerOptions().position(display(fix)).icon(gpsArrowIcon()).anchor(.5f, .5f)
                    .rotateAngle((-lastReliableBearing).toFloat()).setFlat(true).zIndex(10f),
            ).also { positionMarker = it }
            marker.position = display(fix)
            marker.rotateAngle = (-lastReliableBearing).toFloat()
            marker.isVisible = true
            marker.alpha = if (scene.currentFixStale) .45f else 1f
        } ?: run {
            positionMarker?.isVisible = false
        }
        updateCamera(scene, route, checkpoints.map { display(it.latitude, it.longitude) })
        scheduleMarkerLayout()
    }

    private fun scheduleMarkerLayout() {
        mapView?.let { view -> view.removeCallbacks(markerLayout); view.post(markerLayout) }
    }

    private fun updateRoute(amap: AMap, points: List<LatLng>) {
        val density = appContext.resources.displayMetrics.density
        if (points.size < 2) {
            routeCasing?.isVisible = false
            routeLine?.isVisible = false
            return
        }
        routeCasing = (routeCasing ?: amap.addPolyline(
            PolylineOptions().addAll(points).width(7f * density).color(0xE6080A0D.toInt()).zIndex(1f),
        )).also { it.points = points; it.isVisible = true }
        routeLine = (routeLine ?: amap.addPolyline(
            PolylineOptions().addAll(points).width(2.5f * density).color(0xFF78848E.toInt()).zIndex(2f),
        )).also { it.points = points; it.isVisible = true }
    }

    private fun updateActualTrack(amap: AMap, scene: MapScene) {
        val segments = coloredTrackSegments(scene.actualTrack, scene.actualPassElapsedNanos, scene.sectorColors)
        segments.forEachIndexed { index, segment ->
            val points = segment.points.map(::display)
            val line = actualLines.getOrNull(index) ?: amap.addPolyline(
                PolylineOptions().addAll(points).width(4f * appContext.resources.displayMetrics.density).color(segment.color).zIndex(4f),
            ).also(actualLines::add)
            line.points = points
            line.color = segment.color
            line.isVisible = true
        }
        actualLines.drop(segments.size).forEach { it.isVisible = false }
    }

    private fun updateProgressLines(amap: AMap, checkpoints: List<MapCheckpoint>) {
        if (checkpoints.size != 4) {
            progressLines.values.forEach { it.isVisible = false }
            return
        }
        val start = checkpoints.first()
        val finish = checkpoints.last()
        val axis = Geometry.progressLineAxis(start.latitude, start.longitude, finish.latitude, finish.longitude)
        val converted = checkpoints.map { display(it.latitude, it.longitude) }
        val latSpan = max(converted.maxOf { it.latitude } - converted.minOf { it.latitude }, .01)
        val lonSpan = max(converted.maxOf { it.longitude } - converted.minOf { it.longitude }, .01)
        val visibleIndices = checkpoints.filter { it.showProgressLine }.mapTo(mutableSetOf()) { it.orderIndex }
        progressLines.filterKeys { it !in visibleIndices }.values.forEach { it.isVisible = false }
        checkpoints.filter { it.showProgressLine }.forEach { checkpoint ->
            val point = display(checkpoint.latitude, checkpoint.longitude)
            val ends = when (axis) {
                ProgressLineAxis.EAST_WEST -> listOf(LatLng(point.latitude, point.longitude - lonSpan * 1.5), LatLng(point.latitude, point.longitude + lonSpan * 1.5))
                ProgressLineAxis.NORTH_SOUTH -> listOf(LatLng(point.latitude - latSpan * 1.5, point.longitude), LatLng(point.latitude + latSpan * 1.5, point.longitude))
            }
            val line = progressLines[checkpoint.orderIndex] ?: amap.addPolyline(
                PolylineOptions().addAll(ends).width(1.25f * appContext.resources.displayMetrics.density).color(0x706E757C).setDottedLine(true).zIndex(3f),
            ).also { progressLines[checkpoint.orderIndex] = it }
            line.points = ends
            line.isVisible = true
        }
    }

    private fun updateCheckpoints(amap: AMap, checkpoints: List<MapCheckpoint>) {
        val present = checkpoints.mapTo(mutableSetOf()) { it.orderIndex }
        checkpointMarkers.filterKeys { it !in present }.values.forEach { it.isVisible = false }
        checkpointCircles.filterKeys { it !in present }.values.forEach { it.isVisible = false }
        anchorMarkers.filterKeys { it !in present }.values.forEach { it.isVisible = false }
        labelLeaders.filterKeys { it !in present }.values.forEach { it.isVisible = false }
        checkpoints.forEach { checkpoint ->
            val position = display(checkpoint.latitude, checkpoint.longitude)
            val marker = checkpointMarkers[checkpoint.orderIndex] ?: amap.addMarker(
                MarkerOptions().position(position).icon(checkpointIcon(checkpoint.label)).anchor(.5f, .5f).zIndex(8f),
            ).also { checkpointMarkers[checkpoint.orderIndex] = it }
            marker.position = position
            marker.isVisible = true
            marker.alpha = if (latestScene?.phase == MapPhase.PREPARING && checkpoint.orderIndex != 0) .5f else 1f
            val anchor = anchorMarkers[checkpoint.orderIndex] ?: amap.addMarker(
                MarkerOptions().position(position).icon(anchorDotIcon()).anchor(.5f, .5f).zIndex(7.5f),
            ).also { anchorMarkers[checkpoint.orderIndex] = it }
            anchor.position = position
            anchor.isVisible = true
            if (checkpoint.orderIndex == 0 || checkpoint.orderIndex == 3) {
                val circle = checkpointCircles[checkpoint.orderIndex] ?: amap.addCircle(
                    CircleOptions().center(position).radius(checkpoint.radiusMeters).strokeWidth(2f)
                        .strokeColor(0x99DDE3E8.toInt()).fillColor(0x16000000),
                ).also { checkpointCircles[checkpoint.orderIndex] = it }
                circle.center = position
                circle.radius = checkpoint.radiusMeters
                circle.isVisible = latestScene?.phase != MapPhase.PREPARING || checkpoint.orderIndex == 0
            }
        }
    }

    private fun resolveMarkerCollisions() {
        val projection = map?.projection ?: return
        val view = mapView ?: return
        if (view.width <= 0 || view.height <= 0) return
        val density = appContext.resources.displayMetrics.density
        val presentOrders = latestScene?.displayCheckpoints()?.map { it.orderIndex }.orEmpty()
        val anchors = anchorMarkers.filterKeys { it in presentOrders }.mapValues { (_, marker) ->
            projection.toScreenLocation(marker.position).let { MapPixel(it.x.toFloat(), it.y.toFloat()) }
        }
        val position = positionMarker?.takeIf { it.isVisible }?.let { projection.toScreenLocation(it.position) }?.let { MapPixel(it.x.toFloat(), it.y.toFloat()) }
        val placements = layoutMapLabels(anchors, position, view.width.toFloat(), view.height.toFloat(), density, labelSlots,
            if (latestScene?.phase == MapPhase.TIMING) latestScene?.nextCheckpointIndex ?: 0 else 0)
        labelSlots = placements.associate { it.order to it.slot }
        placements.forEach { placement ->
            val marker = checkpointMarkers.getValue(placement.order)
            marker.isVisible = placement.visible
            anchorMarkers[placement.order]?.isVisible = position == null || kotlin.math.hypot(placement.anchor.x-position.x, placement.anchor.y-position.y) > 22f*density
            marker.setAnchor(.5f - (placement.label.x - placement.anchor.x) / (CHECKPOINT_ICON_DP * density),
                .5f - (placement.label.y - placement.anchor.y) / (CHECKPOINT_ICON_HEIGHT_DP * density))
            val labelPosition = projection.fromScreenLocation(android.graphics.Point(placement.label.x.toInt(), placement.label.y.toInt()))
            val leader = labelLeaders[placement.order] ?: map!!.addPolyline(
                PolylineOptions().add(marker.position, labelPosition).width(density).color(0xFFAEB8C0.toInt()).zIndex(7f)
            ).also { labelLeaders[placement.order] = it }
            leader.points = listOf(marker.position, labelPosition)
            leader.isVisible = placement.visible && placement.displaced
        }
    }

    private fun updateCamera(scene: MapScene, route: List<LatLng>, checkpoints: List<LatLng>) {
        val view = mapView ?: return
        val amap = map ?: return
        if (view.width <= 0 || view.height <= 0) return
        when (scene.cameraPolicy) {
            MapCameraPolicy.FULL_ROUTE_LOCKED, MapCameraPolicy.INTERACTIVE_DETAIL -> {
                val signature = listOf(route.hashCode(), checkpoints.hashCode(), view.width, view.height, scene.phase, scene.cameraPolicy).hashCode()
                val position = scene.currentFix?.let(::display)?.let(amap.projection::toScreenLocation)
                val margin = 30 * appContext.resources.displayMetrics.density
                val outside = position != null && (position.x < margin || position.y < margin || position.x > view.width - margin || position.y > view.height - margin)
                if (signature == lastCameraSignature && (scene.cameraPolicy == MapCameraPolicy.INTERACTIVE_DETAIL || !outside)) return
                lastCameraSignature = signature
                val all = route + checkpoints + scene.actualTrack.map(::display) + listOfNotNull(scene.currentFix?.let(::display))
                if (all.isNotEmpty()) view.post { fit(all) }
            }
            MapCameraPolicy.STAGED_RECORDING -> {
                val newest = scene.actualTrack.lastOrNull()?.let(::display) ?: return
                val currentBounds = recordingBounds
                if (currentBounds == null) {
                    view.post { amap.moveCamera(CameraUpdateFactory.newLatLngZoom(newest, 17f)) }
                    recordingBounds = expandedBounds(listOf(newest), 250.0)
                } else if (!inSafeInterior(newest, currentBounds)) {
                    val expanded = expandedBounds(scene.actualTrack.map(::display), 250.0)
                    recordingBounds = expanded
                    view.post { fitBounds(expanded) }
                }
            }
        }
    }

    private fun fit(points: List<LatLng>) {
        val bounds = expandedBounds(points, 80.0)
        recordingBounds = bounds
        fitBounds(bounds)
    }

    private fun fitBounds(bounds: LatLngBounds) {
        val view = mapView ?: return
        val amap = map ?: return
        val density = view.resources.displayMetrics.density
        val padding = (42 * density).toInt()
        cameraSettled = false
        amap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
    }

    private fun expandedBounds(points: List<LatLng>, minimumMeters: Double): LatLngBounds {
        val centerLat = points.map { it.latitude }.average()
        val centerLon = points.map { it.longitude }.average()
        val minimumLat = minimumMeters / 111_320.0
        val minimumLon = minimumMeters / (111_320.0 * cos(Math.toRadians(centerLat))).coerceAtLeast(1.0)
        var south = points.minOf { it.latitude }
        var north = points.maxOf { it.latitude }
        var west = points.minOf { it.longitude }
        var east = points.maxOf { it.longitude }
        if (north - south < minimumLat) { south = centerLat - minimumLat / 2; north = centerLat + minimumLat / 2 }
        if (east - west < minimumLon) { west = centerLon - minimumLon / 2; east = centerLon + minimumLon / 2 }
        return LatLngBounds(LatLng(south, west), LatLng(north, east))
    }

    private fun inSafeInterior(point: LatLng, bounds: LatLngBounds): Boolean {
        val latInset = (bounds.northeast.latitude - bounds.southwest.latitude) * .12
        val lonInset = (bounds.northeast.longitude - bounds.southwest.longitude) * .12
        return point.latitude in (bounds.southwest.latitude + latInset)..(bounds.northeast.latitude - latInset) &&
            point.longitude in (bounds.southwest.longitude + lonInset)..(bounds.northeast.longitude - lonInset)
    }

    private fun configureGestures(amap: AMap, enabled: Boolean) {
        amap.uiSettings.isScrollGesturesEnabled = enabled
        amap.uiSettings.isZoomGesturesEnabled = enabled
        amap.uiSettings.isRotateGesturesEnabled = false
        amap.uiSettings.isTiltGesturesEnabled = false
    }

    private fun display(fix: LocationFix) = display(fix.latitude, fix.longitude)

    private fun display(latitude: Double, longitude: Double): LatLng {
        val key = "%.7f,%.7f".format(java.util.Locale.US, latitude, longitude)
        coordinateCache[key]?.let { return it }
        val source = LatLng(latitude, longitude)
        val converted = CoordinateConverter(appContext).from(CoordinateConverter.CoordType.GPS).coord(source).convert()
        coordinateCache[key] = converted
        if (coordinateCache.size > 8_000) coordinateCache.remove(coordinateCache.keys.first())
        return converted
    }

    fun onResume() = mapView?.onResume() ?: Unit
    fun onPause() = mapView?.onPause() ?: Unit
    fun release() {
        mapView?.removeCallbacks(markerLayout)
        try { mapView?.onPause() } catch (_: Exception) { }
        try {
            backgroundShade?.remove()
            routeCasing?.remove()
            routeLine?.remove()
            actualLines.forEach(Polyline::remove)
            progressLines.values.forEach(Polyline::remove)
            checkpointMarkers.values.forEach(Marker::destroy)
            checkpointCircles.values.forEach(Circle::remove)
            positionMarker?.destroy()
            anchorMarkers.values.forEach(Marker::destroy)
            labelLeaders.values.forEach(Polyline::remove)
            anchorIcon?.recycle()
            checkpointIcons.values.forEach(BitmapDescriptor::recycle)
            positionIcon?.recycle()
            mapView?.onDestroy()
        } catch (error: Exception) {
            onFailure()
        }
        mapView = null
        map = null
        backgroundShade = null
        latestScene = null
        cameraSettled = false
        routeCasing = null
        routeLine = null
        actualLines.clear()
        progressLines.clear()
        checkpointMarkers.clear()
        checkpointCircles.clear()
        positionMarker = null
        checkpointIcons.clear()
        positionIcon = null
        coordinateCache.clear()
        anchorMarkers.clear()
        labelLeaders.clear()
        labelSlots = emptyMap()
        anchorIcon = null
        lastRouteInput = null
        displayedRoute = emptyList()
        lastCameraSignature = null
        recordingBounds = null
    }

    private fun anchorDotIcon(): BitmapDescriptor {
        anchorIcon?.let { return it }
        val size = (4f * appContext.resources.displayMetrics.density).toInt().coerceAtLeast(4)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        AndroidCanvas(bitmap).drawCircle(size / 2f, size / 2f, size / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(222, 229, 235) })
        return BitmapDescriptorFactory.fromBitmap(bitmap).also { anchorIcon = it }
    }

    private fun checkpointIcon(label: String): BitmapDescriptor {
        checkpointIcons[label]?.let { return it }
        val density = appContext.resources.displayMetrics.density
        val width = (CHECKPOINT_ICON_DP * density).toInt().coerceAtLeast(36)
        val height = (CHECKPOINT_ICON_HEIGHT_DP * density).toInt().coerceAtLeast(28)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(32, 43, 53) }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 6*density, 6*density, paint)
        paint.color = AndroidColor.rgb(241,244,247)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 12*density
        paint.isFakeBoldText = true
        canvas.drawText(label, width/2f, height/2f-(paint.ascent()+paint.descent())/2, paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap).also { checkpointIcons[label] = it }
    }

    private fun gpsArrowIcon(): BitmapDescriptor {
        positionIcon?.let { return it }
        val density = appContext.resources.displayMetrics.density
        val size = (32 * density).toInt().coerceAtLeast(32)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val path = AndroidPath().apply {
            moveTo(size * MATERIAL_NAVIGATION_PATH.first().x, size * MATERIAL_NAVIGATION_PATH.first().y)
            MATERIAL_NAVIGATION_PATH.drop(1).forEach { point -> lineTo(size * point.x, size * point.y) }
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.25f * density
            color = AndroidColor.WHITE
        }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.color = AndroidColor.rgb(38, 132, 255)
        canvas.drawPath(path, paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap).also { positionIcon = it }
    }
}

@Composable
private fun AbstractMapScene(scene: MapScene, modifier: Modifier) {
    var lastReliableBearing by remember { mutableStateOf(0.0) }
    val currentFix = scene.currentFix
    val reliableBearing = currentFix?.bearingDegrees?.takeIf {
        (currentFix.bearingAccuracyDegrees ?: 0.0) <= 45.0 && (currentFix.speedMetersPerSecond ?: 0.0) >= 2.0
    }
    LaunchedEffect(reliableBearing) { if (reliableBearing != null) lastReliableBearing = reliableBearing }
    val viewport = remember(scene.routeSourceTrack, scene.checkpoints, scene.phase, scene.cameraPolicy) { CanvasViewport() }
    val slots = remember(scene.checkpoints) { mutableMapOf<Int, Int>() }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF303C47)).graphicsLayer().testTag("abstract-route-map")) {
        Spacer(Modifier.fillMaxSize().drawWithCache {
            val checkpoints = scene.displayCheckpoints().sortedBy { it.orderIndex }
            val checkpointFixes = checkpoints.map { LocationFix(it.latitude, it.longitude, 0, 0, 0.0) }
            val allPoints = scene.routeSourceTrack + scene.actualTrack + scene.currentFix.orEmpty() + checkpointFixes
            val projection = viewport.projection(allPoints, scene.currentFix, size.width, size.height, density)
            fun path(points: List<LocationFix>): Path = Path().apply {
                points.forEachIndexed { index, fix ->
                    val point = projection.project(fix)
                    if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }
            val sourcePath = path(scene.routeSourceTrack)
            val actualPaths = coloredTrackSegments(scene.actualTrack, scene.actualPassElapsedNanos, scene.sectorColors).map { path(it.points) to Color(it.color) }
            val position = scene.currentFix?.let(projection::project)
            val anchors = checkpoints.associate { cp -> cp.orderIndex to projection.project(LocationFix(cp.latitude, cp.longitude, 0, 0, 0.0)).let { MapPixel(it.x, it.y) } }
            val placements = layoutMapLabels(anchors, position?.let { MapPixel(it.x, it.y) }, size.width, size.height, density, slots,
                if (scene.phase == MapPhase.TIMING) scene.nextCheckpointIndex else 0)
            slots.clear(); slots.putAll(placements.associate { it.order to it.slot })
            val paints = checkpoints.associate { cp -> cp.orderIndex to android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.WHITE; textAlign = Paint.Align.CENTER
                textSize = (if (cp.label.length > 1) 11 else 14) * density; isFakeBoldText = true
            } }
            val radii = checkpoints.filter { (it.orderIndex == 0 || it.orderIndex == 3) && (scene.phase != MapPhase.PREPARING || it.orderIndex == 0) }
                .map { cp -> anchors.getValue(cp.orderIndex) to projection.radiusPixels(cp.radiusMeters) }
            val progress = if (checkpoints.size == 4) {
                val axis = Geometry.progressLineAxis(checkpoints.first().latitude, checkpoints.first().longitude, checkpoints.last().latitude, checkpoints.last().longitude)
                checkpoints.filter { it.showProgressLine }.map { cp ->
                    val point = anchors.getValue(cp.orderIndex)
                    if (axis == ProgressLineAxis.EAST_WEST) Offset(0f, point.y) to Offset(size.width, point.y)
                    else Offset(point.x, 0f) to Offset(point.x, size.height)
                }
            } else emptyList()
            val arrow = Path().apply {
                if (position != null) {
                    MATERIAL_NAVIGATION_PATH.forEachIndexed { index, point ->
                        val x = position.x + (point.x - .5f) * 32.dp.toPx()
                        val y = position.y + (point.y - .5f) * 32.dp.toPx()
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }; close()
                }
            }
            val bearing = lastReliableBearing.toFloat()
            onDrawBehind {
                if (scene.routeSourceTrack.size > 1) {
                    drawPath(sourcePath, Color(0xFF05070A), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(sourcePath, Color(0xFF78848E), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                radii.forEach { (point, radius) -> drawCircle(Color(0x99DDE3E8), radius, Offset(point.x, point.y), style = Stroke(2f)) }
                actualPaths.forEach { (path, color) -> drawPath(path, color, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                progress.forEach { (start, end) -> drawLine(Color(0x706E757C), start, end, 1.25.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))) }
                placements.forEach { placement ->
                    val cp = checkpoints.first { it.orderIndex == placement.order }
                    val anchor = Offset(placement.anchor.x, placement.anchor.y)
                    val point = Offset(placement.label.x, placement.label.y)
                    val alpha = if (scene.phase == MapPhase.PREPARING && placement.order != 0) .5f else 1f
                    if (position == null || (anchor-position).getDistance() > 22.dp.toPx()) drawCircle(Color(0xFFDEE5EB), 2.dp.toPx(), anchor)
                    if (!placement.visible) return@forEach
                    if (placement.displaced) drawLine(Color(0xFFAEB8C0).copy(alpha = alpha), anchor, point, 1.dp.toPx())
                    drawRoundRect(Color(0xFF202B35).copy(alpha = alpha), point - Offset(18.dp.toPx(), 14.dp.toPx()), androidx.compose.ui.geometry.Size(36.dp.toPx(), 28.dp.toPx()), CornerRadius(6.dp.toPx()))
                    drawIntoCanvas { canvas ->
                        val paint = paints.getValue(cp.orderIndex)
                        paint.alpha = (255 * alpha).toInt()
                        canvas.nativeCanvas.drawText(cp.label, point.x, point.y - (paint.ascent() + paint.descent()) / 2, paint)
                    }
                }
                if (position != null) rotate(bearing, position) {
                    val alpha = if (scene.currentFixStale) .45f else 1f
                    drawPath(arrow, Color.White.copy(alpha = alpha), style = Stroke(2.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(arrow, Color(0xFF2684FF).copy(alpha = alpha))
                }
            }
        })
    }
}

private class CanvasViewport {
    private var cached: MeterProjection? = null
    private var width = 0f
    private var height = 0f
    fun projection(points: List<LocationFix>, fix: LocationFix?, w: Float, h: Float, density: Float): MeterProjection {
        val current = cached
        val point = fix?.let { current?.project(it) }
        val margin = 30f * density
        val outside = point != null && (point.x < margin || point.y < margin || point.x > w - margin || point.y > h - margin)
        if (current == null || w != width || h != height || outside) {
            cached = MeterProjection(points, w, h)
            width = w; height = h
        }
        return requireNotNull(cached)
    }
}

private class MeterProjection(points: List<LocationFix>, private val width: Float, private val height: Float) {
    private val originLat = points.firstOrNull()?.latitude ?: 0.0
    private val originLon = points.firstOrNull()?.longitude ?: 0.0
    private val local = points.map { Geometry.toLocalMeters(it.latitude, it.longitude, originLat, originLon) }
    private val minX = local.minOfOrNull { it.first } ?: -125.0
    private val maxX = local.maxOfOrNull { it.first } ?: 125.0
    private val minY = local.minOfOrNull { it.second } ?: -125.0
    private val maxY = local.maxOfOrNull { it.second } ?: 125.0
    private val spanX = max(maxX - minX, 250.0)
    private val spanY = max(maxY - minY, 250.0)
    private val scale = min(width * .80 / spanX, height * .80 / spanY).toFloat()
    private val centerX = (minX + maxX) / 2
    private val centerY = (minY + maxY) / 2

    fun radiusPixels(meters: Double): Float = (meters * scale).toFloat()

    fun project(fix: LocationFix): Offset {
        val (x, y) = Geometry.toLocalMeters(fix.latitude, fix.longitude, originLat, originLon)
        return Offset(width / 2 + ((x - centerX) * scale).toFloat(), height / 2 - ((y - centerY) * scale).toFloat())
    }
}

private fun LocationFix?.orEmpty(): List<LocationFix> = if (this == null) emptyList() else listOf(this)

internal data class ColoredTrackSegment(val color: Int, val points: List<LocationFix>)

internal fun coloredTrackSegments(
    track: List<LocationFix>,
    passes: List<Long>,
    colors: List<ResultColor>,
): List<ColoredTrackSegment> {
    if (track.size < 2) return emptyList()
    val result = mutableListOf<ColoredTrackSegment>()
    var activeColor = segmentColor(track[1].elapsedRealtimeNanos, passes, colors)
    var activePoints = mutableListOf(track[0], track[1])
    for (index in 1 until track.lastIndex) {
        val next = track[index + 1]
        val color = segmentColor(next.elapsedRealtimeNanos, passes, colors)
        if (color == activeColor) {
            activePoints += next
        } else {
            result += ColoredTrackSegment(activeColor, activePoints)
            activeColor = color
            activePoints = mutableListOf(track[index], next)
        }
    }
    result += ColoredTrackSegment(activeColor, activePoints)
    return result
}

private fun segmentColor(elapsed: Long, passes: List<Long>, colors: List<ResultColor>): Int {
    if (passes.isEmpty() || elapsed < passes.first()) return 0xFFF4F6F8.toInt()
    val sector = (0 until passes.lastIndex).firstOrNull { elapsed <= passes[it + 1] }
    return when (sector?.let(colors::getOrNull) ?: ResultColor.NEUTRAL) {
        ResultColor.PURPLE -> 0xFFB05CFF.toInt()
        ResultColor.GREEN -> 0xFF38E86B.toInt()
        ResultColor.YELLOW -> 0xFFFFC247.toInt()
        ResultColor.NEUTRAL -> 0xFFD5DADE.toInt()
    }
}

private fun Context.hasValidatedNetwork(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

@Composable
private fun rememberValidatedNetwork(context: Context): Boolean {
    var online by remember(context) { mutableStateOf(context.hasValidatedNetwork()) }
    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { online = context.hasValidatedNetwork() }
            override fun onLost(network: android.net.Network) { online = context.hasValidatedNetwork() }
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        try { manager?.registerDefaultNetworkCallback(callback) } catch (_: Exception) { }
        onDispose { try { manager?.unregisterNetworkCallback(callback) } catch (_: Exception) { } }
    }
    return online
}
