package app.deltat.commute

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class R2RealRecoveryTest {
    @Test fun realTrackSurvivesProcessDeath(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val phase=InstrumentationRegistry.getArguments().getString("recoveryPhase")
        assumeTrue(phase in listOf("seed","verify")); assumeTrue(Build.MODEL.contains("sdk"))
        val app=instrumentation.targetContext.applicationContext as DeltaTApplication
        Thread.sleep(2700)
        val prefs=app.getSharedPreferences("r2-real-recovery",0)
        val out=File(app.getExternalFilesDir(null),"r2-recovery").apply{mkdirs()}
        if(phase=="seed") {
            val source=RealRedesignData(File(app.getExternalFilesDir(null),"redesign-input.json"))
            source.importInto(app)
            val session=app.repository.startSession("import-work","BASELINE_RECORDING")
            source.fixes(source.sample()).take(20).forEach { app.repository.appendFix(session.id,it,false) }
            prefs.edit().putString("id",session.id).putInt("pid",android.os.Process.myPid()).commit()
            assertEquals("RECORDING",app.repository.session(session.id)?.status)
            assertEquals(93,app.repository.sessions.first().size)
            File(out,"seed.txt").writeText("pid=${android.os.Process.myPid()}\nsource=f1dfa83f\npoints=20\n")
        } else {
            val id=requireNotNull(prefs.getString("id",null))
            assertNotEquals(prefs.getInt("pid",-1),android.os.Process.myPid())
            withTimeout(30_000) { while(app.repository.session(id)?.status=="RECORDING") delay(100) }
            assertEquals("INTERRUPTED",app.repository.session(id)?.status)
            assertEquals(20,app.database.dao().track(id).size)
            assertEquals(93,app.repository.sessions.first().size)
            File(out,"verified.txt").writeText("oldPid=${prefs.getInt("pid",-1)}\nnewPid=${android.os.Process.myPid()}\nstatus=INTERRUPTED\npoints=20\noriginalJourneys=92\n")
            app.repository.deleteSession(id)
            assertEquals(92,app.repository.sessions.first().size)
        }
    }
}
