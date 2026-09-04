package app.deltat.commute.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import app.deltat.commute.domain.GnssHealth
import app.deltat.commute.domain.GnssDisplayStabilizer
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.LocationSource

class AndroidGnssLocationSource(context: Context) : LocationSource, LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var onFix: ((LocationFix) -> Unit)? = null
    private var onGnss: ((GnssHealth) -> Unit)? = null
    private val displayStabilizer = GnssDisplayStabilizer()

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onStarted() {
            displayStabilizer.reset()
            onGnss?.invoke(GnssHealth(locationEnabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)))
        }

        override fun onStopped() {
            displayStabilizer.reset()
            onGnss?.invoke(GnssHealth(locationEnabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)))
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val health = satelliteHealth((0 until status.satelliteCount).map { i ->
                SatelliteSignal(status.getConstellationType(i), status.getSvid(i), status.usedInFix(i))
            })
            onGnss?.invoke(health.copy(label = displayStabilizer.update(health)))
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(onFix: (LocationFix) -> Unit, onGnss: (GnssHealth) -> Unit) {
        displayStabilizer.reset()
        this.onFix = onFix
        this.onGnss = onGnss
        onGnss(GnssHealth(locationEnabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)))
        manager.registerGnssStatusCallback(gnssCallback, android.os.Handler(Looper.getMainLooper()))
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
    }

    override fun stop() {
        manager.removeUpdates(this)
        manager.unregisterGnssStatusCallback(gnssCallback)
        onFix = null
        onGnss = null
    }

    override fun onLocationChanged(location: Location) {
        onFix?.invoke(
            LocationFix(
                latitude = location.latitude,
                longitude = location.longitude,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                wallTimeMillis = location.time,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else 999.0,
                speedMetersPerSecond = location.takeIf { it.hasSpeed() }?.speed?.toDouble(),
                speedAccuracyMetersPerSecond = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond?.toDouble(),
                bearingDegrees = location.takeIf { it.hasBearing() }?.bearing?.toDouble(),
                bearingAccuracyDegrees = if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toDouble() else null,
                provider = location.provider ?: "gps",
                isMock = if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider,
            ),
        )
    }

    override fun onProviderDisabled(provider: String) {
        val health = GnssHealth(locationEnabled = false)
        onGnss?.invoke(health.copy(label = displayStabilizer.update(health)))
    }
    override fun onProviderEnabled(provider: String) {
        val health = GnssHealth()
        onGnss?.invoke(health.copy(label = displayStabilizer.update(health)))
    }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
}

internal data class SatelliteSignal(val constellation: Int, val svid: Int, val used: Boolean)

/** Multiple tracked frequencies for one physical satellite must not inflate the count. */
internal fun satelliteHealth(signals: List<SatelliteSignal>): GnssHealth {
    val satellites = signals.groupBy { it.constellation to it.svid }
        .mapValues { (_, channels) -> channels.any { it.used } }
    fun visible(type: Int) = satellites.keys.count { it.first == type }
    fun used(type: Int) = satellites.count { it.key.first == type && it.value }
    val gpsVisible = visible(GnssStatus.CONSTELLATION_GPS)
    val gpsUsed = used(GnssStatus.CONSTELLATION_GPS)
    val beidouVisible = visible(GnssStatus.CONSTELLATION_BEIDOU)
    val beidouUsed = used(GnssStatus.CONSTELLATION_BEIDOU)
    return GnssHealth(gpsVisible, gpsUsed, beidouVisible, beidouUsed,
        satellites.size - gpsVisible - beidouVisible,
        satellites.values.count { it } - gpsUsed - beidouUsed, satelliteStatusReceived = true)
}
