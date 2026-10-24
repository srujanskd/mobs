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
            } finally { bitmap.recycle(); complete() }
        }
        try {
            assertTrue("Initial HTML did not reach the overlay", green.await(10, TimeUnit.SECONDS))
            assertTrue("JavaScript updates did not reach the overlay", blue.await(10, TimeUnit.SECONDS))
        } finally { source.close() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val stopped = frames.get()
        Thread.sleep(250)
        assertEquals(stopped, frames.get())
    }
}
