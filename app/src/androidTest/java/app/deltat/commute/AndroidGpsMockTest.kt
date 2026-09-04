package app.deltat.commute

import android.Manifest
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.location.AndroidGnssLocationSource
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidGpsMockTest {
    @Suppress("DEPRECATION")
    @Test fun androidSystemTestProviderReachesRealAdapterAndRetainsMockFlag() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        val received = CopyOnWriteArrayList<LocationFix>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val source = AndroidGnssLocationSource(context)
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        shell("appops set ${context.packageName} android:mock_location allow")
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            val started = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { source.start({ received += it }, {}); started.countDown() }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            repeat(3) { i ->
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location("gps").apply {
                    latitude = 31.0 + i * .001
                    longitude = 121.0
                    accuracy = 4f
                    speed = 12f
                    bearing = 0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
                Thread.sleep(1_150)
            }
            assertTrue("系统 mock 必须到达真实定位适配器", received.size >= 2)
            assertTrue(received.all { it.isMock && it.provider == "gps" })
            assertEquals(4.0, received.last().accuracyMeters, .001)
            assertEquals(12.0, received.last().speedMetersPerSecond!!, .001)
            assertTrue(received.zipWithNext().all { (a, b) -> b.elapsedRealtimeNanos > a.elapsedRealtimeNanos })
        } finally {
            val stopped = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { source.stop(); stopped.countDown() }
            stopped.await(3, TimeUnit.SECONDS)
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            shell("appops set ${context.packageName} android:mock_location default")
            scenario.close()
        }
    }
}
