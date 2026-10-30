package dev.mobs.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Owns heat limits even when the editor is unbound. A clock keeps severe/cooldown deadlines alive. */
internal class ThermalGuard(
    engine: StreamEngine,
    thermal: StateFlow<ThermalState>,
    scope: CoroutineScope,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        val policy = HeatPolicy()
        scope.launch {
            val ticks = flow { while (true) { emit(nowMs()); delay(1_000) } }
            combine(thermal, engine.state, ticks) { t, s, tick -> Triple(t.level, s.streamActive, tick) }
                .distinctUntilChanged()
                .collect { (level, _, _) ->
                    val mode = policy.update(level, nowMs())
                    try {
                        if (engine.state.value.heatMode != mode) engine.setHeatMode(mode)
                    } catch (_: RuntimeException) {
                        // A driver rejecting a limit must not cancel the service's heat observer.
                        policy.failClosed()
                        if (engine.state.value.streamActive) engine.stopStream(HEAT_CONTROL_FAILURE)
                        engine.detachPreview()
                    }
                    if (engine.state.value.streamActive && policy.mode == HeatMode.COOLDOWN) engine.stopStream(
                        if (level.isCritical()) OVERHEAT_REASON else SUSTAINED_HEAT_REASON,
                    )
                }
        }
    }

    companion object {
        const val HEAT_CONTROL_FAILURE = "Stream stopped because heat controls could not be applied"
        const val OVERHEAT_REASON = "Phone reached a critical temperature"
        const val SUSTAINED_HEAT_REASON = "Stream stopped to cool the phone after sustained severe heat"
    }
}

fun ThermalLevel.isCritical() = this >= ThermalLevel.CRITICAL
