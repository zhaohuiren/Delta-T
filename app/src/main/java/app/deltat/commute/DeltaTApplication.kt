package app.deltat.commute

import android.app.Application
import app.deltat.commute.data.AppRepository
import app.deltat.commute.data.DeltaTDatabase
import app.deltat.commute.location.RuntimeBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DeltaTApplication : Application() {
    lateinit var database: DeltaTDatabase
        private set
    lateinit var repository: AppRepository
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = DeltaTDatabase.create(this)
        repository = AppRepository(database)
        scope.launch {
            val bootstrap = getSharedPreferences("bootstrap", MODE_PRIVATE)
            if (!bootstrap.getBoolean("defaults_seeded", false)) {
                repository.seedDefaults()
                bootstrap.edit().putBoolean("defaults_seeded", true).apply()
            }
            if (!bootstrap.getBoolean("comparison_rules_v3_backfilled", false)) {
                repository.rebuildComparisonHistory()
                repository.rebuildEvaluationComparisonHistory()
                bootstrap.edit().putBoolean("comparison_rules_v3_backfilled", true).apply()
            }
            repository.prepareTimingEngineV3Upgrade()
            repository.runPendingRecalculations()
            repository.prepareTimingEngineV3Upgrade()
            repository.runPendingRecalculations()
            delay(2_000)
            val active = repository.activeSession()
            if (active != null && RuntimeBus.state.value.sessionId != active.id) {
                repository.markInterrupted(active.id, "定位服务未运行，已保留轨迹")
            }
        }
    }
}
