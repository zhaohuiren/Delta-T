package app.deltat.commute

import android.graphics.Color
import android.graphics.Outline
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.domain.EngineStatus
import app.deltat.commute.domain.GnssHealth
import app.deltat.commute.domain.ResultColor
import app.deltat.commute.location.RuntimeSnapshot
import app.deltat.commute.map.MapCheckpoint
import app.deltat.commute.map.MapScene
import app.deltat.commute.ui.*
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.PolygonOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.BitmapDescriptorFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Review prototype ONLY: production sources/layout remain untouched until design approval. */
class MapReviewCaptureTest {
    @Test fun captureLatestBaselineThenMapOnlyProposal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val mode = InstrumentationRegistry.getArguments().getString("captureMode") ?: "after"
        val previewCheckpointDp = InstrumentationRegistry.getArguments().getString("previewCheckpointDp")?.toInt() ?: 28
        val diagnostic = File(context.getExternalFilesDir(null), "delta-t-diagnostics.txt")
        assumeTrue(diagnostic.isFile && BuildConfig.AMAP_AVAILABLE)
        val fixture = RealRouteFixture.read(diagnostic)
        val output = File(context.getExternalFilesDir(null), if (mode == "review") "map-review-r2" else "map-r2-implementation-$mode").apply { mkdirs() }
        val route = RouteProfileEntity("map-review-route", "上班", "TO_WORK", createdAtMillis = 1, updatedAtMillis = 1)
        val actual = fixture.track.take((fixture.track.size * .25).toInt())
        val state = mutableStateOf(RuntimeSnapshot(
            sessionId = "map-review-session", routeId = route.id, routeName = route.name,
            mode = "TIMED", status = "S1 计时中", engineStatus = EngineStatus.SECTOR_1,
            elapsedNanos = 120_000_000_000, track = actual, lastFix = actual.last(),
            routeSourceTrack = fixture.track, routeCheckpoints = fixture.checkpoints,
            gnssHealth = GnssHealth(12, 9), liveColor = ResultColor.NEUTRAL, currentSector = 1,
        ))
        val scene = MapScene(fixture.track, actual, fixture.checkpoints.map(MapCheckpoint::from), actual.last())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        lateinit var original: MapView
        val klass = Class.forName("app.deltat.commute.ui.AmapSceneController")
        val constructor = klass.declaredConstructors.single().apply { isAccessible = true }
        var controller: Any? = null
        fun call(name: String, vararg args: Any?): Any? = klass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(controller, *args)
        fun field(name: String): Any? = klass.getDeclaredField(name).apply { isAccessible = true }.get(controller)
        try {
            scenario.onActivity {
                activity = it
                it.setContent {
                    DeltaTTheme { CompositionLocalProvider(LocalMapFeatureConfig provides MapFeatureConfig(true, true, true)) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) { TimingScreen(rememberNavController(), route, listOf(route), state.value, {}, {}, {}) }
                    } }
                }
            }
            Thread.sleep(6_000)
            screenshot(File(output, "before-s1-120s.png"))
            if (mode == "gps-small-preview") {
                scenario.onActivity {
                    val health = state.value.gnssHealth
                    state.value = state.value.copy(gnssHealth = health.copy(label = "${health.displayLabel()} · ${health.usedTotal}/${health.visibleTotal}"))
                }
                Thread.sleep(1_000)
                scenario.onActivity {
                    val density = it.resources.displayMetrics.density
                    val map = requireNotNull(findMap(it.window.decorView)).map
                    val checkpoints = fixture.checkpoints.map { cp ->
                        cp.label to CoordinateConverter(it).from(CoordinateConverter.CoordType.GPS).coord(LatLng(cp.latitude, cp.longitude)).convert()
                    }
                    map.mapScreenMarkers.filter { marker -> marker.icons?.firstOrNull()?.width == (34 * density).toInt() }.forEach { marker ->
                        val label = checkpoints.minBy { (_, p) -> kotlin.math.abs(p.latitude - marker.position.latitude) + kotlin.math.abs(p.longitude - marker.position.longitude) }.first
                        val size = (previewCheckpointDp * density).toInt()
                        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 21, 25) }
                        canvas.drawCircle(size / 2f, size / 2f, size * .43f, paint)
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 1.5f * density
                        paint.color = Color.rgb(221, 227, 232)
                        canvas.drawCircle(size / 2f, size / 2f, size * .43f, paint)
                        paint.style = Paint.Style.FILL
                        paint.color = Color.WHITE
                        paint.textAlign = Paint.Align.CENTER
                        paint.textSize = (if (label.length > 1) 11 else 14) * density
                        paint.isFakeBoldText = true
                        canvas.drawText(label, size / 2f, size / 2f - (paint.ascent() + paint.descent()) / 2, paint)
                        val xOffset = (.5f - marker.anchorU) * 34 * density
                        val yOffset = (.5f - marker.anchorV) * 34 * density
                        marker.setIcon(BitmapDescriptorFactory.fromBitmap(bitmap))
                        marker.setAnchor(.5f - xOffset / size, .5f - yOffset / size)
                    }
                }
                Thread.sleep(1_000)
                screenshot(File(output, "proposal-gps-counts-${previewCheckpointDp}dp.png"))
                return
            }
            if (mode != "review") return
            scenario.onActivity {
                original = findMap(it.window.decorView) ?: error("实际计时页没有 MapView")
                val density = it.resources.displayMetrics.density
                val decor = it.window.decorView as ViewGroup
                val position = IntArray(2).also(original::getLocationOnScreen)
                val decorPosition = IntArray(2).also(decor::getLocationOnScreen)
                val width = decor.width - (32 * density).toInt()
                val height = original.height
                val frame = FrameLayout(it).apply {
                    setBackgroundColor(Color.BLACK)
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 16 * density) }
                    }
                    clipToOutline = true
                }
                controller = constructor.newInstance(it.applicationContext, { error("地图预览失败") })
                val view = call("createView", it) as View
                frame.addView(view, FrameLayout.LayoutParams(-1, -1))
                val x = (16 * density).toInt()
                val y = position[1] - decorPosition[1]
                decor.addView(frame, FrameLayout.LayoutParams(width, height).apply { leftMargin = x; topMargin = y })
                call("onResume")
                File(output, "geometry.txt").writeText("beforeWidthPx=${original.width}\nafterWidthPx=$width\nheightPx=$height\ndensity=$density\nframeY=$y\nfixtureProgress=0.25\ntimer=02:00.00\n")
            }
            Thread.sleep(1_000)
            scenario.onActivity {
                call("update", scene)
                val map = field("map") as AMap
                // One test-only translucent overlay, below route polylines and markers.
                map.addPolygon(PolygonOptions().add(LatLng(5.0, 60.0), LatLng(60.0, 60.0), LatLng(60.0, 150.0), LatLng(5.0, 150.0))
                    .fillColor(Color.argb(218, 0, 0, 0)).strokeWidth(0f).zIndex(0f))
            }
            Thread.sleep(1_000)
            scenario.onActivity {
                val density = it.resources.displayMetrics.density
                val routeLine = field("routeLine") as Polyline
                routeLine.width = 4 * density
                (field("routeCasing") as Polyline).width = 7 * density
                (field("actualLines") as List<*>).forEach { line -> (line as Polyline).width = 5 * density }
                val bounds = LatLngBounds.builder().also { builder -> routeLine.points.forEach(builder::include) }.build()
                (field("map") as AMap).moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, (26 * density).toInt()))
            }
            Thread.sleep(4_000)
            scenario.onActivity { call("resolveMarkerCollisions") }
            Thread.sleep(1_000)
            screenshot(File(output, "proposal-r2-s1-120s.png"))
            scenario.onActivity {
                val view = field("mapView") as MapView
                val map = field("map") as AMap
                val p = (field("routeLine") as Polyline).points.map { map.projection.toScreenLocation(it) }
                assertTrue(p.all { it.x in 0 until view.width && it.y in 0 until view.height })
                File(output, "geometry.txt").appendText("mapWidthPx=${view.width}\nmapHeightPx=${view.height}\nzoom=${map.cameraPosition.zoom}\nrouteWidthPx=${p.maxOf { it.x } - p.minOf { it.x }}\nrouteHeightPx=${p.maxOf { it.y } - p.minOf { it.y }}\n")
            }
        } finally {
            scenario.onActivity { if (controller != null) call("release"); it.setContent { } }
            scenario.close()
        }
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findMap(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun screenshot(file: File) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
