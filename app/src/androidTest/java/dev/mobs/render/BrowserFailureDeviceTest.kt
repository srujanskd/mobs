package dev.mobs.render

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserFailureDeviceTest {
    @Test fun rendererCrashReportsAnOverlayErrorAndAllowsANewDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failed = CountDownLatch(1)
        // Android's documented renderer-crash fixture. Scene URLs cannot use this scheme.
        val broken = BrowserSource(context, "chrome://crash", 64, 64, onError = { failed.countDown() }) { _, complete -> complete() }
        try {
            assertTrue("A renderer crash must report an overlay error without killing the app", failed.await(10, TimeUnit.SECONDS))
        } finally { broken.close() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val recovered = CountDownLatch(1)
        val replacement = BrowserSource(context, "data:text/html,<body style='background:lime'>", 64, 64) { bitmap, complete ->
            try {
                if (Color.green(bitmap.getPixel(32, 32)) > 240) recovered.countDown()
            } finally { complete() }
        }
        try { assertTrue("A replacement widget must render", recovered.await(10, TimeUnit.SECONDS)) }
        finally { replacement.close() }
    }
}
