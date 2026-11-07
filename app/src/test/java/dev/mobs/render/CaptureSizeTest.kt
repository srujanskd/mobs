package dev.mobs.render

import dev.mobs.scene.SceneSource
import dev.mobs.scene.SourceBounds
import dev.mobs.scene.SourceKind
import org.junit.Assert.*
import org.junit.Test

class CaptureSizeTest {
    private val full = CaptureSize(1920, 1080)
    private val hd = CaptureSize(1280, 720)
    private val small = CaptureSize(640, 360)
    private val sizes = listOf(CaptureSize(320, 180), small, hd, full, CaptureSize(3840, 2160))
    private val front = SceneSource(name = "Front", kind = SourceKind.FRONT_CAMERA,
        bounds = SourceBounds(.64f, .6f, .32f, .32f))

    @Test fun previewPreservesDisplayAspectWithoutPhoneResolutionBuffers() {
        assertEquals(CaptureSize(1280, 576), previewBufferSize(2400, 1080))
        assertEquals(CaptureSize(576, 1280), previewBufferSize(1080, 2400))
        assertEquals(CaptureSize(1280, 720), previewBufferSize(1920, 1080))
        assertEquals(CaptureSize(640, 480), previewBufferSize(640, 480))
        for (w in 100..3000 step 113) for (h in 100..3000 step 137) {
            val result = previewBufferSize(w, h)
            assertTrue(result.width <= w && result.height <= h)
            assertTrue(result.pixels <= 1280L * 720)
            assertTrue("Aspect error must stay within rounding one buffer pixel",
                kotlin.math.abs(result.width.toLong() * h - result.height.toLong() * w) <= maxOf(w, h))
        }
    }

    @Test fun insetCameraDoesNotCaptureFourTimesThePixelsItDisplays() {
        assertEquals(small, cameraCaptureSize(sizes, hd, full, front))
    }
    @Test fun fullAndTallCroppedCamerasRetainTheirPixelBudget() {
        assertEquals(full, cameraCaptureSize(sizes, full, full, front.copy(bounds = SourceBounds())))
        assertEquals(full, cameraCaptureSize(sizes, full, full, front.copy(bounds = SourceBounds(width = .5f))))
        assertEquals(hd, cameraCaptureSize(sizes, hd, full, front.copy(bounds = SourceBounds())))
    }
    @Test fun rotationAccountsForThePixelsDiscardedByCenterCrop() {
        assertEquals(hd, cameraCaptureSize(sizes, hd, full, front.copy(rotation = 90)))
        assertEquals(hd, cameraCaptureSize(sizes, hd, full, front.copy(rotation = 270)))
        assertEquals(small, cameraCaptureSize(sizes, hd, full, front.copy(rotation = 180)))
    }
    @Test fun onlyAdvertisedMatchingAspectSizesAreSelected() {
        assertEquals(hd, cameraCaptureSize(listOf(CaptureSize(640, 480), hd), hd, full, front))
        assertEquals(hd, cameraCaptureSize(listOf(hd), hd, full, front))
        assertEquals(small, cameraCaptureSize(sizes.reversed(), hd, full, front.copy(bounds = SourceBounds(width = .04f, height = .04f))))
    }
    @Test fun largerInsetRequestsEnoughDetailWithoutExceedingPreviousCaptureSize() {
        val enlarged = front.copy(bounds = SourceBounds(width = .5f, height = .5f))
        assertEquals(hd, cameraCaptureSize(sizes, hd, full, enlarged))
        assertEquals(hd, cameraCaptureSize(sizes, full, full, enlarged))
    }
}
