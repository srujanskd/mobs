package dev.mobs.render

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFrameRateTest {
    @Test fun selectsSupportedLowRateAndRestoresNormalRate() {
        val ranges = listOf(15..30, 30..30, 15..15, 10..20)
        assertEquals(15..15, captureFrameRate(ranges, 15, 30))
        assertEquals(30..30, captureFrameRate(ranges, 30, 30))
    }
    @Test fun prefersLowestCeilingWhenOnlyVariableRatesAreAvailable() {
        assertEquals(10..20, captureFrameRate(listOf(15..30, 10..20, 30..30), 15, 30))
    }
    @Test fun retainsSupportedProfileWhenThermalRateIsUnavailable() {
        assertEquals(30..30, captureFrameRate(listOf(30..30), 15, 30))
    }
    @Test(expected = IllegalStateException::class) fun rejectsUnsupportedProfile() {
        captureFrameRate(listOf(60..60), 15, 30)
    }
}
