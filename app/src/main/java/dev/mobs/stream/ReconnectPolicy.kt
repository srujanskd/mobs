package dev.mobs.stream

/**
 * Exponential backoff for transport reconnects. The attempt budget covers a single outage and
 * resets on every successful connection, so a long IRL session survives any number of separate
 * short drops. Callbacks arrive on the transport's threads, hence the synchronization.
 */
internal class ReconnectPolicy(
    private val initialDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000,
    private val maxAttempts: Int = 20,
) {
    private var attempts = 0

    /** Attempts made in the current outage. */
    val attempt: Int @Synchronized get() = attempts

    /** Delay before the next attempt, or null once this outage has used its budget. */
    @Synchronized
    fun nextDelayMs(): Long? {
        if (attempts >= maxAttempts) return null
        val delay = (initialDelayMs shl attempts.coerceAtMost(MAX_SHIFT)).coerceAtMost(maxDelayMs)
        attempts++
        return delay
    }

    @Synchronized
    fun reset() {
        attempts = 0
    }

    private companion object {
        /** Keeps the shift far from overflow; the delay is already capped well below this. */
        const val MAX_SHIFT = 16
    }
}
