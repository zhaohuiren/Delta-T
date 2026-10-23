package app.deltat.commute

import androidx.room.withTransaction
import app.deltat.commute.data.*
import app.deltat.commute.domain.LocationFix
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Local-only, user-authorized diagnostic import. No raw data is embedded in any APK.
 * Missing route config is reconstructed for viewing only, never used as a timing oracle. */
internal class RealRedesignData(private val file: File) {
    val root = JSONObject(file.readText())
    val sessions = root.getJSONArray("sessions").objects()
    val routeNames = listOf("上班", "下班")
    fun routeId(name: String) = if (name == "上班") "import-work" else "import-home"
    fun sample(name: String = "上班") = sessions.filter { it.getString("route") == name && it.getString("status") == "COMPLETED" && it.getJSONArray("checkpointPasses").length() == 4 && it.optString("quality") in listOf("A", "B") }.maxBy { it.getLong("startedAt") }
    private var cachedId: String? = null
    private var cachedTrack: List<JSONObject> = emptyList()
    fun track(s: JSONObject): List<JSONObject> {
        val id = s.getString("id")
        if (cachedId != id) {
            cachedTrack = JSONArray(File(file.parentFile, "redesign-tracks/$id.json").readText()).objects()
            cachedId = id
        }
        return cachedTrack
    }
    fun fixes(s: JSONObject): List<LocationFix> = track(s).map { p ->
        LocationFix(p.getDouble("latitude"), p.getDouble("longitude"), p.getLong("elapsedRealtimeNanos"),
            wallTimeMillis = 0, accuracyMeters = p.getDouble("accuracyMeters"), speedMetersPerSecond = p.doubleOrNull("speedMps"),
            provider = "imported-recording", isMock = false)
    }
    fun passes(s: JSONObject) = s.getJSONArray("checkpointPasses").objects().sortedBy { it.getInt("order") }
    fun sourcePoint(s: JSONObject, time: Long) = track(s).minBy { abs(it.getLong("elapsedRealtimeNanos") - time) }
    suspend fun importInto(app: DeltaTApplication) {
        app.database.clearAllTables()
        val dao = app.database.dao()
        app.database.withTransaction {
            routeNames.forEach { name ->
                val group = sessions.filter { it.getString("route") == name }
                val source = sample(name)
                val active = group.first().getString("checkpointVersionId")
                val versions = group.flatMap { listOf(it.getString("checkpointVersionId")) + it.getJSONArray("evaluations").objects().map { e -> e.getString("checkpointVersionId") } }.distinct()
                dao.insertRoute(RouteProfileEntity(routeId(name), name, if (name == "上班") "TO_WORK" else "FROM_WORK", active,
                    source.getString("id"), group.minOf { it.getLong("startedAt") }, root.getLong("exportedAt")))
                versions.forEachIndexed { i, version ->
                    dao.insertVersion(CheckpointVersionEntity(version, routeId(name), i + 1, source.getString("id"), 0, version == active, "诊断导入：路线配置由过点位置近似还原，仅供查看", 0))
                    dao.insertCheckpoints(passes(source).map { pass ->
                        val order = pass.getInt("order"); val time = pass.getLong("elapsedRealtimeNanos"); val p = sourcePoint(source, time)
                        CheckpointEntity("$version-$order", version, order, listOf("A", "S1", "S2", "B")[order],
                            p.getDouble("latitude"), p.getDouble("longitude"), 35.0, null, 100.0, 50.0, null, null)
                    })
                }
            }
            sessions.forEach { s ->
                val id = s.getString("id"); val version = s.getString("checkpointVersionId"); val start = s.getLong("startedAt")
                val duration = s.longOrNull("durationNanos"); val ps = passes(s); val quality = s.getString("quality")
                dao.insertSession(SessionEntity(id, routeId(s.getString("route")), version, "TIMED", s.getString("status"), null,
                    startedWallTimeMillis = start, bootCount = 0, startElapsedNanos = ps.firstOrNull()?.getLong("elapsedRealtimeNanos"),
                    finishElapsedNanos = ps.lastOrNull()?.takeIf { it.getInt("order") == 3 }?.getLong("elapsedRealtimeNanos"),
                    totalDurationNanos = duration, validity = if (s.getString("status") == "COMPLETED" && duration != null) "VALID" else "INVALID",
                    qualityGrade = quality, appVersion = "imported-diagnostics-v2", deviceModel = "not-exported", androidVersion = "not-exported",
                    maxSpeedMps = s.doubleOrNull("maxSpeedMps"), maxSpeedElapsedNanos = s.longOrNull("maxSpeedElapsedNanos"),
                    maxSpeedWallTimeMillis = s.longOrNull("maxSpeedWallTimeMillis"), missingCheckpointMask = s.optInt("missingCheckpointMask"),
                    timingEngineVersion = s.optInt("timingEngineVersion", 1), createdAtMillis = start))
                track(s).forEach { p -> dao.insertTrackPoint(TrackPointEntity(
                    sessionId = id, sequence = p.getLong("sequence"), latitude = p.getDouble("latitude"), longitude = p.getDouble("longitude"),
                    elapsedRealtimeNanos = p.getLong("elapsedRealtimeNanos"), wallTimeMillis = 0,
                    accuracyMeters = p.getDouble("accuracyMeters"), speedMps = p.doubleOrNull("speedMps"), speedAccuracyMps = p.doubleOrNull("speedAccuracyMps"),
                    bearingDegrees = null, bearingAccuracy = null, provider = "imported-recording", isMock = false, usedForTiming = p.getBoolean("usedForTiming"))) }
                ps.forEach { p ->
                    val order = p.getInt("order"); val time = p.getLong("elapsedRealtimeNanos"); val loc = sourcePoint(s, time)
                    dao.insertPass(CheckpointPassEntity("$id-$order", id, "$version-$order", order, time, loc.getDouble("latitude"), loc.getDouble("longitude"),
                        p.getString("triggerMode"), 0.0, p.optLong("uncertaintyMillis"), quality))
                }
                s.getJSONArray("evaluations").objects().forEach { e ->
                    val eid = e.getString("id"); val ev = e.getString("checkpointVersionId"); val valid = e.getString("status") == "VALID" && quality in listOf("A", "B")
                    val ep = e.getJSONArray("passes").objects().sortedBy { it.getInt("order") }
                    dao.insertEvaluation(SessionEvaluationEntity(eid, id, ev, e.getString("status"), e.stringOrNull("failureReason"),
                        startElapsedNanos = ep.firstOrNull()?.getLong("elapsedRealtimeNanos"),
                        finishElapsedNanos = ep.lastOrNull()?.takeIf { it.getInt("order") == 3 }?.getLong("elapsedRealtimeNanos"),
                        totalDurationNanos = e.longOrNull("durationNanos"), qualityGrade = quality, recordQualified = valid,
                        monthlyDeltaNanos = e.longOrNull("monthlyDeltaNanos"), weeklyDeltaNanos = e.longOrNull("weeklyDeltaNanos"),
                        totalResultColor = e.getString("color"), timingEngineVersion = e.optInt("timingEngineVersion", 1), missingCheckpointMask = e.optInt("missingCheckpointMask"),
                        createdAtMillis = start, updatedAtMillis = start))
                    ep.forEach { p ->
                        val order = p.getInt("order"); val time = p.getLong("elapsedRealtimeNanos"); val loc = sourcePoint(s, time)
                        dao.insertEvaluationPass(EvaluationPassEntity("$eid-$order", eid, "$ev-$order", order, time,
                            loc.getDouble("latitude"), loc.getDouble("longitude"), p.getString("triggerMode"), 0.0, 0, quality))
                    }
                    e.getJSONArray("sectors").objects().forEach { sector ->
                        val monthly = sector.longOrNull("monthlyDeltaNanos"); val weekly = sector.longOrNull("weeklyDeltaNanos")
                        val color = when { monthly != null && monthly < 0 -> "PURPLE"; weekly != null && weekly < 0 -> "GREEN"; (weekly ?: monthly ?: 0) > 0 -> "YELLOW"; else -> "NEUTRAL" }
                        dao.insertEvaluationSector(EvaluationSectorEntity(eid, sector.getInt("index"), sector.getLong("durationNanos"),
                            monthlyDeltaNanos = monthly, weeklyDeltaNanos = weekly, resultColorAtCompletion = color, recordQualified = valid))
                    }
                }
            }
        }
    }
}
internal fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
internal fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
internal fun JSONObject.doubleOrNull(key: String) = if (isNull(key)) null else getDouble(key)
internal fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else getString(key)
