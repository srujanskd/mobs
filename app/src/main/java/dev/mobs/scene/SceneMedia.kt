package dev.mobs.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private val importMutex = Mutex()

/** Copy picked media into owned, size-bounded storage so scenes survive revoked picker URIs. */
suspend fun importSceneImage(context: Context, uri: String): String = withContext(Dispatchers.IO) {
    importMutex.withLock {
    val directory = File(context.filesDir, "scene-media").apply { mkdirs() }
    require(directory.listFiles().orEmpty().sumOf { it.length() } < 128L * 1024 * 1024) { "Scene image storage is full" }
    val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri.toUri())) { decoder, info, _ ->
        val scale = minOf(1f, 1920f / info.size.width, 1080f / info.size.height)
        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val file = File(directory, "${UUID.randomUUID()}.png")
    try {
        file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        file.name
    } catch (error: Exception) { file.delete(); throw error }
    finally { image.recycle() }
}
}
