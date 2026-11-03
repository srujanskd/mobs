package dev.mobs.render

/** Prefer a supported fixed rate. Never invent a Camera2 range when a limit is unavailable. */
internal fun captureFrameRate(ranges: List<IntRange>, desired: Int, fallback: Int): IntRange {
    return ranges.firstOrNull { it.first == desired && it.last == desired }
        // A 15..30 request is not a 15 fps ceiling. Prefer a real supported cap,
        // even if it is slightly lower, before falling back to a variable range.
        ?: ranges.takeIf { desired < fallback }?.filter { it.last <= desired }
            ?.maxWithOrNull(compareBy<IntRange> { it.last }.thenBy { it.first })
        ?: ranges.filter { desired in it }.minWithOrNull(compareBy<IntRange> { it.last }.thenBy { it.last - it.first })
        ?: ranges.firstOrNull { it.first == fallback && it.last == fallback }
        ?: ranges.filter { fallback in it }.minByOrNull { it.last - it.first }
        ?: error("Camera cannot capture at requested frame rate")
}
