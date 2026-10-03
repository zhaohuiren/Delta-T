package app.deltat.commute

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.data.CheckpointEntity
import app.deltat.commute.data.CheckpointVersionEntity
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.domain.GnssHealth
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.LocationSource
import app.deltat.commute.location.AndroidGnssLocationSource
import app.deltat.commute.location.RuntimeBus
import app.deltat.commute.location.TimingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TimingServiceIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DeltaTApplication
    private val dao get() = app.database.dao()
    private val repository get() = app.repository
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var source: ControlledLocationSource
    private val base = SystemClock.elapsedRealtimeNanos()

    @Before fun setUpIsolatedService() {
        assumeTrue("故障测试仅运行隔离模拟器", Build.MODEL.contains("sdk"))
        activity = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(2_300) // Await application startup interruption recovery before creating a session.
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        source = ControlledLocationSource()
        app.locationSourceFactory = { source }
        RuntimeBus.reset()
        db {
            for (routeId in listOf("service-r1", "service-r2")) {
                dao.insertRoute(RouteProfileEntity(routeId, routeId, "CUSTOM", "v-$routeId", createdAtMillis = 1, updatedAtMillis = 1))
                dao.insertVersion(CheckpointVersionEntity("v-$routeId", routeId, 1, null, 1, true, "isolated-test"))
                dao.insertCheckpoints(listOf(1, 3, 5, 7).mapIndexed { i, x ->
                    CheckpointEntity("$routeId-$i", "v-$routeId", i, listOf("A", "S1", "S2", "B")[i],
                        31.0, 121.0 + x * .001, 35.0, 90.0, 100.0, 50.0, null)
                })
            }
        }
    }

    @After fun tearDown() {
        if (!::activity.isInitialized) return
        db { for (name in listOf("test_fail_track", "test_fail_pass", "test_fail_sector", "test_fail_session"))
            app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $name") }
        app.stopService(Intent(app, TimingService::class.java))
        Thread.sleep(300)
        app.locationSourceFactory = { AndroidGnssLocationSource(it) }
        RuntimeBus.reset()
        db {
            for (id in listOf("service-r1", "service-r2")) repository.deleteRoute(id)
        }
        activity.close()
    }

    @Test fun repeatedStartSwitchAndStopDoNotCreateCompetingSessions() {
        repeat(10) { command(TimingService.ACTION_ARM, "service-r1") }
        await { source.starts.get() == 1 && RuntimeBus.state.value.sessionId != null }
        val old = requireNotNull(RuntimeBus.state.value.sessionId)
        command(TimingService.ACTION_SWITCH_ROUTE, "service-r2")
        await { RuntimeBus.state.value.routeId == "service-r2" }
        assertNull(db { repository.session(old) })
        assertEquals(1, source.starts.get())
        source.emit(fix(0))
        source.emit(fix(1))
        command(TimingService.ACTION_STOP)
        repeat(100) { source.emit(fix(it + 2)) }
        await { RuntimeBus.state.value.sessionId == null && source.stops.get() > 0 }
        assertNull(db { repository.activeSession() })
        assertEquals(0, RuntimeBus.state.value.pendingFixCount)
    }

    @Test fun recordedBaselineLeavesActiveModeAndRetainsEditableSession() {
        command(TimingService.ACTION_START_BASELINE, "service-r1")
        await { RuntimeBus.state.value.sessionId != null && source.starts.get() > 0 }
        val id = RuntimeBus.state.value.sessionId!!
        for (i in 0..8) source.emit(fix(i))
        await { db { dao.track(id).size } == 9 }
        command(TimingService.ACTION_STOP_BASELINE)
        await { source.stops.get() > 0 && RuntimeBus.state.value.mode == "BASELINE_RECORDED" }
        assertEquals(id, RuntimeBus.state.value.sessionId)
        assertEquals("RECORDED", db { repository.session(id)!!.status })
        assertEquals("路线轨迹已录制", RuntimeBus.state.value.status)
        assertNull(db { repository.activeSession() })
    }

    @Test fun completedDisplayUsesInterpolatedFinishInsteadOfLastFixTime() {
        val id = start()
        for (i in 0..6) { source.emit(fix(i)); await { db { dao.track(id).size } >= i + 1 } }
        source.emit(fix(8).copy(elapsedRealtimeNanos = base + 35_000_000_000L))
        await { db { repository.session(id)?.status } == "COMPLETED" }
        val saved = db { repository.session(id)!! }
        assertEquals(saved.totalDurationNanos, RuntimeBus.state.value.elapsedNanos)
        assertEquals(saved.finishElapsedNanos!! - saved.startElapsedNanos!!, RuntimeBus.state.value.elapsedNanos)
    }

    @Test fun startingInsideAExposesExitRequirementWithoutStartingTimer() {
        val id = start()
        source.emit(fix(1))
        await { RuntimeBus.state.value.startRequiresExit }
        assertNull(RuntimeBus.state.value.startedAtElapsedNanos)
        assertTrue(db { dao.passes(id).isEmpty() })
        source.emit(fix(0).copy(elapsedRealtimeNanos = base + 20_000_000_000L))
        await { !RuntimeBus.state.value.startRequiresExit }
        assertNull(RuntimeBus.state.value.startedAtElapsedNanos)
        assertTrue(db { dao.passes(id).isEmpty() })
    }

    @Test fun mockTrackIsPersistedForDiagnosticsButCannotStartTimingOrEnterLeaderboard() {
        val id = start()
        for (i in 0..8) source.emit(fix(i).copy(isMock = true))
        await { db { dao.track(id).size } == 9 }
        assertTrue(db { repository.session(id)!!.isMockDetected })
        assertTrue(db { dao.track(id).none { it.usedForTiming } })
        assertTrue(db { dao.passes(id).isEmpty() })
        assertNull(RuntimeBus.state.value.startedAtElapsedNanos)
        command(TimingService.ACTION_STOP)
        await { RuntimeBus.state.value.sessionId == null }
        assertNotEquals("VALID", db { repository.session(id)!!.validity })
    }

    @Test fun destroyingRealServicePreservesInterruptedSessionAndStopsLocation() {
        val id = start()
        source.emit(fix(0))
        await { db { dao.track(id).size } == 1 }
        app.stopService(Intent(app, TimingService::class.java))
        await { db { repository.session(id)?.status } == "INTERRUPTED" }
        assertTrue(source.stops.get() > 0)
        assertEquals(1, db { dao.track(id).size })
    }

    @Test fun locationStartupFailureIsInterruptedAndNotificationIsRemoved() {
        source.failStart = true
        command(TimingService.ACTION_ARM, "service-r1")
        await { RuntimeBus.state.value.error?.contains("定位启动失败") == true }
        assertNull(db { repository.activeSession() })
        await {
            app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none { it.id == 4101 }
        }
        assertTrue("部分启动失败也必须清理定位注册", source.stops.get() > 0)
    }

    @Test fun trackAndPassFailuresDoNotPublishUnpersistedEngineStateAndCanRetry() {
        val id = start()
        trigger("test_fail_track", "track_points", "INSERT", "NEW.sessionId = '$id'")
        source.emit(fix(0))
        await { RuntimeBus.state.value.error != null }
        assertTrue(db { dao.track(id).isEmpty() })
        drop("test_fail_track")
        RuntimeBus.update { it.copy(error = null) }
        source.emit(fix(0))
        await { db { dao.track(id).size } == 1 }
        trigger("test_fail_pass", "checkpoint_passes", "INSERT", "NEW.sessionId = '$id'")
        for (i in 1..2) source.emit(fix(i))
        await { RuntimeBus.state.value.error != null }
        assertTrue(db { dao.passes(id).isEmpty() })
        assertNull(RuntimeBus.state.value.startedAtElapsedNanos)
        drop("test_fail_pass")
        RuntimeBus.update { it.copy(error = null) }
        source.emit(fix(2))
        await { db { dao.passes(id).isNotEmpty() } && RuntimeBus.state.value.startedAtElapsedNanos != null }
        assertNotNull(RuntimeBus.state.value.startedAtElapsedNanos)
        for (i in 3..8) source.emit(fix(i))
        await { db { repository.session(id)?.status } == "COMPLETED" }
        assertEquals(4, db { dao.passes(id).size })
        assertEquals(3, db { dao.sectors(id).size })
        await { app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none { it.id == 4101 } }
    }

    @Test fun sectorAndSessionFaultsRollBackCompletionAndAllowReprocessing() {
        val id = start()
        for (i in 0..6) { source.emit(fix(i)); await { db { dao.track(id).size } >= i + 1 } }
        trigger("test_fail_sector", "sector_results", "INSERT", "NEW.sessionId = '$id'")
        for (i in 7..8) source.emit(fix(i))
        await { RuntimeBus.state.value.error != null }
        assertNotEquals("COMPLETED", db { repository.session(id)?.status })
        assertEquals(3, db { dao.passes(id).size })
        assertEquals(0, db { dao.sectors(id).size })
        drop("test_fail_sector")
        trigger("test_fail_session", "sessions", "UPDATE", "NEW.id = '$id' AND NEW.status = 'COMPLETED'")
        RuntimeBus.update { it.copy(error = null) }
        source.emit(fix(8))
        await { RuntimeBus.state.value.error != null }
        assertNotEquals("COMPLETED", db { repository.session(id)?.status })
        drop("test_fail_session")
        RuntimeBus.update { it.copy(error = null) }
        source.emit(fix(8))
        await { db { repository.session(id)?.status } == "COMPLETED" }
        assertEquals(4, db { dao.passes(id).size })
        assertEquals(3, db { dao.sectors(id).size })
    }

    private fun start(): String {
        command(TimingService.ACTION_ARM, "service-r1")
        await { RuntimeBus.state.value.sessionId != null && source.starts.get() > 0 }
        return requireNotNull(RuntimeBus.state.value.sessionId)
    }
    private fun command(action: String, route: String? = null) {
        activity.onActivity { it.startService(Intent(it, TimingService::class.java).setAction(action).putExtra(TimingService.EXTRA_ROUTE_ID, route)) }
    }
    private fun fix(x: Int) = LocationFix(31.0, 121.0 + x * .001, base + x * 5_000_000_000L,
        System.currentTimeMillis() + x * 5_000L, 5.0, 12.0, 90.0, 3.0, "fixture", false)
    private fun trigger(name: String, table: String, event: String, condition: String) = db {
        app.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER $name BEFORE $event ON $table WHEN $condition BEGIN SELECT RAISE(ABORT, 'injected-$name'); END")
    }
    private fun drop(name: String) = db { app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER $name") }
    private fun <T> db(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8_000
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= end) fail("等待超时：${RuntimeBus.state.value}")
            Thread.sleep(20)
        }
    }
}

internal class ControlledLocationSource : LocationSource {
    @Volatile private var callback: ((LocationFix) -> Unit)? = null
    val starts = AtomicInteger()
    val stops = AtomicInteger()
    var failStart = false
    override fun start(onFix: (LocationFix) -> Unit, onGnss: (GnssHealth) -> Unit) {
        starts.incrementAndGet()
        callback = onFix
        if (failStart) throw IllegalStateException("injected-start-failure")
        onGnss(GnssHealth(12, 9))
    }
    override fun stop() { stops.incrementAndGet(); callback = null }
    fun emit(fix: LocationFix) { callback?.invoke(fix) }
}
