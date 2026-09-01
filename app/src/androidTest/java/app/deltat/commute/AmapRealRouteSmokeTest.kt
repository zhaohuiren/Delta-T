package app.deltat.commute

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.ResultColor
import app.deltat.commute.map.MapCameraPolicy
import app.deltat.commute.map.MapCheckpoint
import app.deltat.commute.map.MapScene
import app.deltat.commute.ui.DeltaTTheme
import app.deltat.commute.ui.LocalMapFeatureConfig
import app.deltat.commute.ui.MapFeatureConfig
import app.deltat.commute.ui.RouteMap
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class AmapRealRouteSmokeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun realDiagnosticRouteCreatesAmapAndFitsFullRoute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val diagnostic = File(context.getExternalFilesDir(null), "delta-t-diagnostics.txt")
        assumeTrue("本地未提供真实路线诊断包", diagnostic.isFile)
        assumeTrue("未配置高德 Key", BuildConfig.AMAP_AVAILABLE)
        val scene = routeScene(diagnostic)

        composeRule.setContent {
            DeltaTTheme {
                CompositionLocalProvider(
                    LocalMapFeatureConfig provides MapFeatureConfig(
                        sdkAvailable = true,
                        privacyAgreed = true,
                        networkAvailableOverride = true,
                    ),
                ) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        RouteMap(scene, Modifier.fillMaxSize())
                    }
                }
            }
        }

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("route-map").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("route-map").assertExists()
        composeRule.onNodeWithTag("abstract-route-map").assertDoesNotExist()
        composeRule.waitForIdle()
        Thread.sleep(5_000)
        composeRule.saveScreenshot("amap-real-route-smoke.png")
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/amap-real-route-device.png")
        ParcelFileDescriptor.AutoCloseInputStream(screenshot).use { it.readBytes() }
    }

    private fun routeScene(file: File): MapScene {
        val sessions = JSONObject(file.readText()).getJSONArray("sessions")
        val selected = (0 until sessions.length())
            .map(sessions::getJSONObject)
            .filter { it.optString("status") == "COMPLETED" && it.optJSONArray("checkpointPasses")?.length() == 4 }
            .maxBy { it.getJSONArray("track").length() }
        val rawTrack = selected.getJSONArray("track")
        val stride = max(1, rawTrack.length() / 1_200)
        val fullTrack = (0 until rawTrack.length() step stride).map { index ->
            val point = rawTrack.getJSONObject(index)
            point.toFix()
        }.let { sampled ->
            val last = rawTrack.getJSONObject(rawTrack.length() - 1).toFix()
            if (sampled.lastOrNull()?.elapsedRealtimeNanos == last.elapsedRealtimeNanos) sampled else sampled + last
        }
        val passes = selected.getJSONArray("checkpointPasses")
        val checkpoints = (0 until passes.length()).map { order ->
            val elapsed = passes.getJSONObject(order).getLong("elapsedRealtimeNanos")
            val nearest = fullTrack.minBy { abs(it.elapsedRealtimeNanos - elapsed) }
            MapCheckpoint(
                label = listOf("A", "S1", "S2", "B")[order],
                orderIndex = order,
                latitude = nearest.latitude,
                longitude = nearest.longitude,
                radiusMeters = 35.0,
                showProgressLine = order in 1..2,
            )
        }
        val visibleCount = (fullTrack.size * .62).toInt().coerceAtLeast(2)
        return MapScene(
            routeSourceTrack = fullTrack,
            actualTrack = fullTrack.take(visibleCount),
            checkpoints = checkpoints,
            currentFix = fullTrack[visibleCount - 1],
            actualPassElapsedNanos = checkpoints.mapIndexed { index, checkpoint ->
                fullTrack.minBy {
                    abs(it.latitude - checkpoint.latitude) + abs(it.longitude - checkpoint.longitude)
                }.elapsedRealtimeNanos
            },
            sectorColors = listOf(ResultColor.PURPLE, ResultColor.GREEN, ResultColor.YELLOW),
            cameraPolicy = MapCameraPolicy.FULL_ROUTE_LOCKED,
        )
    }

    private fun JSONObject.toFix() = LocationFix(
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
        elapsedRealtimeNanos = getLong("elapsedRealtimeNanos"),
        wallTimeMillis = 0L,
        accuracyMeters = optDouble("accuracyMeters", 10.0),
        speedMetersPerSecond = optDouble("speedMps").takeUnless(Double::isNaN),
        bearingDegrees = null,
    )
}
