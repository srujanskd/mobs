package dev.mobs.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Stops a live stream cleanly once the device reaches a critical thermal state, rather than
 * letting the system kill it or degrading the output. Lower levels never change the stream.
 */
internal class ThermalGuard(engine: StreamEngine, thermal: StateFlow<ThermalState>, scope: CoroutineScope) {
    init {
        scope.launch {
            combine(thermal, engine.state) { t, s -> t.level.isCritical() && s.streamActive }
                .distinctUntilChanged()
                .collect { overheated -> if (overheated) engine.stopStream(OVERHEAT_REASON) }
        }
    }

    companion object {
        const val OVERHEAT_REASON = "Phone reached a critical temperature"
    }
}

/** At this level the plan requires a clean stop; the editor also refuses to go live. */
fun ThermalLevel.isCritical() = this >= ThermalLevel.CRITICAL
