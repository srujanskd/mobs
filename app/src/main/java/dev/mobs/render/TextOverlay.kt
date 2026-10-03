package dev.mobs.render

/**
 * A text source drawn into the encoded frame. Sizes are fractions of the output so the same overlay
 * suits any profile; it is rasterized once per change, never per frame.
 */
data class TextOverlay(
    val text: String,
    val anchor: OverlayAnchor = OverlayAnchor.TOP_CENTER,
    /** Glyph size as a fraction of output height. */
    val textSize: Float = 0.055f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val backgroundColor: Int = 0x99000000.toInt(),
    /** Text wraps beyond this fraction of output width. */
    val maxWidth: Float = 0.8f,
    /** Gap to the frame edge as a fraction of output height, applied on both axes. */
    val margin: Float = 0.04f,
)

enum class OverlayAnchor(val horizontal: Horizontal, val vertical: Vertical) {
    TOP_LEFT(Horizontal.LEFT, Vertical.TOP),
    TOP_CENTER(Horizontal.CENTER, Vertical.TOP),
    TOP_RIGHT(Horizontal.RIGHT, Vertical.TOP),
    BOTTOM_LEFT(Horizontal.LEFT, Vertical.BOTTOM),
    BOTTOM_CENTER(Horizontal.CENTER, Vertical.BOTTOM),
    BOTTOM_RIGHT(Horizontal.RIGHT, Vertical.BOTTOM),
    ;

    enum class Horizontal { LEFT, CENTER, RIGHT }
    enum class Vertical { TOP, BOTTOM }
}
