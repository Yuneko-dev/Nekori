package eu.kanade.tachiyomi.data.font

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitExempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Manages custom fonts for the novel reader.
 * Supports downloading from Google Fonts API and importing local fonts.
 */
class FontManager(
    private val context: Context,
    private val storageManager: StorageManager = Injekt.get(),
    private val networkHelper: NetworkHelper = Injekt.get(),
) {
    private val googleFonts = GoogleFontsCatalog {
        val request = Request.Builder().url("https://fonts.google.com/metadata/fonts").build()
        networkHelper.client.rateLimitExempt().newCall(request).awaitSuccess().use { it.body.string() }
    }

    // Cache for loaded typefaces
    private val typefaceCache = java.util.concurrent.ConcurrentHashMap<String, Typeface>()

    /**
     * Get the fonts directory, creating it if necessary.
     */
    fun getFontsDirectory(): UniFile? {
        return storageManager.getFontsDirectory()
    }

    /**
     * Get list of installed custom fonts.
     */
    suspend fun getInstalledFonts(): List<FontInfo> = withContext(Dispatchers.IO) {
        val fontsDir = getFontsDirectory() ?: return@withContext emptyList()

        fontsDir.listFiles()
            ?.filter {
                it.isFile && it.name?.let { name ->
                    name.endsWith(".ttf", ignoreCase = true) ||
                        name.endsWith(".otf", ignoreCase = true) ||
                        name.endsWith(".woff", ignoreCase = true) ||
                        name.endsWith(".woff2", ignoreCase = true)
                } == true
            }
            ?.mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                val displayName = name.substringBeforeLast(".").replace("_", " ").replace("-", " ")
                FontInfo(
                    name = displayName,
                    fileName = name,
                    path = file.uri.toString(),
                    isCustom = true,
                )
            }
            ?: emptyList()
    }

    /**
     * Get system fonts.
     */
    fun getSystemFonts(): List<FontInfo> {
        return listOf(
            FontInfo(context.stringResource(TDMR.strings.novel_font_sans_serif), "sans-serif", "sans-serif", false),
            FontInfo(context.stringResource(TDMR.strings.novel_font_serif), "serif", "serif", false),
            FontInfo(context.stringResource(TDMR.strings.novel_font_monospace), "monospace", "monospace", false),
        )
    }

    /**
     * Import a font file from a URI.
     */
    suspend fun importFont(uri: Uri): Result<FontInfo> = withContext(Dispatchers.IO) {
        var stagedFile: UniFile? = null
        var saved = false
        try {
            val fontsDir = getFontsDirectory()
                ?: return@withContext Result.failure(Exception("Cannot access fonts directory"))

            val sourceFile = UniFile.fromUri(context, uri)
                ?: return@withContext Result.failure(Exception("Cannot access source file"))

            val fileName = sourceFile.name
                ?: return@withContext Result.failure(Exception("Cannot determine file name"))

            // Validate file extension
            if (!fileName.endsWith(".ttf", ignoreCase = true) &&
                !fileName.endsWith(".otf", ignoreCase = true)
            ) {
                return@withContext Result.failure(Exception("Invalid font format. Only TTF and OTF are supported."))
            }

            // Raw-file providers may return an existing destination; cleanup must not delete it.
            val existingFile = fontsDir.findFile(fileName)
            val targetFile = fontsDir.createFile(fileName)
                ?: return@withContext Result.failure(Exception("Cannot create font file"))
            stagedFile = targetFile.takeIf { it.uri != existingFile?.uri }

            // Copy file
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.openOutputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Cannot read source file"))
            currentCoroutineContext().ensureActive()

            // Validate font file properly
            try {
                // First try direct file path if available
                val filePath = targetFile.filePath
                val testTypeface = if (filePath != null) {
                    Typeface.createFromFile(filePath)
                } else {
                    // For scoped storage we need to copy to temp file first to validate
                    context.contentResolver.openInputStream(targetFile.uri)?.use { input ->
                        val tempFile = File.createTempFile("font_validate_", ".tmp", context.cacheDir)
                        try {
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                            Typeface.createFromFile(tempFile)
                        } finally {
                            tempFile.delete()
                        }
                    }
                }

                if (testTypeface == null) {
                    throw Exception("Failed to load typeface")
                }

                // Additional check: Android often returns default typeface instead of null
                // when font is invalid, so verify we didn't just get fallback
                if (testTypeface == Typeface.DEFAULT) {
                    // Check file header to verify it's actually a font file
                    context.contentResolver.openInputStream(targetFile.uri)?.use { input ->
                        val header = ByteArray(4)
                        if (input.read(header) != 4) throw Exception("File too small")

                        // TTF: 0x00010000 or 0x74727565 ('true')
                        // OTF: 0x4F54544F ('OTTO')
                        val magic = (header[0].toInt() shl 24) or
                            (header[1].toInt() shl 16) or
                            (header[2].toInt() shl 8) or
                            header[3].toInt()

                        if (magic != 0x00010000 &&
                            magic != 0x74727565 &&
                            magic != 0x4F54544F
                        ) {
                            throw Exception("Not a valid TTF/OTF file")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Font validation failed: ${e.message}" }
                return@withContext Result.failure(Exception("Invalid or corrupted font file"))
            }

            val displayName = fileName.substringBeforeLast(".").replace("_", " ").replace("-", " ")
            currentCoroutineContext().ensureActive()
            typefaceCache.remove(targetFile.uri.toString())
            saved = true
            Result.success(
                FontInfo(
                    name = displayName,
                    fileName = fileName,
                    path = targetFile.uri.toString(),
                    isCustom = true,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to import font" }
            Result.failure(e)
        } finally {
            if (!saved) runCatching { stagedFile?.delete() }
        }
    }

    /**
     * Download a font from Google Fonts.
     */
    fun downloadGoogleFont(fontFamily: String): Flow<FontDownloadState> = flow {
        emit(FontDownloadState.Downloading(0))

        var stagedFile: UniFile? = null
        var saved = false
        try {
            // Only one face is saved, so request the default style.
            val cssUrl = "https://fonts.googleapis.com/css2".toHttpUrl().newBuilder()
                .addQueryParameter("family", fontFamily)
                .addQueryParameter("display", "swap")
                .build()

            val request = Request.Builder()
                .url(cssUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = networkHelper.client.rateLimitExempt().newCall(request).awaitSuccess()
            val css = response.use { it.body.string() }

            // Parse font URLs from CSS
            val urlRegex = """url\((https://fonts\.gstatic\.com/[^)]+\.(?:ttf|woff2?))\)""".toRegex()
            val fontUrls = urlRegex.findAll(css).map { it.groupValues[1] }.toList()

            if (fontUrls.isEmpty()) {
                throw Exception("No font files found for $fontFamily")
            }

            // Download the first TTF or WOFF2 file
            val fontUrl = fontUrls.firstOrNull { it.endsWith(".ttf") }
                ?: fontUrls.first()

            emit(FontDownloadState.Downloading(25))

            val fontRequest = Request.Builder()
                .url(fontUrl)
                .build()

            val fontResponse = networkHelper.client.rateLimitExempt().newCall(fontRequest).awaitSuccess()
            val fontBytes = fontResponse.use { it.body.bytes() }

            emit(FontDownloadState.Downloading(75))

            // Save to fonts directory
            val fontsDir = getFontsDirectory()
                ?: throw Exception("Cannot access fonts directory")

            val extension = if (fontUrl.endsWith(".woff2")) {
                "woff2"
            } else if (fontUrl.endsWith(".woff")) {
                "woff"
            } else {
                "ttf"
            }
            val fileName = "${fontFamily.replace(" ", "_")}.$extension"

            val existingFile = fontsDir.findFile(fileName)
            val targetFile = fontsDir.createFile(fileName)
                ?: throw Exception("Cannot create font file")
            stagedFile = targetFile.takeIf { it.uri != existingFile?.uri }

            targetFile.openOutputStream().use { output ->
                output.write(fontBytes)
            }
            currentCoroutineContext().ensureActive()

            emit(FontDownloadState.Downloading(100))

            // Validate downloaded font before returning success
            // Check file header
            context.contentResolver.openInputStream(targetFile.uri)?.use { input ->
                val header = ByteArray(4)
                if (input.read(header) != 4) throw Exception("Downloaded file is incomplete")

                val magic = (header[0].toInt() shl 24) or
                    (header[1].toInt() shl 16) or
                    (header[2].toInt() shl 8) or
                    header[3].toInt()

                if (magic != 0x00010000 &&
                    magic != 0x74727565 &&
                    magic != 0x4F54544F &&
                    magic != 0x774F4632 && // woff2
                    magic != 0x774F4646
                ) { // woff
                    throw Exception("Downloaded file is not a valid font")
                }
            } ?: throw Exception("Cannot read downloaded font")

            val fontInfo = FontInfo(
                name = fontFamily,
                fileName = fileName,
                path = targetFile.uri.toString(),
                isCustom = true,
            )

            typefaceCache.remove(fontInfo.path)
            currentCoroutineContext().ensureActive()
            saved = true
            emit(FontDownloadState.Success(fontInfo))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to download font: $fontFamily" }
            emit(FontDownloadState.Error(e.message ?: "Unknown error"))
        } finally {
            if (!saved) runCatching { stagedFile?.delete() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Search Google Fonts.
     */
    suspend fun searchGoogleFonts(query: String): List<GoogleFontInfo> = withContext(Dispatchers.IO) {
        googleFonts.search(query)
    }

    /**
     * Delete a custom font.
     */
    suspend fun deleteFont(fontInfo: FontInfo): Boolean = withContext(Dispatchers.IO) {
        if (!fontInfo.isCustom) return@withContext false

        try {
            val fontsDir = getFontsDirectory() ?: return@withContext false
            val file = fontsDir.findFile(fontInfo.fileName)
            (file?.delete() ?: false).also { deleted ->
                if (deleted) typefaceCache.remove(fontInfo.path)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to delete font: ${fontInfo.fileName}" }
            false
        }
    }

    /**
     * Get a Typeface for a font.
     */
    fun getTypeface(fontInfo: FontInfo): Typeface? {
        if (!fontInfo.isCustom) {
            return when (fontInfo.path) {
                "sans-serif" -> Typeface.SANS_SERIF
                "serif" -> Typeface.SERIF
                "monospace" -> Typeface.MONOSPACE
                else -> null
            }
        }

        return try {
            context.contentResolver.openInputStream(Uri.parse(fontInfo.path))?.use { input ->
                typefaceCache[fontInfo.path]?.let { return it }
                val tempFile = File.createTempFile("font_", ".tmp", context.cacheDir)
                try {
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                    Typeface.createFromFile(tempFile).also { typefaceCache[fontInfo.path] = it }
                } finally {
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to load typeface: ${fontInfo.path}" }
            null
        }
    }

    companion object {
        private const val FONTS_PATH = "fonts"
    }
}

/**
 * Information about a font.
 */
@Serializable
data class FontInfo(
    val name: String,
    val fileName: String,
    val path: String,
    val isCustom: Boolean,
)

/**
 * Information about a Google Font.
 */
@Serializable
data class GoogleFontInfo(
    val family: String,
    val category: String,
    val variants: List<String>,
)

/**
 * State of a font download.
 */
sealed class FontDownloadState {
    data class Downloading(val progress: Int) : FontDownloadState()
    data class Success(val fontInfo: FontInfo) : FontDownloadState()
    data class Error(val message: String) : FontDownloadState()
}
