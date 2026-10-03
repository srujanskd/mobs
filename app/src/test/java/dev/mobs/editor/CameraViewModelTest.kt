package dev.mobs.editor

import dev.mobs.FakeStreamDestinationStore
import dev.mobs.FakeStreamEngine
import dev.mobs.stream.CameraStatus
import dev.mobs.stream.DestinationProblem
import dev.mobs.stream.DestinationResult
import dev.mobs.stream.StreamDestination
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
        val viewModel = CameraViewModel(FakeStreamDestinationStore.holding(null))
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
        val viewModel = CameraViewModel(FakeStreamDestinationStore.holding(null))
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

    private val youtube = (StreamDestination.parse(StreamDestination.YOUTUBE_RTMPS_SERVER, "abcd-1234")
        as DestinationResult.Valid).destination

    @Test
    fun goLiveStreamsToTheSavedDestinationOnce() = runTest {
        val engine = FakeStreamEngine()
        val viewModel = CameraViewModel(FakeStreamDestinationStore.holding(youtube))
        viewModel.bind(engine)
        runCurrent()
        assertEquals(SavedDestination.Ready(youtube), viewModel.destination.value)
        viewModel.goLive()
        viewModel.goLive()
        assertEquals(listOf(youtube), engine.started)
        viewModel.stopStream()
        assertEquals(1, engine.stopped)
        assertFalse(engine.state.value.streamActive)
        viewModel.unbind()
    }

    @Test
    fun goLiveNeedsADestinationAndAnAcceptedProfile() = runTest {
        val engine = FakeStreamEngine()
        val viewModel = CameraViewModel(FakeStreamDestinationStore.holding(null))
        viewModel.bind(engine)
        runCurrent()
        assertEquals(SavedDestination.Missing, viewModel.destination.value)
        viewModel.goLive()
        assertTrue(engine.started.isEmpty())

        viewModel.saveDestination(youtube.server, youtube.streamKey)
        engine.state.value = engine.state.value.copy(profileError = "rejected")
        viewModel.goLive()
        assertTrue(engine.started.isEmpty())
        viewModel.unbind()
    }

    @Test
    fun invalidDestinationIsReportedAndNotPersisted() = runTest {
        val store = FakeStreamDestinationStore.holding(youtube)
        val viewModel = CameraViewModel(store)
        runCurrent()
        assertEquals(DestinationProblem.KEY_BLANK, viewModel.saveDestination(youtube.server, " "))
        runCurrent()
        assertEquals(SavedDestination.Ready(youtube), viewModel.destination.value)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun destinationSavedWhileLoadingIsNotReplacedByTheStaleLoad() = runTest {
        val store = FakeStreamDestinationStore()
        val viewModel = CameraViewModel(store)
        runCurrent()
        assertEquals(SavedDestination.Loading, viewModel.destination.value)
        assertNull(viewModel.saveDestination("rtmps://example.com/live", "fresh"))
        store.loaded.complete(youtube)
        runCurrent()
        val ready = viewModel.destination.value as SavedDestination.Ready
        assertEquals("fresh", ready.destination.streamKey)
        assertEquals(listOf(ready.destination), store.saved)
    }
}
