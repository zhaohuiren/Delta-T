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
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.LocationSource

class AndroidGnssLocationSource(context: Context) : LocationSource, LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var onFix: ((LocationFix) -> Unit)? = null
    private var onGnss: ((GnssHealth) -> Unit)? = null

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var gpsVisible = 0; var gpsUsed = 0
            var beidouVisible = 0; var beidouUsed = 0
            var otherVisible = 0; var otherUsed = 0
            for (i in 0 until status.satelliteCount) {
                val used = status.usedInFix(i)
                when (status.getConstellationType(i)) {
                    GnssStatus.CONSTELLATION_GPS -> { gpsVisible++; if (used) gpsUsed++ }
                    GnssStatus.CONSTELLATION_BEIDOU -> { beidouVisible++; if (used) beidouUsed++ }
                    else -> { otherVisible++; if (used) otherUsed++ }
                }
            }
            val totalUsed = gpsUsed + beidouUsed + otherUsed
            onGnss?.invoke(
                GnssHealth(gpsVisible, gpsUsed, beidouVisible, beidouUsed, otherVisible, otherUsed,
                    when { totalUsed >= 8 -> "GNSS 良好"; totalUsed >= 4 -> "GNSS 一般"; else -> "正在提高精度" }),
            )
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(onFix: (LocationFix) -> Unit, onGnss: (GnssHealth) -> Unit) {
        this.onFix = onFix
        this.onGnss = onGnss
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

    override fun onProviderDisabled(provider: String) = onGnss?.invoke(GnssHealth(label = "系统定位已关闭")) ?: Unit
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
}
