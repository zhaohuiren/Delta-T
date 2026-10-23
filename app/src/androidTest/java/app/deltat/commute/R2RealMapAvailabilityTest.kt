package app.deltat.commute

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.map.MapCheckpoint
import app.deltat.commute.map.MapScene
import app.deltat.commute.ui.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Exercises production UI fallback/recovery; does not pretend to toggle the OS network. */
class R2RealMapAvailabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadedRouteSurvivesRepeatedOfflinePrivacyAndSdkFallbackRecovery() {
        assumeTrue(BuildConfig.AMAP_AVAILABLE)
        val config = mutableStateOf(MapFeatureConfig(true, true, true))
        val app=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DeltaTApplication
        val source=RealRedesignData(java.io.File(app.getExternalFilesDir(null),"redesign-input.json"))
        val points=source.fixes(source.sample())
        val scene = MapScene(points, points.take(60), source.passes(source.sample()).map { pass -> points.indices.minBy { kotlin.math.abs(points[it].elapsedRealtimeNanos-pass.getLong("elapsedRealtimeNanos")) } }.mapIndexed { i, index ->
            MapCheckpoint(listOf("A", "S1", "S2", "B")[i], i, points[index].latitude, points[index].longitude, 35.0, i in 1..2)
        })
        compose.setContent { DeltaTTheme { CompositionLocalProvider(LocalMapFeatureConfig provides config.value) {
            Box(Modifier.fillMaxSize()) { RouteMap(scene, Modifier.fillMaxSize()) }
        } } }
        val fallbacks = listOf(MapFeatureConfig(true, true, false), MapFeatureConfig(true, false, true), MapFeatureConfig(false, true, true))
        for ((index, fallback) in fallbacks.withIndex()) {
            compose.onNodeWithTag("route-map").assertExists()
            Thread.sleep(1_500)
            compose.runOnIdle { config.value = fallback }
            compose.onNodeWithTag("abstract-route-map").assertExists()
            compose.onNodeWithTag("route-map").assertDoesNotExist()
            compose.saveScreenshot("r2-real-map-fallback-$index.png")
            compose.runOnIdle { config.value = MapFeatureConfig(true, true, true) }
            compose.onNodeWithTag("route-map").assertExists()
            compose.onNodeWithTag("abstract-route-map").assertDoesNotExist()
            Thread.sleep(1_500)
            compose.saveScreenshot("r2-real-map-recovered-$index.png")
        }
    }
}
