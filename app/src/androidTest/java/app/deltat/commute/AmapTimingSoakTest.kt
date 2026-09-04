package app.deltat.commute

import android.os.Debug
import android.os.SystemClock
import android.view.Choreographer
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.domain.EngineStatus
import app.deltat.commute.domain.GnssHealth
import app.deltat.commute.domain.ResultColor
import app.deltat.commute.location.RuntimeSnapshot
import app.deltat.commute.location.RuntimeSector
import app.deltat.commute.ui.DeltaTTheme
import app.deltat.commute.ui.LocalMapFeatureConfig
import app.deltat.commute.ui.MapFeatureConfig
import app.deltat.commute.ui.TimingScreen
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Real Choreographer/elapsedRealtime clock, without Compose's virtual test clock. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class AmapTimingSoakTest {
    @Test fun realMapTimingMemoryAndFrameSoak() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val diagnostic = File(context.getExternalFilesDir(null), "delta-t-diagnostics.txt")
        assumeTrue("需要本地真实路线诊断包", diagnostic.isFile)
        assumeTrue("需要高德配置", BuildConfig.AMAP_AVAILABLE)
        val seconds = InstrumentationRegistry.getArguments().getString("soakSeconds")?.toInt() ?: 20
        require(seconds in 10..3_600)
        val output = File(context.getExternalFilesDir(null), "deep-test").apply { mkdirs() }
        val fixture = RealRouteFixture.read(diagnostic)
        val route = RouteProfileEntity("soak-route", "上班", "TO_WORK", "soak-version", createdAtMillis = 1, updatedAtMillis = 1)
        val started = SystemClock.elapsedRealtimeNanos()
        val origin = fixture.track.first().elapsedRealtimeNanos
        val span = (fixture.track.last().elapsedRealtimeNanos - origin).coerceAtLeast(1)
        fun rebase(time: Long) = started + ((time - origin).toDouble() / span * (seconds + 5) * 1_000_000_000).toLong()
        val points = fixture.track.map { it.copy(elapsedRealtimeNanos = rebase(it.elapsedRealtimeNanos)) }
        val passes = fixture.passes.map(::rebase)
        val state = mutableStateOf(RuntimeSnapshot(
            sessionId = "soak-session", routeId = route.id, routeName = route.name,
            mode = "TIMED", status = "S1 计时中", engineStatus = EngineStatus.SECTOR_1,
            startedAtElapsedNanos = started, routeSourceTrack = points,
            routeCheckpoints = fixture.checkpoints, gnssHealth = GnssHealth(12, 9),
            liveColor = ResultColor.NEUTRAL, currentSectorColor = ResultColor.NEUTRAL, currentSector = 1,
        ))
        val running = AtomicBoolean(true)
        val frames = AtomicLong()
        val maxFrameNs = AtomicLong()
        val frozenFrames = AtomicLong()
        var lastFrame = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (!running.get()) return
                if (lastFrame != 0L) {
                    val gap = time - lastFrame
                    maxFrameNs.updateAndGet { maxOf(it, gap) }
                    if (gap > 700_000_000L) frozenFrames.incrementAndGet()
                }
                lastFrame = time
                frames.incrementAndGet()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        var missing = 0
        var regressions = 0
        var stalls = 0
        var maxTimerError = 0L
        var previousTimer: Long? = null
        var rows = 0
        val csv = File(output, "soak-${seconds}s.csv")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activityRef: MainActivity
        try {
            scenario.onActivity { activity ->
                activityRef = activity
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    DeltaTTheme {
                        CompositionLocalProvider(LocalMapFeatureConfig provides MapFeatureConfig(true, true, true)) {
                            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                                TimingScreen(rememberNavController(), route, listOf(route), state.value, {}, {}, {})
                            }
                        }
                    }
                }
                Choreographer.getInstance().postFrameCallback(callback)
            }
            csv.bufferedWriter().use { writer ->
                writer.appendLine("elapsed_ms,timer_ms,timer_error_ms,track_points,frames,max_frame_ms,over_700ms_frames,java_used_kb,native_alloc_kb,pss_kb,private_dirty_kb")
                val end = started + seconds * 1_000_000_000L
                var nextSample = SystemClock.elapsedRealtime()
                var took20m = false
                while (SystemClock.elapsedRealtimeNanos() < end) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    val count = points.indexOfFirst { it.elapsedRealtimeNanos > now }.let { if (it < 0) points.size else maxOf(2, it) }
                    val actual = points.take(count).takeLast(4_000)
                    val confirmed = passes.filter { it <= now }
                    onMain {
                        state.value = state.value.copy(
                            elapsedNanos = now - started, track = actual, lastFix = actual.last(),
                            actualPassElapsedNanos = confirmed, actualPassOrderIndices = confirmed.indices.toList(),
                            currentSector = confirmed.size.coerceIn(1, 3),
                            sectors = confirmed.zipWithNext().mapIndexed { index, (a, b) -> RuntimeSector(index + 1, b - a) },
                            engineStatus = when (confirmed.size) { 0, 1 -> EngineStatus.SECTOR_1; 2 -> EngineStatus.SECTOR_2; else -> EngineStatus.SECTOR_3 },
                        )
                    }
                    val timer = onMain { findRoots(activityRef.window.decorView).firstNotNullOfOrNull { findTimer(it.semanticsOwner.unmergedRootSemanticsNode) } }
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
                    val error = timer?.let { abs(it - elapsedMs) }
                    if (elapsedMs > 10_000) {
                        if (timer == null) missing++ else {
                            if (previousTimer != null && timer < previousTimer!!) regressions++
                            if (previousTimer == timer) stalls++
                            maxTimerError = maxOf(maxTimerError, error!!)
                        }
                    }
                    previousTimer = timer
                    val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                    val javaKb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_024
                    writer.appendLine("$elapsedMs,${timer ?: -1},${error ?: -1},${actual.size},${frames.get()},${maxFrameNs.get() / 1_000_000.0},${frozenFrames.get()},$javaKb,${Debug.getNativeHeapAllocatedSize() / 1_024},${memory.totalPss},${memory.totalPrivateDirty}")
                    writer.flush()
                    rows++
                    if (rows == 12) screenshot(File(output, "amap-timing-early.png"))
                    if (!took20m && elapsedMs >= 1_200_000) { screenshot(File(output, "amap-timing-20m.png")); took20m = true }
                    nextSample += 1_000
                    Thread.sleep((nextSample - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                }
            }
            screenshot(File(output, "amap-timing-${seconds}s.png"))
            File(output, "soak-${seconds}s-summary.txt").writeText(
                "durationSeconds=$seconds\nrows=$rows\nframes=${frames.get()}\nmaxFrameMs=${maxFrameNs.get() / 1_000_000.0}\nover700msFrames=${frozenFrames.get()}\nmissingTimer=$missing\nregressions=$regressions\nstalledSamples=$stalls\nmaxTimerErrorMs=$maxTimerError\n"
            )
            assertTrue("至少采集90%的逐秒样本", rows >= seconds * .9)
            assertEquals("真实计时文本不能消失", 0, missing)
            assertEquals("计时不能倒退", 0, regressions)
            assertEquals("一秒采样不应仍显示同一计时", 0, stalls)
            assertTrue("显示计时误差不得超过2秒，实际 $maxTimerError", maxTimerError < 2_000)
        } finally {
            running.set(false)
            onMain {
                Choreographer.getInstance().removeFrameCallback(callback)
                activityRef.setContent { }
            }
            scenario.close()
        }
    }

    private fun screenshot(file: File) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun findTimer(node: SemanticsNode): Long? {
        val text = if (node.config.getOrNull(SemanticsProperties.TestTag) == "live-timer")
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty() else ""
        val match = Regex("^(\\d+):(\\d{2})\\.(\\d{2})$").matchEntire(text)
        if (match != null) return match.groupValues[1].toLong() * 60_000 + match.groupValues[2].toLong() * 1_000 + match.groupValues[3].toLong() * 10
        for (child in node.children) {
            val found = findTimer(child)
            if (found != null) return found
        }
        return null
    }

    private fun findRoots(view: View): List<RootForTest> = buildList {
        if (view is RootForTest) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(findRoots(view.getChildAt(i)))
    }

    private fun <T> onMain(block: () -> T): T {
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        Handler(Looper.getMainLooper()).post { result = runCatching(block); done.countDown() }
        check(done.await(10, TimeUnit.SECONDS)) { "主线程超过10秒未响应" }
        return requireNotNull(result).getOrThrow()
    }
}
