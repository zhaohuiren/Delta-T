package app.deltat.commute

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.ResultColor
import app.deltat.commute.map.MapCameraPolicy
import app.deltat.commute.map.MapCheckpoint
import app.deltat.commute.map.MapScene
import app.deltat.commute.ui.CHECKPOINT_ICON_DP
import com.amap.api.maps.AMap
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.Polyline
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Probes the real private controller via reflection; production exposes no diagnostic API. */
class AmapResourceMatrixTest {
    @Test fun realSdkViewportRouteBoundsOverlayReuseAndIdempotentRelease() {
        assumeTrue(BuildConfig.AMAP_AVAILABLE)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "deep-test").apply { mkdirs() }
        val evidence = StringBuilder("shape,width_dp,height_dp,zoom,actual_lines,icons,markers,route_points_inside\n")
        val klass = Class.forName("app.deltat.commute.ui.AmapSceneController")
        val constructor = klass.declaredConstructors.single().apply { isAccessible = true }
        var failures = 0
        val controllers = mutableListOf<Any>()
        fun construct(): Any = constructor.newInstance(context.applicationContext, { failures++ }).also(controllers::add)
        fun call(controller: Any, name: String, vararg args: Any?): Any? {
            val method = klass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
            method.isAccessible = true
            return method.invoke(controller, *args)
        }
        fun field(controller: Any, name: String): Any? = klass.getDeclaredField(name).apply { isAccessible = true }.get(controller)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var holder: FrameLayout
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it; holder = FrameLayout(it); it.setContentView(holder) }
        try {
            for (shape in listOf("short", "horizontal", "vertical", "diagonal", "foldback")) {
                val points = synthetic(shape)
                val checkpoints = listOf(0, 1_333, 2_666, 3_999).mapIndexed { i, index ->
                    val p = points[index]
                    MapCheckpoint(listOf("A", "S1", "S2", "B")[i], i, p.latitude, p.longitude, 35.0, i in 1..2)
                }
                for ((widthDp, ratio) in listOf(300 to 1.24f, 360 to 1.24f, 400 to 1.24f, 300 to 1.69f, 360 to 1.69f, 400 to 1.69f)) {
                    val controller = construct()
                    lateinit var view: View
                    onMain {
                        view = call(controller, "createView", activity) as View
                        val density = activity.resources.displayMetrics.density
                        holder.addView(view, FrameLayout.LayoutParams((widthDp * density).toInt(), (widthDp / ratio * density).toInt()))
                        call(controller, "onResume")
                    }
                    Thread.sleep(350)
                    val scene = MapScene(points, points, checkpoints, points[2_666].copy(speedMetersPerSecond = 15.0, bearingDegrees = 90.0),
                        listOf(0L, 1_333_000_000_000L, 2_666_000_000_000L, 3_999_000_000_000L),
                        listOf(ResultColor.PURPLE, ResultColor.GREEN, ResultColor.YELLOW), MapCameraPolicy.FULL_ROUTE_LOCKED)
                    onMain { call(controller, "update", scene) }
                    Thread.sleep(450)
                    onMain {
                        call(controller, "update", scene)
                        val map = field(controller, "map") as AMap
                        assertTrue(map.uiSettings.isScaleControlsEnabled)
                        assertFalse(map.uiSettings.isScrollGesturesEnabled)
                        val shadeBefore = field(controller, "backgroundShade")
                        assertNotNull(shadeBefore)
                        val linesBefore = (field(controller, "actualLines") as List<*>).toList()
                        val iconsBefore = (field(controller, "checkpointIcons") as Map<*, *>).values.toList()
                        val markerBefore = field(controller, "positionMarker")
                        repeat(25) { index -> call(controller, "update", scene.copy(currentFix = scene.currentFix!!.copy(bearingDegrees = (index * 45 % 360).toDouble()))) }
                        for (angle in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 359.0)) {
                            call(controller, "update", scene.copy(currentFix = scene.currentFix!!.copy(bearingDegrees = angle)))
                            val marker = field(controller, "positionMarker") as Marker
                            assertEquals((360.0 - angle) % 360.0, (marker.rotateAngle.toDouble() + 360.0) % 360.0, .1)
                        }
                        call(controller, "update", scene.copy(currentFix = scene.currentFix!!.copy(speedMetersPerSecond = 0.0, bearingDegrees = 90.0)))
                        assertEquals(1.0, ((field(controller, "positionMarker") as Marker).rotateAngle.toDouble() + 360.0) % 360.0, .1)
                        assertEquals(linesBefore, field(controller, "actualLines"))
                        assertEquals(iconsBefore, (field(controller, "checkpointIcons") as Map<*, *>).values.toList())
                        assertSame(markerBefore, field(controller, "positionMarker"))
                        assertSame(shadeBefore, field(controller, "backgroundShade"))
                        assertTrue(linesBefore.size <= 5)
                        assertEquals(4, iconsBefore.size)
                        val route = field(controller, "routeLine") as Polyline
                        val margin = 8 * activity.resources.displayMetrics.density
                        val allInside = route.points.all { p ->
                            val screen = map.projection.toScreenLocation(p)
                            screen.x >= margin && screen.y >= margin && screen.x < view.width - margin && screen.y < view.height - margin
                        }
                        assertTrue("$shape/$widthDp 所有路线点须在视口安全区", allInside)
                        val markers = field(controller, "checkpointMarkers") as Map<*, *>
                        assertEquals(4, markers.size)
                        val density = activity.resources.displayMetrics.density
                        val centers = markers.values.map { value ->
                            val marker = value as Marker
                            val p = map.projection.toScreenLocation(marker.position)
                            (p.x + (.5f - marker.anchorU) * CHECKPOINT_ICON_DP * density) to (p.y + (.5f - marker.anchorV) * CHECKPOINT_ICON_DP * density)
                        }
                        centers.forEach { (x, y) ->
                            assertTrue("$shape/$widthDp 标签安全区 x=$x y=$y", x >= 16 * density && x <= view.width - 16 * density && y >= 16 * density && y <= view.height - 16 * density)
                        }
                        for (a in centers.indices) for (b in a + 1 until centers.size) {
                            val dx = centers[a].first - centers[b].first
                            val dy = centers[a].second - centers[b].second
                            assertTrue("$shape/$widthDp 标签 $a/$b 重叠", dx * dx + dy * dy >= 32 * density * 32 * density)
                        }
                        evidence.appendLine("$shape,$widthDp,${widthDp / ratio},${map.cameraPosition.zoom},${linesBefore.size},${iconsBefore.size},${markers.size},$allInside")
                        call(controller, "onPause")
                        call(controller, "onResume")
                        call(controller, "release")
                        call(controller, "release")
                        assertNull(field(controller, "mapView"))
                        assertNull(field(controller, "backgroundShade"))
                        assertNull(field(controller, "latestScene"))
                        assertTrue((field(controller, "actualLines") as List<*>).isEmpty())
                        assertTrue((field(controller, "checkpointIcons") as Map<*, *>).isEmpty())
                        holder.removeAllViews()
                    }
                }
            }
            assertEquals(0, failures)
        } finally {
            File(output, "amap-resource-matrix.csv").writeText(evidence.toString())
            onMain { controllers.forEach { call(it, "release") }; holder.removeAllViews() }
            scenario.close()
        }
    }

    private fun synthetic(shape: String): List<LocationFix> = (0..3_999).map { index ->
        val t = index / 3_999.0
        val (lat, lon) = when (shape) {
            "short" -> 31.1 + t * .002 to 121.3
            "horizontal" -> 31.1 to 121.3 + t * .2
            "vertical" -> 31.1 + t * .2 to 121.3
            "foldback" -> 31.1 + t * .05 to 121.3 + (if (t < .5) t else 1 - t) * .25
            else -> 31.1 + t * .13 to 121.3 + t * .2
        }
        LocationFix(lat, lon, index * 1_000_000_000L, 0, 5.0)
    }

    private fun <T> onMain(block: () -> T): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        Handler(Looper.getMainLooper()).post { result = runCatching(block); latch.countDown() }
        check(latch.await(15, TimeUnit.SECONDS)) { "地图更新超过15秒未响应" }
        return requireNotNull(result).getOrThrow()
    }
}
