package dev.mobs.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class HeatPolicyTest {
    @Test fun escalatesImmediatelyAndRequiresContinuousCooling() {
        val policy = HeatPolicy()
        assertEquals(HeatMode.WARM, policy.update(ThermalLevel.MODERATE, 0))
        assertEquals(HeatMode.HOT, policy.update(ThermalLevel.SEVERE, 1_000))
        assertEquals(HeatMode.HOT, policy.update(ThermalLevel.LIGHT, 2_000))
        assertEquals(HeatMode.HOT, policy.update(ThermalLevel.LIGHT, 31_999))
        assertEquals(HeatMode.HOT, policy.update(ThermalLevel.MODERATE, 32_000))
        assertEquals(HeatMode.HOT, policy.update(ThermalLevel.NONE, 33_000))
        assertEquals(HeatMode.NORMAL, policy.update(ThermalLevel.NONE, 63_000))
    }

    @Test fun sustainedSevereDeadlineCannotBeResetByRepeatedSamples() {
        val policy = HeatPolicy()
        for (second in 0..59) assertEquals(HeatMode.HOT, policy.update(ThermalLevel.SEVERE, second * 1000L))
        assertEquals(HeatMode.COOLDOWN, policy.update(ThermalLevel.SEVERE, 60_000))
        assertEquals(HeatMode.COOLDOWN, policy.update(ThermalLevel.MODERATE, 61_000))
        assertEquals(HeatMode.COOLDOWN, policy.update(ThermalLevel.LIGHT, 62_000))
        assertEquals(HeatMode.NORMAL, policy.update(ThermalLevel.LIGHT, 92_000))
    }

    @Test fun severeTimerResetsWhenActualSevereStatusClears() {
        val policy = HeatPolicy()
        policy.update(ThermalLevel.SEVERE, 0)
        policy.update(ThermalLevel.MODERATE, 59_000)
        assertEquals(HeatMode.HOT, policy.update(ThermalLevel.SEVERE, 100_000))
        assertEquals(HeatMode.COOLDOWN, policy.update(ThermalLevel.SEVERE, 160_000))
    }

    @Test fun criticalHeatLatchesCooldownEvenWithoutPriorSevereHeat() {
        for (level in listOf(ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY, ThermalLevel.SHUTDOWN)) {
            val policy = HeatPolicy()
            assertEquals(HeatMode.COOLDOWN, policy.update(level, 0))
            assertEquals(HeatMode.COOLDOWN, policy.update(ThermalLevel.NONE, 1))
            assertEquals(HeatMode.NORMAL, policy.update(ThermalLevel.NONE, 30_001))
        }
    }
}
