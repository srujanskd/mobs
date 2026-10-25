package dev.mobs.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import dev.mobs.scene.DualCameraSupport
import dev.mobs.scene.SourceKind

internal data class CameraPair(val rear: String, val front: String)

internal fun findCameraPair(manager: CameraManager): CameraPair? {
    if (Build.VERSION.SDK_INT < 30) return null
    return manager.concurrentCameraIds.asSequence().mapNotNull { ids ->
        val rear = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val front = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        if (rear != null && front != null && supportsSize(manager, rear, Size(1920, 1080)) && supportsSize(manager, front, Size(1280, 720))) CameraPair(rear, front) else null
    }.firstOrNull()
}

internal fun supportsSize(manager: CameraManager, id: String, size: Size): Boolean =
    manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(SurfaceTexture::class.java)?.contains(size) == true

fun sceneDualCameraSupport(context: Context): DualCameraSupport = try {
    val pair = findCameraPair(context.getSystemService(CameraManager::class.java))
    if (pair != null) DualCameraSupport(true, "Front + rear available. Device streaming and power validation still required.")
    else DualCameraSupport(false, "This phone does not advertise concurrent front + rear capture at the required sizes. Single-camera scenes are available.")
} catch (_: Exception) {
    DualCameraSupport(false, "Unable to query concurrent cameras on this phone")
}

internal fun cameraId(manager: CameraManager, kind: SourceKind): String = manager.cameraIdList.first {
    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
        if (kind == SourceKind.REAR_CAMERA) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
}

/** Logical-camera zoom allows Android to choose an optical lens when the device supports it. */
fun sceneLensOptions(context: Context, kind: SourceKind, dual: Boolean = false): List<Float> = try {
    val manager = context.getSystemService(CameraManager::class.java)
    val pair = if (dual) findCameraPair(manager) else null
    val id = if (pair == null) cameraId(manager, kind) else if (kind == SourceKind.REAR_CAMERA) pair.rear else pair.front
    val info = manager.getCameraCharacteristics(id)
    val range = zoomRange(info)
    (listOf(range.start) + listOf(1f, 2f, 3f, 5f).filter { it in range }).distinct().sorted()
} catch (_: Exception) { listOf(1f) }

/** Pinch uses the full advertised range, including values between the lens shortcuts. */
fun scenePinchZoomRange(context: Context, kind: SourceKind, dual: Boolean): ClosedFloatingPointRange<Float> = try {
    val manager = context.getSystemService(CameraManager::class.java)
    val pair = if (dual) findCameraPair(manager) else null
    val id = if (pair == null) cameraId(manager, kind) else if (kind == SourceKind.REAR_CAMERA) pair.rear else pair.front
    val range = zoomRange(manager.getCameraCharacteristics(id))
    // Crossing 1x in concurrent mode needs a camera restart. Keep it an explicit lens action.
    (if (dual) range.start.coerceAtLeast(1f) else range.start)..range.endInclusive
} catch (_: Exception) { 1f..1f }

private fun zoomRange(info: CameraCharacteristics): ClosedFloatingPointRange<Float> {
    val range = if (Build.VERSION.SDK_INT >= 30) info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
    val minimum = (range?.lower ?: 1f).coerceIn(.1f, 100f)
    val maximum = (range?.upper ?: info.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceIn(minimum, 100f)
    return minimum..maximum
}
