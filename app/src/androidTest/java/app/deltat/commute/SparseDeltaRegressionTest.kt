package app.deltat.commute

import app.deltat.commute.domain.*
import app.deltat.commute.location.TimingService
import org.junit.Assert.*
import org.junit.Test

class SparseDeltaRegressionTest {
    @Test fun thirdSectorUsesS2TimestampAfterMissingS1() {
        val service = TimingService()
        fun fix(x: Double, seconds: Long) = LocationFix(0.0, x / 111195.0, seconds * 1_000_000_000L, 0, 3.0)
        val cps = (0..3).map { Checkpoint("$it", it, "$it", 0.0, it * 300.0 / 111195.0) }
        val passes = listOf(0 to 0L, 2 to 12_000_000_000L).map { (index, time) -> CheckpointPass(cps[index], time, 0.0, cps[index].longitude, TriggerMode.LINE_CROSSING, 0.0, 100) }
        TimingService::class.java.getDeclaredField("engineState").apply { isAccessible = true }.set(service, EngineState(EngineStatus.SECTOR_3, 3, passes))
        val referenceClass = Class.forName("app.deltat.commute.location.TimingService\$PreparedReference")
        val reference = referenceClass.declaredConstructors.single().apply { isAccessible = true }.newInstance((0..9).map { fix(it * 100.0, it.toLong()) }, listOf(0L, 3_000_000_000L, 6_000_000_000L, 9_000_000_000L))
        val progressClass = Class.forName("app.deltat.commute.location.TimingService\$LiveReferenceProgress")
        val progress = progressClass.declaredConstructors.single().apply { isAccessible = true }.newInstance()
        progressClass.getDeclaredField("expectedElapsedNanos").apply { isAccessible = true }.setLong(progress, 6_000_000_000L)
        val result = TimingService::class.java.declaredMethods.first { it.name == "calculateLiveComparison" }.apply { isAccessible = true }.invoke(service, fix(700.0, 15), fix(650.0, 14), 0L, 2, false, reference, progress)
        val delta = result.javaClass.getDeclaredField("sectorDeltaNanos").apply { isAccessible = true }.get(result)
        assertEquals(2_000_000_000L, delta)
    }
}
