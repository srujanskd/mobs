package dev.mobs.render

import org.junit.Assert.assertEquals
import org.junit.Test

class LogoDecodeSizeTest {
    @Test
    fun squareLogoIsLimitedByHeight() {
        // 15% of 1080 = 162 px tall.
        assertEquals(162 to 162, logoDecodeSize(1024, 1024, 1920, 1080))
    }

    @Test
    fun wideLogoIsLimitedByWidthWithoutDistortion() {
        // 30% of 1920 = 576 px wide; 4000x1000 scales to 576x144.
        assertEquals(576 to 144, logoDecodeSize(4000, 1000, 1920, 1080))
    }

    @Test
    fun smallImagesAreNeverUpscaled() {
        assertEquals(64 to 32, logoDecodeSize(64, 32, 1920, 1080))
    }

    @Test
    fun extremeAspectRatiosKeepAtLeastOnePixel() {
        assertEquals(576 to 1, logoDecodeSize(100_000, 10, 1920, 1080))
    }
}
