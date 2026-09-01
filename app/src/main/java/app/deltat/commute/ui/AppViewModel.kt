package app.deltat.commute.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.deltat.commute.BuildConfig
import app.deltat.commute.DeltaTApplication
import app.deltat.commute.data.AppRepository
import app.deltat.commute.data.RouteProfileEntity
import app.deltat.commute.data.SessionListRow
import app.deltat.commute.data.LeaderboardRow
import app.deltat.commute.data.CheckpointRecalculationRequest
import app.deltat.commute.data.RecalculationPreview
import app.deltat.commute.location.TimingService
import app.deltat.commute.location.RuntimeBus
import app.deltat.commute.domain.CheckpointSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val displayPreferences = application.getSharedPreferences("display_preferences", Application.MODE_PRIVATE)
    private val privacyAgreedAtStartup = displayPreferences.getBoolean("amap_privacy_agreed", false)
    private val _amapPrivacyAgreed = MutableStateFlow(privacyAgreedAtStartup)
    val amapPrivacyAgreed: StateFlow<Boolean> = _amapPrivacyAgreed.asStateFlow()
    private val _amapPrivacyPrompted = MutableStateFlow(
        privacyAgreedAtStartup || displayPreferences.getBoolean("amap_privacy_prompted", false),
    )
    val amapPrivacyPrompted: StateFlow<Boolean> = _amapPrivacyPrompted.asStateFlow()
    val mapAvailable: Boolean = BuildConfig.AMAP_AVAILABLE && BuildConfig.AMAP_API_KEY.isNotBlank()
    val repository: AppRepository = (application as DeltaTApplication).repository
    val routes: StateFlow<List<RouteProfileEntity>> = repository.routes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sessions: StateFlow<List<SessionListRow>> = repository.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val leaderboard: StateFlow<List<LeaderboardRow>> = repository.leaderboard.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun agreeToAmapPrivacy() {
        if (!mapAvailable) return
        _amapPrivacyAgreed.value = true
        _amapPrivacyPrompted.value = true
        displayPreferences.edit().putBoolean("amap_privacy_agreed", true).putBoolean("amap_privacy_prompted", true).apply()
    }

    fun declineAmapPrivacy() {
        _amapPrivacyAgreed.value = false
        _amapPrivacyPrompted.value = true
        displayPreferences.edit().putBoolean("amap_privacy_agreed", false).putBoolean("amap_privacy_prompted", true).apply()
    }

    fun createRoute(name: String, onCreated: (String) -> Unit) = viewModelScope.launch {
        onCreated(repository.createRoute(name))
    }

    fun startBaseline(routeId: String) {
        val intent = Intent(getApplication(), TimingService::class.java)
            .setAction(TimingService.ACTION_START_BASELINE)
            .putExtra(TimingService.EXTRA_ROUTE_ID, routeId)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startTimed(routeId: String) {
        val intent = Intent(getApplication(), TimingService::class.java)
            .setAction(TimingService.ACTION_ARM)
            .putExtra(TimingService.EXTRA_ROUTE_ID, routeId)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun startAutoTimed() {
        val intent = Intent(getApplication(), TimingService::class.java)
            .setAction(TimingService.ACTION_ARM_AUTO)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun switchTimedRoute(routeId: String) {
        val intent = Intent(getApplication(), TimingService::class.java)
            .setAction(TimingService.ACTION_SWITCH_ROUTE)
            .putExtra(TimingService.EXTRA_ROUTE_ID, routeId)
        getApplication<Application>().startService(intent)
    }

    fun stopBaseline() = getApplication<Application>().startService(
        Intent(getApplication(), TimingService::class.java).setAction(TimingService.ACTION_STOP_BASELINE),
    )

    fun stopTimed() = getApplication<Application>().startService(
        Intent(getApplication(), TimingService::class.java).setAction(TimingService.ACTION_STOP),
    )

    fun saveCheckpoints(sessionId: String, fractions: List<Float>, onSaved: () -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { repository.saveBaselineCheckpoints(sessionId, fractions) }
            .onSuccess { RuntimeBus.reset(); onSaved() }
            .onFailure { onError(it.message ?: "保存计时点失败") }
    }

    fun previewCheckpointSelections(
        sessionId: String,
        selections: List<CheckpointSelection>,
        onReady: (RecalculationPreview) -> Unit,
        onError: (String) -> Unit,
    ) = viewModelScope.launch {
        runCatching { repository.previewCheckpointSelections(sessionId, selections) }
            .onSuccess(onReady)
            .onFailure { onError(it.message ?: "无法预演历史成绩") }
    }

    fun saveCheckpointSelections(
        sessionId: String,
        selections: List<CheckpointSelection>,
        onStarted: (CheckpointRecalculationRequest) -> Unit,
        onError: (String) -> Unit,
    ) = viewModelScope.launch {
        runCatching { repository.saveCheckpointSelections(sessionId, selections) }
            .onSuccess { request ->
                RuntimeBus.reset()
                onStarted(request)
                launch(Dispatchers.IO) { repository.runPendingRecalculations(request.jobId) }
            }
            .onFailure { onError(it.message ?: "保存计时点失败") }
    }

    fun retryRecalculation(jobId: String, onError: (String) -> Unit = {}) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { repository.retryRecalculation(jobId) } }.onFailure { onError(it.message ?: "重算重试失败") }
    }

    fun deleteSession(sessionId: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        repository.deleteSession(sessionId)
        onDone()
    }

    fun deleteRoute(routeId: String, onDone: () -> Unit = {}, onError: (String) -> Unit = {}) = viewModelScope.launch {
        runCatching { repository.deleteRoute(routeId) }
            .onSuccess { onDone() }
            .onFailure { onError(it.message ?: "删除路线失败") }
    }

    suspend fun exportJson() = withContext(Dispatchers.IO) { repository.exportJson() }
    suspend fun exportCsv() = withContext(Dispatchers.IO) { repository.exportCsv() }
}
