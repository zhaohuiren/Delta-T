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
class MapAvailabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadedRouteSurvivesRepeatedOfflinePrivacyAndSdkFallbackRecovery() {
        assumeTrue(BuildConfig.AMAP_AVAILABLE)
        val config = mutableStateOf(MapFeatureConfig(true, true, true))
        val points = (0..100).map { LocationFix(31.1 + it * .0001, 121.3 + it * .0001, it * 1_000_000_000L, 0, 5.0) }
        val scene = MapScene(points, points.take(60), listOf(0, 33, 66, 100).mapIndexed { i, index ->
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
            compose.saveScreenshot("map-fallback-$index.png")
            compose.runOnIdle { config.value = MapFeatureConfig(true, true, true) }
            compose.onNodeWithTag("route-map").assertExists()
            compose.onNodeWithTag("abstract-route-map").assertDoesNotExist()
            Thread.sleep(1_500)
            compose.saveScreenshot("map-recovered-$index.png")
        }
    }
}
