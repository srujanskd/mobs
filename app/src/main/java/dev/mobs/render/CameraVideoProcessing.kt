package dev.mobs.render

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest

/** Use the phone's automatic color/exposure pipeline without slower still-photo processing. */
internal fun CaptureRequest.Builder.configureVideoProcessing(info: CameraCharacteristics) {
    fun supported(key: CaptureRequest.Key<Int>, modes: IntArray?, preferred: Int) {
        if (modes?.contains(preferred) == true) set(key, preferred)
    }
    supported(CaptureRequest.CONTROL_MODE, info.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES), CaptureRequest.CONTROL_MODE_AUTO)
    supported(CaptureRequest.CONTROL_AE_MODE, info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES), CaptureRequest.CONTROL_AE_MODE_ON)
    supported(CaptureRequest.CONTROL_AWB_MODE, info.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES), CaptureRequest.CONTROL_AWB_MODE_AUTO)
    val focus = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
    val focusMode = if (focus?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true)
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_OFF
    supported(CaptureRequest.CONTROL_AF_MODE, focus, focusMode)
    supported(CaptureRequest.NOISE_REDUCTION_MODE, info.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), CaptureRequest.NOISE_REDUCTION_MODE_FAST)
    supported(CaptureRequest.EDGE_MODE, info.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), CaptureRequest.EDGE_MODE_FAST)
    supported(CaptureRequest.TONEMAP_MODE, info.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES), CaptureRequest.TONEMAP_MODE_FAST)
    supported(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, info.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES), CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
}
