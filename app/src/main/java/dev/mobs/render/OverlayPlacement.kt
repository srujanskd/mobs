package dev.mobs.render

import kotlin.math.min

/**
 * Where a rasterized overlay sits in the output, in percent of output width and height with the
 * origin at the top-left. Width and height keep the bitmap's own pixel size, so text is never
 * stretched.
 */
data class OverlayPlacement(
    val leftPercent: Float,
    val topPercent: Float,
    val widthPercent: Float,
    val heightPercent: Float,
)

fun placeOverlay(
    bitmapWidth: Int,
    bitmapHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    anchor: OverlayAnchor,
    margin: Float,
): OverlayPlacement {
    // Shrink uniformly if the bitmap is larger than the frame; never distort it.
    val fit = min(1f, min(outputWidth.toFloat() / bitmapWidth, outputHeight.toFloat() / bitmapHeight))
    val width = bitmapWidth * fit / outputWidth * 100f
    val height = bitmapHeight * fit / outputHeight * 100f
    val marginX = margin * outputHeight / outputWidth * 100f
    val marginY = margin * 100f
    val left = when (anchor.horizontal) {
        OverlayAnchor.Horizontal.LEFT -> marginX
        OverlayAnchor.Horizontal.CENTER -> (100f - width) / 2f
        OverlayAnchor.Horizontal.RIGHT -> 100f - width - marginX
    }
    val top = when (anchor.vertical) {
        OverlayAnchor.Vertical.TOP -> marginY
        OverlayAnchor.Vertical.BOTTOM -> 100f - height - marginY
    }
    return OverlayPlacement(
        leftPercent = left.coerceIn(0f, 100f - width),
        topPercent = top.coerceIn(0f, 100f - height),
        widthPercent = width,
        heightPercent = height,
    )
}
