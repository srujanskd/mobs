package dev.mobs.scene

import java.util.UUID

/** Durable scene data. No camera, surface, bitmap, or encoder handles belong here. */
enum class SourceKind { REAR_CAMERA, FRONT_CAMERA, TEXT, IMAGE, COLOR, BROWSER }

data class SourceBounds(val x: Float = 0f, val y: Float = 0f, val width: Float = 1f, val height: Float = 1f) {
    init {
        require(listOf(x, y, width, height).all { it.isFinite() })
        require(width in MIN_SIZE..1f && height in MIN_SIZE..1f)
        require(x >= 0f && y >= 0f && x + width <= 1.00001f && y + height <= 1.00001f)
    }

    fun move(dx: Float, dy: Float) = copy(x = (x + dx).coerceIn(0f, 1f - width), y = (y + dy).coerceIn(0f, 1f - height))
    fun resize(dw: Float, dh: Float) = copy(width = (width + dw).coerceIn(MIN_SIZE, 1f - x), height = (height + dh).coerceIn(MIN_SIZE, 1f - y))

    companion object { const val MIN_SIZE = 0.04f }
}

data class SceneSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: SourceKind,
    val bounds: SourceBounds = SourceBounds(),
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
    val text: String = "",
    /** A file name inside the owned scene media directory, never an external URI. */
    val media: String = "",
    val mirrored: Boolean = false,
    val zoom: Float = 1f,
    val url: String = "",
    val color: Int = 0xFF17231F.toInt(),
    val rotation: Int = 0,
) {
    init {
        require(id.isNotBlank() && id.length <= 64)
        require(name.isNotBlank() && name.length <= 80)
        require(opacity.isFinite() && opacity in 0f..1f)
        require(rotation in setOf(0, 90, 180, 270))
        require(zoom.isFinite() && zoom in 0.1f..100f)
        require(url.length <= 2048 && (url.isEmpty() || isBrowserUrl(url)))
        require(text.length <= 500)
        require(media.isEmpty() || media.matches(Regex("[a-zA-Z0-9-]+\\.png")))
    }
}

data class Scene(val id: String = UUID.randomUUID().toString(), val name: String, val sources: List<SceneSource>) {
    init {
        require(id.isNotBlank() && id.length <= 64)
        require(name.isNotBlank() && name.length <= 80)
        require(sources.size <= MAX_SOURCES)
        require(sources.map { it.id }.distinct().size == sources.size)
        require(sources.count { it.kind == SourceKind.FRONT_CAMERA } <= 1)
        require(sources.count { it.kind == SourceKind.REAR_CAMERA } <= 1)
    }

    val cameras: Set<SourceKind> get() = sources.filter { it.visible && it.opacity > 0f && it.kind in CAMERA_KINDS }.map { it.kind }.toSet()
    fun update(source: SceneSource) = copy(sources = sources.map { if (it.id == source.id) source else it })
    fun duplicate() = copy(id = UUID.randomUUID().toString(), name = (name.take(70) + " copy"), sources = sources.map { it.copy(id = UUID.randomUUID().toString()) })

    companion object {
        const val MAX_SOURCES = 12
        val CAMERA_KINDS = setOf(SourceKind.REAR_CAMERA, SourceKind.FRONT_CAMERA)
    }
}

data class SceneCollection(val scenes: List<Scene>, val activeId: String) {
    init {
        require(scenes.size in 1..MAX_SCENES)
        require(scenes.map { it.id }.distinct().size == scenes.size)
        require(scenes.any { it.id == activeId })
    }
    val active: Scene get() = scenes.first { it.id == activeId }
    fun save(scene: Scene) = copy(scenes = if (scenes.any { it.id == scene.id }) scenes.map { if (it.id == scene.id) scene else it } else scenes + scene)
    fun delete(id: String): SceneCollection {
        require(scenes.size > 1)
        val remaining = scenes.filterNot { it.id == id }
        return SceneCollection(remaining, if (activeId == id) remaining.first().id else activeId)
    }
    companion object {
        const val MAX_SCENES = 24
        fun initial(): SceneCollection {
            val camera = Scene(name = "Camera", sources = listOf(SceneSource(name = "Rear camera", kind = SourceKind.REAR_CAMERA)))
            return SceneCollection(listOf(camera), camera.id)
        }
    }
}

/** Device-reported capability, not a claim that a phone passed the streaming soak. */
data class DualCameraSupport(val available: Boolean = false, val reason: String = "Checking dual camera support")

/** Only network pages are accepted; local files and script URLs are never scene sources. */
fun isBrowserUrl(value: String): Boolean = try {
    val uri = java.net.URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
} catch (_: Exception) { false }

/** Hardware-reported zoom is separate from the saved scene's requested setting. */
data class CameraZoomState(val requested: Float, val actual: Float? = null, val failed: Boolean = false) {
    val confirmed: Boolean get() = actual != null && kotlin.math.abs(actual - requested) < .03f && !failed
}
