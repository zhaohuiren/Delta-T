package app.deltat.commute

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.deltat.commute.data.DeltaTDatabase
import app.deltat.commute.data.AppRepository
import app.deltat.commute.data.CheckpointEntity
import app.deltat.commute.data.CheckpointPassEntity
import app.deltat.commute.data.CheckpointVersionEntity
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.data.SectorResultEntity
import app.deltat.commute.data.SessionEntity
import app.deltat.commute.data.TrackPointEntity
import app.deltat.commute.data.SessionEvaluationEntity
import app.deltat.commute.data.EvaluationSectorEntity
import app.deltat.commute.domain.CheckpointSelection
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class DeltaTDatabaseTest {
    @get:Rule val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DeltaTDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrationOneToTwoPreservesDataAndAddsSpeedColumns() {
        migrationHelper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO route_profiles (id,name,directionType,activeCheckpointVersionId,referenceSessionId,createdAtMillis,updatedAtMillis,archived) VALUES ('r','上班','TO_WORK',NULL,NULL,1,1,0)")
            close()
        }
        migrationHelper.runMigrationsAndValidate("migration-test", 2, true, DeltaTDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT name FROM route_profiles WHERE id='r'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("上班", cursor.getString(0))
            }
            db.query("PRAGMA table_info(sessions)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertTrue("maxSpeedMps" in names)
                assertTrue("maxSpeedElapsedNanos" in names)
                assertTrue("maxSpeedWallTimeMillis" in names)
            }
        }
    }

    @Test fun migrationTwoToThreeAddsPeriodComparisonMetadata() {
        migrationHelper.createDatabase("migration-v3-test", 2).apply {
            execSQL("INSERT INTO route_profiles (id,name,directionType,activeCheckpointVersionId,referenceSessionId,createdAtMillis,updatedAtMillis,archived) VALUES ('r','上班','TO_WORK',NULL,NULL,1,1,0)")
            close()
        }
        migrationHelper.runMigrationsAndValidate("migration-v3-test", 3, true, DeltaTDatabase.MIGRATION_2_3).use { db ->
            db.query("PRAGMA table_info(sessions)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertTrue("monthlyReferenceSessionIdSnapshot" in names)
                assertTrue("weeklyBestTotalNanos" in names)
                assertTrue("totalResultColor" in names)
                assertTrue("comparisonRulesVersion" in names)
            }
            db.query("PRAGMA table_info(sector_results)").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertTrue("monthlyBestDurationNanos" in names)
                assertTrue("weeklyDeltaNanos" in names)
            }
        }
    }

    @Test fun migrationThreeToFourPreservesOriginalScoresAndCreatesVersionedEvaluation() {
        migrationHelper.createDatabase("migration-v4-test", 3).apply {
            execSQL("INSERT INTO route_profiles (id,name,directionType,activeCheckpointVersionId,referenceSessionId,createdAtMillis,updatedAtMillis,archived) VALUES ('r','上班','TO_WORK','v','s',1,1,0)")
            execSQL("INSERT INTO checkpoint_versions (id,routeProfileId,versionNumber,createdFromSessionId,createdAtMillis,active,changeReason) VALUES ('v','r',1,'s',1,1,'test')")
            execSQL("INSERT INTO checkpoints (id,checkpointVersionId,orderIndex,label,latitude,longitude,radiusMeters,preferredBearing,bearingTolerance,gateHalfWidthMeters,sourceTrackPointId) VALUES ('cp','v',0,'A',31.0,121.0,35.0,NULL,100.0,50.0,NULL)")
            execSQL("INSERT INTO sessions (id,routeProfileId,checkpointVersionId,mode,status,startedWallTimeMillis,bootCount,startElapsedNanos,finishElapsedNanos,totalDurationNanos,validity,qualityGrade,appVersion,deviceModel,androidVersion,isMockDetected,totalResultColor,comparisonRulesVersion,createdAtMillis) VALUES ('s','r','v','TIMED','COMPLETED',1000,0,10,110,100,'VALID','A','0.2.2','test','16',0,'PURPLE',2,1000)")
            execSQL("INSERT INTO checkpoint_passes (id,sessionId,checkpointId,orderIndex,passElapsedNanos,interpolatedLatitude,interpolatedLongitude,triggerMode,closestDistanceMeters,estimatedUncertaintyMillis,confidence) VALUES ('p','s','cp',0,10,31.0,121.0,'LINE_CROSSING',0.0,100,'A')")
            execSQL("INSERT INTO sector_results (sessionId,sectorIndex,durationNanos,referenceDurationNanos,previousBestDurationNanos,deltaToReferenceNanos,resultColorAtCompletion,recordQualified,monthlyBestDurationNanos,weeklyBestDurationNanos,monthlyDeltaNanos,weeklyDeltaNanos,comparisonRulesVersion) VALUES ('s',0,100,NULL,NULL,NULL,'PURPLE',1,NULL,NULL,NULL,NULL,2)")
            close()
        }
        migrationHelper.runMigrationsAndValidate("migration-v4-test", 4, true, DeltaTDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT sessionId,checkpointVersionId,totalDurationNanos,totalResultColor FROM session_evaluations WHERE id='legacy-s'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("s", cursor.getString(0))
                assertEquals("v", cursor.getString(1))
                assertEquals(100L, cursor.getLong(2))
                assertEquals("PURPLE", cursor.getString(3))
            }
            db.query("SELECT COUNT(*) FROM evaluation_passes WHERE evaluationId='legacy-s'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM evaluation_sectors WHERE evaluationId='legacy-s'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT totalDurationNanos FROM sessions WHERE id='s'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(100L, cursor.getLong(0))
            }
        }
    }

    @Test fun migrationFourToFivePreservesRowsAndAddsSparsePassMetadata() {
        migrationHelper.createDatabase("migration-v5-test", 4).apply {
            execSQL("INSERT INTO route_profiles (id,name,directionType,activeCheckpointVersionId,referenceSessionId,createdAtMillis,updatedAtMillis,archived) VALUES ('r','上班','TO_WORK','v',NULL,1,1,0)")
            execSQL("INSERT INTO checkpoint_versions (id,routeProfileId,versionNumber,createdFromSessionId,createdAtMillis,active,changeReason) VALUES ('v','r',1,NULL,1,1,'test')")
            execSQL("INSERT INTO sessions (id,routeProfileId,checkpointVersionId,mode,status,startedWallTimeMillis,bootCount,totalDurationNanos,validity,qualityGrade,appVersion,deviceModel,androidVersion,isMockDetected,totalResultColor,comparisonRulesVersion,createdAtMillis) VALUES ('s','r','v','TIMED','COMPLETED',1000,0,100,'VALID','A','0.4','test','16',0,'NEUTRAL',2,1000)")
            execSQL("INSERT INTO session_evaluations (id,sessionId,checkpointVersionId,status,totalDurationNanos,qualityGrade,recordQualified,totalResultColor,comparisonRulesVersion,timingEngineVersion,createdAtMillis,updatedAtMillis) VALUES ('e','s','v','VALID',100,'A',1,'NEUTRAL',2,1,1000,1000)")
            close()
        }
        migrationHelper.runMigrationsAndValidate("migration-v5-test", 5, true, DeltaTDatabase.MIGRATION_4_5).use { db ->
            db.query("SELECT missingCheckpointMask,timingEngineVersion FROM sessions WHERE id='s'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
            }
            db.query("SELECT missingCheckpointMask,timingEngineVersion FROM session_evaluations WHERE id='e'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
            }
        }
    }

    @Test fun routeRoundTrip() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        val route = RouteProfileEntity("r", "上班", "TO_WORK", createdAtMillis = 1, updatedAtMillis = 1)
        db.dao().insertRoute(route)
        assertEquals(route, db.dao().route("r"))
        db.close()
    }

    @Test fun historyRowsHaveStableTieBreakOrdering() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        val dao = db.dao()
        dao.insertRoute(RouteProfileEntity("r", "上班", "TO_WORK", createdAtMillis = 1, updatedAtMillis = 1))
        suspend fun insert(id: String, createdAt: Long) {
            dao.insertSession(
                SessionEntity(
                    id = id, routeProfileId = "r", checkpointVersionId = null, mode = "TIMED", status = "COMPLETED",
                    referenceSessionIdSnapshot = null, startedWallTimeMillis = 100, bootCount = 0,
                    appVersion = "test", deviceModel = "test", androidVersion = "16", createdAtMillis = createdAt,
                ),
            )
        }
        insert("a", 1)
        insert("b", 2)
        insert("c", 2)

        assertEquals(listOf("c", "b", "a"), dao.observeSessionList().first().map { it.id })
        db.close()
    }

    @Test fun appendFixIsAtomicAndMockPointsAreNeverUsedForTiming() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        val dao = db.dao()
        dao.insertRoute(RouteProfileEntity("r", "上班", "TO_WORK", createdAtMillis = 1, updatedAtMillis = 1))
        dao.insertSession(
            SessionEntity(
                "s", "r", null, "TIMED", "ARMED", null, 1, bootCount = 0,
                appVersion = "test", deviceModel = "test", androidVersion = "16", createdAtMillis = 1,
            ),
        )
        val repository = AppRepository(db)
        coroutineScope {
            repeat(20) { index ->
                launch {
                    repository.appendFix(
                        "s",
                        app.deltat.commute.domain.LocationFix(31.0, 121.0 + index * .00001, index.toLong(), index.toLong(), 5.0),
                        usedForTiming = true,
                    )
                }
            }
        }
        repository.appendFix(
            "s",
            app.deltat.commute.domain.LocationFix(31.0, 121.1, 100, 100, 5.0, isMock = true),
            usedForTiming = true,
        )

        val track = dao.track("s")
        assertEquals((0L..20L).toList(), track.map { it.sequence })
        assertEquals(false, track.last().usedForTiming)
        assertEquals(true, dao.session("s")?.isMockDetected)
        db.close()
    }

    @Test fun leaderboardQueryUsesActiveVersionAndExcludesQualityC() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        val dao = db.dao()
        dao.insertRoute(RouteProfileEntity("r", "上班", "TO_WORK", activeCheckpointVersionId = "v", createdAtMillis = 1, updatedAtMillis = 1))
        suspend fun insert(id: String, total: Long, quality: String, qualified: Boolean) {
            dao.insertSession(SessionEntity(id, "r", "v", "TIMED", "COMPLETED", null, 1, bootCount = 0, appVersion = "test", deviceModel = "test", androidVersion = "16", createdAtMillis = 1))
            dao.insertEvaluation(SessionEvaluationEntity("e-$id", id, "v", "VALID", totalDurationNanos = total, qualityGrade = quality, recordQualified = qualified, createdAtMillis = 1, updatedAtMillis = 1))
            dao.insertEvaluationSector(EvaluationSectorEntity("e-$id", 0, total / 3, recordQualified = qualified))
        }
        insert("fast", 90, "B", true)
        insert("slow", 100, "A", true)
        insert("bad", 80, "C", false)

        val rows = dao.observeLeaderboard().first()
        assertEquals(listOf("fast", "slow"), rows.map { it.sessionId })
        assertEquals(30L, rows.first().sector1Nanos)
        db.close()
    }

    @Test fun timingBenchmarksKeepIndependentSectorReferenceTrips() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        val dao = db.dao()
        val now = System.currentTimeMillis()
        dao.insertRoute(RouteProfileEntity("r", "上班", "TO_WORK", activeCheckpointVersionId = "v", createdAtMillis = 1, updatedAtMillis = 1))
        suspend fun insert(id: String, total: Long, sectors: List<Long>, started: Long) {
            dao.insertSession(SessionEntity(id, "r", "v", "TIMED", "COMPLETED", null, started, totalDurationNanos = total, validity = "VALID", qualityGrade = "A", bootCount = 0, appVersion = "test", deviceModel = "test", androidVersion = "16", createdAtMillis = started))
            dao.insertEvaluation(SessionEvaluationEntity("e-$id", id, "v", "VALID", totalDurationNanos = total, qualityGrade = "A", recordQualified = true, timingEngineVersion = 3, createdAtMillis = started, updatedAtMillis = started))
            sectors.forEachIndexed { index, duration -> dao.insertEvaluationSector(EvaluationSectorEntity("e-$id", index, duration, recordQualified = true)) }
        }
        insert("best-s1", 100, listOf(10, 50, 40), now - 2_000)
        insert("best-s2", 110, listOf(20, 30, 60), now - 1_000)

        val benchmarks = requireNotNull(AppRepository(db).comparisonBenchmarksForTiming("r", now))
        assertEquals("best-s1", benchmarks.monthlySectorReferences[0]?.session?.id)
        assertEquals("best-s2", benchmarks.monthlySectorReferences[1]?.session?.id)
        assertEquals(listOf(10L, 30L, 40L), benchmarks.monthlySectorBestNanos)
        db.close()
    }

    @Test fun activeRouteSourceTrackCannotBeDeletedIndividually() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        db.dao().insertRoute(RouteProfileEntity("r", "上班", "TO_WORK", referenceSessionId = "s", createdAtMillis = 1, updatedAtMillis = 1))
        db.dao().insertSession(
            SessionEntity(
                id = "s", routeProfileId = "r", checkpointVersionId = null, mode = "TIMED", status = "COMPLETED",
                referenceSessionIdSnapshot = null, startedWallTimeMillis = 1, bootCount = 0,
                appVersion = "test", deviceModel = "test", androidVersion = "16", createdAtMillis = 1,
            ),
        )
        assertTrue(runCatching { AppRepository(db).deleteSession("s") }.isFailure)
        assertEquals("s", db.dao().route("r")?.referenceSessionId)
        assertTrue(db.dao().session("s") != null)
        db.close()
    }

    @Test fun deletingRouteCascadesThroughRecordedData() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        val dao = db.dao()
        dao.insertRoute(RouteProfileEntity("r", "上班", "TO_WORK", activeCheckpointVersionId = "v", referenceSessionId = "s", createdAtMillis = 1, updatedAtMillis = 1))
        dao.insertVersion(CheckpointVersionEntity("v", "r", 1, "s", 1, true, "test"))
        dao.insertCheckpoints(listOf(CheckpointEntity("cp", "v", 0, "A", 31.0, 121.0, 35.0, null, 100.0, 50.0, null)))
        dao.insertSession(
            SessionEntity(
                id = "s", routeProfileId = "r", checkpointVersionId = "v", mode = "TIMED", status = "COMPLETED",
                referenceSessionIdSnapshot = null, startedWallTimeMillis = 1, bootCount = 0,
                appVersion = "test", deviceModel = "test", androidVersion = "16", createdAtMillis = 1,
            ),
        )
        dao.insertTrackPoint(TrackPointEntity(sessionId = "s", sequence = 0, latitude = 31.0, longitude = 121.0, elapsedRealtimeNanos = 1, wallTimeMillis = 1, accuracyMeters = 5.0, speedMps = null, bearingDegrees = null, bearingAccuracy = null, provider = "gps", isMock = false, usedForTiming = true))
        dao.insertPass(CheckpointPassEntity("p", "s", "cp", 0, 1, 31.0, 121.0, "MANUAL_REVIEW", 0.0, 500, "B"))
        dao.insertSector(SectorResultEntity("s", 0, 10, null, null, null, "NEUTRAL", true))

        AppRepository(db).deleteRoute("r")

        assertEquals(null, dao.route("r"))
        assertEquals(null, dao.session("s"))
        assertTrue(dao.track("s").isEmpty())
        assertTrue(dao.checkpoints("v").isEmpty())
        assertTrue(dao.passes("s").isEmpty())
        assertTrue(dao.sectors("s").isEmpty())
        db.close()
    }

    @Test fun activeRouteCannotBeDeleted() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        db.dao().insertRoute(RouteProfileEntity("r", "上班", "TO_WORK", createdAtMillis = 1, updatedAtMillis = 1))
        db.dao().insertSession(
            SessionEntity(
                id = "s", routeProfileId = "r", checkpointVersionId = null, mode = "TIMED", status = "ARMED",
                referenceSessionIdSnapshot = null, startedWallTimeMillis = 1, bootCount = 0,
                appVersion = "test", deviceModel = "test", androidVersion = "16", createdAtMillis = 1,
            ),
        )

        assertTrue(runCatching { AppRepository(db).deleteRoute("r") }.isFailure)
        assertTrue(db.dao().route("r") != null)
        assertTrue(db.dao().session("s") != null)
        db.close()
    }

    @Test fun currentRecordsExcludeOldCheckpointVersions() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        val dao = db.dao()
        dao.insertRoute(RouteProfileEntity("r", "上班", "TO_WORK", activeCheckpointVersionId = "v2", createdAtMillis = 1, updatedAtMillis = 2))
        dao.insertSession(
            SessionEntity(
                id = "old", routeProfileId = "r", checkpointVersionId = "v1", mode = "TIMED", status = "COMPLETED",
                referenceSessionIdSnapshot = null, startedWallTimeMillis = 1, totalDurationNanos = 80,
                validity = "VALID", bootCount = 0, appVersion = "test", deviceModel = "test", androidVersion = "16", createdAtMillis = 1,
            ),
        )
        dao.insertSession(
            SessionEntity(
                id = "new", routeProfileId = "r", checkpointVersionId = "v2", mode = "TIMED", status = "COMPLETED",
                referenceSessionIdSnapshot = null, startedWallTimeMillis = 2, totalDurationNanos = 100,
                validity = "VALID", bootCount = 0, appVersion = "test", deviceModel = "test", androidVersion = "16", createdAtMillis = 2,
            ),
        )
        dao.insertSector(SectorResultEntity("old", 0, 20, null, null, null, "PURPLE", true))
        dao.insertSector(SectorResultEntity("new", 0, 30, null, null, null, "NEUTRAL", true))
        dao.insertEvaluation(SessionEvaluationEntity("e-new", "new", "v2", "VALID", totalDurationNanos = 100, qualityGrade = "A", recordQualified = true, timingEngineVersion = 3, createdAtMillis = 2, updatedAtMillis = 2))
        dao.insertEvaluationSector(EvaluationSectorEntity("e-new", 0, 30, recordQualified = true))

        assertEquals(30L, AppRepository(db).sectorBests("r").single().durationNanos)
        assertEquals(listOf("new"), AppRepository(db).comparableSessions("r").map { it.id })
        db.close()
    }

    @Test fun movingCheckpointsRecalculatesAllTracksAndActivatesOnlyAfterCompletion() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        val dao = db.dao()
        dao.insertRoute(RouteProfileEntity("r", "上班", "TO_WORK", activeCheckpointVersionId = "v1", referenceSessionId = "source", createdAtMillis = 1, updatedAtMillis = 1))
        dao.insertVersion(CheckpointVersionEntity("v1", "r", 1, "source", 1, true, "original"))

        suspend fun insertRun(id: String, startedAt: Long, stepSeconds: Long): List<Long> {
            dao.insertSession(
                SessionEntity(
                    id = id, routeProfileId = "r", checkpointVersionId = "v1", mode = "TIMED", status = "COMPLETED",
                    referenceSessionIdSnapshot = null, startedWallTimeMillis = startedAt, totalDurationNanos = 60_000_000_000,
                    validity = "VALID", qualityGrade = "A", bootCount = 0, appVersion = "test", deviceModel = "test",
                    androidVersion = "16", createdAtMillis = startedAt,
                ),
            )
            return (0..8).map { index ->
                dao.insertTrackPoint(
                    TrackPointEntity(
                        sessionId = id, sequence = index.toLong(), latitude = 31.0, longitude = 121.0 + index * 0.001,
                        elapsedRealtimeNanos = index * stepSeconds * 1_000_000_000L,
                        wallTimeMillis = startedAt + index * stepSeconds * 1_000L, accuracyMeters = 5.0,
                        speedMps = 12.0, bearingDegrees = 90.0, bearingAccuracy = 3.0, provider = "gps",
                        isMock = false, usedForTiming = true,
                    ),
                )
            }
        }

        val sourcePoints = insertRun("source", 1_000, 10)
        insertRun("history", 2_000, 8)
        dao.insertEvaluation(SessionEvaluationEntity("old-source", "source", "v1", "VALID", totalDurationNanos = 60_000_000_000, qualityGrade = "A", recordQualified = true, createdAtMillis = 1_000, updatedAtMillis = 1_000))
        val selections = listOf(1, 3, 5, 7).map { CheckpointSelection(sourcePoints[it], 0.0) }
        val repository = AppRepository(db)

        val preview = repository.previewCheckpointSelections("source", selections)
        assertEquals(2, preview.totalSessions)
        assertEquals(2, preview.successfulSessions)

        val request = repository.saveCheckpointSelections("source", selections)
        assertEquals("v1", dao.route("r")?.activeCheckpointVersionId)
        assertEquals(false, dao.checkpointVersion(request.checkpointVersionId)?.active)

        repository.runPendingRecalculations(request.jobId)

        assertEquals(request.checkpointVersionId, dao.route("r")?.activeCheckpointVersionId)
        assertEquals(true, dao.checkpointVersion(request.checkpointVersionId)?.active)
        assertEquals("VALID", dao.evaluation("source", request.checkpointVersionId)?.status)
        assertEquals("VALID", dao.evaluation("history", request.checkpointVersionId)?.status)
        assertEquals(4, dao.evaluationPasses(requireNotNull(dao.evaluation("history", request.checkpointVersionId)).id).size)
        assertEquals(3, dao.evaluationSectors(requireNotNull(dao.evaluation("history", request.checkpointVersionId)).id).size)
        assertEquals("COMPLETED", dao.recalculationJob(request.jobId)?.status)
        assertTrue(dao.evaluation("source", "v1") != null)
        db.close()
    }

    @Test fun engineV3UpgradeKeepsOriginalRowsAndSwitchesCheckpointVersionOnlyAfterReplay() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        val dao = db.dao()
        dao.insertRoute(RouteProfileEntity("r", "上班", "TO_WORK", activeCheckpointVersionId = "v2", referenceSessionId = "source", createdAtMillis = 1, updatedAtMillis = 1))
        dao.insertVersion(CheckpointVersionEntity("v2", "r", 1, "source", 1, true, "旧计时引擎"))
        dao.insertSession(
            SessionEntity(
                id = "source", routeProfileId = "r", checkpointVersionId = "v2", mode = "BASELINE_RECORDING", status = "RECORDED",
                referenceSessionIdSnapshot = null, startedWallTimeMillis = 1, bootCount = 0,
                appVersion = "0.5.0", deviceModel = "test", androidVersion = "16", createdAtMillis = 1,
            ),
        )
        val pointIds = (0..7).map { index ->
            dao.insertTrackPoint(
                TrackPointEntity(
                    sessionId = "source", sequence = index.toLong(), latitude = 31.0, longitude = 121.0 + index * .001,
                    elapsedRealtimeNanos = index * 10_000_000_000L, wallTimeMillis = index * 10_000L,
                    accuracyMeters = 5.0, speedMps = 12.0, bearingDegrees = 90.0, bearingAccuracy = 3.0,
                    provider = "gps", isMock = false, usedForTiming = true,
                ),
            )
        }
        val selected = listOf(0, 2, 4, 7)
        dao.insertCheckpoints(selected.mapIndexed { order, pointIndex ->
            CheckpointEntity(
                id = "old-$order", checkpointVersionId = "v2", orderIndex = order,
                label = listOf("A", "S1", "S2", "B")[order], latitude = 31.0, longitude = 121.0 + pointIndex * .001,
                radiusMeters = 35.0, preferredBearing = 90.0, bearingTolerance = 100.0, gateHalfWidthMeters = 50.0,
                sourceTrackPointId = pointIds[pointIndex],
            )
        })
        dao.insertEvaluation(
            SessionEvaluationEntity(
                id = "original-evaluation", sessionId = "source", checkpointVersionId = "v2", status = "VALID",
                totalDurationNanos = 70_000_000_000L, qualityGrade = "B", recordQualified = true,
                timingEngineVersion = 2, createdAtMillis = 1, updatedAtMillis = 1,
            ),
        )
        val repository = AppRepository(db)

        assertEquals(8, repository.routeSourceTrack("r").size)
        repository.prepareTimingEngineV3Upgrade()
        assertEquals("v2", dao.route("r")?.activeCheckpointVersionId)

        repository.runPendingRecalculations()

        val upgradedVersion = requireNotNull(dao.route("r")?.activeCheckpointVersionId)
        assertTrue(upgradedVersion != "v2")
        assertEquals(3, dao.evaluation("source", upgradedVersion)?.timingEngineVersion)
        assertTrue(dao.evaluation("source", "v2") != null)
        assertEquals(8, dao.track("source").size)
        assertEquals("source", dao.route("r")?.referenceSessionId)
        db.close()
    }

    @Test fun historyBackfillUsesPriorMonthAndClippedWeekBenchmarks() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        val dao = db.dao()
        dao.insertRoute(RouteProfileEntity("r", "上班", "TO_WORK", activeCheckpointVersionId = "v", createdAtMillis = 1, updatedAtMillis = 1))
        fun time(day: Int): Long = ZonedDateTime.of(2026, 7, day, 8, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        suspend fun insertRun(id: String, day: Int, total: Long) {
            dao.insertSession(
                SessionEntity(
                    id = id, routeProfileId = "r", checkpointVersionId = "v", mode = "TIMED", status = "COMPLETED",
                    referenceSessionIdSnapshot = null, startedWallTimeMillis = time(day), totalDurationNanos = total,
                    validity = "VALID", qualityGrade = "A", bootCount = 0, appVersion = "test",
                    deviceModel = "test", androidVersion = "16", comparisonRulesVersion = 0, createdAtMillis = time(day),
                ),
            )
            dao.insertSector(SectorResultEntity(id, 0, total, null, null, null, "NEUTRAL", true, comparisonRulesVersion = 0))
        }
        insertRun("month-best", 1, 100_000_000_000L)
        insertRun("week-first", 6, 120_000_000_000L)
        insertRun("week-improvement", 7, 110_000_000_000L)

        AppRepository(db).rebuildComparisonHistory()

        assertEquals("NEUTRAL", dao.session("month-best")?.totalResultColor)
        assertEquals("YELLOW", dao.session("week-first")?.totalResultColor)
        assertEquals("GREEN", dao.session("week-improvement")?.totalResultColor)
        assertEquals(10_000_000_000L, dao.session("week-improvement")?.weeklyDeltaNanos?.let { -it })
        assertEquals("GREEN", dao.sectors("week-improvement").single().resultColorAtCompletion)
        assertEquals(3, dao.session("week-improvement")?.comparisonRulesVersion)
        assertEquals(3, dao.sectors("week-improvement").single().comparisonRulesVersion)

        val snapshot = dao.session("week-improvement")
        AppRepository(db).rebuildComparisonHistory()
        assertEquals(snapshot, dao.session("week-improvement"))
        db.close()
    }

    @Test fun diagnosticsExportsIncludeCheckpointVersionAndFilteredSpeedPeak() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DeltaTDatabase::class.java).build()
        val dao = db.dao()
        dao.insertRoute(
            RouteProfileEntity(
                id = "r", name = "上班", directionType = "TO_WORK",
                activeCheckpointVersionId = "v2", createdAtMillis = 1, updatedAtMillis = 2,
            ),
        )
        dao.insertSession(
            SessionEntity(
                id = "s", routeProfileId = "r", checkpointVersionId = "v2", mode = "TIMED", status = "COMPLETED",
                referenceSessionIdSnapshot = null, startedWallTimeMillis = 1000, totalDurationNanos = 120_000_000_000,
                validity = "VALID", qualityGrade = "A", bootCount = 0, appVersion = "test",
                deviceModel = "test", androidVersion = "16", maxSpeedMps = 23.4,
                maxSpeedElapsedNanos = 75_000_000_000, maxSpeedWallTimeMillis = 76_000,
                createdAtMillis = 1000,
            ),
        )
        dao.insertTrackPoint(
            TrackPointEntity(
                sessionId = "s", sequence = 0, latitude = 31.0, longitude = 121.0,
                elapsedRealtimeNanos = 1, wallTimeMillis = 1000, accuracyMeters = 5.0,
                speedMps = 23.4, speedAccuracyMps = 1.2, bearingDegrees = 90.0,
                bearingAccuracy = 2.0, provider = "gps", isMock = false, usedForTiming = true,
            ),
        )

        val repository = AppRepository(db)
        val json = repository.exportJson()
        val csv = repository.exportCsv()

        assertTrue(json.contains("\"checkpointVersionId\":\"v2\""))
        assertTrue(json.contains("\"maxSpeedMps\":23.4"))
        assertTrue(json.contains("\"maxSpeedElapsedNanos\":75000000000"))
        assertTrue(json.contains("\"speedAccuracyMps\":1.2"))
        assertTrue(csv.startsWith("id,route,startedAtMillis,durationNanos,status,validity,quality,checkpointVersionId,evaluationStatus,evaluationFailureReason,maxSpeedMps,maxSpeedWallTimeMillis"))
        db.close()
    }
}
