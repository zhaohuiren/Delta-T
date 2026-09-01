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
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    val online = config.networkAvailableOverride ?: remember(context) { context.hasValidatedNetwork() }
    var failed by remember(scene.cameraPolicy) { mutableStateOf(false) }
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
            runCatching { controller.createView(viewContext) }.getOrElse { error ->
                Log.e("RouteMap", "AMap MapView initialization failed", error)
                onFailure()
                View(viewContext)
            }
        },
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF090C0F)).testTag("route-map"),
        update = {
            runCatching { controller.update(scene) }.onFailure { error ->
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
    private var lastCameraSignature: Int? = null
    private var recordingBounds: LatLngBounds? = null
    private var lastReliableBearing = 0.0
    private val coordinateCache = LinkedHashMap<String, LatLng>()

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
                uiSettings.isZoomControlsEnabled = false
                uiSettings.isScaleControlsEnabled = false
                uiSettings.isCompassEnabled = false
                uiSettings.isMyLocationButtonEnabled = false
                setOnMapLoadedListener { mapView?.setBackgroundColor(AndroidColor.rgb(9, 12, 15)) }
            }
        }
    }

    fun update(scene: MapScene) {
        val amap = map ?: return
        configureGestures(amap, scene.cameraPolicy == MapCameraPolicy.INTERACTIVE_DETAIL)
        amap.clear()
        val route = scene.routeSourceTrack.map(::display)
        val actual = scene.actualTrack.map(::display)
        if (route.size >= 2) amap.addPolyline(PolylineOptions().addAll(route).width(7f).color(0xFF3D444A.toInt()).zIndex(1f))
        drawActualTrack(amap, scene, actual)
        val checkpoints = scene.checkpoints.sortedBy { it.orderIndex }
        drawProgressLines(amap, checkpoints)
        checkpoints.forEach { checkpoint -> drawCheckpoint(amap, checkpoint) }
        scene.currentFix?.let { fix ->
            val reliable = fix.bearingDegrees?.takeIf { (fix.bearingAccuracyDegrees ?: 0.0) <= 45.0 && (fix.speedMetersPerSecond ?: 0.0) >= 2.0 }
            if (reliable != null) lastReliableBearing = reliable
            amap.addMarker(
                MarkerOptions().position(display(fix)).icon(gpsArrowIcon()).anchor(.5f, .5f)
                    .rotateAngle((-lastReliableBearing).toFloat()).setFlat(true).zIndex(10f),
            )
            checkpoints.firstOrNull { checkpoint ->
                checkpoint.showProgressLine && Geometry.distanceMeters(
                    fix.latitude, fix.longitude, checkpoint.latitude, checkpoint.longitude,
                ) <= max(checkpoint.radiusMeters, 18.0)
            }?.let { overlapped ->
                // Keep the S label legible when the position arrow sits directly on the checkpoint.
                amap.addMarker(
                    MarkerOptions().position(display(overlapped.latitude, overlapped.longitude))
                        .icon(checkpointIcon(overlapped.label)).anchor(.5f, 1.65f).zIndex(11f),
                )
            }
        }
        updateCamera(scene, route, checkpoints.map { display(it.latitude, it.longitude) })
    }

    private fun drawActualTrack(amap: AMap, scene: MapScene, actual: List<LatLng>) {
        if (actual.size < 2) return
        actual.zipWithNext().forEachIndexed { index, (a, b) ->
            val elapsed = scene.actualTrack[index + 1].elapsedRealtimeNanos
            amap.addPolyline(
                PolylineOptions().add(a, b).width(10f).color(segmentColor(elapsed, scene.actualPassElapsedNanos, scene.sectorColors)).zIndex(3f),
            )
        }
    }

    private fun drawProgressLines(amap: AMap, checkpoints: List<MapCheckpoint>) {
        if (checkpoints.size != 4) return
        val start = checkpoints.first()
        val finish = checkpoints.last()
        val axis = Geometry.progressLineAxis(start.latitude, start.longitude, finish.latitude, finish.longitude)
        val converted = checkpoints.map { display(it.latitude, it.longitude) }
        val latSpan = max(converted.maxOf { it.latitude } - converted.minOf { it.latitude }, .01)
        val lonSpan = max(converted.maxOf { it.longitude } - converted.minOf { it.longitude }, .01)
        checkpoints.filter { it.showProgressLine }.forEach { checkpoint ->
            val point = display(checkpoint.latitude, checkpoint.longitude)
            val ends = when (axis) {
                ProgressLineAxis.EAST_WEST -> listOf(LatLng(point.latitude, point.longitude - lonSpan * 1.5), LatLng(point.latitude, point.longitude + lonSpan * 1.5))
                ProgressLineAxis.NORTH_SOUTH -> listOf(LatLng(point.latitude - latSpan * 1.5, point.longitude), LatLng(point.latitude + latSpan * 1.5, point.longitude))
            }
            amap.addPolyline(
                PolylineOptions().addAll(ends).width(5f).color(0xB36E757C.toInt()).setDottedLine(true).zIndex(2f),
            )
        }
    }

    private fun drawCheckpoint(amap: AMap, checkpoint: MapCheckpoint) {
        val position = display(checkpoint.latitude, checkpoint.longitude)
        if (checkpoint.orderIndex == 0 || checkpoint.orderIndex == 3) {
            amap.addCircle(
                CircleOptions().center(position).radius(checkpoint.radiusMeters).strokeWidth(2f)
                    .strokeColor(0x99DDE3E8.toInt()).fillColor(0x16000000),
            )
        }
        amap.addMarker(
            MarkerOptions().position(position).icon(checkpointIcon(checkpoint.label)).anchor(.5f, .5f).zIndex(8f),
        )
    }

    private fun updateCamera(scene: MapScene, route: List<LatLng>, checkpoints: List<LatLng>) {
        val view = mapView ?: return
        val amap = map ?: return
        val all = (route + checkpoints).ifEmpty { scene.actualTrack.map(::display) }
        if (all.isEmpty()) return
        when (scene.cameraPolicy) {
            MapCameraPolicy.FULL_ROUTE_LOCKED, MapCameraPolicy.INTERACTIVE_DETAIL -> {
                val signature = 31 * route.hashCode() + checkpoints.hashCode()
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
        val bounds = expandedBounds(points, 250.0)
        recordingBounds = bounds
        fitBounds(bounds)
    }

    private fun fitBounds(bounds: LatLngBounds) {
        val view = mapView ?: return
        val amap = map ?: return
        val padding = (48f * view.resources.displayMetrics.density).toInt()
        amap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
        view.post {
            if ((amap.cameraPosition?.zoom ?: 17f) > 17f) amap.moveCamera(CameraUpdateFactory.zoomTo(17f))
        }
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
        runCatching { mapView?.onPause() }
        runCatching { mapView?.onDestroy() }.onFailure { onFailure() }
        mapView = null
        map = null
        coordinateCache.clear()
    }

    private fun checkpointIcon(label: String): BitmapDescriptor {
        val density = appContext.resources.displayMetrics.density
        val size = (34 * density).toInt().coerceAtLeast(34)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = AndroidColor.rgb(18, 21, 25)
        canvas.drawCircle(size / 2f, size / 2f, size * .43f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        paint.color = AndroidColor.rgb(221, 227, 232)
        canvas.drawCircle(size / 2f, size / 2f, size * .43f, paint)
        paint.style = Paint.Style.FILL
        paint.color = AndroidColor.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = (if (label.length > 1) 11 else 14) * density
        paint.isFakeBoldText = true
        val baseline = size / 2f - (paint.ascent() + paint.descent()) / 2
        canvas.drawText(label, size / 2f, baseline, paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun gpsArrowIcon(): BitmapDescriptor {
        val density = appContext.resources.displayMetrics.density
        val size = (36 * density).toInt().coerceAtLeast(36)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val path = AndroidPath().apply {
            moveTo(size * .5f, size * .08f)
            lineTo(size * .82f, size * .88f)
            lineTo(size * .5f, size * .70f)
            lineTo(size * .18f, size * .88f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 4 * density
            color = AndroidColor.WHITE
        }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.color = AndroidColor.rgb(38, 132, 255)
        canvas.drawPath(path, paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}

@Composable
private fun AbstractMapScene(scene: MapScene, modifier: Modifier) {
    val density = LocalDensity.current
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
            path(scene.routeSourceTrack, Color(0xFF3D444A), 2f)
            if (scene.actualTrack.size >= 2) {
                scene.actualTrack.zipWithNext().forEachIndexed { index, (a, b) ->
                    drawLine(
                        Color(segmentColor(scene.actualTrack[index + 1].elapsedRealtimeNanos, scene.actualPassElapsedNanos, scene.sectorColors)),
                        projection.project(a), projection.project(b), 3.dp.toPx(), StrokeCap.Round,
                    )
                }
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
                val point = projection.project(LocationFix(checkpoint.latitude, checkpoint.longitude, 0, 0, 0.0))
                drawCircle(Color(0xFF121519), 10.dp.toPx(), point)
                drawCircle(Color(0xFFDDE3E8), 10.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = AndroidColor.WHITE
                        textAlign = Paint.Align.CENTER
                        textSize = with(density) { 10.dp.toPx() }
                        isFakeBoldText = true
                    }
                    canvas.nativeCanvas.drawText(checkpoint.label, point.x, point.y - (paint.ascent() + paint.descent()) / 2, paint)
                }
            }
            scene.currentFix?.let { fix ->
                val center = projection.project(fix)
                val bearing = Math.toRadians(fix.bearingDegrees ?: 0.0)
                val forward = Offset(sin(bearing).toFloat(), -cos(bearing).toFloat())
                val side = Offset(forward.y, -forward.x)
                val tip = center + forward * 13.dp.toPx()
                val left = center - forward * 9.dp.toPx() + side * 8.dp.toPx()
                val right = center - forward * 9.dp.toPx() - side * 8.dp.toPx()
                val arrow = Path().apply { moveTo(tip.x, tip.y); lineTo(left.x, left.y); lineTo(center.x, center.y); lineTo(right.x, right.y); close() }
                drawPath(arrow, Color.White, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                drawPath(arrow, Color(0xFF2684FF))
                checkpoints.firstOrNull { checkpoint ->
                    checkpoint.showProgressLine && Geometry.distanceMeters(
                        fix.latitude, fix.longitude, checkpoint.latitude, checkpoint.longitude,
                    ) <= max(checkpoint.radiusMeters, 18.0)
                }?.let { checkpoint ->
                    val badge = center + Offset(18.dp.toPx(), -18.dp.toPx())
                    drawCircle(Color(0xFF121519), 10.dp.toPx(), badge)
                    drawCircle(Color(0xFFDDE3E8), 10.dp.toPx(), badge, style = Stroke(1.5.dp.toPx()))
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = AndroidColor.WHITE
                            textAlign = Paint.Align.CENTER
                            textSize = with(density) { 10.dp.toPx() }
                            isFakeBoldText = true
                        }
                        canvas.nativeCanvas.drawText(
                            checkpoint.label,
                            badge.x,
                            badge.y - (paint.ascent() + paint.descent()) / 2,
                            paint,
                        )
                    }
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
    private val scale = min(width * .88 / spanX, height * .88 / spanY).toFloat()
    private val centerX = (minX + maxX) / 2
    private val centerY = (minY + maxY) / 2

    fun project(fix: LocationFix): Offset {
        val (x, y) = Geometry.toLocalMeters(fix.latitude, fix.longitude, originLat, originLon)
        return Offset(width / 2 + ((x - centerX) * scale).toFloat(), height / 2 - ((y - centerY) * scale).toFloat())
    }
}

private fun LocationFix?.orEmpty(): List<LocationFix> = if (this == null) emptyList() else listOf(this)

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
