package dev.mobs.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [TextOverlay] into a bitmap at output resolution, so the GPU samples it 1:1. Called
 * only when the text or style changes; the engine caches the result.
 */
object TextRasterizer {

    /** Returns null for blank text. */
    fun rasterize(overlay: TextOverlay, outputWidth: Int, outputHeight: Int): Bitmap? {
        val text = overlay.text.trim()
        if (text.isEmpty()) return null
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = overlay.textSize * outputHeight
            color = overlay.textColor
            typeface = Typeface.DEFAULT_BOLD
        }
        val padding = ceil(paint.textSize * PADDING_EM).toInt()
        val maxTextWidth = max(1, (overlay.maxWidth * outputWidth).toInt() - 2 * padding)
        val textWidth = min(ceil(Layout.getDesiredWidth(text, paint)).toInt(), maxTextWidth).coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
            .setAlignment(overlay.anchor.alignment())
            .setIncludePad(false)
            .setMaxLines(MAX_LINES)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val bitmap = createBitmap(textWidth + 2 * padding, layout.height + 2 * padding)
        val canvas = Canvas(bitmap)
        val radius = padding * CORNER_FRACTION
        canvas.drawRoundRect(
            RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = overlay.backgroundColor },
        )
        canvas.translate(padding.toFloat(), padding.toFloat())
        layout.draw(canvas)
        return bitmap
    }

    private fun OverlayAnchor.alignment() = when (horizontal) {
        OverlayAnchor.Horizontal.LEFT -> Layout.Alignment.ALIGN_NORMAL
        OverlayAnchor.Horizontal.CENTER -> Layout.Alignment.ALIGN_CENTER
        OverlayAnchor.Horizontal.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
    }

    private const val PADDING_EM = 0.4f
    private const val CORNER_FRACTION = 0.6f
    private const val MAX_LINES = 3
}
