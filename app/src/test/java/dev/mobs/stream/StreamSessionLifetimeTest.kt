package dev.mobs.stream

import dev.mobs.FakeStreamEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamSessionLifetimeTest {
    @Test
    fun retrySurvivesEditorDepartureAndStopsAfterTerminalFailure() = runTest {
        val engine = FakeStreamEngine()
        var stops = 0
        val lifetime = StreamSessionLifetime(engine, backgroundScope) { stops++ }
        runCurrent()
        assertEquals(0, stops) // Service creation must allow time for its first binding.
        lifetime.onEditorBound()
        engine.state.value = StreamEngineState(
            streamActive = true,
            connection = ConnectionState.Failed("retrying"),
        )
        lifetime.onEditorUnbound()
        runCurrent()
        assertEquals(0, stops)
        assertEquals(1, engine.detached)
        assertEquals(0, engine.stopped)
        engine.state.value = engine.state.value.copy(streamActive = false)
        runCurrent()
        assertEquals(1, stops)
    }

    @Test
    fun returningEditorRetainsSessionAfterStreamStops() = runTest {
        val engine = FakeStreamEngine()
        var stops = 0
        val lifetime = StreamSessionLifetime(engine, backgroundScope) { stops++ }
        lifetime.onEditorBound()
        engine.state.value = StreamEngineState(streamActive = true)
        lifetime.onEditorUnbound()
        lifetime.onEditorBound()
        engine.state.value = StreamEngineState()
        runCurrent()
        assertEquals(0, stops)
        lifetime.onEditorUnbound()
        assertEquals(1, stops)
    }

    @Test
    fun previewOnlyServiceStopsWhenEditorLeaves() = runTest {
        val engine = FakeStreamEngine()
        var stops = 0
        val lifetime = StreamSessionLifetime(engine, backgroundScope) { stops++ }
        runCurrent()
        lifetime.onEditorBound()
        lifetime.onEditorUnbound()
        assertEquals(1, stops)
        assertEquals(1, engine.detached)
    }
}
