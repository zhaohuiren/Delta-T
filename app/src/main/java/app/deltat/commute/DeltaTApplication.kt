package app.deltat.commute

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import app.deltat.commute.data.AppRepository
import app.deltat.commute.data.DeltaTDatabase
import app.deltat.commute.location.RuntimeBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DeltaTApplication : Application() {
    // Internal dependency seam; only androidTest supplies a fake. No user-facing mock switch.
    internal var locationSourceFactory: (android.content.Context) -> app.deltat.commute.domain.LocationSource =
        { app.deltat.commute.location.AndroidGnssLocationSource(it) }
    lateinit var database: DeltaTDatabase
        private set
    lateinit var repository: AppRepository
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        capturePreviousProcessExit()
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
            repository.prepareTimingEngineUpgrade()
            repository.runPendingRecalculations()
            delay(2_000)
            val active = repository.activeSession()
            if (active != null && RuntimeBus.state.value.sessionId != active.id) {
                repository.markInterrupted(active.id, "定位服务未运行，已保留轨迹")
            }
        }
    }

    fun markInterruptedFromDestroyedService(sessionId: String, reason: String) {
        scope.launch { repository.markInterrupted(sessionId, reason) }
    }

    fun recentExitSummary(): String = getSharedPreferences("runtime_diagnostics", MODE_PRIVATE)
        .getString("previous_exit_summary", "暂无可用记录") ?: "暂无可用记录"

    private fun capturePreviousProcessExit() {
        if (Build.VERSION.SDK_INT < 30) return
        val exit = getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(packageName, 0, 1)
            .firstOrNull() ?: return
        val reason = when (exit.reason) {
            android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
            android.app.ApplicationExitInfo.REASON_CRASH,
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "崩溃"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "系统低内存"
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源使用过高"
            android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "用户或系统设置停止"
            android.app.ApplicationExitInfo.REASON_SIGNALED -> "系统信号终止"
            else -> "系统原因 ${exit.reason}"
        }
        getSharedPreferences("runtime_diagnostics", MODE_PRIVATE).edit()
            .putString("previous_exit_summary", "$reason · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(exit.timestamp))}")
            .apply()
    }
}
