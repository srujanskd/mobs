package dev.mobs.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun delaysDoubleUpToTheCapThenTheOutageGivesUp() {
        val policy = ReconnectPolicy(initialDelayMs = 1_000, maxDelayMs = 5_000, maxAttempts = 5)
        val delays = List(5) { policy.nextDelayMs() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), delays)
        assertEquals(5, policy.attempt)
        assertNull(policy.nextDelayMs())
    }

    @Test
    fun aSuccessfulConnectionRestoresTheFullBudget() {
        val policy = ReconnectPolicy(initialDelayMs = 1_000, maxDelayMs = 30_000, maxAttempts = 2)
        repeat(10) {
            assertEquals(1_000L, policy.nextDelayMs())
            assertEquals(2_000L, policy.nextDelayMs())
            policy.reset()
        }
        assertEquals(0, policy.attempt)
    }

    @Test
    fun largeAttemptCountsStayAtTheCapWithoutOverflow() {
        val policy = ReconnectPolicy(initialDelayMs = 1_000, maxDelayMs = 30_000, maxAttempts = 100)
        val delays = List(100) { policy.nextDelayMs()!! }
        assertEquals(30_000L, delays.last())
        assertEquals(delays.sorted(), delays)
    }
}
