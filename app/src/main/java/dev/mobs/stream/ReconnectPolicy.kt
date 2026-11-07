package dev.mobs.stream

/**
 * Exponential backoff for transport reconnects. The attempt budget covers a single outage and
 * resets once a connection has stayed up for [stableMs], so a long IRL session survives any
 * number of separate short drops. An ingest that accepts and then drops the stream at once
 * (for example a key already in use) keeps using the same budget and eventually fails instead
 * of redialling every second forever. Callbacks arrive on the transport's threads, hence the
 * synchronization.
 */
internal class ReconnectPolicy(
    private val initialDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000,
    private val maxAttempts: Int = 20,
    private val stableMs: Long = 30_000,
) {
    private var attempts = 0
    private var connectedAtMs: Long? = null

    /** Attempts made in the current outage. */
    val attempt: Int @Synchronized get() = attempts

    /** Records an accepted connection; the budget resets only if it lasts [stableMs]. */
    @Synchronized
    fun connected(nowMs: Long) {
        connectedAtMs = nowMs
    }

    /** Delay before the next attempt, or null once this outage has used its budget. */
    @Synchronized
    fun nextDelayMs(nowMs: Long = 0): Long? {
        connectedAtMs?.let { if (nowMs - it >= stableMs) attempts = 0 }
        connectedAtMs = null
        if (attempts >= maxAttempts) return null
        val delay = (initialDelayMs shl attempts.coerceAtMost(MAX_SHIFT)).coerceAtMost(maxDelayMs)
        attempts++
        return delay
    }

    @Synchronized
    fun reset() {
        attempts = 0
        connectedAtMs = null
    }

    private companion object {
        /** Keeps the shift far from overflow; the delay is already capped well below this. */
        const val MAX_SHIFT = 16
    }
}
