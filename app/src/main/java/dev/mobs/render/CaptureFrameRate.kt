package dev.mobs.render

/** Prefer a supported fixed rate. Never invent a Camera2 range when a limit is unavailable. */
internal fun captureFrameRate(ranges: List<IntRange>, desired: Int, fallback: Int): IntRange {
    return ranges.firstOrNull { it.first == desired && it.last == desired }
        ?: ranges.filter { desired in it }.minWithOrNull(compareBy<IntRange> { it.last }.thenBy { it.last - it.first })
        ?: ranges.firstOrNull { it.first == fallback && it.last == fallback }
        ?: ranges.filter { fallback in it }.minByOrNull { it.last - it.first }
        ?: error("Camera cannot capture at requested frame rate")
}
