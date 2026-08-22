package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import mihon.core.archive.rewriteResolvedAssetRefs
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Shared utility for reading text content (HTML / TXT) from downloaded chapters.
 *
 * Both [eu.kanade.tachiyomi.data.translation.TranslationService] and
 * [eu.kanade.tachiyomi.data.epub.EpubExportJob] previously had their own
 * copy of this logic.  This class is the single source of truth.
 *
 * It supports:
 * - Plain directories containing `.html` / `.txt` files
 * - CBZ archives (read via libarchive's [ArchiveReader])
 * - Fallback CBZ lookup in the manga directory when the chapter directory
 *   itself is not found
 */
class ChapterContentReader(
    private val context: Context,
    private val downloadProvider: DownloadProvider,
) {

    /**
     * Content and assets needed to write one downloaded chapter into an EPUB.
     *
     * The content has already had resolvable relative assets rewritten to the
     * reader image scheme, so [images] is only populated when the content can
     * reference it.
     */
    data class ExportContent(
        val content: String,
        val images: Map<String, ByteArray>,
    )

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Read the text content for [chapter] from its downloaded files.
     *
     * @return the concatenated content, or `null` if nothing was found.
     */
    fun readDownloadedContent(
        manga: Manga,
        chapter: Chapter,
        source: eu.kanade.tachiyomi.source.Source,
    ): String? {
        return try {
            readFromChapterDir(manga, chapter, source)
                ?: readFromMangaDirCbz(manga, chapter, source)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read downloaded chapter: ${chapter.name}" }
            null
        }
    }

    /**
     * Reads export content from a chapter location resolved by
     * [DownloadProvider.findChapterDirs]. Directories are listed once and CBZ
     * archives are enumerated once, avoiding the separate content/image scans
     * used by the legacy APIs.
     */
    fun readExportContent(resolvedFile: UniFile): ExportContent? {
        return try {
            if (resolvedFile.isFile && resolvedFile.name.isArchiveFile()) {
                readExportContentFromArchive(resolvedFile)
            } else {
                readExportContentFromDirectory(resolvedFile)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read export content from ${resolvedFile.name}" }
            null
        }
    }

    // ── Internals ───────────────────────────────────────────────────

    private fun readFromChapterDir(
        manga: Manga,
        chapter: Chapter,
        source: eu.kanade.tachiyomi.source.Source,
    ): String? {
        val chapterDirOrCbz = downloadProvider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            source,
        ) ?: return null

        val isCbz = chapterDirOrCbz.name?.let { it.endsWith(".cbz") || it.endsWith(".zip") } == true

        return if (isCbz) {
            readContentFromCbz(chapterDirOrCbz)
        } else {
            readContentFromDirectory(chapterDirOrCbz)
        }
    }

    /**
     * When `findChapterDir` returns null, fall back to scanning the manga directory
     * for a CBZ whose base name matches the chapter.
     */
    private fun readFromMangaDirCbz(
        manga: Manga,
        chapter: Chapter,
        source: eu.kanade.tachiyomi.source.Source,
    ): String? {
        val mangaDir = downloadProvider.findMangaDir(manga.title, source) ?: return null
        val cbzFiles = mangaDir.listFiles()?.filter {
            it.isFile && (it.name?.endsWith(".cbz") == true || it.name?.endsWith(".zip") == true)
        } ?: return null

        val validNames = downloadProvider.getValidChapterDirNames(
            chapter.name,
            chapter.scanlator,
            chapter.url,
        )

        val matchingCbz = cbzFiles.find { cbz ->
            val base = cbz.name?.substringBeforeLast(".") ?: ""
            validNames.any { it == base }
        } ?: return null

        return readContentFromCbz(matchingCbz)
    }

    // ── Image reading ───────────────────────────────────────────────

    private fun readExportContentFromDirectory(dir: UniFile): ExportContent? {
        val allFiles = dir.listFiles()?.toList() ?: return null
        val fileNames = allFiles.mapNotNull { it.name }.toSet()
        val content = readContentFromFiles(allFiles, fileNames::contains) ?: return null
        val images = if (content.contains(NOVEL_IMAGE_SCHEME)) {
            readImagesFromFiles(allFiles)
        } else {
            emptyMap()
        }
        return ExportContent(content, images)
    }

    private fun readExportContentFromArchive(cbzFile: UniFile): ExportContent? {
        val descriptor = context.contentResolver.openFileDescriptor(cbzFile.uri, "r") ?: return null
        return descriptor.use {
            ArchiveReader(it).use { reader ->
                val contentFileNames = mutableListOf<String>()
                val imageFileNames = mutableListOf<String>()
                val entryBaseNames = mutableSetOf<String>()
                reader.useEntries { entries ->
                    entries.forEach { entry ->
                        if (!entry.isFile) return@forEach
                        entryBaseNames += entry.name.substringAfterLast('/')
                        val name = entry.name.lowercase()
                        if (name.isContentFile()) {
                            contentFileNames += entry.name
                        }
                        if (name.isImageFile()) {
                            imageFileNames += entry.name
                        }
                    }
                }

                val content = readContentFromArchive(reader, contentFileNames, entryBaseNames) ?: return@use null
                val images = if (content.contains(NOVEL_IMAGE_SCHEME)) {
                    readImagesFromArchive(reader, imageFileNames)
                } else {
                    emptyMap()
                }
                ExportContent(content, images)
            }
        }
    }

    private fun rewriteAssetRefs(text: String, fileExists: (String) -> Boolean): String =
        rewriteResolvedAssetRefs(text, fileExists)

    companion object {
        private const val NOVEL_IMAGE_SCHEME = "tsundoku-novel-image://"
        private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif")
    }

    // ── File-type readers ───────────────────────────────────────────

    /**
     * Read `.html` / `.txt` files from a plain directory, sorted by name.
     */
    private fun readContentFromDirectory(dir: UniFile): String? {
        val allFiles = dir.listFiles() ?: return null
        return readContentFromFiles(allFiles.asList()) { name -> dir.findFile(name) != null }
    }

    /**
     * Read `.html` / `.htm` / `.xhtml` / `.txt` entries from a CBZ archive.
     *
     * Uses libarchive's [ArchiveReader] for compatibility with archives
     * created by the download system's ZipWriter.
     */
    fun readContentFromCbz(cbzFile: UniFile): String? {
        val uri = cbzFile.uri
        logcat(LogPriority.DEBUG) { "CBZ: reading from $uri" }
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use { descriptor ->
                ArchiveReader(descriptor).use { reader ->
                    val contentFileNames = mutableListOf<String>()
                    val entryBaseNames = mutableSetOf<String>()
                    reader.useEntries { seq ->
                        seq.forEach { entry ->
                            if (!entry.isFile) return@forEach
                            entryBaseNames.add(entry.name.substringAfterLast('/'))
                            val name = entry.name.lowercase()
                            if (name.isContentFile()) {
                                contentFileNames.add(entry.name)
                            }
                        }
                    }
                    readContentFromArchive(reader, contentFileNames, entryBaseNames)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "CBZ: failed to read archive $uri" }
            null
        }
    }

    private fun readContentFromFiles(
        allFiles: List<UniFile>,
        fileExists: (String) -> Boolean,
    ): String? {
        val htmlFiles = allFiles.filter { it.isFile && it.name?.endsWith(".html") == true }.sortedBy { it.name }
        val txtFiles = allFiles.filter { it.isFile && it.name?.endsWith(".txt") == true }.sortedBy { it.name }
        val files = htmlFiles.ifEmpty { txtFiles }
        if (files.isEmpty()) return null

        val content = files.joinToString("\n\n") { file ->
            context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }.orEmpty()
        }
        return content.ifBlank { null }?.let { rewriteAssetRefs(it, fileExists) }
    }

    private fun readImagesFromFiles(allFiles: List<UniFile>): Map<String, ByteArray> {
        return allFiles.asSequence()
            .filter { it.isFile && it.name.isImageFile() }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                context.contentResolver.openInputStream(file.uri)?.use { name to it.readBytes() }
            }
            .toMap()
    }

    private fun readContentFromArchive(
        reader: ArchiveReader,
        contentFileNames: List<String>,
        entryBaseNames: Set<String>,
    ): String? {
        val entries = contentFileNames.mapNotNull { fileName ->
            try {
                reader.getInputStream(fileName)?.use { fileName to it.bufferedReader().readText() }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "CBZ: failed to read entry $fileName" }
                null
            }
        }
        return entries.sortedBy { it.first }
            .joinToString("\n\n") { it.second }
            .ifEmpty { null }
            ?.let { rewriteAssetRefs(it, entryBaseNames::contains) }
    }

    private fun readImagesFromArchive(reader: ArchiveReader, imageFileNames: List<String>): Map<String, ByteArray> {
        return imageFileNames.asSequence().mapNotNull { fileName ->
            try {
                reader.getInputStream(fileName)?.use { fileName.substringAfterLast('/') to it.readBytes() }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "CBZ: failed to read image $fileName" }
                null
            }
        }.toMap()
    }

    private fun String?.isArchiveFile(): Boolean =
        this?.let { it.endsWith(".cbz") || it.endsWith(".zip") } == true

    private fun String?.isImageFile(): Boolean =
        this?.lowercase()?.let { name -> IMAGE_EXTENSIONS.any(name::endsWith) } == true

    private fun String.isContentFile(): Boolean =
        endsWith(".html") || endsWith(".htm") || endsWith(".xhtml") || endsWith(".txt")
}
