package dev.mobs.render

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPlacementTest {
    private val delta = 0.001f

    private fun place(anchor: OverlayAnchor, width: Int = 480, height: Int = 108) =
        placeOverlay(width, height, 1920, 1080, anchor, margin = 0.04f)

    @Test
    fun sizeMatchesBitmapPixelsSoTextIsNotStretched() {
        val placement = place(OverlayAnchor.TOP_LEFT)
        assertEquals(25f, placement.widthPercent, delta)
        assertEquals(10f, placement.heightPercent, delta)
    }

    @Test
    fun marginIsTheSamePixelGapOnBothAxes() {
        val placement = place(OverlayAnchor.TOP_LEFT)
        // 4% of 1080 = 43.2 px; as a share of 1920 that is 2.25%.
        assertEquals(2.25f, placement.leftPercent, delta)
        assertEquals(4f, placement.topPercent, delta)
    }

    @Test
    fun anchorsPositionTheTopLeftCornerInsideTheFrame() {
        place(OverlayAnchor.TOP_CENTER).run {
            assertEquals(37.5f, leftPercent, delta)
            assertEquals(4f, topPercent, delta)
        }
        place(OverlayAnchor.BOTTOM_RIGHT).run {
            assertEquals(100f - 25f - 2.25f, leftPercent, delta)
            assertEquals(100f - 10f - 4f, topPercent, delta)
        }
        place(OverlayAnchor.BOTTOM_LEFT).run {
            assertEquals(2.25f, leftPercent, delta)
            assertEquals(86f, topPercent, delta)
        }
    }

    @Test
    fun oversizedBitmapShrinksUniformlyAndStaysInFrame() {
        val placement = place(OverlayAnchor.TOP_RIGHT, width = 3840, height = 432)
        assertEquals(100f, placement.widthPercent, delta)
        assertEquals(20f, placement.heightPercent, delta)
        assertEquals(0f, placement.leftPercent, delta)
        assertEquals(4f, placement.topPercent, delta)
    }
}
