package dev.mobs.stream

import dev.mobs.FakeStreamEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThermalGuardTest {
    @Test
    fun novemberFourthTraceEntersWarmModeBeforeSevereHeat() = runTest {
        val engine = FakeStreamEngine()
        engine.state.value = StreamEngineState(streamActive = true)
        val thermal = MutableStateFlow(ThermalState())
        ThermalGuard(engine, thermal, backgroundScope) { testScheduler.currentTime }
        runCurrent()
        advanceTimeBy(106_600)
        thermal.value = ThermalState(level = ThermalLevel.LIGHT)
        runCurrent()
        assertEquals(30, engine.state.value.heatMode.videoFps)
        advanceTimeBy(324_400)
        thermal.value = ThermalState(level = ThermalLevel.MODERATE)
        runCurrent()
        // The captured session stayed moderate from 431s to 1476.2s while both cameras ran.
        // The November 5 dev4 run halved cadence at moderate heat and heated at the same
        // 4.2 C per 15 minutes, so warm mode pauses the inset camera instead of cutting fps.
        assertEquals(HeatMode.WARM, engine.state.value.heatMode)
        assertEquals("Moderate heat keeps full cadence", 30, engine.state.value.heatMode.videoFps)
        advanceTimeBy(1_045_200)
        runCurrent()
        assertEquals(HeatMode.WARM, engine.state.value.heatMode)
        assertEquals("Moderate heat alone must not stop the broadcast", 0, engine.stopped)
        thermal.value = ThermalState(level = ThermalLevel.SEVERE)
        runCurrent()
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(1, engine.stopped)
    }

    @Test
    fun sustainedSevereHeatStopsBeforeWaitingForCritical() = runTest {
        val engine = FakeStreamEngine()
        val thermal = MutableStateFlow(ThermalState(level = ThermalLevel.SEVERE))
        ThermalGuard(engine, thermal, backgroundScope) { testScheduler.currentTime }
        engine.state.value = StreamEngineState(streamActive = true)
        runCurrent()
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals("A severe device must not keep broadcasting indefinitely", 1, engine.stopped)
    }

    @Test
    fun limitsApplyWithoutAnEditorAndCooldownCannotBeBypassedByRestarting() = runTest {
        val engine = FakeStreamEngine()
        val thermal = MutableStateFlow(ThermalState(level = ThermalLevel.MODERATE))
        ThermalGuard(engine, thermal, backgroundScope) { testScheduler.currentTime }
        runCurrent()
        assertEquals(HeatMode.WARM, engine.state.value.heatMode)
        thermal.value = ThermalState(level = ThermalLevel.SEVERE)
        engine.state.value = engine.state.value.copy(streamActive = true)
        runCurrent()
        assertEquals(HeatMode.HOT, engine.state.value.heatMode)
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(HeatMode.COOLDOWN, engine.state.value.heatMode)
        assertEquals(1, engine.stopped)
        engine.state.value = engine.state.value.copy(streamActive = true)
        runCurrent()
        assertEquals(2, engine.stopped)
        thermal.value = ThermalState(level = ThermalLevel.LIGHT)
        runCurrent()
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(HeatMode.NORMAL, engine.state.value.heatMode)
    }

    @Test
    fun failedHeatControlStopsSafelyAndDoesNotCancelTheObserver() = runTest {
        val fake = FakeStreamEngine()
        var reject = true
        val engine = object : StreamEngine by fake {
            override fun setHeatMode(mode: HeatMode) {
                fake.setHeatMode(mode)
                if (reject) { reject = false; throw IllegalStateException("driver refused") }
            }
        }
        val thermal = MutableStateFlow(ThermalState(level = ThermalLevel.MODERATE))
        fake.state.value = StreamEngineState(streamActive = true)
        ThermalGuard(engine, thermal, backgroundScope) { testScheduler.currentTime }
        runCurrent()
        assertEquals(listOf(ThermalGuard.HEAT_CONTROL_FAILURE), fake.failures)
        assertEquals(HeatMode.COOLDOWN, engine.state.value.heatMode)
        thermal.value = ThermalState(level = ThermalLevel.NONE)
        runCurrent()
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(HeatMode.NORMAL, engine.state.value.heatMode)
    }

    @Test
    fun criticalTemperatureStopsALiveStreamOnceWithAReason() = runTest {
        val engine = FakeStreamEngine()
        val thermal = MutableStateFlow(ThermalState())
        ThermalGuard(engine, thermal, backgroundScope) { testScheduler.currentTime }
        engine.state.value = StreamEngineState(streamActive = true)
        thermal.value = ThermalState(level = ThermalLevel.SEVERE)
        runCurrent()
        assertEquals(0, engine.stopped)

        thermal.value = ThermalState(level = ThermalLevel.CRITICAL)
        runCurrent()
        thermal.value = ThermalState(level = ThermalLevel.EMERGENCY)
        runCurrent()
        assertEquals(listOf<String?>(ThermalGuard.OVERHEAT_REASON), engine.failures)
        assertEquals(ConnectionState.Failed(ThermalGuard.OVERHEAT_REASON), engine.state.value.connection)
    }

    @Test
    fun criticalTemperatureWithoutAStreamDoesNothingUntilOneStarts() = runTest {
        val engine = FakeStreamEngine()
        val thermal = MutableStateFlow(ThermalState(level = ThermalLevel.CRITICAL))
        ThermalGuard(engine, thermal, backgroundScope) { testScheduler.currentTime }
        runCurrent()
        assertEquals(0, engine.stopped)

        // Go Live is refused at this level, but a stream that starts anyway is still stopped.
        engine.state.value = StreamEngineState(streamActive = true)
        runCurrent()
        assertEquals(1, engine.stopped)
    }
}
