package app.deltat.commute.location

import app.deltat.commute.domain.GnssHealth
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.LocationSource
import kotlinx.coroutines.*

class ReplayLocationSource(
    private val fixes: List<LocationFix>,
    private val speedMultiplier: Double = 1.0,
) : LocationSource {
    private var job: Job? = null
    override fun start(onFix: (LocationFix) -> Unit, onGnss: (GnssHealth) -> Unit) {
        onGnss(GnssHealth(10, 8, 12, 9, 5, 3, "回放定位"))
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            fixes.forEachIndexed { index, fix ->
                if (index > 0) {
                    val gapMillis = ((fix.elapsedRealtimeNanos - fixes[index - 1].elapsedRealtimeNanos) / 1_000_000 / speedMultiplier).toLong()
                    delay(gapMillis.coerceIn(0, 2_000))
                }
                onFix(fix)
            }
        }
    }
    override fun stop() { job?.cancel(); job = null }
}

class FakeLocationSource : LocationSource {
    private var onFix: ((LocationFix) -> Unit)? = null
    override fun start(onFix: (LocationFix) -> Unit, onGnss: (GnssHealth) -> Unit) {
        this.onFix = onFix
        onGnss(GnssHealth(8, 7, 8, 7, label = "测试定位"))
    }
    fun emit(fix: LocationFix) = onFix?.invoke(fix)
    override fun stop() { onFix = null }
}
