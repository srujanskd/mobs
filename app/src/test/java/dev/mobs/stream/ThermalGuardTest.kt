package dev.mobs.stream

import dev.mobs.FakeStreamEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThermalGuardTest {
    @Test
    fun criticalTemperatureStopsALiveStreamOnceWithAReason() = runTest {
        val engine = FakeStreamEngine()
        val thermal = MutableStateFlow(ThermalState())
        ThermalGuard(engine, thermal, backgroundScope)
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
        ThermalGuard(engine, thermal, backgroundScope)
        runCurrent()
        assertEquals(0, engine.stopped)

        // Go Live is refused at this level, but a stream that starts anyway is still stopped.
        engine.state.value = StreamEngineState(streamActive = true)
        runCurrent()
        assertEquals(1, engine.stopped)
    }
}
