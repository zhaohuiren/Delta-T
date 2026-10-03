package app.deltat.commute

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.data.*
import app.deltat.commute.domain.CheckpointSelection
import app.deltat.commute.domain.DefaultTimingEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HistoryRegressionTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DeltaTDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())
    @Test fun migrationFiveToSixPreservesCheckpointsAndMarksUnknownEngineExplicitly() {
        migrations.createDatabase("r3-migration", 5).apply {
            execSQL("INSERT INTO checkpoint_versions VALUES ('v','r',1,'s',1,1,'manual')")
            execSQL("INSERT INTO checkpoints VALUES ('a','v',0,'A',0.0,0.1,35.0,90.0,100.0,50.0,42)")
            close()
        }
        migrations.runMigrationsAndValidate("r3-migration", 6, true, DeltaTDatabase.MIGRATION_5_6).use { db ->
            db.query("SELECT timingEngineVersion FROM checkpoint_versions WHERE id='v'").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.query("SELECT sourceTrackPointId,sourceElapsedNanos,longitude FROM checkpoints WHERE id='a'").use { assertTrue(it.moveToFirst()); assertEquals(42, it.getLong(0)); assertTrue(it.isNull(1)); assertEquals(.1, it.getDouble(2), 0.0) }
        }
    }
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
    private inline fun <T> DeltaTDatabase.use(block: (DeltaTDatabase) -> T): T = try { block(this) } finally { close() }
    private suspend fun seed(db: DeltaTDatabase, mock: Boolean = false): List<Long> {
        val dao = db.dao()
        dao.insertRoute(RouteProfileEntity("r", "route", "TO_WORK", createdAtMillis = 1, updatedAtMillis = 1))
        dao.insertSession(session("source", mock))
        return (0..12).map { dao.insertTrackPoint(point("source", it, (it - 1) * 100.0, mock)) }
    }
    private fun session(id: String, mock: Boolean = false) = SessionEntity(id, "r", null, "TIMED", "COMPLETED", null, 1000, bootCount = 0, appVersion = "fixture", deviceModel = "test", androidVersion = "36", isMockDetected = mock, createdAtMillis = 1)
    private fun point(id: String, index: Int, x: Double, mock: Boolean = false) = TrackPointEntity(sessionId = id, sequence = index.toLong(), latitude = 0.0, longitude = x / 111195.0, elapsedRealtimeNanos = index * 8_000_000_000L, wallTimeMillis = 1000 + index * 8000L, accuracyMeters = 3.0, speedMps = 12.0, bearingDegrees = null, bearingAccuracy = null, provider = "gps", isMock = mock, usedForTiming = !mock)
    private fun selections(ids: List<Long>, fractional: Boolean = false) = listOf(1, 4, 7, 10).mapIndexed { index, i -> CheckpointSelection(ids[i], if (fractional) listOf(.2, .4, .6, .8)[index] else 0.0) }

    @Test fun fractionalSourceTimeSurvivesUpgradeAndNewEditsDoNotUpgradeAgain() = runTest {
        database().use { db ->
            val ids = seed(db); val dao = db.dao(); val recalc = HistoricalRecalculator(db)
            val request = recalc.create("source", selections(ids, true)); recalc.runPending(request.jobId)
            val before = dao.evaluation("source", request.checkpointVersionId)!!.totalDurationNanos!!
            assertEquals(76_800_000_000L, before)
            assertNull(recalc.createEngineUpgrade("r"))
            // Exercise legacy v5 recovery: only the exact segment and coordinate survived.
            db.openHelper.writableDatabase.execSQL("UPDATE checkpoint_versions SET timingEngineVersion=0 WHERE id=?", arrayOf(request.checkpointVersionId))
            db.openHelper.writableDatabase.execSQL("UPDATE checkpoints SET sourceElapsedNanos=NULL WHERE checkpointVersionId=?", arrayOf(request.checkpointVersionId))
            val upgrade = recalc.createEngineUpgrade("r")!!; recalc.runPending(upgrade)
            val active = dao.route("r")!!.activeCheckpointVersionId!!
            assertNotEquals(request.checkpointVersionId, active)
            assertEquals(before.toDouble(), dao.evaluation("source", active)!!.totalDurationNanos!!.toDouble(), 100.0)
            assertEquals(DefaultTimingEngine.TIMING_ENGINE_VERSION, dao.checkpointVersion(active)!!.timingEngineVersion)
            assertNull(recalc.createEngineUpgrade("r"))
            assertEquals(before, dao.evaluation("source", request.checkpointVersionId)!!.totalDurationNanos)
        }
    }
    @Test fun mockSourceCannotCreateAnOfficialEvaluation() = runTest {
        database().use { db ->
            val ids = seed(db, true); val recalc = HistoricalRecalculator(db)
            assertTrue(runCatching { recalc.create("source", selections(ids)) }.exceptionOrNull() is IllegalArgumentException)
            assertNull(db.dao().route("r")!!.activeCheckpointVersionId)
        }
    }
    @Test fun ambiguousLegacySourceKeepsActiveVersionAndDoesNotLoopUpgrades() = runTest {
        database().use { db ->
            val ids = seed(db); val dao = db.dao(); val recalc = HistoricalRecalculator(db)
            val request = recalc.create("source", selections(ids)); recalc.runPending(request.jobId)
            db.openHelper.writableDatabase.execSQL("UPDATE checkpoint_versions SET timingEngineVersion=0 WHERE id=?", arrayOf(request.checkpointVersionId))
            db.openHelper.writableDatabase.execSQL("UPDATE checkpoints SET sourceElapsedNanos=NULL,latitude=latitude+0.0001 WHERE checkpointVersionId=?", arrayOf(request.checkpointVersionId))
            val job = recalc.createEngineUpgrade("r")!!; recalc.runPending(job)
            assertEquals("FAILED", dao.recalculationJob(job)!!.status)
            assertEquals(request.checkpointVersionId, dao.route("r")!!.activeCheckpointVersionId)
            assertEquals("VALID", dao.evaluation("source", request.checkpointVersionId)!!.status)
            assertNull(recalc.createEngineUpgrade("r"))
        }
    }
    @Test fun missingIntermediatePointRetainsValidTotalAndOnlyAdjacentSector() = runTest {
        database().use { db ->
            val ids = seed(db); val dao = db.dao(); dao.insertSession(session("sparse"))
            listOf(400.0, 25.0, 400.0, 610.0, 650.0, 910.0).forEachIndexed { i, x -> dao.insertTrackPoint(point("sparse", i, x)) }
            val recalc = HistoricalRecalculator(db); val request = recalc.create("source", selections(ids)); recalc.runPending(request.jobId)
            val result = dao.evaluation("sparse", request.checkpointVersionId)!!
            assertEquals("VALID", result.status)
            assertEquals(2, result.missingCheckpointMask)
            assertEquals(listOf(0, 2, 3), dao.evaluationPasses(result.id).map { it.orderIndex })
            assertEquals(listOf(2), dao.evaluationSectors(result.id).map { it.sectorIndex })
            assertTrue(result.recordQualified)
        }
    }
}
