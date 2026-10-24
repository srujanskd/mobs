package dev.mobs.scene

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SceneStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store() = SceneStore(File(folder.root, "scenes.mobs"))

    @Test fun allSourcePropertiesAndSceneOrderSurviveReload() {
        val scene = Scene(name = "ನಗರ 🎥", sources = SourceKind.entries.map { kind -> SceneSource(
            name = kind.name, kind = kind, bounds = SourceBounds(0.1f, 0.2f, 0.3f, 0.4f),
            rotation = 180, mirrored = true, zoom = 2f, url = "https://example.com/widget?token=private",
            visible = false, locked = true, opacity = 0.6f, text = "Hello\nನಮಸ್ಕಾರ 👋", media = "owned-image.png", color = 0xFF445566.toInt(),
        ) })
        val collection = SceneCollection.initial().save(scene).copy(activeId = scene.id)
        store().save(collection)
        assertEquals(collection, store().load())
    }

    @Test fun versionOneScenesLoadWithoutMirroringAndKeepTheirSources() {
        val file = File(folder.root, "scenes.mobs")
        java.io.DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0x4D4F4253); out.writeInt(1); out.writeUTF("scene"); out.writeInt(1)
            out.writeUTF("scene"); out.writeUTF("Original"); out.writeInt(1)
            out.writeUTF("camera"); out.writeUTF("Front"); out.writeUTF("FRONT_CAMERA")
            listOf(0f, 0f, 1f, 1f).forEach(out::writeFloat)
            out.writeBoolean(true); out.writeBoolean(false); out.writeFloat(1f)
            out.writeUTF(""); out.writeUTF(""); out.writeInt(0)
        }
        val collection = store().load()
        val source = collection.active.sources.single()
        assertFalse(source.mirrored)
        assertEquals(1f, source.zoom)
        assertEquals(SourceKind.FRONT_CAMERA, source.kind)
        store().save(collection)
        assertEquals(collection, store().load())
    }

    @Test fun versionTwoScenesKeepMirrorZoomAndLinksWhenRotationIsAdded() {
        val file = File(folder.root, "scenes.mobs")
        java.io.DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(0x4D4F4253); out.writeInt(2); out.writeUTF("scene"); out.writeInt(1)
            out.writeUTF("scene"); out.writeUTF("Existing"); out.writeInt(1)
            out.writeUTF("rear"); out.writeUTF("Rear"); out.writeUTF("REAR_CAMERA")
            listOf(0f, 0f, 1f, 1f).forEach(out::writeFloat)
            out.writeBoolean(true); out.writeBoolean(false); out.writeFloat(1f)
            out.writeUTF(""); out.writeUTF(""); out.writeInt(0)
            out.writeBoolean(true); out.writeFloat(.6f); out.writeUTF("https://example.com/widget")
        }
        val loaded = store().load()
        val source = loaded.active.sources.single()
        assertEquals(0, source.rotation)
        assertTrue(source.mirrored)
        assertEquals(.6f, source.zoom)
        assertEquals("https://example.com/widget", source.url)
        store().save(loaded)
        assertEquals(loaded, store().load())
        assertEquals(180, loaded.active.update(source.copy(rotation = 180)).sources.single().rotation)
    }

    @Test fun webLinksRejectLocalFilesScriptsCredentialsAndInvalidHosts() {
        listOf("file:///private/file", "javascript:alert(1)", "https://", "http://example.com", "https://user:password@example.com", "https://bad host").forEach {
            assertFalse(it, isBrowserUrl(it))
        }
        assertTrue(isBrowserUrl("https://streamlabs.com/widgets/chat-box/v1/secret"))
        try { SceneSource(name = "Bad zoom", kind = SourceKind.REAR_CAMERA, zoom = Float.NaN); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun truncatedOrUnknownVersionNeverSilentlyReplacesSavedScenes() {
        val file = File(folder.root, "scenes.mobs")
        file.writeBytes(byteArrayOf(1, 2, 3))
        try { store().load(); fail("Corruption must be reported") } catch (_: Exception) { }
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        store().save(SceneCollection.initial())
        val bytes = file.readBytes().apply { this[7] = 99 }
        file.writeBytes(bytes)
        try { store().load(); fail("Unknown version must be reported") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun repeatedSceneEditsKeepOneBoundedCollectionAndNoTemporaryFiles() {
        var collection = SceneCollection.initial()
        repeat(500) { index ->
            val scene = collection.active.copy(name = "Session $index")
            collection = collection.save(scene)
            store().save(collection)
            assertEquals(collection, store().load())
        }
        assertEquals(listOf("scenes.mobs"), folder.root.list()!!.toList())
        assertTrue(File(folder.root, "scenes.mobs").length() < 4_096)
    }
}
