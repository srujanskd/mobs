package dev.mobs.scene

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SceneTest {
    @Test fun draggingAndResizingNeverLeaveTheCanvas() {
        val random = Random(8821)
        var bounds = SourceBounds(0.2f, 0.2f, 0.4f, 0.4f)
        repeat(20_000) {
            bounds = if (random.nextBoolean()) bounds.move(random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2)
            else bounds.resize(random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2)
            assertTrue(bounds.x >= 0f && bounds.y >= 0f)
            assertTrue(bounds.width >= SourceBounds.MIN_SIZE && bounds.height >= SourceBounds.MIN_SIZE)
            assertTrue(bounds.x + bounds.width <= 1.00001f && bounds.y + bounds.height <= 1.00001f)
        }
    }

    @Test fun nonFiniteAndOffCanvasGeometryIsRejected() {
        listOf<() -> Unit>(
            { SourceBounds(Float.NaN) }, { SourceBounds(width = Float.POSITIVE_INFINITY) },
            { SourceBounds(x = -0.1f) }, { SourceBounds(x = 0.5f, width = 0.6f) },
            { SourceBounds(height = 0f) }, { SceneSource(name = "Bad", kind = SourceKind.TEXT, opacity = Float.NaN) },
            { SceneSource(name = "Bad", kind = SourceKind.IMAGE, media = "../secrets.png") },
        ).forEach { expectInvalid(it) }
    }

    @Test fun duplicateOwnsItsSourceIdsAndEditsLeaveOriginalUntouched() {
        val original = SceneCollection.initial().active
        val copy = original.duplicate()
        assertNotEquals(original.id, copy.id)
        assertNotEquals(original.sources[0].id, copy.sources[0].id)
        val changed = copy.update(copy.sources[0].copy(visible = false))
        assertTrue(original.sources[0].visible)
        assertFalse(changed.sources[0].visible)
    }

    @Test fun cameraSetTracksVisibilityAndOpacityAndRejectsDuplicateCapture() {
        val rear = SceneSource(name = "Rear", kind = SourceKind.REAR_CAMERA)
        val front = SceneSource(name = "Front", kind = SourceKind.FRONT_CAMERA)
        val dual = Scene(name = "Dual", sources = listOf(rear, front))
        assertEquals(Scene.CAMERA_KINDS, dual.cameras)
        assertEquals(setOf(SourceKind.REAR_CAMERA), dual.update(front.copy(visible = false)).cameras)
        assertEquals(setOf(SourceKind.REAR_CAMERA), dual.update(front.copy(opacity = 0f)).cameras)
        expectInvalid { dual.copy(sources = dual.sources + rear.copy(id = "other")) }
    }

    @Test fun limitsAndActiveSceneIntegrityAreEnforced() {
        val initial = SceneCollection.initial()
        expectInvalid { initial.delete(initial.activeId) }
        expectInvalid { initial.copy(activeId = "missing") }
        expectInvalid { initial.copy(scenes = initial.scenes + initial.active) }
        expectInvalid { Scene(name = "Too many", sources = List(Scene.MAX_SOURCES + 1) { SceneSource(name = "Text", kind = SourceKind.TEXT) }) }
        var collection = initial
        repeat(SceneCollection.MAX_SCENES - 1) { collection = collection.save(initial.active.duplicate()) }
        expectInvalid { collection.save(initial.active.duplicate()) }
        val next = collection.delete(initial.activeId)
        assertEquals(SceneCollection.MAX_SCENES - 1, next.scenes.size)
        assertTrue(next.scenes.any { it.id == next.activeId })
    }

    private fun expectInvalid(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }
}
