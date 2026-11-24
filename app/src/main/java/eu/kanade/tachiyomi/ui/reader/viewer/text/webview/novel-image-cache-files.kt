@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class NovelImageCacheFiles(private val directory: File, private val lock: Any) {
    private val prefix = "$SHARED_FILE_PREFIX${UUID.randomUUID()}-"
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
            if (owners[key] !== owner || !isActive()) return
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create reader asset cache" }
            File.createTempFile(prefix, ".tmp", directory)
        }
        try {
            temp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (!isActive()) return
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
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

    fun sweepStaleFiles(nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - STALE_FILE_AGE_MS
        directory.listFiles()?.forEach { file ->
            if (file.name.startsWith(SHARED_FILE_PREFIX) &&
                !file.name.startsWith(prefix) &&
                file.lastModified() < cutoff
            ) {
                runCatching { file.delete() }
            }
        }
    }

    fun clear() = synchronized(lock) {
        owners.clear()
        cached.values.forEach { it.delete() }
        cached.clear()
    }

    companion object {
        internal const val SHARED_FILE_PREFIX = "novel-image-"
        internal const val STALE_FILE_AGE_MS = 60 * 60 * 1000L
    }
}
