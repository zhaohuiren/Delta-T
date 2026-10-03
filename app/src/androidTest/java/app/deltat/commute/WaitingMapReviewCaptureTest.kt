package app.deltat.commute

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.domain.*
import app.deltat.commute.location.RuntimeSnapshot
import app.deltat.commute.location.RuntimeSector
import app.deltat.commute.ui.*
import com.amap.api.maps.MapView
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Diagnostic capture only. No design overrides, database writes, Service or OS GPS injection. */
class WaitingMapReviewCaptureTest {
    @Test fun captureCurrentWaitingFlowAndCandidateRoutingDefect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val diagnostic = File(context.getExternalFilesDir(null), "delta-t-diagnostics.txt")
        assumeTrue(diagnostic.isFile && BuildConfig.AMAP_AVAILABLE)
        val fixture = RealRouteFixture.read(diagnostic)
        val layoutBaseline = InstrumentationRegistry.getArguments().getString("layoutBaseline") == "true"
        val preparedProposal = InstrumentationRegistry.getArguments().getString("preparedProposal") == "true"
        val output = File(context.getExternalFilesDir(null), if (preparedProposal) "prepared-layout-r2" else if (layoutBaseline) "timing-square-map-r1-20260907" else "waiting-review-20260907").apply { mkdirs() }
        val metadata = File(output, "capture-metadata.txt")
        metadata.writeText("Current production UI; historical route geometry with deterministic review states.\n")
        val route = RouteProfileEntity("review-waiting", "上班", "TO_WORK", createdAtMillis = 1, updatedAtMillis = 1)
        val a = fixture.checkpoints.first()
        fun nearA(meters: Double, time: Long) = LocationFix(a.latitude - meters / 111_320.0, a.longitude,
            time, 0, 6.0, speedMetersPerSecond = 8.0, bearingDegrees = 0.0, provider = "review-fixture")
        val outside = nearA(128.0, 10_000_000_000)
        val inside = nearA(10.0, 30_000_000_000)
        val engine = DefaultTimingEngine()
        val armed = engine.processFix(EngineState(), outside, fixture.checkpoints).newState
        val candidate = engine.processFix(armed, inside, fixture.checkpoints).newState
        assertEquals(EngineStatus.START_CANDIDATE, candidate.status)
        assertTrue(candidate.passes.isEmpty())
        val startedInside = engine.processFix(EngineState(), inside, fixture.checkpoints).newState
        assertEquals(true, startedInside.startRequiresExit)
        metadata.appendText("Engine reproduction: outside 128m -> inside 10m = START_CANDIDATE, zero confirmed passes. Initial fix inside A = startRequiresExit true.\n")
        val waiting = RuntimeSnapshot(sessionId = "review", routeId = route.id, routeName = route.name,
            mode = "TIMED", status = "等待驶过起点 A", engineStatus = EngineStatus.ARMED,
            routeDetectionDistanceMeters = 128.0, routeSourceTrack = fixture.track,
            routeCheckpoints = fixture.checkpoints, gnssHealth = GnssHealth(12, 9),
            lastFix = outside, track = listOf(outside))
        val state = mutableStateOf(waiting.copy(mode = "AUTO_ARMING", routeId = null, lastFix = null, track = emptyList()))
        val online = mutableStateOf(true)
        val fontScale = mutableStateOf(1f)
        val proposal = mutableStateOf(false)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value),
                        LocalMapFeatureConfig provides MapFeatureConfig(true, true, online.value)) {
                        DeltaTTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            if (preparedProposal && state.value.engineStatus in listOf(EngineStatus.ARMED, EngineStatus.START_CANDIDATE)) PreparedLayoutProposal(state.value)
                            else if (proposal.value) TimingSquareMapProposal(state.value) else TimingScreen(rememberNavController(), route, listOf(route), state.value,
                                {}, {}, {}, elapsedRealtimeNanos = {
                                    (state.value.startedAtElapsedNanos ?: 0L) + state.value.elapsedNanos
                                }, animateClock = false)
                        } }
                    }
                }
            }
            fun capture(name: String, snapshot: RuntimeSnapshot, connected: Boolean = true, scale: Float = 1f) {
                scenario.onActivity { state.value = snapshot; online.value = connected; fontScale.value = scale }
                Thread.sleep(2_500)
                instrumentation.waitForIdleSync()
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                metadata.appendText("$name time=${Instant.now()} status=${snapshot.engineStatus} passes=${snapshot.actualPassElapsedNanos.size} fontScale=$scale onlineOverride=$connected\n")
                scenario.onActivity { activity ->
                    val mapView = findMap(activity.window.decorView)
                    if (mapView != null) {
                        val loc = IntArray(2).also(mapView::getLocationOnScreen)
                        val fix = snapshot.lastFix
                        val position = fix?.let { CoordinateConverter(context).from(CoordinateConverter.CoordType.GPS)
                            .coord(LatLng(it.latitude, it.longitude)).convert() }?.let { mapView.map.projection.toScreenLocation(it) }
                        val visible = position?.let { it.x in 0 until mapView.width && it.y in 0 until mapView.height }
                        metadata.appendText("  map=${mapView.width}x${mapView.height} origin=${loc.joinToString()} zoom=${mapView.map.cameraPosition.zoom} currentFixInside=$visible\n")
                    } else metadata.appendText("  no native MapView (detecting or Canvas fallback)\n")
                }
            }
            if (layoutBaseline) {
                capture("01-waiting-ratio-baseline", waiting.copy(gnssHealth = GnssHealth(48, 28)))
                val cutoff = fixture.passes[1] + (fixture.passes[2] - fixture.passes[1]) / 2
                val actual = fixture.track.takeWhile { it.elapsedRealtimeNanos <= cutoff }
                capture("02-live-s2-baseline", waiting.copy(
                    engineStatus = EngineStatus.SECTOR_2, status = "S2 计时中", currentSector = 2,
                    startedAtElapsedNanos = 1_000_000_000, elapsedNanos = 1_038_650_000_000,
                    track = actual, lastFix = actual.last(), actualPassElapsedNanos = fixture.passes.take(2),
                    sectors = listOf(RuntimeSector(0, 766_030_000_000, 184_910_000_000, ResultColor.YELLOW,
                        monthlyDeltaNanos = 184_910_000_000)),
                    monthlyDeltaNanos = 178_570_000_000, currentSectorMonthlyDeltaNanos = -6_330_000_000,
                    monthlyReferenceTrack = fixture.track, maxSpeedMps = 77.3 / 3.6,
                    gnssHealth = GnssHealth(48, 28),
                ))
                if (InstrumentationRegistry.getArguments().getString("proposal") == "true") {
                    scenario.onActivity { proposal.value = true }
                    capture("03-live-s2-proposal-r1", state.value)
                }
                return
            }
            capture("01-detecting", state.value)
            // Choose a point outside the entire source-route envelope to expose camera omission.
            val far = outside.copy(latitude = fixture.track.minOf { it.latitude } - .02)
            capture("02-waiting-outside-route", waiting.copy(lastFix = far, track = listOf(far),
                routeDetectionDistanceMeters = Geometry.distanceMeters(far.latitude, far.longitude, a.latitude, a.longitude)))
            capture("03-waiting-128m", waiting)
            capture("04-initially-inside-a-10m", waiting.copy(lastFix = inside, track = listOf(inside), routeDetectionDistanceMeters = 10.0, startRequiresExit = startedInside.startRequiresExit == true))
            capture("05-start-candidate-zero-passes", waiting.copy(lastFix = inside, track = listOf(outside, inside),
                engineStatus = candidate.status, routeDetectionDistanceMeters = 10.0))
            capture("06-confirmed-s1", waiting.copy(lastFix = inside, track = listOf(outside, inside),
                engineStatus = EngineStatus.SECTOR_1, startedAtElapsedNanos = 28_000_000_000,
                elapsedNanos = 2_000_000_000, currentSector = 1, actualPassElapsedNanos = listOf(28_000_000_000)))
            capture("07-waiting-offline-128m", waiting, connected = false)
            capture("08-waiting-location-disabled", waiting.copy(gnssHealth = GnssHealth(locationEnabled = false)))
            capture("09-waiting-large-font", waiting, scale = 1.3f)
            capture("10-waiting-missing-location", waiting.copy(lastFix = null))
            capture("11-waiting-low-accuracy", waiting.copy(lastFix = outside.copy(accuracyMeters = 80.0)))
            capture("12-waiting-stale-location", waiting.copy(lastFix = outside.copy(elapsedRealtimeNanos = -20_000_000_000L)))
            capture("13-candidate-falls-back-to-waiting", waiting)

        } finally {
            scenario.onActivity { it.setContent {} }
            scenario.close()
        }
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findMap(view.getChildAt(i))?.let { return it }
        return null
    }
}
