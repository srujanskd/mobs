package dev.mobs.scene

import dev.mobs.FakeStreamEngine
import dev.mobs.editor.SceneViewModel
import dev.mobs.stream.StreamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SceneEditorStateTest {
    @get:Rule val folder = TemporaryFolder()
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun savingDraftDoesNotTouchOutputAndApplyDoesNotStopTheStream() = runTest {
        val store = SceneStore(File(folder.root, "scenes.mobs"))
        val original = SceneCollection.initial()
        store.save(original)
        val engine = FakeStreamEngine().apply { state.value = state.value.copy(streamActive = true) }
        val model = SceneViewModel(store, StandardTestDispatcher(testScheduler))
        model.bind(engine)
        advanceUntilIdle()
        val draft = original.active.copy(name = "Edited title")
        assertTrue(model.save(draft, apply = false))
        advanceUntilIdle()
        assertEquals(original.active, engine.state.value.scene)
        assertEquals(draft, store.load().active)
        assertTrue(model.save(draft, apply = true))
        advanceUntilIdle()
        assertEquals(draft, engine.state.value.scene)
        assertTrue(engine.state.value.streamActive)
        assertEquals(0, engine.stopped)
        assertEquals(0, engine.released)
        model.bind(null)
    }

    @Test fun rejectedDualSceneDoesNotReplaceSavedActiveScene() = runTest {
        val store = SceneStore(File(folder.root, "scenes.mobs"))
        val original = SceneCollection.initial()
        store.save(original)
        val fake = FakeStreamEngine()
        val engine = object : StreamEngine by fake {
            override fun setScene(scene: Scene) {
                if (scene.cameras.size == 2) fake.state.value = fake.state.value.copy(sceneError = "Dual capture unavailable")
                else fake.setScene(scene)
            }
        }
        val model = SceneViewModel(store, StandardTestDispatcher(testScheduler))
        model.bind(engine)
        advanceUntilIdle()
        val dual = original.active.copy(sources = original.active.sources + SceneSource(name = "Front", kind = SourceKind.FRONT_CAMERA))
        assertFalse(model.save(dual, apply = true))
        advanceUntilIdle()
        assertEquals(original, store.load())
        assertEquals(original.active, fake.state.value.scene)
        assertEquals("Dual capture unavailable", model.error.value)
        model.bind(null)
    }

    @Test fun refreshWaitsForPendingSavesAndKeepsTheAppliedOutputSeparateFromDrafts() = runTest {
        val store = SceneStore(File(folder.root, "scenes.mobs"))
        val original = SceneCollection.initial()
        store.save(original)
        val engine = FakeStreamEngine().apply { state.value = state.value.copy(prepared = true, streamActive = true, microphoneMuted = true) }
        val model = SceneViewModel(store, StandardTestDispatcher(testScheduler))
        model.bind(engine)
        advanceUntilIdle()
        val draft = original.active.copy(name = "Unapplied draft")
        assertTrue(model.save(draft, false))
        model.refresh()
        model.refresh()
        assertTrue(model.refreshing.value)
        advanceUntilIdle()
        assertEquals(draft, store.load().active)
        assertEquals(draft, model.collection.value!!.active)
        assertEquals(original.active, engine.state.value.scene)
        assertEquals(1, engine.sceneRefreshes)
        assertTrue(engine.state.value.streamActive)
        assertTrue(engine.state.value.microphoneMuted)
        assertEquals(0, engine.stopped)
        assertEquals(0, engine.released)
        assertTrue(engine.started.isEmpty())
        assertFalse(model.refreshing.value)
        assertNotNull(model.notice.value)
        model.bind(null)
    }

    @Test fun refreshReloadsEverySavedSceneAndKeepsMemoryWhenTheFileIsCorrupt() = runTest {
        val file = File(folder.root, "scenes.mobs")
        val store = SceneStore(file)
        val original = SceneCollection.initial()
        store.save(original)
        val engine = FakeStreamEngine().apply { state.value = state.value.copy(prepared = true) }
        val model = SceneViewModel(store, StandardTestDispatcher(testScheduler))
        model.bind(engine)
        advanceUntilIdle()
        val added = original.save(Scene(name = "Chat scene", sources = emptyList()))
        store.save(added)
        model.refresh()
        advanceUntilIdle()
        assertEquals(added, model.collection.value)
        assertEquals(1, engine.sceneRefreshes)
        file.writeText("corrupt")
        model.refresh()
        advanceUntilIdle()
        assertEquals(added, model.collection.value)
        assertEquals("corrupt", file.readText())
        assertNotNull(model.error.value)
        assertFalse(model.refreshing.value)
        assertEquals(1, engine.sceneRefreshes)
        model.bind(null)
    }

    @Test fun corruptSavedCollectionIsReportedWithoutOverwritingIt() = runTest {
        val file = File(folder.root, "scenes.mobs").apply { writeText("corrupt") }
        val model = SceneViewModel(SceneStore(file), StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertNull(model.collection.value)
        assertNotNull(model.error.value)
        assertFalse(model.save(SceneCollection.initial().active, false))
        assertEquals("corrupt", file.readText())
    }
}
