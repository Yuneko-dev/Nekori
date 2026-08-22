package eu.kanade.tachiyomi.data.epub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hippo.unifile.UniFile
import eu.kanade.presentation.reader.settings.CodeSnippet
import eu.kanade.tachiyomi.data.download.ChapterContentReader
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.awaitInitialized
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.epub.EpubExportNaming
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.archive.EpubWriter
import mihon.core.archive.ZipWriter
import mihon.core.archive.epubReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.NovelDownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.model.ChapterRef
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.source.local.LocalNovelSource
import tachiyomi.source.local.io.LocalNovelSourceFileSystem
import tachiyomi.source.local.isLocal
import tachiyomi.source.local.isLocalNovel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.util.UUID

class EpubExportJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val sourceManager: SourceManager = Injekt.get()
    private val mangaRepository: MangaRepository = Injekt.get()
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get()
    private val downloadProvider: DownloadProvider = Injekt.get()
    private val networkHelper: NetworkHelper = Injekt.get()
    private val translatedChapterRepository: TranslatedChapterRepository = Injekt.get()
    private val novelDownloadPreferences: NovelDownloadPreferences = Injekt.get()
    private val localNovelFileSystem: LocalNovelSourceFileSystem = Injekt.get()
    private val readerPreferences: ReaderPreferences = Injekt.get()

    private val notificationBuilder = context.notificationBuilder(Notifications.CHANNEL_EPUB_EXPORT) {
        setSmallIcon(android.R.drawable.ic_menu_save)
        setContentTitle(context.stringResource(TDMR.strings.epub_export_job_title))
        setContentText(context.stringResource(TDMR.strings.notification_starting))
        setProgress(0, 0, true)
        setOngoing(true)
        setOnlyAlertOnce(true)
    }
    private var lastProgressNotificationAt = 0L

    private fun isRunOwner(): Boolean = synchronized(runLock) { activeRun?.id == id }

    private fun notifyIfRunOwner(block: () -> Unit) {
        synchronized(runLock) {
            if (activeRun?.id == id) {
                block()
            }
        }
    }

    private suspend fun ensureActiveRun() {
        currentCoroutineContext().ensureActive()
        if (!isRunOwner()) {
            throw CancellationException("EPUB export was replaced")
        }
    }

    override suspend fun doWork(): Result {
        val mangaIds = inputData.getLongArray(KEY_MANGA_IDS)?.toList() ?: return Result.failure()
        val uriString = inputData.getString(KEY_OUTPUT_URI) ?: return Result.failure()
        val downloadedOnly = inputData.getBoolean(KEY_DOWNLOADED_ONLY, false)
        val translationMode = TranslationMode.fromKey(
            inputData.getString(KEY_TRANSLATION_MODE) ?: TranslationMode.ORIGINAL.key,
        )
        val includeChapterCount = inputData.getBoolean(KEY_INCLUDE_CHAPTER_COUNT, false)
        val includeChapterRange = inputData.getBoolean(KEY_INCLUDE_CHAPTER_RANGE, false)
        val includeStatus = inputData.getBoolean(KEY_INCLUDE_STATUS, false)
        val joinVolumes = inputData.getBoolean(KEY_JOIN_VOLUMES, true)
        val includeVolumeNumber = inputData.getBoolean(KEY_INCLUDE_VOLUME_NUMBER, false)
        val includeCustomCss = inputData.getBoolean(KEY_INCLUDE_CUSTOM_CSS, false)
        val includeCustomJs = inputData.getBoolean(KEY_INCLUDE_CUSTOM_JS, false)
        val outputUri = uriString.toUri()

        if (!claimRun(id, outputUri)) {
            throw CancellationException("EPUB export was replaced")
        }

        logcat(LogPriority.INFO) {
            "EPUB Export starting: ${mangaIds.size} novels, downloadedOnly=$downloadedOnly, " +
                "translationMode=$translationMode, joinVolumes=$joinVolumes, " +
                "includeVolumeNumber=$includeVolumeNumber, includeCustomCss=$includeCustomCss, " +
                "includeCustomJs=$includeCustomJs"
        }

        try {
            setForegroundSafely()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to set foreground service" }
        }

        return withIOContext {
            try {
                performExport(
                    mangaIds = mangaIds,
                    outputUri = outputUri,
                    downloadedOnly = downloadedOnly,
                    translationMode = translationMode,
                    includeChapterCount = includeChapterCount,
                    includeChapterRange = includeChapterRange,
                    includeStatus = includeStatus,
                    joinVolumes = joinVolumes,
                    includeVolumeNumber = includeVolumeNumber,
                    includeCustomCss = includeCustomCss,
                    includeCustomJs = includeCustomJs,
                )
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "EPUB export failed" }
                showErrorNotification(
                    e.message ?: context.stringResource(MR.strings.unknown_error),
                )
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_EPUB_EXPORT_PROGRESS,
            notificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private suspend fun performExport(
        mangaIds: List<Long>,
        outputUri: Uri,
        downloadedOnly: Boolean,
        translationMode: TranslationMode,
        includeChapterCount: Boolean,
        includeChapterRange: Boolean,
        includeStatus: Boolean,
        joinVolumes: Boolean,
        includeVolumeNumber: Boolean,
        includeCustomCss: Boolean,
        includeCustomJs: Boolean,
    ) {
        logcat(LogPriority.INFO) {
            "performExport called with ${mangaIds.size} manga IDs, outputUri=$outputUri, translationMode=$translationMode, joinVolumes=$joinVolumes"
        }

        val bundledCss = if (includeCustomCss) collectActiveCustomCss() else null
        val bundledJs = if (includeCustomJs) collectActiveCustomJs() else null

        val mangasById = mangaRepository.getMangasByIds(mangaIds).associateBy(Manga::id)
        val mangaList = mangaIds.mapNotNull(mangasById::get)
        if (mangaList.isEmpty()) {
            logcat(LogPriority.ERROR) { "No manga found for IDs: $mangaIds" }
            showErrorNotification(context.stringResource(TDMR.strings.epub_export_job_error_no_novels))
            return
        }

        logcat(LogPriority.INFO) { "Found ${mangaList.size} manga to export" }

        val tempDir = File(context.cacheDir, "epub_export_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        var successCount = 0
        var skippedCount = 0
        val totalCount = mangaList.size
        val deflateLevel = novelDownloadPreferences.epubCompressionLevel.get()
        val completedArtifacts = mutableListOf<CompletedArtifact>()
        val usedEntryNames = mutableSetOf<String>()

        try {
            for (manga in mangaList) {
                val localEpubContexts = mutableMapOf<String, LocalEpubContext>()
                try {
                    val source = resolveExportSource(manga)
                    if (source == null || !source.isNovelSource()) {
                        logcat(LogPriority.WARN) { "${manga.title}: Not a novel source" }
                        skippedCount++
                        continue
                    }

                    val chapters = sortChaptersForEpubExport(getChaptersByMangaId.await(manga.id))

                    if (chapters.isEmpty()) {
                        logcat(LogPriority.WARN) { "${manga.title}: No chapters found" }
                        skippedCount++
                        continue
                    }

                    val chapterContents = mutableListOf<ChapterContent>()
                    val isLocalSource = source.isLocal() || manga.isLocalNovel()
                    val chapterReader = ChapterContentReader(context, downloadProvider)
                    val chapterFiles = if (isLocalSource) {
                        emptyMap()
                    } else {
                        downloadProvider.findChapterDirs(chapters, manga, source).second
                    }
                    val translationsByChapterId = if (translationMode != TranslationMode.ORIGINAL) {
                        try {
                            translatedChapterRepository.getAllTranslationsForNovel(
                                source.toString(),
                                manga.title,
                                chapters.map { ChapterRef(it.id, it.name, it.url) },
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) { "Failed to batch translations for ${manga.title}" }
                            emptyMap()
                        }
                    } else {
                        emptyMap()
                    }
                    val localVolumePositions = mutableMapOf<String, Int>()

                    for ((chapterIndex, chapter) in chapters.withIndex()) {
                        ensureActiveRun()
                        val isDownloaded = if (isLocalSource) {
                            true
                        } else {
                            chapter.id in chapterFiles
                        }

                        val chapterTranslations = if (translationMode != TranslationMode.ORIGINAL) {
                            translationsByChapterId[chapter.id].orEmpty()
                        } else {
                            emptyList()
                        }
                        val hasTranslation = chapterTranslations.isNotEmpty()
                        val localReference = if (isLocalSource) parseLocalEpubReference(chapter.url) else null
                        val localContext = localReference?.let {
                            getOrCreateLocalEpubContext(it, localEpubContexts)
                        }
                        val localChapterHref = localReference?.chapterHref
                        val localOrderInVolume = localContext?.let { context ->
                            val current = localVolumePositions[context.key] ?: 0
                            localVolumePositions[context.key] = current + 1
                            current
                        }
                        val resolvedLocalChapterHref = if (localContext != null) {
                            findBestTocEntry(localContext.toc, localChapterHref, localOrderInVolume)?.href
                                ?: localChapterHref
                        } else {
                            null
                        }

                        if (chapterIndex == 0) {
                            logcat(LogPriority.DEBUG) {
                                "${manga.title} ch ${chapter.name}: isDownloaded=$isDownloaded, hasTranslation=$hasTranslation, localRef=${localReference?.key}"
                            }
                        }

                        // Skip undownloaded chapters if downloadedOnly and no translation available
                        if (downloadedOnly && !isDownloaded && !hasTranslation) {
                            if (chapterIndex < 3) {
                                logcat(LogPriority.DEBUG) {
                                    "${manga.title} ch ${chapter.name}: skipping - not downloaded and downloadedOnly=true"
                                }
                            }
                            continue
                        }

                        val translatedContent = chapterTranslations.firstOrNull()
                            ?.translatedContent
                            ?.takeIf { it.isNotBlank() }
                        val hasCandidateOutput = when (translationMode) {
                            TranslationMode.ORIGINAL -> isDownloaded
                            TranslationMode.TRANSLATED -> translatedContent != null
                            TranslationMode.BOTH -> isDownloaded || translatedContent != null
                        }

                        if (hasCandidateOutput) {
                            chapterContents.add(
                                ChapterContent(
                                    chapter = chapter,
                                    name = resolveExportChapterTitle(
                                        chapterName = chapter.name,
                                        localContext = localContext,
                                        chapterHref = resolvedLocalChapterHref,
                                        fallbackOrder = localOrderInVolume,
                                    ),
                                    order = chapterIndex,
                                    translatedContent = translatedContent,
                                    resolvedDownload = chapterFiles[chapter.id],
                                    localContext = localContext,
                                    localOrderInVolume = localOrderInVolume,
                                    chapterHref = resolvedLocalChapterHref,
                                ),
                            )
                        }
                    }

                    if (chapterContents.isEmpty()) {
                        logcat(LogPriority.WARN) {
                            "${manga.title}: No chapters could be exported (chapters=${chapters.size}, downloadedOnly=$downloadedOnly, translationMode=$translationMode)"
                        }
                        skippedCount++
                        continue
                    }

                    logcat(LogPriority.INFO) {
                        "${manga.title}: Exporting ${chapterContents.size} chapters (mode=$translationMode)"
                    }

                    val volumeUnits = buildVolumeUnits(chapterContents, joinVolumes)
                    if (volumeUnits.isEmpty()) {
                        skippedCount++
                        continue
                    }
                    val splitByVolume = !joinVolumes && volumeUnits.size > 1
                    val joinedVolumeSuffix = if (!splitByVolume) {
                        buildJoinedVolumeSuffix(chapterContents, includeVolumeNumber)
                    } else {
                        null
                    }

                    val statusLabel = EpubExportNaming.mangaStatusLabel(manga.status)

                    // Get cover image
                    val coverImage = readCoverImage(manga.thumbnailUrl)

                    // Create EPUB metadata
                    val metadata = EpubWriter.Metadata(
                        title = manga.title.trim().ifBlank { manga.url },
                        author = manga.author?.trim()?.takeIf { it.isNotBlank() }
                            ?: manga.artist?.trim()?.takeIf { it.isNotBlank() },
                        description = buildMetadataDescription(
                            baseDescription = manga.description,
                            statusLabel = statusLabel,
                            includeStatus = includeStatus,
                        ),
                        language = source.lang.ifBlank { "en" },
                        genres = normalizeGenres(manga.genre),
                        publisher = source.name.takeIf { it.isNotBlank() },
                    )

                    suspend fun writeEpub(
                        candidates: List<ChapterContent>,
                        epubMetadata: EpubWriter.Metadata,
                        volumeSuffix: String?,
                        translationSuffix: String?,
                        writeOriginal: Boolean,
                    ): Boolean {
                        val tempFile = File(
                            tempDir,
                            "artifact-${completedArtifacts.size.toString().padStart(5, '0')}.epub",
                        )
                        val output = tempFile.outputStream()
                        val session = try {
                            EpubWriter(deflateLevel).open(
                                outputStream = output,
                                metadata = epubMetadata,
                                coverImage = coverImage,
                                customCss = bundledCss,
                                customJs = bundledJs,
                            )
                        } catch (e: Throwable) {
                            output.close()
                            throw e
                        }
                        val writtenChapters = mutableListOf<ChapterContent>()
                        try {
                            candidates.forEachIndexed { candidateIndex, candidate ->
                                ensureActiveRun()
                                updateProgress(
                                    candidate.order + 1,
                                    chapters.size,
                                    "${manga.title}: ${candidate.name}",
                                    force = candidateIndex == candidates.lastIndex,
                                )
                                val outputChapter = if (writeOriginal) {
                                    readOriginalChapterForExport(
                                        content = candidate,
                                        source = source,
                                        reader = chapterReader,
                                        isLocalSource = isLocalSource,
                                    )
                                } else {
                                    candidate.translatedContent?.let {
                                        EpubWriter.Chapter(
                                            title = candidate.name,
                                            content = it,
                                            order = candidate.order,
                                        )
                                    }
                                }
                                if (outputChapter != null) {
                                    session.append(outputChapter)
                                    writtenChapters += candidate
                                }
                            }
                            if (writtenChapters.isEmpty()) {
                                session.abort()
                                tempFile.delete()
                                return false
                            }
                            session.finish()
                            completedArtifacts += CompletedArtifact(
                                file = tempFile,
                                entryName = uniqueEntryName(
                                    buildExportFilename(
                                        mangaTitle = manga.title,
                                        chapterContents = writtenChapters,
                                        includeChapterCount = includeChapterCount,
                                        includeChapterRange = includeChapterRange,
                                        includeStatus = includeStatus,
                                        statusLabel = statusLabel,
                                        volumeSuffix = volumeSuffix,
                                        translationSuffix = translationSuffix,
                                    ),
                                    usedEntryNames,
                                ),
                            )
                            return true
                        } catch (e: Throwable) {
                            session.abort()
                            tempFile.delete()
                            throw e
                        }
                    }

                    var writtenFilesForManga = 0

                    for ((unitIndex, volumeUnit) in volumeUnits.withIndex()) {
                        val volumeSuffix = if (splitByVolume) {
                            buildVolumeSuffix(
                                unit = volumeUnit,
                                includeVolumeNumber = includeVolumeNumber,
                                fallbackIndex = unitIndex + 1,
                            )
                        } else {
                            joinedVolumeSuffix
                        }

                        val metadataForUnit = if (!volumeUnit.label.isNullOrBlank() && splitByVolume) {
                            metadata.copy(title = "${metadata.title} - ${volumeUnit.label}")
                        } else {
                            metadata
                        }

                        when (translationMode) {
                            TranslationMode.ORIGINAL -> {
                                if (writeEpub(
                                        candidates = volumeUnit.chapters,
                                        epubMetadata = metadataForUnit,
                                        volumeSuffix = volumeSuffix,
                                        translationSuffix = null,
                                        writeOriginal = true,
                                    )
                                ) {
                                    writtenFilesForManga++
                                }
                            }

                            TranslationMode.TRANSLATED -> {
                                if (writeEpub(
                                        candidates = volumeUnit.chapters,
                                        epubMetadata = metadataForUnit,
                                        volumeSuffix = volumeSuffix,
                                        translationSuffix = null,
                                        writeOriginal = false,
                                    )
                                ) {
                                    writtenFilesForManga++
                                }
                            }

                            TranslationMode.BOTH -> {
                                if (writeEpub(
                                        candidates = volumeUnit.chapters,
                                        epubMetadata = metadataForUnit,
                                        volumeSuffix = volumeSuffix,
                                        translationSuffix = "Original",
                                        writeOriginal = true,
                                    )
                                ) {
                                    writtenFilesForManga++
                                }

                                if (writeEpub(
                                        candidates = volumeUnit.chapters,
                                        epubMetadata = metadataForUnit.copy(
                                            title = "${metadataForUnit.title} [Translated]",
                                        ),
                                        volumeSuffix = volumeSuffix,
                                        translationSuffix = "Translated",
                                        writeOriginal = false,
                                    )
                                ) {
                                    writtenFilesForManga++
                                }
                            }
                        }
                    }

                    if (writtenFilesForManga > 0) {
                        logcat(LogPriority.INFO) {
                            "Exported ${manga.title}: ${chapterContents.size} chapters into $writtenFilesForManga file(s)"
                        }
                        successCount++
                    } else {
                        logcat(LogPriority.WARN) {
                            "${manga.title}: No EPUB output generated after filtering"
                        }
                        skippedCount++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to export ${manga.title}" }
                    skippedCount++
                } finally {
                    localEpubContexts.values.forEach { localContext ->
                        runCatching { localContext.reader.close() }
                    }
                }
            }

            // Write to output
            logcat(LogPriority.INFO) {
                "Export complete: ${completedArtifacts.size} EPUB files in temp dir, successCount=$successCount, skippedCount=$skippedCount"
            }

            if (completedArtifacts.isEmpty()) {
                logcat(LogPriority.ERROR) { "No EPUB files were created in temp dir" }
                error(context.stringResource(TDMR.strings.epub_export_job_error_no_files))
            }

            writeFinalOutput(
                artifacts = completedArtifacts,
                outputUri = outputUri,
                shouldZipOutput = completedArtifacts.size > 1 || totalCount > 1 || !joinVolumes,
            )

            ensureActiveRun()
            showCompleteNotification(successCount, skippedCount)
        } finally {
            // Cleanup
            tempDir.deleteRecursively()
        }
    }

    private suspend fun resolveExportSource(manga: Manga): Source? {
        sourceManager.get(manga.source)?.let { return it }

        sourceManager.awaitInitialized()

        sourceManager.get(manga.source)?.let { return it }

        if (manga.isLocalNovel()) {
            sourceManager.get(LocalNovelSource.ID)?.let { return it }
        }

        return null
    }

    private suspend fun writeFinalOutput(
        artifacts: List<CompletedArtifact>,
        outputUri: Uri,
        shouldZipOutput: Boolean,
    ) {
        finalWriteMutex.withLock {
            try {
                ensureActiveRun()
                if (shouldZipOutput) {
                    val destination = UniFile.fromUri(context, outputUri)
                        ?: error("Failed to access output URI: $outputUri")
                    ZipWriter(context, destination, novelDownloadPreferences.zipCompressionLevel().get()).use { zip ->
                        artifacts.forEach { artifact ->
                            ensureActiveRun()
                            zip.write(
                                file = UniFile.fromFile(artifact.file)
                                    ?: error("Failed to open staged EPUB: ${artifact.file}"),
                                entryName = artifact.entryName,
                                isCancelled = { isStopped || !isRunOwner() },
                            )
                        }
                    }
                } else {
                    val artifact = artifacts.single()
                    context.contentResolver.openOutputStream(outputUri)?.use { output ->
                        artifact.file.inputStream().use { input ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                ensureActiveRun()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    } ?: error("Failed to open output stream for URI: $outputUri")
                }
                ensureActiveRun()
            } catch (e: CancellationException) {
                cleanupIncompleteOutput(outputUri)
                throw e
            }
        }
    }

    private fun cleanupIncompleteOutput(outputUri: Uri) {
        val replacementUsesSameUri = synchronized(runLock) {
            activeRun?.let { it.id != id && it.outputUri == outputUri } == true
        }
        if (replacementUsesSameUri) {
            runCatching { context.contentResolver.openOutputStream(outputUri, "wt")?.close() }
        } else {
            runCatching { context.contentResolver.delete(outputUri, null, null) }
        }
    }

    private data class ChapterContent(
        val chapter: Chapter,
        val name: String,
        val order: Int,
        val translatedContent: String?,
        val resolvedDownload: UniFile?,
        val localContext: LocalEpubContext?,
        val localOrderInVolume: Int?,
        val chapterHref: String?,
    )

    private data class CompletedArtifact(
        val file: File,
        val entryName: String,
    )

    private data class LocalEpubReference(
        val key: String,
        val chapterHref: String?,
    )

    private data class LocalEpubContext(
        val key: String,
        val volumeLabel: String,
        val volumeNumber: Int?,
        val reader: mihon.core.archive.EpubReader,
        val toc: List<mihon.core.archive.EpubReader.EpubChapter>,
    )

    private data class VolumeUnit(
        val label: String?,
        val number: Int?,
        val chapters: List<ChapterContent>,
    )

    private fun parseLocalEpubReference(chapterUrl: String): LocalEpubReference? {
        val filePath = chapterUrl.substringBefore("#").trim()
        val chapterHref = chapterUrl.substringAfter("#", "").trim().takeIf { it.isNotBlank() }

        val pathParts = filePath.split('/', limit = 2)
        if (pathParts.size != 2) return null

        val novelDirName = pathParts[0].trim()
        val epubFileName = pathParts[1].trim()
        if (
            novelDirName.isBlank() ||
            epubFileName.isBlank() ||
            !epubFileName.endsWith(".epub", ignoreCase = true)
        ) {
            return null
        }

        return LocalEpubReference(
            key = "$novelDirName/$epubFileName",
            chapterHref = chapterHref,
        )
    }

    private fun getOrCreateLocalEpubContext(
        reference: LocalEpubReference,
        cache: MutableMap<String, LocalEpubContext>,
    ): LocalEpubContext? {
        cache[reference.key]?.let { return it }

        val pathParts = reference.key.split('/', limit = 2)
        if (pathParts.size != 2) return null
        val novelDirName = pathParts[0]
        val epubFileName = pathParts[1]

        val epubFile = localNovelFileSystem.getBaseDirectory()
            ?.findFile(novelDirName)
            ?.findFile(epubFileName)
            ?: return null

        val reader = runCatching { epubFile.epubReader(context) }
            .onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to open local EPUB for export: ${reference.key}"
                }
            }
            .getOrNull() ?: return null

        val toc = runCatching { reader.getNormalizedTableOfContents() }
            .getOrElse {
                logcat(LogPriority.WARN, it) {
                    "Failed to parse TOC for local EPUB: ${reference.key}"
                }
                emptyList()
            }

        val volumeLabel = epubFileName.substringBeforeLast('.', epubFileName)
        val context = LocalEpubContext(
            key = reference.key,
            volumeLabel = volumeLabel,
            volumeNumber = extractVolumeNumber(volumeLabel),
            reader = reader,
            toc = toc,
        )

        cache[reference.key] = context
        return context
    }

    /**
     * Reads a single chapter from a local EPUB into the export pipeline. Returns the body
     * HTML plus the raw bytes of every internal image it references
     */
    private fun readLocalEpubChapterForExport(
        localContext: LocalEpubContext,
        chapterHref: String?,
        fallbackOrder: Int?,
    ): mihon.core.archive.EpubReader.ChapterExportData? {
        val reader = localContext.reader
        val tocEntry = findBestTocEntry(localContext.toc, chapterHref, fallbackOrder)

        val hrefAttempts = buildList {
            chapterHref?.takeIf { it.isNotBlank() }?.let(::add)
            tocEntry?.href?.takeIf { it.isNotBlank() }?.let(::add)
            chapterHref?.substringBefore("#")?.takeIf { it.isNotBlank() }?.let(::add)
            tocEntry?.href?.substringBefore("#")?.takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()

        hrefAttempts.forEach { href ->
            val data = runCatching { reader.extractChapterForExport(href) }
                .getOrNull()
                ?.takeIf { it.html.isNotBlank() }
            if (data != null) return data
        }

        if (chapterHref.isNullOrBlank()) {
            val fallback = runCatching {
                val packagePath = reader.getPackageHref()
                val pages = reader.getPagesFromDocument(reader.getPackageDocument(packagePath))
                val combined = StringBuilder()
                val mergedImages = linkedMapOf<String, ByteArray>()
                pages.forEach { pageHref ->
                    val data = reader.extractChapterForExport(pageHref)
                    if (data.html.isNotBlank()) {
                        if (combined.isNotEmpty()) combined.append("\n\n")
                        combined.append(data.html)
                        data.images.forEach { (id, bytes) -> mergedImages.putIfAbsent(id, bytes) }
                    }
                }
                if (combined.isNotEmpty()) {
                    mihon.core.archive.EpubReader.ChapterExportData(combined.toString(), mergedImages)
                } else {
                    null
                }
            }.getOrNull()

            if (fallback != null) return fallback
        }

        return null
    }

    private fun resolveExportChapterTitle(
        chapterName: String,
        localContext: LocalEpubContext?,
        chapterHref: String?,
        fallbackOrder: Int?,
    ): String {
        val tocTitle = localContext
            ?.let { findBestTocEntry(it.toc, chapterHref, fallbackOrder)?.title }
            ?.trim()
            .orEmpty()

        if (tocTitle.isNotBlank()) {
            return tocTitle
        }

        if (localContext != null) {
            val strippedName = stripVolumePrefix(chapterName, localContext.volumeLabel)
            if (strippedName.isNotBlank()) {
                return strippedName
            }
        }

        return chapterName.trim().ifBlank { "Chapter" }
    }

    private fun findBestTocEntry(
        toc: List<mihon.core.archive.EpubReader.EpubChapter>,
        chapterHref: String?,
        fallbackOrder: Int?,
    ): mihon.core.archive.EpubReader.EpubChapter? {
        return findTocEntryForHref(toc, chapterHref)
            ?: fallbackOrder?.let { order -> toc.getOrNull(order) }
    }

    private fun stripVolumePrefix(chapterName: String, volumeLabel: String): String {
        val trimmedName = chapterName.trim()
        if (trimmedName.isBlank()) return trimmedName

        val prefixPattern = Regex("^${Regex.escape(volumeLabel)}\\s*-\\s*", RegexOption.IGNORE_CASE)
        val stripped = trimmedName.replace(prefixPattern, "")
        return stripped.ifBlank { trimmedName }
    }

    private fun findTocEntryForHref(
        toc: List<mihon.core.archive.EpubReader.EpubChapter>,
        chapterHref: String?,
    ): mihon.core.archive.EpubReader.EpubChapter? {
        if (chapterHref.isNullOrBlank() || toc.isEmpty()) return null

        val targetPath = normalizeHrefPath(chapterHref.substringBefore("#"))
        val targetFragment = normalizeHrefFragment(chapterHref.substringAfter("#", ""))

        return toc.firstOrNull { tocEntry ->
            val tocPath = normalizeHrefPath(tocEntry.href.substringBefore("#"))
            val tocFragment = normalizeHrefFragment(tocEntry.href.substringAfter("#", ""))
            tocPath == targetPath &&
                (targetFragment.isBlank() || tocFragment == targetFragment)
        } ?: toc.firstOrNull { tocEntry ->
            normalizeHrefPath(tocEntry.href.substringBefore("#")) == targetPath
        }
    }

    private fun normalizeHrefPath(path: String): String {
        val cleaned = decodeUrlComponent(path.trim())
            .replace('\\', '/')
            .removePrefix("./")
        return cleaned.lowercase()
    }

    private fun normalizeHrefFragment(fragment: String): String {
        return decodeUrlComponent(fragment.trim().removePrefix("#")).lowercase()
    }

    private fun decodeUrlComponent(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun extractVolumeNumber(volumeLabel: String): Int? {
        val patterns = listOf(
            Regex("(?i)\\bvol(?:ume)?\\.?\\s*(\\d{1,4})\\b"),
            Regex("(?i)\\bv\\.?\\s*(\\d{1,4})\\b"),
        )

        for (pattern in patterns) {
            val number = pattern.find(volumeLabel)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            if (number != null) {
                return number
            }
        }

        return null
    }

    private fun buildVolumeUnits(
        chapterContents: List<ChapterContent>,
        joinVolumes: Boolean,
    ): List<VolumeUnit> {
        val sortedContents = chapterContents.sortedBy { it.order }
        if (sortedContents.isEmpty()) return emptyList()

        if (joinVolumes) {
            val first = sortedContents.first()
            return listOf(
                VolumeUnit(
                    label = first.localContext?.volumeLabel,
                    number = first.localContext?.volumeNumber,
                    chapters = sortedContents,
                ),
            )
        }

        val groupedByVolume = linkedMapOf<String, MutableList<ChapterContent>>()
        sortedContents.forEach { chapter ->
            val key = chapter.localContext?.key ?: "__default__"
            groupedByVolume.getOrPut(key) { mutableListOf() }.add(chapter)
        }

        if (groupedByVolume.size <= 1) {
            val first = sortedContents.first()
            return listOf(
                VolumeUnit(
                    label = first.localContext?.volumeLabel,
                    number = first.localContext?.volumeNumber,
                    chapters = sortedContents,
                ),
            )
        }

        return groupedByVolume.map { (_, groupedChapters) ->
            val first = groupedChapters.first()
            VolumeUnit(
                label = first.localContext?.volumeLabel,
                number = first.localContext?.volumeNumber,
                chapters = groupedChapters,
            )
        }
    }

    private fun buildVolumeSuffix(
        unit: VolumeUnit,
        includeVolumeNumber: Boolean,
        fallbackIndex: Int,
    ): String {
        if (includeVolumeNumber && unit.number != null) {
            return "v${unit.number}"
        }

        return unit.label?.trim()?.takeIf { it.isNotBlank() } ?: "vol$fallbackIndex"
    }

    private fun buildJoinedVolumeSuffix(
        chapterContents: List<ChapterContent>,
        includeVolumeNumber: Boolean,
    ): String? {
        if (!includeVolumeNumber) {
            return null
        }

        val volumeCount = chapterContents
            .mapNotNull { it.localContext?.key }
            .distinct()
            .size

        if (volumeCount > 1) {
            return "${volumeCount}vol"
        }

        val volumeNumbers = chapterContents
            .mapNotNull { it.localContext?.volumeNumber }
            .distinct()
            .sorted()

        if (volumeNumbers.isNotEmpty()) {
            return if (volumeNumbers.size == 1) {
                "v${volumeNumbers.first()}"
            } else {
                "v${volumeNumbers.first()}-${volumeNumbers.last()}"
            }
        }

        return null
    }

    private fun buildExportFilename(
        mangaTitle: String,
        chapterContents: List<ChapterContent>,
        includeChapterCount: Boolean,
        includeChapterRange: Boolean,
        includeStatus: Boolean,
        statusLabel: String?,
        volumeSuffix: String? = null,
        translationSuffix: String? = null,
    ): String {
        val filenameBuilder = StringBuilder(EpubExportNaming.sanitizeFilename(mangaTitle))
        EpubExportNaming.appendChapterCount(
            filenameBuilder = filenameBuilder,
            chapterCount = chapterContents.size,
            includeChapterCount = includeChapterCount,
        )
        EpubExportNaming.appendChapterRange(
            filenameBuilder = filenameBuilder,
            chapterNumbers = chapterContents.map { it.chapter.chapterNumber },
            includeChapterRange = includeChapterRange,
        )
        EpubExportNaming.appendStatusLabel(
            filenameBuilder = filenameBuilder,
            statusLabel = statusLabel,
            includeStatus = includeStatus,
        )

        volumeSuffix?.takeIf { it.isNotBlank() }?.let {
            filenameBuilder.append(" [${EpubExportNaming.sanitizeFilename(it)}]")
        }

        translationSuffix?.takeIf { it.isNotBlank() }?.let {
            filenameBuilder.append(" [$it]")
        }

        filenameBuilder.append(".epub")
        return filenameBuilder.toString()
    }

    private fun uniqueEntryName(requestedName: String, usedNames: MutableSet<String>): String {
        if (usedNames.add(requestedName)) return requestedName

        val extension = requestedName.substringAfterLast('.', missingDelimiterValue = "")
        val stem = requestedName.removeSuffix(if (extension.isBlank()) "" else ".$extension")
        var suffix = 2
        while (true) {
            val candidate = "$stem ($suffix)" + if (extension.isBlank()) "" else ".$extension"
            if (usedNames.add(candidate)) return candidate
            suffix++
        }
    }

    private fun toEmbeddedImages(images: Map<String, ByteArray>): List<EpubWriter.EmbeddedImage> {
        if (images.isEmpty()) return emptyList()
        val maxSizeKb = novelDownloadPreferences.maxImageSizeKb().get()
        val quality = novelDownloadPreferences.imageCompressionQuality().get()
        return images.map { (id, bytes) ->
            val (mime, ext) = EpubWriter.detectImageType(bytes)
            val finalBytes = if (maxSizeKb > 0 && bytes.size > maxSizeKb * 1024 && mime != "image/gif") {
                compressImageForEpub(bytes, quality, maxSizeKb) ?: bytes
            } else {
                bytes
            }
            val (finalMime, finalExt) = if (finalBytes !== bytes) "image/jpeg" to "jpg" else mime to ext
            EpubWriter.EmbeddedImage(id = id, bytes = finalBytes, mimeType = finalMime, extension = finalExt)
        }
    }

    private suspend fun readOriginalChapterForExport(
        content: ChapterContent,
        source: Source,
        reader: ChapterContentReader,
        isLocalSource: Boolean,
    ): EpubWriter.Chapter? {
        if (!isLocalSource && content.resolvedDownload == null) return null

        val localExport = content.localContext?.let { localContext ->
            readLocalEpubChapterForExport(
                localContext = localContext,
                chapterHref = content.chapterHref,
                fallbackOrder = content.localOrderInVolume,
            )
        }
        val exportContent = when {
            localExport != null -> ChapterContentReader.ExportContent(
                content = localExport.html,
                images = localExport.images,
            )
            isLocalSource -> ChapterContentReader.ExportContent(
                content = source.fetchPageText(Page(0, content.chapter.url)),
                images = emptyMap(),
            )
            else -> content.resolvedDownload?.let(reader::readExportContent)
        }
        val body = exportContent?.content.orEmpty()
        if (body.isBlank() && !isLocalSource) return null

        return EpubWriter.Chapter(
            title = content.name,
            content = body,
            order = content.order,
            images = toEmbeddedImages(exportContent?.images.orEmpty()),
        )
    }

    private fun compressImageForEpub(imageBytes: ByteArray, quality: Int, maxSizeKb: Int): ByteArray? {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
            var currentQuality = quality.coerceIn(10, 100)
            var outputBytes: ByteArray
            do {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, out)
                outputBytes = out.toByteArray()
                currentQuality -= 10
            } while (outputBytes.size > maxSizeKb * 1024 && currentQuality >= 10)
            bitmap.recycle()
            outputBytes
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to compress image for EPUB" }
            null
        }
    }

    private fun normalizeGenres(genres: List<String>?): List<String> {
        val mergedGenres = linkedMapOf<String, String>()
        genres.orEmpty().forEach { genre ->
            val normalizedGenre = genre.trim()
            if (normalizedGenre.isNotBlank()) {
                mergedGenres.putIfAbsent(normalizedGenre.lowercase(), normalizedGenre)
            }
        }
        return mergedGenres.values.toList()
    }

    private fun buildMetadataDescription(
        baseDescription: String?,
        statusLabel: String?,
        includeStatus: Boolean,
    ): String? {
        val sections = mutableListOf<String>()

        baseDescription
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(sections::add)

        if (includeStatus && !statusLabel.isNullOrBlank()) {
            sections += "Status: $statusLabel"
        }

        return sections.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun readCoverImage(thumbnailUrl: String?): ByteArray? {
        val url = thumbnailUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return runCatching {
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                val request = okhttp3.Request.Builder().url(url).build()
                networkHelper.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body.bytes()
                }
            } else {
                context.contentResolver.openInputStream(url.toUri())?.use { stream -> stream.readBytes() }
            }
        }.getOrNull()
    }

    private fun updateProgress(current: Int, total: Int, title: String, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        notifyIfRunOwner {
            if (!shouldNotifyEpubProgress(now, lastProgressNotificationAt, force)) return@notifyIfRunOwner
            lastProgressNotificationAt = now
            context.notify(
                Notifications.ID_EPUB_EXPORT_PROGRESS,
                notificationBuilder
                    .setContentTitle(context.stringResource(TDMR.strings.epub_export_job_title))
                    .setContentText(
                        context.stringResource(
                            TDMR.strings.epub_export_job_progress,
                            current,
                            total,
                            title,
                        ),
                    )
                    .setProgress(total, current, false)
                    .build(),
            )
        }
    }

    private fun showCompleteNotification(success: Int, skipped: Int) {
        notifyIfRunOwner {
            context.cancelNotification(Notifications.ID_EPUB_EXPORT_PROGRESS)
            val message = if (skipped > 0) {
                context.stringResource(TDMR.strings.epub_export_job_complete_with_skipped, success, skipped)
            } else {
                context.stringResource(TDMR.strings.epub_export_job_complete, success)
            }
            context.notify(
                Notifications.ID_EPUB_EXPORT_COMPLETE,
                context.notificationBuilder(Notifications.CHANNEL_EPUB_EXPORT) {
                    setSmallIcon(android.R.drawable.ic_menu_save)
                    setContentTitle(context.stringResource(TDMR.strings.epub_export_job_complete_title))
                    setContentText(message)
                    setAutoCancel(true)
                }.build(),
            )
        }
    }

    /**
     * Joins every currently enabled CSS source into a single stylesheet body,
     * separated by `/* @snippet: title */` markers.
     *
     * Returns null when there's nothing to bundle.
     */
    private fun collectActiveCustomCss(): String? {
        val pieces = mutableListOf<String>()

        readerPreferences.novelCustomCss.get()
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { pieces += "/* @custom-css */\n$it" }

        decodeSnippets(readerPreferences.novelCustomCssSnippets.get(), "CSS")
            .filter { it.enabled && it.code.isNotBlank() }
            .forEach { snippet ->
                pieces += "/* @snippet: ${snippet.title.ifBlank { "untitled" }} */\n${snippet.code}"
            }

        return pieces.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    /**
     * Joins every currently enabled JS source
     * into a single script body.
     *
     * Returns null when nothing is enabled.
     */
    private fun collectActiveCustomJs(): String? {
        val pieces = mutableListOf<String>()

        readerPreferences.novelCustomJs.get()
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { pieces += wrapJsPiece(title = "custom-js", code = it) }

        decodeSnippets(readerPreferences.novelCustomJsSnippets.get(), "JS")
            .filter { it.enabled && it.code.isNotBlank() }
            .forEach { snippet ->
                pieces += wrapJsPiece(
                    title = snippet.title.ifBlank { "untitled" },
                    code = snippet.code,
                )
            }

        return pieces.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun decodeSnippets(json: String, label: String): List<CodeSnippet> {
        return try {
            Json.decodeFromString<List<CodeSnippet>>(json)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to decode $label snippets for export" }
            emptyList()
        }
    }

    private fun wrapJsPiece(title: String, code: String): String {
        return "/* @snippet: $title */\n(function () {\n  try {\n$code\n  } catch (e) {\n" +
            "    if (typeof console !== 'undefined' && console.error) console.error(e);\n" +
            "  }\n})();"
    }

    private fun showErrorNotification(error: String) {
        notifyIfRunOwner {
            context.cancelNotification(Notifications.ID_EPUB_EXPORT_PROGRESS)
            context.notify(
                Notifications.ID_EPUB_EXPORT_COMPLETE,
                context.notificationBuilder(Notifications.CHANNEL_EPUB_EXPORT) {
                    setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                    setContentTitle(context.stringResource(TDMR.strings.epub_export_job_failed_title))
                    setContentText(error)
                    setAutoCancel(true)
                }.build(),
            )
        }
    }

    companion object {
        private const val TAG = "EpubExportJob"
        private const val KEY_MANGA_IDS = "manga_ids"
        private const val KEY_OUTPUT_URI = "output_uri"
        private const val KEY_DOWNLOADED_ONLY = "downloaded_only"
        private const val KEY_TRANSLATION_MODE = "translation_mode"
        private const val KEY_INCLUDE_CHAPTER_COUNT = "include_chapter_count"
        private const val KEY_INCLUDE_CHAPTER_RANGE = "include_chapter_range"
        private const val KEY_INCLUDE_STATUS = "include_status"
        private const val KEY_JOIN_VOLUMES = "join_volumes"
        private const val KEY_INCLUDE_VOLUME_NUMBER = "include_volume_number"
        private const val KEY_INCLUDE_CUSTOM_CSS = "include_custom_css"
        private const val KEY_INCLUDE_CUSTOM_JS = "include_custom_js"
        private const val PROGRESS_NOTIFICATION_INTERVAL_MS = 1000L

        private data class RunOwner(val id: UUID, val outputUri: Uri)

        private val runLock = Any()
        private var activeRun: RunOwner? = null
        private val finalWriteMutex = Mutex()

        internal fun sortChaptersForEpubExport(chapters: List<Chapter>): List<Chapter> =
            chapters.sortedWith(compareByDescending<Chapter> { it.sourceOrder }.thenBy { it.id })

        internal fun shouldNotifyEpubProgress(now: Long, lastNotifyAt: Long, force: Boolean): Boolean =
            force || lastNotifyAt == 0L || now - lastNotifyAt >= PROGRESS_NOTIFICATION_INTERVAL_MS

        private fun claimRun(id: UUID, outputUri: Uri): Boolean = synchronized(runLock) {
            val current = activeRun
            when {
                current == null -> {
                    activeRun = RunOwner(id, outputUri)
                    true
                }
                current.id == id -> true
                else -> false
            }
        }

        fun start(
            context: Context,
            mangaIds: List<Long>,
            outputUri: Uri,
            downloadedOnly: Boolean = false,
            translationMode: TranslationMode = TranslationMode.ORIGINAL,
            includeChapterCount: Boolean = false,
            includeChapterRange: Boolean = false,
            includeStatus: Boolean = false,
            joinVolumes: Boolean = true,
            includeVolumeNumber: Boolean = false,
            includeCustomCss: Boolean = false,
            includeCustomJs: Boolean = false,
        ) {
            val data = workDataOf(
                KEY_MANGA_IDS to mangaIds.toLongArray(),
                KEY_OUTPUT_URI to outputUri.toString(),
                KEY_DOWNLOADED_ONLY to downloadedOnly,
                KEY_TRANSLATION_MODE to translationMode.key,
                KEY_INCLUDE_CHAPTER_COUNT to includeChapterCount,
                KEY_INCLUDE_CHAPTER_RANGE to includeChapterRange,
                KEY_INCLUDE_STATUS to includeStatus,
                KEY_JOIN_VOLUMES to joinVolumes,
                KEY_INCLUDE_VOLUME_NUMBER to includeVolumeNumber,
                KEY_INCLUDE_CUSTOM_CSS to includeCustomCss,
                KEY_INCLUDE_CUSTOM_JS to includeCustomJs,
            )

            val request = OneTimeWorkRequestBuilder<EpubExportJob>()
                .addTag(TAG)
                .setInputData(data)
                .build()

            synchronized(runLock) {
                activeRun = RunOwner(request.id, outputUri)
                context.notify(
                    Notifications.ID_EPUB_EXPORT_PROGRESS,
                    context.notificationBuilder(Notifications.CHANNEL_EPUB_EXPORT) {
                        setSmallIcon(android.R.drawable.ic_menu_save)
                        setContentTitle(context.stringResource(TDMR.strings.epub_export_job_title))
                        setContentText(context.stringResource(TDMR.strings.notification_starting))
                        setProgress(0, 0, true)
                        setOngoing(true)
                        setOnlyAlertOnce(true)
                    }.build(),
                )
            }

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
