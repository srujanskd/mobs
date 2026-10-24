package dev.mobs.stream

import dev.mobs.FakeStreamEngine
import dev.mobs.scene.Scene
import dev.mobs.scene.SceneCollection
import dev.mobs.scene.SceneSource
import dev.mobs.scene.SourceKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Virtual-time policy soaks, not evidence of camera/codec/battery endurance. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionStabilityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun threeHourSessionRecordsAtOneHzThroughSceneChangesAndRepeatedOutages() = runTest {
        val engine = FakeStreamEngine()
        val thermal = MutableStateFlow(ThermalState())
        var serviceStops = 0
        val lifetime = StreamSessionLifetime(engine, backgroundScope) { serviceStops++ }
        lifetime.onEditorBound()
        SessionRecorder(folder.root, engine, thermal, { BatterySample(80, 35f, null, null, false) }, backgroundScope,
            StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, { 1_800_000_000_000L + testScheduler.currentTime })
        val camera = SceneCollection.initial().active
        val card = Scene(name = "Title", sources = listOf(SceneSource(name = "Text", kind = SourceKind.TEXT, text = "Hello")))
        val reconnect = ReconnectPolicy()
        engine.state.value = StreamEngineState(streamActive = true, connection = ConnectionState.Connected)
        runCurrent()
        lifetime.onEditorUnbound()
        repeat(180) { minute ->
            engine.setScene(if (minute % 2 == 0) camera else card)
            if (minute % 5 == 0) {
                repeat(3) { assertNotNull(reconnect.nextDelayMs()) }
                engine.state.value = engine.state.value.copy(connection = ConnectionState.Reconnecting(3, "fixture outage"))
                runCurrent()
                advanceTimeBy(5_000)
                reconnect.reset()
                engine.state.value = engine.state.value.copy(connection = ConnectionState.Connected)
                advanceTimeBy(55_000)
            } else advanceTimeBy(60_000)
        }
        runCurrent()
        assertEquals(0, serviceStops)
        assertEquals(0, engine.stopped)
        engine.stopStream()
        runCurrent()
        assertEquals(1, serviceStops)
        val files = folder.root.listFiles()!!
        assertEquals(1, files.size)
        val lines = files.single().readLines()
        assertEquals(10_803, lines.size) // Header, t=0 through t=10800, and terminal marker.
        assertTrue(lines.last().contains(",10800.0,"))
        val size = files.single().length()
        advanceTimeBy(60_000)
        assertEquals(size, files.single().length())
    }

    @Test fun thousandsOfIndependentOutagesDoNotConsumeFutureRetryBudgets() {
        val policy = ReconnectPolicy()
        repeat(10_000) {
            assertEquals(1_000L, policy.nextDelayMs())
            repeat(19) { assertTrue(policy.nextDelayMs()!! in 1_000..30_000) }
            assertNull(policy.nextDelayMs())
            policy.reset()
        }
    }
}
