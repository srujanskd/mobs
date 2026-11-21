package dev.mobs.render

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import dev.mobs.stream.HeatMode

/**
 * How much ISP work a camera request asks for. The main camera always uses [FULL]. The inset of
 * a two-camera scene is drawn small enough that edge sharpening and stabilization are invisible,
 * so it uses [LIGHT] by default and [MINIMAL] at moderate heat, before it is paused entirely.
 */
internal enum class VideoProcessing { FULL, LIGHT, MINIMAL }

internal data class CameraCaptureProfile(val fps: Int, val processing: VideoProcessing)

/** The inset's default rate. The main camera drives output cadence, so the stream stays at full rate. */
internal const val INSET_FPS = 15
/** The inset's rate at moderate heat, while [InsetCameraPause] waits out its grace period. */
internal const val INSET_WARM_FPS = 10

internal fun cameraCaptureProfile(inset: Boolean, mode: HeatMode, fps: Int): CameraCaptureProfile {
    val base = minOf(fps, mode.videoFps)
    return when {
        !inset -> CameraCaptureProfile(base, VideoProcessing.FULL)
        mode == HeatMode.NORMAL -> CameraCaptureProfile(minOf(base, INSET_FPS), VideoProcessing.LIGHT)
        else -> CameraCaptureProfile(minOf(base, INSET_WARM_FPS), VideoProcessing.MINIMAL)
    }
}

/** Use the phone's automatic color/exposure pipeline without slower still-photo processing. */
internal fun CaptureRequest.Builder.configureVideoProcessing(
    info: CameraCharacteristics,
    level: VideoProcessing = VideoProcessing.FULL,
    stabilizationEnabled: Boolean = true,
) {
    /** Sets the first advertised mode; leaves the template default when none is advertised. */
    fun prefer(key: CaptureRequest.Key<Int>, modes: IntArray?, vararg choices: Int) {
        choices.firstOrNull { modes?.contains(it) == true }?.let { set(key, it) }
    }
    val reduced = level != VideoProcessing.FULL
    val minimal = level == VideoProcessing.MINIMAL
    prefer(CaptureRequest.CONTROL_MODE, info.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES), CaptureRequest.CONTROL_MODE_AUTO)
    prefer(CaptureRequest.CONTROL_AE_MODE, info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES), CaptureRequest.CONTROL_AE_MODE_ON)
    prefer(CaptureRequest.CONTROL_AWB_MODE, info.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES), CaptureRequest.CONTROL_AWB_MODE_AUTO)
    val focus = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
    val focusMode = if (focus?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true)
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_OFF
    prefer(CaptureRequest.CONTROL_AF_MODE, focus, focusMode)
    val noise = info.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
    if (minimal) prefer(CaptureRequest.NOISE_REDUCTION_MODE, noise, CaptureRequest.NOISE_REDUCTION_MODE_OFF, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
    else prefer(CaptureRequest.NOISE_REDUCTION_MODE, noise, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
    val edge = info.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
    if (reduced) prefer(CaptureRequest.EDGE_MODE, edge, CaptureRequest.EDGE_MODE_OFF, CaptureRequest.EDGE_MODE_FAST)
    else prefer(CaptureRequest.EDGE_MODE, edge, CaptureRequest.EDGE_MODE_FAST)
    prefer(CaptureRequest.TONEMAP_MODE, info.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES), CaptureRequest.TONEMAP_MODE_FAST)
    val aberration = info.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
    if (minimal) prefer(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, aberration,
        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
    else prefer(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, aberration, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
    // Crop-only cameras report zoom through SCALER_CROP_REGION, which electronic stabilization
    // can also change. Use optical stabilization there to preserve zoom confirmation.
    val hasZoomRatio = Build.VERSION.SDK_INT >= 30 && info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null
    val videoModes = info.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
    val stabilize = stabilizationEnabled && !reduced
    val videoMode = videoStabilizationMode(stabilize && hasZoomRatio, videoModes)
    prefer(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, videoModes, videoMode)
    val opticalMode = if (stabilize && videoMode == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
    prefer(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
        info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION), opticalMode)
}

/** Prefer stabilization that covers the SurfaceTexture used by the scene compositor. */
@SuppressLint("InlinedApi") // Use the API 33 mode only if the camera advertises its value.
internal fun videoStabilizationMode(enabled: Boolean, modes: IntArray?): Int = when {
    !enabled -> CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    modes?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) == true ->
        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
    modes?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true -> CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
    else -> CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
}
