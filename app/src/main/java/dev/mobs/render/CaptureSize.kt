package dev.mobs.render

import dev.mobs.scene.SceneSource

internal data class CaptureSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/**
 * Capture enough pixels for the center-cropped viewport, not an entire output-sized
 * image for every inset. Preserve the previous aspect ratio and maximum size, and
 * use only advertised sizes. A 640x360 floor avoids tiny, low-detail camera modes.
 */
internal fun cameraCaptureSize(
    supported: List<CaptureSize>,
    fallback: CaptureSize,
    output: CaptureSize,
    source: SceneSource,
): CaptureSize {
    val rotated = source.rotation == 90 || source.rotation == 270
    val viewportWidth = output.width * source.bounds.width
    val viewportHeight = output.height * source.bounds.height
    val scale = maxOf(
        (if (rotated) viewportHeight else viewportWidth) / fallback.width,
        (if (rotated) viewportWidth else viewportHeight) / fallback.height,
        minOf(640, fallback.width).toFloat() / fallback.width,
        minOf(360, fallback.height).toFloat() / fallback.height,
    ).coerceAtMost(1f)
    return supported.filter {
        it.width <= fallback.width && it.height <= fallback.height &&
            it.width.toLong() * fallback.height == it.height.toLong() * fallback.width &&
            it.width >= fallback.width * scale && it.height >= fallback.height * scale
    }.minByOrNull { it.pixels } ?: fallback
}

/** Keep the display aspect ratio while bounding preview GPU work to 720p. */
internal fun previewBufferSize(width: Int, height: Int): CaptureSize {
    require(width > 0 && height > 0)
    val scale = minOf(1.0, 1280.0 / maxOf(width, height), 720.0 / minOf(width, height))
    return CaptureSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
}
