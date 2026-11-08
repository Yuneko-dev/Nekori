@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class NovelImageCacheFiles(private val directory: File, private val lock: Any) {
    private val prefix = "novel-image-${UUID.randomUUID()}-"
    private val cached = mutableMapOf<String, File>()
    private val owners = mutableMapOf<String, Any>()

    fun claim(key: String, owner: Any) = synchronized(lock) { owners[key] = owner }

    fun isClaimed(key: String): Boolean = synchronized(lock) { key in owners }

    fun release(key: String, owner: Any) = synchronized(lock) {
        if (owners[key] === owner) owners.remove(key)
    }

    fun contains(key: String): Boolean = synchronized(lock) { cached[key]?.isFile == true }

    fun open(key: String): InputStream? = synchronized(lock) {
        cached[key]?.let { file ->
            runCatching { file.inputStream() }.getOrElse {
                cached.remove(key)
                null
            }
        }
    }

    fun write(key: String, owner: Any, path: String, input: InputStream, isActive: () -> Boolean) {
        val temp = synchronized(lock) {
            if (owners[key] !== owner) return
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create reader asset cache" }
            File.createTempFile(prefix, ".tmp", directory)
        }
        try {
            temp.outputStream().use { output -> input.copyTo(output) }
            synchronized(lock) {
                if (owners[key] !== owner || !isActive()) return
                val suffix = path.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "bin")
                    .takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) } ?: "bin"
                val target = File(directory, "${temp.name.removeSuffix(".tmp")}.$suffix")
                // A unique target never replaces another writer's file or an open response stream.
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                cached.put(key, target)?.delete()
            }
        } finally {
            temp.delete()
            release(key, owner)
        }
    }

    fun clear() = synchronized(lock) {
        owners.clear()
        cached.values.forEach { it.delete() }
        cached.clear()
    }
}
