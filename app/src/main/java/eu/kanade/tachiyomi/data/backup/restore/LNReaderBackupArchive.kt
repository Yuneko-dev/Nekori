package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import mihon.core.archive.forEachArchiveEntry
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Use the provider's existing descriptor: reopening /proc/self/fd can be denied by scoped storage. */
internal suspend fun forEachLnReaderBackupEntry(
    context: Context,
    uri: Uri,
    block: suspend (String, Boolean, InputStream) -> Unit,
) {
    val descriptor = try {
        context.contentResolver.openFileDescriptor(uri, "r")
    } catch (_: IOException) {
        null
    }
    if (descriptor != null) {
        descriptor.use {
            forEachArchiveEntry(it) { entry, input ->
                currentCoroutineContext().ensureActive()
                block(entry.name, !entry.isFile, input)
            }
        }
    } else {
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open LNReader backup")
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                block(entry.name, entry.isDirectory, zip)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
