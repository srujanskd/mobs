package dev.mobs.editor

import dev.mobs.FakeStreamEngine
import dev.mobs.stream.CameraStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {
    @Before
    fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After
    fun cleanup() { Dispatchers.resetMain() }

    @Test
    fun rapidMuteTogglesUseCurrentEngineStateBeforeCollectionRuns() = runTest {
        val engine = FakeStreamEngine()
        val viewModel = CameraViewModel()
        viewModel.bind(engine)
        viewModel.toggleMicrophone()
        assertTrue(engine.state.value.microphoneMuted)
        viewModel.toggleMicrophone()
        assertFalse(engine.state.value.microphoneMuted)
        viewModel.switchCamera()
        assertEquals(1, engine.switches)
        runCurrent()
        assertFalse(viewModel.state.value!!.microphoneMuted)
        viewModel.unbind()
    }

    @Test
    fun rebindDropsOldEngineUpdatesWithoutEndingItsSession() = runTest {
        val oldEngine = FakeStreamEngine()
        val newEngine = FakeStreamEngine()
        val viewModel = CameraViewModel()
        viewModel.bind(oldEngine)
        runCurrent()
        viewModel.bind(newEngine)
        oldEngine.state.value = oldEngine.state.value.copy(camera = CameraStatus.Error("old"))
        newEngine.state.value = newEngine.state.value.copy(camera = CameraStatus.Open)
        runCurrent()
        assertEquals(CameraStatus.Open, viewModel.state.value!!.camera)
        assertEquals(1, oldEngine.detached)
        assertEquals(0, oldEngine.stopped)
        assertEquals(0, oldEngine.released)
        viewModel.unbind()
        newEngine.state.value = newEngine.state.value.copy(camera = CameraStatus.Closed)
        runCurrent()
        assertNull(viewModel.state.value)
        assertEquals(0, newEngine.released)
    }
}
