package dev.mobs.stream

/** Runtime limits. The saved scene and requested profile are never rewritten. */
enum class HeatMode(val browserFps: Int, val videoFps: Int, val bitrateCap: Int) {
    NORMAL(30, 30, Int.MAX_VALUE),
    WARM(15, 30, 6_000_000),
    HOT(5, 15, 4_000_000),
    COOLDOWN(1, 15, 4_000_000);

    val suspendPreview: Boolean get() = this >= HOT
}

/** Escalate immediately; restore only after 30 continuous seconds at light or below. */
internal class HeatPolicy {
    var mode = HeatMode.NORMAL
        private set
    private var coolSince: Long? = null
    private var severeSince: Long? = null

    fun failClosed() {
        mode = HeatMode.COOLDOWN
        coolSince = null
    }

    fun update(level: ThermalLevel, nowMs: Long): HeatMode {
        if (level >= ThermalLevel.SEVERE) {
            if (severeSince == null) severeSince = nowMs
        } else severeSince = null
        val requested = when {
            level.isCritical() || severeSince?.let { nowMs - it >= SEVERE_TIMEOUT_MS } == true -> HeatMode.COOLDOWN
            level >= ThermalLevel.SEVERE -> HeatMode.HOT
            level >= ThermalLevel.MODERATE -> HeatMode.WARM
            else -> HeatMode.NORMAL
        }
        if (requested > mode) mode = requested
        if (level <= ThermalLevel.LIGHT) {
            if (coolSince == null) coolSince = nowMs
            if (nowMs - requireNotNull(coolSince) >= RECOVERY_MS) mode = HeatMode.NORMAL
        } else coolSince = null
        return mode
    }

    companion object {
        const val SEVERE_TIMEOUT_MS = 60_000L
        const val RECOVERY_MS = 30_000L
    }
}
