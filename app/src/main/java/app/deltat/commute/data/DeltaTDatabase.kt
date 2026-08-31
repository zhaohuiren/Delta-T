package app.deltat.commute.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RouteProfileEntity::class, CheckpointVersionEntity::class, CheckpointEntity::class,
        SessionEntity::class, TrackPointEntity::class, CheckpointPassEntity::class,
        SectorResultEntity::class, GnssSnapshotEntity::class,
        SessionEvaluationEntity::class, EvaluationPassEntity::class,
        EvaluationSectorEntity::class, CheckpointRecalculationJobEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class DeltaTDatabase : RoomDatabase() {
    abstract fun dao(): DeltaTDao

    companion object {
        fun create(context: Context): DeltaTDatabase = Room.databaseBuilder(
            context,
            DeltaTDatabase::class.java,
            "delta-t.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points ADD COLUMN speedAccuracyMps REAL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN maxSpeedMps REAL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN maxSpeedElapsedNanos INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN maxSpeedWallTimeMillis INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN monthlyReferenceSessionIdSnapshot TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN weeklyReferenceSessionIdSnapshot TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN monthlyBestTotalNanos INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN weeklyBestTotalNanos INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN monthlyDeltaNanos INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN weeklyDeltaNanos INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN totalResultColor TEXT NOT NULL DEFAULT 'NEUTRAL'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN comparisonRulesVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sector_results ADD COLUMN monthlyBestDurationNanos INTEGER")
                db.execSQL("ALTER TABLE sector_results ADD COLUMN weeklyBestDurationNanos INTEGER")
                db.execSQL("ALTER TABLE sector_results ADD COLUMN monthlyDeltaNanos INTEGER")
                db.execSQL("ALTER TABLE sector_results ADD COLUMN weeklyDeltaNanos INTEGER")
                db.execSQL("ALTER TABLE sector_results ADD COLUMN comparisonRulesVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS session_evaluations (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        checkpointVersionId TEXT NOT NULL,
                        status TEXT NOT NULL,
                        failureReason TEXT,
                        startElapsedNanos INTEGER,
                        finishElapsedNanos INTEGER,
                        totalDurationNanos INTEGER,
                        qualityGrade TEXT NOT NULL,
                        recordQualified INTEGER NOT NULL,
                        monthlyReferenceSessionId TEXT,
                        weeklyReferenceSessionId TEXT,
                        monthlyBestTotalNanos INTEGER,
                        weeklyBestTotalNanos INTEGER,
                        monthlyDeltaNanos INTEGER,
                        weeklyDeltaNanos INTEGER,
                        totalResultColor TEXT NOT NULL,
                        comparisonRulesVersion INTEGER NOT NULL,
                        timingEngineVersion INTEGER NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_session_evaluations_sessionId_checkpointVersionId ON session_evaluations(sessionId, checkpointVersionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_session_evaluations_checkpointVersionId ON session_evaluations(checkpointVersionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_session_evaluations_status ON session_evaluations(status)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS evaluation_passes (
                        id TEXT NOT NULL PRIMARY KEY,
                        evaluationId TEXT NOT NULL,
                        checkpointId TEXT NOT NULL,
                        orderIndex INTEGER NOT NULL,
                        passElapsedNanos INTEGER NOT NULL,
                        interpolatedLatitude REAL NOT NULL,
                        interpolatedLongitude REAL NOT NULL,
                        triggerMode TEXT NOT NULL,
                        closestDistanceMeters REAL NOT NULL,
                        estimatedUncertaintyMillis INTEGER NOT NULL,
                        confidence TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_evaluation_passes_evaluationId_orderIndex ON evaluation_passes(evaluationId, orderIndex)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS evaluation_sectors (
                        evaluationId TEXT NOT NULL,
                        sectorIndex INTEGER NOT NULL,
                        durationNanos INTEGER NOT NULL,
                        monthlyBestDurationNanos INTEGER,
                        weeklyBestDurationNanos INTEGER,
                        monthlyDeltaNanos INTEGER,
                        weeklyDeltaNanos INTEGER,
                        resultColorAtCompletion TEXT NOT NULL,
                        recordQualified INTEGER NOT NULL,
                        comparisonRulesVersion INTEGER NOT NULL,
                        PRIMARY KEY(evaluationId, sectorIndex)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_evaluation_sectors_evaluationId ON evaluation_sectors(evaluationId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS checkpoint_recalculation_jobs (
                        id TEXT NOT NULL PRIMARY KEY,
                        routeProfileId TEXT NOT NULL,
                        checkpointVersionId TEXT NOT NULL,
                        sourceSessionId TEXT NOT NULL,
                        status TEXT NOT NULL,
                        totalSessions INTEGER NOT NULL,
                        completedSessions INTEGER NOT NULL,
                        successfulSessions INTEGER NOT NULL,
                        failedSessions INTEGER NOT NULL,
                        errorMessage TEXT,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL,
                        finishedAtMillis INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_checkpoint_recalculation_jobs_checkpointVersionId ON checkpoint_recalculation_jobs(checkpointVersionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_checkpoint_recalculation_jobs_status ON checkpoint_recalculation_jobs(status)")

                db.execSQL("""
                    INSERT INTO session_evaluations (
                        id, sessionId, checkpointVersionId, status, failureReason,
                        startElapsedNanos, finishElapsedNanos, totalDurationNanos,
                        qualityGrade, recordQualified, monthlyReferenceSessionId,
                        weeklyReferenceSessionId, monthlyBestTotalNanos, weeklyBestTotalNanos,
                        monthlyDeltaNanos, weeklyDeltaNanos, totalResultColor,
                        comparisonRulesVersion, timingEngineVersion, createdAtMillis, updatedAtMillis
                    )
                    SELECT 'legacy-' || id, id, checkpointVersionId,
                        CASE WHEN validity = 'VALID' AND totalDurationNanos IS NOT NULL THEN 'VALID' ELSE 'INCOMPLETE' END,
                        invalidReason, startElapsedNanos, finishElapsedNanos, totalDurationNanos,
                        qualityGrade, CASE WHEN validity = 'VALID' THEN 1 ELSE 0 END,
                        monthlyReferenceSessionIdSnapshot, weeklyReferenceSessionIdSnapshot,
                        monthlyBestTotalNanos, weeklyBestTotalNanos, monthlyDeltaNanos,
                        weeklyDeltaNanos, totalResultColor, comparisonRulesVersion, 1,
                        createdAtMillis, createdAtMillis
                    FROM sessions WHERE checkpointVersionId IS NOT NULL
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO evaluation_passes
                    SELECT p.id, 'legacy-' || p.sessionId, p.checkpointId, p.orderIndex,
                        p.passElapsedNanos, p.interpolatedLatitude, p.interpolatedLongitude,
                        p.triggerMode, p.closestDistanceMeters, p.estimatedUncertaintyMillis, p.confidence
                    FROM checkpoint_passes p
                    JOIN session_evaluations e ON e.id = 'legacy-' || p.sessionId
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO evaluation_sectors
                    SELECT 'legacy-' || s.sessionId, s.sectorIndex, s.durationNanos,
                        s.monthlyBestDurationNanos, s.weeklyBestDurationNanos,
                        s.monthlyDeltaNanos, s.weeklyDeltaNanos, s.resultColorAtCompletion,
                        s.recordQualified, s.comparisonRulesVersion
                    FROM sector_results s
                    JOIN session_evaluations e ON e.id = 'legacy-' || s.sessionId
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN missingCheckpointMask INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN timingEngineVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE session_evaluations ADD COLUMN missingCheckpointMask INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
