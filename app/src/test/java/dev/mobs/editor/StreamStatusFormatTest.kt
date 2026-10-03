package dev.mobs.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStatusFormatTest {
    @Test
    fun elapsedUsesHoursOnlyOnceNeeded() {
        assertEquals("0:00", formatElapsed(-500))
        assertEquals("0:59", formatElapsed(59_999))
        assertEquals("59:59", formatElapsed(3_599_000))
        assertEquals("1:00:00", formatElapsed(3_600_000))
        assertEquals("3:07:05", formatElapsed((3 * 3_600 + 7 * 60 + 5) * 1_000L))
    }
}
