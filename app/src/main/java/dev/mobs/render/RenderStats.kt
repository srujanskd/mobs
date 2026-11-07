package dev.mobs.render

/** Once-per-second work measurements. Submission time includes swap waits, not GPU completion. */
data class RenderStats(
    val compositionFps: Int = 0,
    val meanFrameMs: Double = 0.0,
    val maxFrameMs: Double = 0.0,
    val rearWidth: Int = 0,
    val rearHeight: Int = 0,
    val frontWidth: Int = 0,
    val frontHeight: Int = 0,
    val rearRequestedFpsMin: Int = 0,
    val rearRequestedFpsMax: Int = 0,
    val frontRequestedFpsMin: Int = 0,
    val frontRequestedFpsMax: Int = 0,
    val retainedBrowserDocuments: Int = 0,
    val glVendor: String? = null,
    val glRenderer: String? = null,
)
