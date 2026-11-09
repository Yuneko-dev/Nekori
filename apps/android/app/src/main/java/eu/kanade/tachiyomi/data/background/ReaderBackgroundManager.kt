package eu.kanade.tachiyomi.data.background

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Xml
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import tachiyomi.core.common.storage.createFileWithExactName
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream

data class ReaderBackgroundImage(val id: String, val name: String, val uri: Uri, val custom: Boolean)

class ReaderBackgroundManager(
    private val context: Context,
    private val storage: StorageManager = Injekt.get(),
) {
    suspend fun images(): List<ReaderBackgroundImage> = withContext(Dispatchers.IO) {
        context.assets.list(BUNDLED_DIRECTORY).orEmpty().filter(::validFileName).sorted().map { name ->
            ReaderBackgroundImage(
                "asset:$name",
                name,
                Uri.parse("file:///android_asset/$BUNDLED_DIRECTORY/$name"),
                false,
            )
        } +
            storage.getBackgroundsDirectory()?.listFiles().orEmpty()
                .filter { it.isFile && validFileName(it.name.orEmpty()) }
                .map { image(it) }.sortedBy { it.name.lowercase() }
    }

    suspend fun importImage(uri: Uri): ReaderBackgroundImage = withContext(Dispatchers.IO) {
        val source = UniFile.fromUri(context, uri) ?: error("Source unavailable")
        val originalName = source.name ?: error("Missing filename")
        val extension = originalName.substringAfterLast('.', "").lowercase()
        require(extension in EXTENSIONS) { "Unsupported image" }
        val temp = File.createTempFile("reader-background-", ".$extension", context.cacheDir)
        var target: UniFile? = null
        var saved = false
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BYTES) { "Image exceeds 20 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Cannot read image")
            validate(temp, extension)
            val directory = storage.getBackgroundsDirectory() ?: error("Storage unavailable")
            val existing = directory.listFiles().orEmpty().mapNotNull { it.name?.lowercase() }.toSet()
            val baseName = safeName(originalName.substringBeforeLast('.'))
            var name = "$baseName.$extension"
            var suffix = 1
            while (name.lowercase() in existing) name = "$baseName (${suffix++}).$extension"
            check(directory.findFile(name) == null) { "Filename already exists" }
            val destination = directory.createFileWithExactName(context, name) ?: error("Cannot create image")
            target = destination
            temp.inputStream().use { input -> destination.openOutputStream().use { input.copyTo(it) } }
            currentCoroutineContext().ensureActive()
            val result = image(destination)
            saved = true
            result
        } finally {
            temp.delete()
            if (!saved) runCatching { target?.delete() }
        }
    }

    suspend fun rename(id: String, name: String): ReaderBackgroundImage = withContext(Dispatchers.IO) {
        require(validFileName(id))
        val directory = storage.getBackgroundsDirectory() ?: error("Storage unavailable")
        val file = directory.findFile(id) ?: error("Image unavailable")
        val newName = "${safeName(name)}.${id.substringAfterLast('.')}"
        if (newName != id) {
            check(directory.findFile(newName) == null) { "Filename already exists" }
            check(file.renameTo(newName)) { "Cannot rename image" }
        }
        image(directory.findFile(newName) ?: error("Image unavailable"))
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        require(validFileName(id))
        check(storage.getBackgroundsDirectory()?.findFile(id)?.delete() == true) { "Cannot delete image" }
    }

    // Called only by the WebView interception worker. IDs can never address arbitrary content URIs.
    fun open(id: String): InputStream? = when {
        id.startsWith("asset:") && validFileName(id.removePrefix("asset:")) ->
            context.assets.open("$BUNDLED_DIRECTORY/${id.removePrefix("asset:")}")
        validFileName(id) -> storage.getBackgroundsDirectory()?.findFile(id)?.openInputStream()
        else -> null
    }

    private fun image(file: UniFile): ReaderBackgroundImage {
        val id = requireNotNull(file.name)
        return ReaderBackgroundImage(id, id, file.uri, true)
    }

    suspend fun folderUri(): Uri = withContext(Dispatchers.IO) {
        val folder = storage.getBackgroundsDirectory() ?: error("Storage unavailable")
        if (folder.uri.scheme == "content") return@withContext folder.uri
        val path = File(requireNotNull(folder.filePath)).canonicalFile
        val primary = Environment.getExternalStorageDirectory().canonicalFile
        require(path.toPath().startsWith(primary.toPath())) { "Folder cannot be opened by the document provider" }
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:${path.relativeTo(primary).path}",
        )
    }

    private fun validate(file: File, extension: String) {
        if (extension == "svg") {
            val parser = Xml.newPullParser()
            file.inputStream().use { input ->
                parser.setInput(input, null)
                var rootSeen = false
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    require(parser.eventType != XmlPullParser.DOCDECL) { "SVG document declarations unsupported" }
                    if (parser.eventType == XmlPullParser.START_TAG && !rootSeen) {
                        require(parser.name == "svg") { "Invalid SVG" }
                        rootSeen = true
                    }
                    parser.nextToken()
                }
                require(rootSeen) { "Empty SVG" }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth in 1..MAX_EDGE && bounds.outHeight in 1..MAX_EDGE) { "Invalid image dimensions" }
            require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS) { "Image too large" }
        }
    }

    companion object {
        private const val BUNDLED_DIRECTORY = "novel-reader/backgrounds"
        private val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "svg")
        private const val MAX_BYTES = 20L * 1024 * 1024
        private const val MAX_EDGE = 8192
        private const val MAX_PIXELS = 32_000_000L

        internal fun validFileName(name: String): Boolean = name.isNotBlank() &&
            name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() } &&
            name.substringAfterLast('.', "").lowercase() in EXTENSIONS && name != "." && name != ".."

        internal fun safeName(name: String): String = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).trim('.').ifBlank { "Background" }

        fun mimeType(id: String) = when (id.substringAfterLast('.').lowercase()) {
            "svg" -> "image/svg+xml"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/png"
        }
    }
}
