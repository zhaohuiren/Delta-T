package app.deltat.commute

import app.deltat.commute.domain.Checkpoint
import app.deltat.commute.domain.LocationFix
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Reads the user-authorized local diagnostic file; never embedded in the production APK. */
internal data class RealRouteFixture(
    val track: List<LocationFix>, val checkpoints: List<Checkpoint>, val passes: List<Long>,
) {
    companion object {
        fun read(file: File): RealRouteFixture {
            val sessions = JSONObject(file.readText()).getJSONArray("sessions")
            val selected = (0 until sessions.length()).map(sessions::getJSONObject)
                .filter { it.optString("status") == "COMPLETED" && it.optJSONArray("checkpointPasses")?.length() == 4 }
                .maxBy { it.getJSONArray("track").length() }
            val raw = selected.getJSONArray("track")
            val track = (0 until raw.length()).map { i -> raw.getJSONObject(i).let { p ->
                LocationFix(p.getDouble("latitude"), p.getDouble("longitude"),
                    p.getLong("elapsedRealtimeNanos"), p.optLong("wallTimeMillis"),
                    p.optDouble("accuracyMeters", 8.0), p.optDouble("speedMps", 12.0),
                    p.optDouble("bearingDegrees", 0.0), provider = "test-fixture")
            } }
            val rawPasses = selected.getJSONArray("checkpointPasses")
            val passes = (0..3).map { rawPasses.getJSONObject(it).getLong("elapsedRealtimeNanos") }
            val checkpoints = passes.mapIndexed { i, time ->
                val p = track.minBy { abs(it.elapsedRealtimeNanos - time) }
                Checkpoint("real-$i", i, listOf("A", "S1", "S2", "B")[i], p.latitude, p.longitude)
            }
            return RealRouteFixture(track, checkpoints, passes)
        }
    }
}
