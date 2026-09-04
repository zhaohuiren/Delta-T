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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.MapView
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

internal const val CHECKPOINT_ICON_DP = 26f

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
        AmapScene(scene, modifier) { failed = true }
    } else {
        AbstractMapScene(scene, modifier)
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
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF090C0F)).testTag("route-map"),
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
    private var mapView: MapView? = null
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

    fun createView(context: Context): View {
        MapsInitializer.updatePrivacyShow(appContext, true, true)
        MapsInitializer.updatePrivacyAgree(appContext, true)
        return MapView(context).also { view ->
            mapView = view
            view.onCreate(Bundle())
            map = view.map.apply {
                mapType = AMap.MAP_TYPE_NIGHT
                isTrafficEnabled = false
                showMapText(false)
                showBuildings(false)
                backgroundShade = addPolygon(
                    PolygonOptions().add(LatLng(5.0, 60.0), LatLng(60.0, 60.0), LatLng(60.0, 150.0), LatLng(5.0, 150.0))
                        .fillColor(AndroidColor.argb(218, 0, 0, 0)).strokeWidth(0f).zIndex(0f),
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
                    latestScene?.let(::update)
                }
            }
        }
    }

    fun update(scene: MapScene) {
        val amap = map ?: return
        latestScene = scene
        configureGestures(amap, scene.cameraPolicy == MapCameraPolicy.INTERACTIVE_DETAIL)
        val route = scene.routeSourceTrack.map(::display)
        updateRoute(amap, route)
        updateActualTrack(amap, scene)
        val checkpoints = scene.checkpoints.sortedBy { it.orderIndex }
        updateProgressLines(amap, checkpoints)
        updateCheckpoints(amap, checkpoints)
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
            PolylineOptions().addAll(points).width(4f * density).color(0xFFE5E9ED.toInt()).zIndex(2f),
        )).also { it.points = points; it.isVisible = true }
    }

    private fun updateActualTrack(amap: AMap, scene: MapScene) {
        val segments = coloredTrackSegments(scene.actualTrack, scene.actualPassElapsedNanos, scene.sectorColors)
        segments.forEachIndexed { index, segment ->
            val points = segment.points.map(::display)
            val line = actualLines.getOrNull(index) ?: amap.addPolyline(
                PolylineOptions().addAll(points).width(5f * appContext.resources.displayMetrics.density).color(segment.color).zIndex(4f),
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
                PolylineOptions().addAll(ends).width(5f).color(0xB36E757C.toInt()).setDottedLine(true).zIndex(3f),
            ).also { progressLines[checkpoint.orderIndex] = it }
            line.points = ends
            line.isVisible = true
        }
    }

    private fun updateCheckpoints(amap: AMap, checkpoints: List<MapCheckpoint>) {
        val present = checkpoints.mapTo(mutableSetOf()) { it.orderIndex }
        checkpointMarkers.filterKeys { it !in present }.values.forEach { it.isVisible = false }
        checkpointCircles.filterKeys { it !in present }.values.forEach { it.isVisible = false }
        checkpoints.forEach { checkpoint ->
            val position = display(checkpoint.latitude, checkpoint.longitude)
            val marker = checkpointMarkers[checkpoint.orderIndex] ?: amap.addMarker(
                MarkerOptions().position(position).icon(checkpointIcon(checkpoint.label)).anchor(.5f, .5f).zIndex(8f),
            ).also { checkpointMarkers[checkpoint.orderIndex] = it }
            marker.position = position
            marker.isVisible = true
            if (checkpoint.orderIndex == 0 || checkpoint.orderIndex == 3) {
                val circle = checkpointCircles[checkpoint.orderIndex] ?: amap.addCircle(
                    CircleOptions().center(position).radius(checkpoint.radiusMeters).strokeWidth(2f)
                        .strokeColor(0x99DDE3E8.toInt()).fillColor(0x16000000),
                ).also { checkpointCircles[checkpoint.orderIndex] = it }
                circle.center = position
                circle.radius = checkpoint.radiusMeters
                circle.isVisible = true
            }
        }
    }

    private fun resolveMarkerCollisions() {
        val projection = map?.projection ?: return
        val view = mapView ?: return
        if (view.width <= 0 || view.height <= 0) return
        val density = appContext.resources.displayMetrics.density
        val occupied = mutableListOf<android.graphics.Point>()
        positionMarker?.takeIf { it.isVisible }?.let { occupied += projection.toScreenLocation(it.position) }
        checkpointMarkers.toSortedMap().forEach { (order, marker) ->
            if (!marker.isVisible) return@forEach
            val point = projection.toScreenLocation(marker.position)
            val margin = 20f * density
            fun safeCenter(x: Float, y: Float) = android.graphics.Point(
                x.coerceIn(margin, max(margin, view.width - margin)).toInt(),
                y.coerceIn(margin, max(margin, view.height - margin)).toInt(),
            )
            fun overlaps(candidate: android.graphics.Point): Boolean = occupied.any { other ->
                val dx = (other.x - candidate.x).toFloat()
                val dy = (other.y - candidate.y).toFloat()
                dx * dx + dy * dy < 40f * density * 40f * density
            }
            val original = safeCenter(point.x.toFloat(), point.y.toFloat())
            val offset = 42f * density
            val candidates = listOf(
                original,
                safeCenter(point.x.toFloat(), point.y - offset),
                safeCenter(point.x.toFloat(), point.y + offset),
                safeCenter(point.x - offset, point.y.toFloat()),
                safeCenter(point.x + offset, point.y.toFloat()),
            )
            val center = candidates.firstOrNull { !overlaps(it) } ?: candidates[1 + order % 4]
            marker.setAnchor(
                .5f - (center.x - point.x) / (CHECKPOINT_ICON_DP * density),
                .5f - (center.y - point.y) / (CHECKPOINT_ICON_DP * density),
            )
            occupied += center
        }
    }

    private fun updateCamera(scene: MapScene, route: List<LatLng>, checkpoints: List<LatLng>) {
        val view = mapView ?: return
        val amap = map ?: return
        val all = (route + checkpoints).ifEmpty { scene.actualTrack.map(::display) }
        if (all.isEmpty()) return
        when (scene.cameraPolicy) {
            MapCameraPolicy.FULL_ROUTE_LOCKED, MapCameraPolicy.INTERACTIVE_DETAIL -> {
                val signature = 31 * (31 * route.hashCode() + checkpoints.hashCode()) +
                    31 * view.width + view.height
                if (signature == lastCameraSignature) return
                lastCameraSignature = signature
                view.post { fit(all) }
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
        val padding = (26 * density).toInt()
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
        lastCameraSignature = null
        recordingBounds = null
    }

    private fun checkpointIcon(label: String): BitmapDescriptor {
        checkpointIcons[label]?.let { return it }
        val density = appContext.resources.displayMetrics.density
        val size = (CHECKPOINT_ICON_DP * density).toInt().coerceAtLeast(CHECKPOINT_ICON_DP.toInt())
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = AndroidColor.rgb(18, 21, 25)
        canvas.drawCircle(size / 2f, size / 2f, size * .43f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = AndroidColor.rgb(221, 227, 232)
        canvas.drawCircle(size / 2f, size / 2f, size * .43f, paint)
        paint.style = Paint.Style.FILL
        paint.color = AndroidColor.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = (if (label.length > 1) 11 else 14) * density
        paint.isFakeBoldText = true
        val baseline = size / 2f - (paint.ascent() + paint.descent()) / 2
        canvas.drawText(label, size / 2f, baseline, paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap).also { checkpointIcons[label] = it }
    }

    private fun gpsArrowIcon(): BitmapDescriptor {
        positionIcon?.let { return it }
        val density = appContext.resources.displayMetrics.density
        val size = (36 * density).toInt().coerceAtLeast(36)
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
    val density = LocalDensity.current
    var lastReliableBearing by remember { mutableStateOf(0.0) }
    val currentFix = scene.currentFix
    val reliableBearing = currentFix?.bearingDegrees?.takeIf {
        (currentFix.bearingAccuracyDegrees ?: 0.0) <= 45.0 && (currentFix.speedMetersPerSecond ?: 0.0) >= 2.0
    }
    LaunchedEffect(reliableBearing) { if (reliableBearing != null) lastReliableBearing = reliableBearing }
    val allPoints = scene.routeSourceTrack + scene.actualTrack + scene.currentFix.orEmpty()
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF090C0F)).testTag("abstract-route-map"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val checkpointFixes = scene.checkpoints.map { LocationFix(it.latitude, it.longitude, 0, 0, 0.0) }
            val projection = MeterProjection(allPoints + checkpointFixes, size.width, size.height)
            fun path(points: List<LocationFix>, color: Color, width: Float) {
                if (points.size < 2) return
                val projected = points.map(projection::project)
                val routePath = Path().apply {
                    moveTo(projected.first().x, projected.first().y)
                    projected.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(routePath, color, style = Stroke(width.dp.toPx(), cap = StrokeCap.Round))
            }
            path(scene.routeSourceTrack, Color(0xFF05070A), 6f)
            path(scene.routeSourceTrack, Color(0xFFE5E9ED), 3f)
            coloredTrackSegments(scene.actualTrack, scene.actualPassElapsedNanos, scene.sectorColors).forEach { segment ->
                path(segment.points, Color(segment.color), 4f)
            }
            val checkpoints = scene.checkpoints.sortedBy { it.orderIndex }
            if (checkpoints.size == 4) {
                val axis = Geometry.progressLineAxis(checkpoints.first().latitude, checkpoints.first().longitude, checkpoints.last().latitude, checkpoints.last().longitude)
                checkpoints.filter { it.showProgressLine }.forEach { checkpoint ->
                    val point = projection.project(LocationFix(checkpoint.latitude, checkpoint.longitude, 0, 0, 0.0))
                    val start = if (axis == ProgressLineAxis.EAST_WEST) Offset(0f, point.y) else Offset(point.x, 0f)
                    val end = if (axis == ProgressLineAxis.EAST_WEST) Offset(size.width, point.y) else Offset(point.x, size.height)
                    drawLine(
                        Color(0xB36E757C), start, end, 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                    )
                }
            }
            checkpoints.forEach { checkpoint ->
                val originalPoint = projection.project(LocationFix(checkpoint.latitude, checkpoint.longitude, 0, 0, 0.0))
                val overlapsPosition = scene.currentFix?.let { fix ->
                    Geometry.distanceMeters(fix.latitude, fix.longitude, checkpoint.latitude, checkpoint.longitude) <= max(checkpoint.radiusMeters, 18.0)
                } == true
                val shiftedPoint = if (overlapsPosition) originalPoint + Offset(if (checkpoint.orderIndex % 2 == 0) 24.dp.toPx() else -24.dp.toPx(), -24.dp.toPx()) else originalPoint
                val markerMargin = 14.dp.toPx()
                val point = Offset(
                    shiftedPoint.x.coerceIn(markerMargin, max(markerMargin, size.width - markerMargin)),
                    shiftedPoint.y.coerceIn(markerMargin, max(markerMargin, size.height - markerMargin)),
                )
                val markerRadius = (CHECKPOINT_ICON_DP * .43f).dp.toPx()
                drawCircle(Color(0xFF121519), markerRadius, point)
                drawCircle(Color(0xFFDDE3E8), markerRadius, point, style = Stroke(1.5.dp.toPx()))
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = AndroidColor.WHITE
                        textAlign = Paint.Align.CENTER
                        textSize = with(density) { (if (checkpoint.label.length > 1) 11 else 14).dp.toPx() }
                        isFakeBoldText = true
                    }
                    canvas.nativeCanvas.drawText(checkpoint.label, point.x, point.y - (paint.ascent() + paint.descent()) / 2, paint)
                }
            }
            scene.currentFix?.let { fix ->
                val center = projection.project(fix)
                val iconSize = 32.dp.toPx()
                val arrow = Path().apply {
                    val first = MATERIAL_NAVIGATION_PATH.first()
                    moveTo(center.x + (first.x - .5f) * iconSize, center.y + (first.y - .5f) * iconSize)
                    MATERIAL_NAVIGATION_PATH.drop(1).forEach { point ->
                        lineTo(center.x + (point.x - .5f) * iconSize, center.y + (point.y - .5f) * iconSize)
                    }
                    close()
                }
                rotate(lastReliableBearing.toFloat(), center) {
                    drawPath(arrow, Color.White, style = Stroke(2.25.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(arrow, Color(0xFF2684FF))
                }
            }
        }
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
