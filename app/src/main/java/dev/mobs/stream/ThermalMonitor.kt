package dev.mobs.stream

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Android's thermal status levels, in increasing severity. */
enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

data class ThermalState(
    val level: ThermalLevel = ThermalLevel.NONE,
    /**
     * Forecast thermal headroom: 1.0 is where the device starts severe throttling. Null before the
     * first reading, below API 30, or when the vendor does not report it.
     */
    val headroom: Float? = null,
)

/**
 * Observes thermal status through Android's listener and polls headroom no more often than the
 * documented 10-second minimum; faster calls return NaN.
 */
class ThermalMonitor(private val context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)
    private val _state = MutableStateFlow(ThermalState(level = power.currentThermalStatus.toThermalLevel()))
    val state: StateFlow<ThermalState> = _state.asStateFlow()

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        _state.update { it.copy(level = status.toThermalLevel()) }
    }
    private var headroomJob: Job? = null

    fun start(scope: CoroutineScope) {
        power.addThermalStatusListener(context.mainExecutor, listener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            headroomJob = scope.launch {
                while (isActive) {
                    val headroom = power.getThermalHeadroom(HEADROOM_INTERVAL_SECONDS)
                    _state.update { it.copy(headroom = headroom.takeUnless(Float::isNaN)) }
                    delay(HEADROOM_INTERVAL_SECONDS * 1_000L)
                }
            }
        }
    }

    fun stop() {
        power.removeThermalStatusListener(listener)
        headroomJob?.cancel()
        headroomJob = null
    }

    private fun Int.toThermalLevel() = when (this) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalLevel.EMERGENCY
        else -> ThermalLevel.SHUTDOWN
    }

    private companion object {
        /** Also the forecast window, so each reading predicts the state at the next poll. */
        const val HEADROOM_INTERVAL_SECONDS = 10
    }
}
