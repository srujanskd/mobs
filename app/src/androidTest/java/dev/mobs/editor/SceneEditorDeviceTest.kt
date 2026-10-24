package dev.mobs.editor

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import org.junit.Before
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.mobs.scene.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SceneEditorDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitForIdle()
    }

    @Test fun saveAndApplyAreSeparateAndTextSourcesCanBeAdded() {
        val collection = SceneCollection.initial()
        val calls = mutableListOf<Pair<Scene, Boolean>>()
        compose.setContent { MaterialTheme {
            SceneEditor(collection, DualCameraSupport(false, "Single camera only"),
                onSave = { scene, apply -> calls += scene to apply; true }, onDelete = {}, onClose = {})
        } }
        compose.onNodeWithContentDescription("Add source").performClick()
        compose.onNodeWithText("Text", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertFalse(calls.last().second)
            assertEquals(2, calls.last().first.sources.size)
            assertEquals(SourceKind.TEXT, calls.last().first.sources.last().kind)
        }
        compose.onNodeWithText("Apply to output").performClick()
        compose.runOnIdle { assertTrue(calls.last().second) }
    }

    @Test fun browserSourceRequiresValidHttpsLinkAndSavesItToTheScene() {
        val collection = SceneCollection.initial()
        val saved = mutableListOf<Scene>()
        compose.setContent { MaterialTheme {
            SceneEditor(collection, DualCameraSupport(), onSave = { scene, _ -> saved += scene; true }, onDelete = {}, onClose = {})
        } }
        compose.onNodeWithContentDescription("Add source").performClick()
        compose.onNodeWithText("Chat / web link").performClick()
        compose.onNodeWithText("Add source").assertIsNotEnabled()
        compose.onNodeWithText("Widget URL").performTextInput("https://example.com/widget")
        compose.onNodeWithText("Add source").assertIsEnabled().performClick()
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals("https://example.com/widget", saved.single().sources.last().url)
            assertEquals(SourceKind.BROWSER, saved.single().sources.last().kind)
        }
    }

    @Test fun cameraRotationCorrectionCanBeSelectedAndSaved() {
        val collection = SceneCollection.initial()
        val source = collection.active.sources.single()
        val saved = mutableListOf<Scene>()
        compose.setContent { MaterialTheme {
            SceneEditor(collection, DualCameraSupport(), onSave = { scene, _ -> saved += scene; true }, onDelete = {}, onClose = {})
        } }
        compose.onNodeWithTag("scene-source-${source.id}").performClick()
        compose.onNodeWithText("180°").performScrollTo().performClick()
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(180, saved.single().sources.single().rotation) }
    }

    @Test fun draggingUpdatesNormalizedSourceBoundsWithoutChangingTheBroadcast() {
        val source = SceneSource(name = "Title", kind = SourceKind.TEXT, text = "Hello", bounds = SourceBounds(0.1f, 0.1f, 0.4f, 0.3f))
        val scene = Scene(name = "Layout", sources = listOf(source))
        val saved = mutableListOf<Pair<Scene, Boolean>>()
        compose.setContent { MaterialTheme {
            SceneEditor(SceneCollection(listOf(scene), scene.id), DualCameraSupport(),
                onSave = { draft, apply -> saved += draft to apply; true }, onDelete = {}, onClose = {})
        } }
        compose.onNodeWithTag("scene-source-${source.id}").performTouchInput {
            swipe(center, center + Offset(40f, 25f), 300)
        }
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            val moved = saved.single().first.sources.single()
            assertTrue(moved.bounds.x > source.bounds.x)
            assertTrue(moved.bounds.y > source.bounds.y)
            assertFalse(saved.single().second)
        }
    }
}
