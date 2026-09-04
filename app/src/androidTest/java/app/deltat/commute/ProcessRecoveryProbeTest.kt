package app.deltat.commute

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.domain.LocationFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Host invokes seed, kills target process, then invokes verify. Kept separate from Activity recreation. */
class ProcessRecoveryProbeTest {
    @Test fun twoProcessInterruptedSessionRecovery(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        assumeTrue("只在宿主进程重建脚本中运行", phase in listOf("seed", "verify"))
        assumeTrue("仅隔离模拟器", Build.MODEL.contains("sdk"))
        val app = instrumentation.targetContext.applicationContext as DeltaTApplication
        Thread.sleep(2_600)
        val prefs = app.getSharedPreferences("deep-test-probe", 0)
        val output = File(app.getExternalFilesDir(null), "deep-test").apply { mkdirs() }
        if (phase == "seed") {
            app.database.dao().insertRoute(RouteProfileEntity("process-probe-route", "恢复测试", "CUSTOM", createdAtMillis = 1, updatedAtMillis = 1))
            val session = app.repository.startSession("process-probe-route", "BASELINE_RECORDING")
            app.repository.appendFix(session.id, LocationFix(31.0, 121.0, 1_000_000_000, 1, 5.0), false)
            prefs.edit().putString("session", session.id).putInt("pid", android.os.Process.myPid()).commit()
            assertEquals("RECORDING", app.repository.session(session.id)?.status)
            File(output, "process-recovery-seed.txt").writeText("pid=${android.os.Process.myPid()}\nsession=${session.id}\n")
        } else {
            val id = requireNotNull(prefs.getString("session", null))
            assertNotEquals(prefs.getInt("pid", -1), android.os.Process.myPid())
            val session = requireNotNull(app.repository.session(id))
            assertEquals("INTERRUPTED", session.status)
            assertEquals(1, app.database.dao().track(id).size)
            assertNotEquals("VALID", session.validity)
            File(output, "process-recovery-verified.txt").writeText("oldPid=${prefs.getInt("pid", -1)}\nnewPid=${android.os.Process.myPid()}\nstatus=${session.status}\ntrackPoints=1\n")
            app.repository.deleteRoute("process-probe-route")
            prefs.edit().clear().commit()
        }
    }
}
