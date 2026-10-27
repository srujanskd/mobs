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
