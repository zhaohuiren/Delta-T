package app.deltat.commute.ui

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.deltat.commute.BuildConfig
import app.deltat.commute.map.CheckpointMapPoint
import app.deltat.commute.map.MapAdapterFactory

enum class RouteDisplayMode(val storageValue: String) {
    SIMPLE("simple"),
    MAP("map"),
    ;

    companion object {
        fun fromStorage(value: String?): RouteDisplayMode = entries.firstOrNull { it.storageValue == value } ?: SIMPLE
    }
}

val LocalRouteDisplayMode = compositionLocalOf { RouteDisplayMode.SIMPLE }

object RouteMapAvailability {
    val available: Boolean
        get() = BuildConfig.AMAP_AVAILABLE && BuildConfig.AMAP_API_KEY.isNotBlank()
}

@Composable
internal fun RealRouteMap(
    points: List<Pair<Double, Double>>,
    referencePoints: List<Pair<Double, Double>> = emptyList(),
    checkpoints: List<CheckpointMapPoint> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val adapter = remember { MapAdapterFactory.create() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(adapter, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> adapter.onResume()
                Lifecycle.Event.ON_PAUSE -> adapter.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) adapter.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            adapter.onPause()
            adapter.release()
        }
    }

    AndroidView(
        factory = { context -> adapter.createView(context) ?: View(context) },
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A0F12)),
        update = {
            adapter.showRoutes(points, referencePoints)
            adapter.showCheckpoints(checkpoints)
        },
    )
}
