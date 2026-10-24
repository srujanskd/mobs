package dev.mobs.render

import android.graphics.PixelFormat
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobs.scene.Scene
import dev.mobs.scene.SceneSource
import dev.mobs.scene.SourceBounds
import dev.mobs.scene.SourceKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real EGL composition and pixel tests. These run on an emulator too, without a camera. */
@RunWith(AndroidJUnit4::class)
class SceneRendererDeviceTest {
    @Test fun sourcePlacementOrderOpacityAndSceneCutsReachTheOutputSurface() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val errors = CopyOnWriteArrayList<String>()
        val reader = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3)
        val renderer = SceneRenderer(context, File(context.filesDir, "scene-media"), 320, 180, 30, error = errors::add)
        val red = SceneSource(name = "Red", kind = SourceKind.COLOR, color = 0xFFFF0000.toInt())
        val blue = SceneSource(name = "Blue", kind = SourceKind.COLOR, bounds = SourceBounds(0.5f, 0f, 0.5f, 1f), color = 0xFF0000FF.toInt())
        val first = Scene(name = "First", sources = listOf(red, blue))
        try {
            renderer.start(reader.surface, first)
            awaitPixel(reader, 40, 90, 255, 0, 0)
            awaitPixel(reader, 280, 90, 0, 0, 255)
            renderer.update(first.update(blue.copy(opacity = 0.5f)))
            awaitPixel(reader, 280, 90, 128, 0, 128)
            renderer.update(first.copy(sources = listOf(blue, red)))
            awaitPixel(reader, 280, 90, 255, 0, 0)
            renderer.update(first.copy(sources = listOf(blue.copy(bounds = SourceBounds()), red.copy(bounds = SourceBounds(0f, 0f, 1f, 0.5f)))))
            awaitPixel(reader, 160, 20, 255, 0, 0)
            awaitPixel(reader, 160, 160, 0, 0, 255)
            renderer.update(Scene(name = "Empty", sources = emptyList()))
            awaitPixel(reader, 40, 90, 0, 0, 0)
            assertTrue(errors.toString(), errors.isEmpty())
        } finally { renderer.stop(); reader.close() }
    }

    @Test fun refreshReloadsImageContentsEvenWhenTheSourceAndFileNameHaveNotChanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "refresh-test-media").apply { mkdirs() }
        val file = File(directory, "image.png")
        fun image(color: Int) {
            android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
                file.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
        }
        val errors = CopyOnWriteArrayList<String>()
        val reader = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3)
        val renderer = SceneRenderer(context, directory, 320, 180, 30, error = errors::add)
        val scene = Scene(name = "Refresh", sources = listOf(SceneSource(name = "Image", kind = SourceKind.IMAGE, media = file.name)))
        try {
            image(android.graphics.Color.RED)
            renderer.start(reader.surface, scene)
            awaitPixel(reader, 160, 90, 255, 0, 0)
            image(android.graphics.Color.BLUE)
            renderer.update(scene)
            awaitPixel(reader, 160, 90, 255, 0, 0)
            renderer.refresh(scene)
            awaitPixel(reader, 160, 90, 0, 0, 255)
            assertTrue(errors.toString(), errors.isEmpty())
        } finally { renderer.stop(); reader.close(); directory.deleteRecursively() }
    }

    @Test fun repeatedSceneCutsAndRendererRestartsReleaseTheirThreads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val errors = CopyOnWriteArrayList<String>()
        repeat(8) {
            val reader = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3)
            val renderer = SceneRenderer(context, File(context.filesDir, "scene-media"), 320, 180, 30, error = errors::add)
            val source = SceneSource(name = "Color", kind = SourceKind.COLOR, color = 0xFF00FF00.toInt())
            val scene = Scene(name = "Soak", sources = listOf(source))
            try {
                renderer.start(reader.surface, scene)
                awaitPixel(reader, 160, 90, 0, 255, 0)
                repeat(40) { index -> renderer.update(scene.update(source.copy(color = if (index % 2 == 0) 0xFFFF0000.toInt() else 0xFF00FF00.toInt()))) }
                renderer.update(scene.update(source.copy(color = 0xFF0000FF.toInt())))
                awaitPixel(reader, 160, 90, 0, 0, 255)
            } finally { renderer.stop(); reader.close() }
        }
        Thread.sleep(300)
        assertTrue(errors.toString(), errors.isEmpty())
        assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "SceneCompositor" })
    }

    private fun awaitPixel(reader: ImageReader, x: Int, y: Int, red: Int, green: Int, blue: Int) {
        val deadline = System.nanoTime() + 8_000_000_000L
        var actual = "no image"
        while (System.nanoTime() < deadline) {
            reader.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val offset = y * plane.rowStride + x * plane.pixelStride
                val r = plane.buffer.get(offset).toInt() and 255
                val g = plane.buffer.get(offset + 1).toInt() and 255
                val b = plane.buffer.get(offset + 2).toInt() and 255
                actual = "$r,$g,$b"
                if (kotlin.math.abs(r - red) <= 3 && kotlin.math.abs(g - green) <= 3 && kotlin.math.abs(b - blue) <= 3) return
            }
            Thread.sleep(30)
        }
        fail("Expected $red,$green,$blue at $x,$y, got $actual")
    }
}
