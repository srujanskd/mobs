package dev.mobs.render

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowserSourceDeviceTest {
    @Test fun cssAnimationStartsAfterIdleAndKeepsFreshFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        val colors = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val sampling = java.util.concurrent.atomic.AtomicBoolean(false)
        val html = """<html><style>body{margin:0;background:rgb(0,255,0);animation:color 1s linear 2s infinite alternate}@keyframes color{from{background:rgb(0,255,0)}to{background:rgb(0,0,255)}}</style><body></body></html>"""
        val source = BrowserSource(context, "data:text/html," + URLEncoder.encode(html, "UTF-8").replace("+", "%20"), 320, 180) { bitmap, complete ->
            try {
                if (Color.green(bitmap.getPixel(40, 40)) > 240) ready.countDown()
                if (sampling.get()) colors.add(bitmap.getPixel(40, 40))
            } finally { complete() }
        }
        try {
            assertTrue("Initial page did not render", ready.await(10, TimeUnit.SECONDS))
            Thread.sleep(2500)
            sampling.set(true)
            Thread.sleep(1500)
            sampling.set(false)
            assertTrue("CSS animation after idle must keep fresh frames, got ${colors.size} colors", colors.size >= 25)
        } finally { source.close() }
    }

    @Test fun idleOverlaySkipsSnapshotsAndStillReceivesDelayedUpdates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val green = CountDownLatch(1)
        val blue = CountDownLatch(1)
        val frames = AtomicInteger()
        val html = """<html><body style="margin:0;background:#00ff00"><script>setTimeout(()=>{document.body.style.background='#0000ff'},5000)</script></body></html>"""
        val source = BrowserSource(context, "data:text/html," + URLEncoder.encode(html, "UTF-8").replace("+", "%20"), 320, 180) { bitmap, complete ->
            try {
                frames.incrementAndGet()
                val pixel = bitmap.getPixel(40, 40)
                if (Color.green(pixel) > 240) green.countDown()
                if (Color.blue(pixel) > 240) blue.countDown()
            } finally { complete() }
        }
        try {
            assertTrue("Initial page did not render", green.await(10, TimeUnit.SECONDS))
            Thread.sleep(1000) // Let initial load invalidations settle.
            val start = frames.get()
            Thread.sleep(2000)
            val idleFrames = frames.get() - start
            android.util.Log.i("BrowserCaptureTest", "Idle overlay: $idleFrames snapshots in 2 seconds")
            assertTrue("Unchanged pages should reuse their texture, got $idleFrames snapshots", idleFrames <= 4)
            assertTrue("A page update after idle must reach output", blue.await(8, TimeUnit.SECONDS))
        } finally { source.close() }
    }

    @Test fun thermalRateLimitCapsAnimatedSnapshotsAndRestoresWithoutReloading() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        val frames = AtomicInteger()
        val colors = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val html = """<html><body style="margin:0;background:rgb(0,255,0)"><script>let n=0;setInterval(()=>document.body.style.background='rgb('+(++n%200)+',255,0)',33)</script></body></html>"""
        val source = BrowserSource(context, "data:text/html," + URLEncoder.encode(html, "UTF-8").replace("+", "%20"), 320, 180) { bitmap, complete ->
            try {
                if (Color.green(bitmap.getPixel(40, 40)) > 240) {
                    frames.incrementAndGet()
                    colors.add(bitmap.getPixel(40, 40))
                    ready.countDown()
                }
            } finally { complete() }
        }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            source.setFrameRate(5)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(300)
            val start = frames.get()
            Thread.sleep(2000)
            val limited = frames.get() - start
            assertTrue("Hot overlays should stay live at <=5 fps, got $limited frames in 2s", limited in 5..11)
            assertTrue("JavaScript must still change the rendered page", colors.size >= 5)
            source.setFrameRate(30)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(300)
            val recovery = frames.get()
            Thread.sleep(1500)
            assertTrue("Cooling should restore animated capture", frames.get() - recovery >= 30)
        } finally { source.close() }
    }

    @Test fun animatedOverlayCapturesAtStreamCadenceAndReusesItsBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        val samples = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val buffers = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<android.graphics.Bitmap, Boolean>()))
        val colors = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val sampling = java.util.concurrent.atomic.AtomicBoolean(false)
        val html = """<html><body style="margin:0"><div id="box" style="position:absolute;width:4000px;height:4000px;background:rgb(0,255,0)"></div><script>let n=0;setInterval(()=>{document.getElementById('box').style.background='rgb('+(++n%200)+',255,0)'},33)</script></body></html>"""
        val source = BrowserSource(context, "data:text/html," + URLEncoder.encode(html, "UTF-8").replace("+", "%20"), 1920, 1080) { bitmap, complete ->
            try {
                val pixel = bitmap.getPixel(40, 40)
                if (Color.green(pixel) > 240) {
                    ready.countDown()
                    if (sampling.get()) {
                        samples.add(System.nanoTime())
                        buffers.add(bitmap)
                        colors.add(pixel)
                    }
                }
            } finally { complete() }
        }
        try {
            assertTrue("Page did not render", ready.await(10, TimeUnit.SECONDS))
            sampling.set(true)
            Thread.sleep(1500)
            sampling.set(false)
            android.util.Log.i("BrowserCaptureTest", "1920x1080: ${samples.size / 1.5} fps, ${colors.size} distinct colors, ${buffers.size} bitmap allocations")
            assertTrue("Expected at least 24 fps, got ${samples.size / 1.5}", samples.size >= 36)
            assertTrue("Captured frames must contain fresh JavaScript updates: ${colors.size}", colors.size >= 25)
            assertEquals("Steady-state capture must reuse its bitmap", 1, buffers.size)
        } finally { source.close() }
    }

    @Test fun slowUploadKeepsOneFrameIntactThroughResizeAndClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val held = java.util.concurrent.CompletableFuture<Pair<android.graphics.Bitmap, () -> Unit>>()
        val frames = AtomicInteger()
        val source = BrowserSource(context, "data:text/html,<html></html>", 128, 128) { bitmap, complete ->
            frames.incrementAndGet()
            held.complete(bitmap to complete)
        }
        try {
            val (bitmap, complete) = held.get(10, TimeUnit.SECONDS)
            source.setViewport(64, 64, true)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(250)
            assertEquals("Uploads must apply backpressure rather than queue frames", 1, frames.get())
            assertEquals(128, bitmap.width)
            assertFalse(bitmap.isRecycled)
            source.close()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertFalse("Closing must not recycle a bitmap still being uploaded", bitmap.isRecycled)
            complete()
            complete()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue("Returned buffers must be released after close", bitmap.isRecycled)
        } finally {
            source.close()
            held.getNow(null)?.second?.invoke()
        }
    }

    @Test fun transparentWebOverlayRendersAndJavascriptUpdatesContinueUntilClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val green = CountDownLatch(1)
        val blue = CountDownLatch(1)
        val frames = AtomicInteger()
        val html = """<html><body style="margin:0;background:transparent"><div id="chat" style="position:absolute;left:20px;top:20px;width:100px;height:100px;background:#00ff00"></div><script>setTimeout(function(){document.getElementById('chat').style.background='#0000ff'},2000)</script></body></html>"""
        val source = BrowserSource(context, "data:text/html," + URLEncoder.encode(html, "UTF-8").replace("+", "%20"), 200, 200) { bitmap, complete ->
            try {
                frames.incrementAndGet()
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                val pixel = bitmap.getPixel(80, 80)
                if (Color.green(pixel) > 240) green.countDown()
                if (Color.blue(pixel) > 240) blue.countDown()
            } finally { complete() }
        }
        try {
            assertTrue("Initial HTML did not reach the overlay", green.await(10, TimeUnit.SECONDS))
            source.setViewport(200, 200, false)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val inactiveFrames = frames.get()
            Thread.sleep(2300)
            assertEquals("Off-scene pages should not produce snapshots", inactiveFrames, frames.get())
            source.setViewport(150, 150, true)
            assertTrue("JavaScript updates must survive inactivity and resizing", blue.await(10, TimeUnit.SECONDS))
        } finally { source.close() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val stopped = frames.get()
        Thread.sleep(250)
        assertEquals(stopped, frames.get())
    }
}
