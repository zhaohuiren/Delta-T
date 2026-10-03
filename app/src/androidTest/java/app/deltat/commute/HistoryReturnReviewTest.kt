package app.deltat.commute

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.data.*
import app.deltat.commute.domain.*
import app.deltat.commute.location.*
import app.deltat.commute.ui.*
import com.amap.api.maps.MapView
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Collections

/** Read-only diagnostic: replace this ViewModel's display flows, never persist fixture rows. */
class HistoryReturnReviewTest {

    @Test fun captureProductionNavigationAndStatsWithDisplayFixtures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val staticClock = InstrumentationRegistry.getArguments().getString("staticClock") == "true"
        val output = File(context.getExternalFilesDir(null), if (staticClock) "integrated-r2-static-control" else "integrated-r2-review").apply { mkdirs() }
        val fixture = RealRouteFixture.read(File(context.getExternalFilesDir(null), "delta-t-diagnostics.txt"))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val oldRuntime = RuntimeBus.state.value
        val work = RouteProfileEntity("review-work", "上班", "TO_WORK", "review-v", "review-best", 1, 1)
        val home = work.copy(id = "review-home", name = "下班", directionType = "FROM_WORK")
        val base = 1_788_192_000_000L // fixed September 2026 fixture timestamps, independent of wall clock
        val totals = listOf(1722700L, 1694300L, 1678100L, 1740100L, 1705000L, 1829200L, 1689200L)
        val rows = totals.mapIndexed { i, ms ->
            LeaderboardRow("review-$i", work.id, work.name, base + (i / 2) * 86_400_000L + (i % 2) * 3_600_000L,
                ms * 1_000_000L, "A", ms * 300_000L, ms * 350_000L, ms * 350_000L)
        }
        val sessions = (0 until 2_000).map { i ->
            val row = rows[i % rows.size]
            SessionListRow(id = "history-$i", routeProfileId = work.id, routeName = work.name,
                startedWallTimeMillis = base - i * 43_200_000L, totalDurationNanos = row.totalDurationNanos,
                status = "COMPLETED", validity = "VALID", qualityGrade = "A", referenceSessionIdSnapshot = null,
                checkpointVersionId = "review-v", maxSpeedMps = null, maxSpeedElapsedNanos = null, maxSpeedWallTimeMillis = null)
        }
        val midpoint = fixture.passes[1] + (fixture.passes[2] - fixture.passes[1]) / 2
        val actual = fixture.track.takeWhile { it.elapsedRealtimeNanos <= midpoint }
        val frames = Collections.synchronizedList(mutableListOf<String>())
        var phase = "setup"
        val handlerThread = HandlerThread("review-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metric, dropped ->
            frames.add("$phase,${metric.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0},$dropped")
        }
        val log = File(output, "capture-metadata.txt")
        log.writeText("Production NavHost + screens. In-memory display flows only, 2000 history rows; 7 statistical trips. No fixture DB writes. staticClockControl=$staticClock\n")
        try {
            scenario.onActivity { activity ->
                val vm = AppViewModel(activity.application)
                fun replace(name: String, value: Any) = AppViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
                replace("routes", MutableStateFlow(listOf(work, home)))
                replace("sessions", MutableStateFlow(sessions))
                replace("leaderboard", MutableStateFlow(rows))
                replace("amapPrivacyAgreed", MutableStateFlow(true))
                replace("amapPrivacyPrompted", MutableStateFlow(true))
                RuntimeBus.update { RuntimeSnapshot(sessionId = "review-active", routeId = work.id, routeName = work.name,
                    mode = "TIMED", status = "S2 计时中", engineStatus = EngineStatus.SECTOR_2, currentSector = 2,
                    startedAtElapsedNanos = if (staticClock) null else SystemClock.elapsedRealtimeNanos() - 1_038_650_000_000L,
                    elapsedNanos = 1_038_650_000_000L, routeSourceTrack = fixture.track, routeCheckpoints = fixture.checkpoints,
                    track = actual, lastFix = actual.last(), actualPassElapsedNanos = fixture.passes.take(2),
                    gnssHealth = GnssHealth(48, 28), maxSpeedMps = 77.3 / 3.6) }
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper))
                activity.setContent { DeltaTTheme { DeltaTApp(vm, true) { it() } } }
            }
            fun capture(name: String) {
                Thread.sleep(500)
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                log.appendText("$name ${Instant.now()}\n")
            }
            fun click(label: String, action: () -> Unit) {
                phase = label
                val began = SystemClock.elapsedRealtime()
                action()
                Thread.sleep(650)
                log.appendText("$label action+settleMs=${SystemClock.elapsedRealtime() - began} (includes fixed 650ms, not navigation latency)\n")
            }
            fun node(label: String): AccessibilityNodeInfo? {
                fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (n == null) return null
                    if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
                    for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                    return null
                }
                return find(instrumentation.uiAutomation.rootInActiveWindow)
            }
            fun awaitLabel(label: String): AccessibilityNodeInfo {
                repeat(100) { node(label)?.let { return it }; Thread.sleep(100) }
                error("Missing visible label: $label")
            }
            fun tap(label: String) {
                val target = awaitLabel(label)
                val bounds = android.graphics.Rect().also(target::getBoundsInScreen)
                check(!bounds.isEmpty) { "Empty bounds: $label" }
                val now = SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                    val event = android.view.MotionEvent.obtain(now, now + index * 60, action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    check(instrumentation.uiAutomation.injectInputEvent(event, true))
                    event.recycle()
                }
            }
            Thread.sleep(1_000)
            awaitLabel("Delta T")
            capture("01-home-active")
            for (i in 0 until 8) {
                click("$i-home-history") { tap("历史") }
                awaitLabel("历史记录")
                if (i == 0) capture("02-history-2000")
                click("$i-history-home") { tap("首页") }
                awaitLabel("Delta T")
                click("$i-home-timing") { tap("返回计时") }
                scenario.onActivity { activity ->
                    fun find(v: View): MapView? {
                        if (v is MapView) return v
                        if (v is ViewGroup) for (j in 0 until v.childCount) find(v.getChildAt(j))?.let { return it }
                        return null
                    }
                    log.appendText("$i mapViewIdentity=${find(activity.window.decorView)?.let(System::identityHashCode)}\n")
                }
                if (i == 0) capture("03-timing-after-history")
                click("$i-timing-home") { tap("返回") }
            }
            phase = "stats"
            tap("纪录")
            tap("月统计")
            capture("04-month-seven-trips")
            tap("周统计")
            capture("05-week-seven-trips")
            File(output, "frame-metrics.csv").writeText("phase,totalDurationMs,droppedCallbacks\n" + synchronized(frames) { frames.joinToString("\n") })
        } finally {
            File(output, "frame-metrics.csv").writeText("phase,totalDurationMs,droppedCallbacks\n" + synchronized(frames) { frames.joinToString("\n") })
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            RuntimeBus.update { oldRuntime }
            handlerThread.quitSafely()
            scenario.close()
        }
    }
}
