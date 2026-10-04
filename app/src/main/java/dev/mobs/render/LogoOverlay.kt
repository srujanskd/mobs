package dev.mobs.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An image source drawn into the encoded frame. [image] is already decoded at its output pixel
 * size, so the GPU samples it 1:1 and no full-resolution original stays in memory.
 */
class LogoOverlay(
    val image: Bitmap,
    val anchor: OverlayAnchor = OverlayAnchor.TOP_RIGHT,
    /** Gap to the frame edge as a fraction of output height, applied on both axes. */
    val margin: Float = 0.04f,
) {
    fun withAnchor(anchor: OverlayAnchor) = LogoOverlay(image, anchor, margin)
}

/**
 * The pixel size to decode a [sourceWidth] x [sourceHeight] image at: [maxHeight] of the output
 * height, narrowed to [maxWidth] of the output width for wide logos, never upscaled, and at least
 * one pixel on each side.
 */
fun logoDecodeSize(
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    maxHeight: Float = 0.15f,
    maxWidth: Float = 0.3f,
): Pair<Int, Int> {
    val scale = min(
        1f,
        min(maxHeight * outputHeight / sourceHeight, maxWidth * outputWidth / sourceWidth),
    )
    return max(1, (sourceWidth * scale).roundToInt()) to max(1, (sourceHeight * scale).roundToInt())
}

fun interface LogoDecoder {
    /**
     * Returns the decoded image, or null when [uri] cannot be read as an image. The URI is passed
     * as text so callers and their tests need no Android `Uri`.
     */
    suspend fun decode(uri: String, outputWidth: Int, outputHeight: Int): Bitmap?
}

/** Decodes straight to the target size, so a 12 MP photo never exists in memory at full size. */
class ContentLogoDecoder(context: Context) : LogoDecoder {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun decode(uri: String, outputWidth: Int, outputHeight: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri.toUri())) { decoder, info, _ ->
                val (width, height) = logoDecodeSize(info.size.width, info.size.height, outputWidth, outputHeight)
                decoder.setTargetSize(width, height)
                // GL uploads read pixels from the CPU; hardware bitmaps cannot be uploaded.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            Log.w(TAG, "Logo decode failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val TAG = "LogoDecoder"
    }
}
