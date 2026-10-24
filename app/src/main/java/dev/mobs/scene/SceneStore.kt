package dev.mobs.scene

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Versioned, bounded scene storage. A failed write leaves the previous collection intact. */
class SceneStore(private val file: File) {
    @Synchronized
    fun load(): SceneCollection {
        if (!file.exists()) return SceneCollection.initial()
        require(file.length() <= MAX_BYTES) { "Scene collection is too large" }
        return file.inputStream().buffered().use { stream ->
            val input = DataInputStream(stream)
            require(input.readInt() == MAGIC) { "Unsupported scene file" }
            val version = input.readInt()
            require(version in 1..VERSION) { "Unsupported scene version" }
            val active = input.readUTF()
            val scenes = List(input.readInt().also { require(it in 1..SceneCollection.MAX_SCENES) }) {
                val id = input.readUTF()
                val name = input.readUTF()
                val sources = List(input.readInt().also { require(it in 0..Scene.MAX_SOURCES) }) {
                    SceneSource(
                        id = input.readUTF(), name = input.readUTF(), kind = SourceKind.valueOf(input.readUTF()),
                        bounds = SourceBounds(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()),
                        visible = input.readBoolean(), locked = input.readBoolean(), opacity = input.readFloat(),
                        text = input.readUTF(), media = input.readUTF(), color = input.readInt(),
                        mirrored = if (version >= 2) input.readBoolean() else false,
                        zoom = if (version >= 2) input.readFloat() else 1f,
                        url = if (version >= 2) input.readUTF() else "",
                        rotation = if (version >= 3) input.readInt() else 0,
                    )
                }
                Scene(id, name, sources)
            }
            require(input.read() == -1) { "Unexpected scene data" }
            SceneCollection(scenes, active)
        }
    }

    @Synchronized
    fun save(collection: SceneCollection) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(collection.activeId)
                output.writeInt(collection.scenes.size)
                collection.scenes.forEach { scene ->
                    output.writeUTF(scene.id)
                    output.writeUTF(scene.name)
                    output.writeInt(scene.sources.size)
                    scene.sources.forEach { source ->
                        output.writeUTF(source.id)
                        output.writeUTF(source.name)
                        output.writeUTF(source.kind.name)
                        with(source.bounds) { output.writeFloat(x); output.writeFloat(y); output.writeFloat(width); output.writeFloat(height) }
                        output.writeBoolean(source.visible)
                        output.writeBoolean(source.locked)
                        output.writeFloat(source.opacity)
                        output.writeUTF(source.text)
                        output.writeUTF(source.media)
                        output.writeInt(source.color)
                        output.writeBoolean(source.mirrored)
                        output.writeFloat(source.zoom)
                        output.writeUTF(source.url)
                        output.writeInt(source.rotation)
                    }
                }
                output.flush()
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    companion object {
        private const val MAGIC = 0x4D4F4253
        private const val VERSION = 3
        private const val MAX_BYTES = 2_000_000L
    }
}
