package dev.mobs.editor

import dev.mobs.FakeStreamEngine
import dev.mobs.scene.Scene
import dev.mobs.scene.SceneStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ScenePinchZoomTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun samplesAccumulateFromCurrentZoomClampAndOnlyPersistAtGestureEnd() = runTest {
        val store = SceneStore(temporary.newFile())
        val initial = dev.mobs.scene.SceneCollection.initial()
        store.save(initial)
        val model = SceneViewModel(store, StandardTestDispatcher(testScheduler))
        val engine = FakeStreamEngine()
        model.bind(engine)
        runCurrent()
        val scene = engine.state.value.scene!!
        val camera = scene.sources.single()
        model.pinchZoom(scene.id, camera.id, 2f, .5f..5f, false)
        model.pinchZoom(scene.id, camera.id, 2f, .5f..5f, false)
        assertEquals(4f, engine.state.value.scene!!.sources.single().zoom)
        runCurrent()
        assertEquals(1f, store.load().active.sources.single().zoom)
        model.pinchZoom(scene.id, camera.id, 2f, .5f..5f, false)
        assertEquals(5f, engine.state.value.scene!!.sources.single().zoom)
        model.pinchZoom(scene.id, camera.id, .01f, .5f..5f, false)
        assertEquals(.5f, engine.state.value.scene!!.sources.single().zoom)
        model.pinchZoom(scene.id, camera.id, 1f, .5f..5f, true)
        runCurrent()
        assertEquals(.5f, store.load().active.sources.single().zoom)
    }

    @Test fun oldGestureCannotChangeOrSaveANewSceneAndInvalidSamplesAreIgnored() = runTest {
        val store = SceneStore(temporary.root.resolve("scenes"))
        val model = SceneViewModel(store, StandardTestDispatcher(testScheduler))
        val engine = FakeStreamEngine()
        model.bind(engine)
        runCurrent()
        val scene = engine.state.value.scene!!
        val camera = scene.sources.single()
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f).forEach {
            model.pinchZoom(scene.id, camera.id, it, 1f..5f, false)
        }
        assertEquals(scene, engine.state.value.scene)
        val replacement = Scene(name = "Other", sources = scene.sources)
        engine.setScene(replacement)
        model.pinchZoom(scene.id, camera.id, 2f, 1f..5f, false)
        model.pinchZoom(scene.id, camera.id, 1f, 1f..5f, true)
        runCurrent()
        assertEquals(replacement, engine.state.value.scene)
        assertFalse(temporary.root.resolve("scenes").exists())
    }
    @Test fun longGestureKeepsEditsInMemoryAndPersistsOnlyItsFinalState() = runTest {
        val file = temporary.root.resolve("long-gesture")
        val store = SceneStore(file)
        val model = SceneViewModel(store, StandardTestDispatcher(testScheduler))
        val engine = FakeStreamEngine()
        model.bind(engine)
        runCurrent()
        val scene = engine.state.value.scene!!
        val camera = scene.sources.single()
        repeat(10_000) { index ->
            model.pinchZoom(scene.id, camera.id, if (index % 2 == 0) 1.01f else .99f, .5f..5f, false)
            // Let pending IO run: a per-sample save would create a file here.
            if (index % 100 == 0) {
                runCurrent()
                assertFalse("Gesture sample wrote to disk", file.exists())
            }
        }
        model.pinchZoom(scene.id, camera.id, 1f, .5f..5f, true)
        runCurrent()
        assertEquals(engine.state.value.scene, store.load().active)
    }

    @Test fun removedHiddenAndTransparentSourcesCannotBeChangedByAnOldGesture() = runTest {
        val store = SceneStore(temporary.root.resolve("inactive-source"))
        val model = SceneViewModel(store, StandardTestDispatcher(testScheduler))
        val engine = FakeStreamEngine()
        model.bind(engine)
        runCurrent()
        val scene = engine.state.value.scene!!
        val camera = scene.sources.single()
        val inactiveScenes = listOf(
            scene.copy(sources = emptyList()),
            scene.update(camera.copy(visible = false)),
            scene.update(camera.copy(opacity = 0f)),
            scene.update(camera.copy(kind = dev.mobs.scene.SourceKind.TEXT)),
        )
        inactiveScenes.forEach { inactive ->
            engine.setScene(inactive)
            model.pinchZoom(scene.id, camera.id, 2f, .5f..5f, false)
            model.pinchZoom(scene.id, camera.id, 1f, .5f..5f, true)
            runCurrent()
            assertEquals(inactive, engine.state.value.scene)
            assertFalse(temporary.root.resolve("inactive-source").exists())
        }
    }

}
