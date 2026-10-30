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
    @Test fun thermalPacingReducesCompositionAndRecoversOnTheSameSurface() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val errors = CopyOnWriteArrayList<String>()
        val frames = java.util.concurrent.atomic.AtomicInteger()
        val ready = java.util.concurrent.CountDownLatch(1)
        val consumer = android.os.HandlerThread("ThermalTestConsumer").apply { start() }
        val reader = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use {
            frames.incrementAndGet()
            ready.countDown()
        } }, android.os.Handler(consumer.looper))
        val renderer = SceneRenderer(context, context.cacheDir, 320, 180, 30, error = errors::add)
        try {
            renderer.setHeatMode(dev.mobs.stream.HeatMode.HOT)
            renderer.start(reader.surface, Scene(name = "Thermal", sources = listOf(
                SceneSource(name = "Color", kind = SourceKind.COLOR, color = 0xFF00FF00.toInt()),
            )))
            assertTrue(ready.await(8, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(300)
            val hotStart = frames.get()
            Thread.sleep(2000)
            val hotFrames = frames.get() - hotStart
            assertTrue("Hot composition should stay live at <=15 fps: $hotFrames frames", hotFrames in 20..32)
            renderer.setHeatMode(dev.mobs.stream.HeatMode.NORMAL)
            Thread.sleep(300)
            val coolStart = frames.get()
            Thread.sleep(2000)
            val coolFrames = frames.get() - coolStart
            assertTrue("Cooling must restore normal cadence: $coolFrames frames", coolFrames >= 50)
            assertTrue(errors.toString(), errors.isEmpty())
        } finally { renderer.stop(); reader.close(); consumer.quitSafely(); consumer.join(2000) }
    }

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

    @Test fun browserStateSurvivesSceneCutsAndDifferentSourceIds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val errors = CopyOnWriteArrayList<String>()
        val loads = java.util.concurrent.atomic.AtomicInteger()
        val reader = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3)
        val renderer = SceneRenderer(context, context.cacheDir, 320, 180, 30, error = errors::add,
            browserFactory = { ctx, _, w, h, failed, frame ->
                assertTrue("Browser width must match full or half output bounds", w == 320 || w == 160)
                assertEquals("Browser pixels must match the output height", 180, h)
                val color = if (loads.incrementAndGet() == 1) "#00ff00" else "#ff0000"
                val html = "<html><body style='margin:0'><div style='position:absolute;left:0;top:0;width:4000px;height:4000px;background:$color'></div></body></html>"
                BrowserSource(ctx, "data:text/html," + java.net.URLEncoder.encode(html, "UTF-8").replace("+", "%20"), w, h, failed, onFrame = frame)
            })
        val chat = SceneSource(name = "Chat", kind = SourceKind.BROWSER, url = "https://chat.example/widget")
        val first = Scene(name = "Chat", sources = listOf(chat))
        val away = Scene(name = "Away", sources = emptyList())
        try {
            renderer.start(reader.surface, first)
            awaitPixel(reader, 160, 90, 0, 255, 0)
            renderer.update(away)
            awaitPixel(reader, 160, 90, 0, 0, 0)
            renderer.update(first)
            awaitPixel(reader, 160, 90, 0, 255, 0)
            val duplicate = first.duplicate().let { scene -> scene.copy(sources = scene.sources.map {
                it.copy(bounds = SourceBounds(0f, 0f, .5f, 1f))
            }) }
            renderer.update(duplicate)
            awaitPixel(reader, 280, 90, 0, 0, 0)
            awaitPixel(reader, 40, 90, 0, 255, 0)
            renderer.update(first.update(chat.copy(visible = false)))
            awaitPixel(reader, 40, 90, 0, 0, 0)
            renderer.update(first)
            awaitPixel(reader, 160, 90, 0, 255, 0)
            assertEquals("Changing scenes must not reload the chat document", 1, loads.get())
            renderer.refresh(first)
            awaitPixel(reader, 160, 90, 255, 0, 0)
            assertEquals("Explicit refresh must still reload the document", 2, loads.get())
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
