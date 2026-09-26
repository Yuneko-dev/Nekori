@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.pagedSourceOrder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSection
import eu.kanade.tachiyomi.data.backup.models.BackupNovelStructure
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.jsplugin.model.JsPlugin
import eu.kanade.tachiyomi.jsplugin.model.JsPluginRepository
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.novel.model.NovelLayout
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalNovelSource
import tachiyomi.source.local.io.LocalNovelSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Imports LNReader backup files (.zip) into Tsundoku.
 *
 * LNReader backup format:
 * ```
 * backup.zip
 * ├── Version.json
 * ├── Category.json
 * ├── NovelAndChapters/
 * │   ├── {novelId}.json  (each contains novel info + chapters array)
 * │   └── ...
 * └── Setting.json
 * ```
 */
class LNReaderBackupImporter(
    private val context: Context,
    private val notifier: BackupNotifier? = null,
    private val jsPluginManager: JsPluginManager = Injekt.get(),
    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(),
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    private val stubSourceRepository: StubSourceRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val localNovelFileSystem: LocalNovelSourceFileSystem = Injekt.get(),
    private val settingsRestorer: LNReaderSettingsRestorer = LNReaderSettingsRestorer(),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
    private val errors = ConcurrentLinkedQueue<Pair<Date, String>>()

    @Serializable
    data class LNNovel(
        val id: Int = 0,
        val path: String = "",
        val pluginId: String = "",
        val name: String = "",
        val cover: String? = null,
        val summary: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val status: String? = null,
        val genres: String? = null,
        @Serializable(with = FlexibleBooleanSerializer::class)
        val inLibrary: Boolean = false,
        @Serializable(with = FlexibleBooleanSerializer::class)
        val isLocal: Boolean = false,
        val totalPages: Int = 0,
        val chapters: List<LNChapter> = emptyList(),
    )

    @Serializable
    data class LNChapter(
        val id: Int = 0,
        val novelId: Int = 0,
        val path: String = "",
        val name: String = "",
        val releaseTime: String? = null,
        val readTime: String? = null,
        @Serializable(with = FlexibleBooleanSerializer::class)
        val bookmark: Boolean = false,
        @Serializable(with = FlexibleBooleanSerializer::class)
        val unread: Boolean = true,
        @Serializable(with = FlexibleBooleanSerializer::class)
        val isDownloaded: Boolean = false,
        val updatedTime: String? = null,
        val chapterNumber: Float? = null,
        val page: String = "",
        val progress: Int? = null,
        val position: Int? = null,
        val scanlator: String? = null,
        val readDuration: Long? = null,
        val timeSpent: Long? = null,
    )

    @Serializable
    data class LNCategory(
        val id: Int = 0,
        val name: String = "",
        val sort: Int = 0,
        val novelIds: List<Int> = emptyList(),
    )

    @Serializable
    data class LNRepository(val url: String)

    @Serializable
    data class LNVersion(
        val version: String? = null,
        val appVersion: String? = null,
        val formatVersion: Int? = null,
        val sections: LNSections? = null,
    )

    @Serializable
    data class LNSections(
        val library: Boolean = true,
        val settings: Boolean = true,
        val plugins: Boolean = true,
        val downloadedFiles: Boolean = true,
    )

    data class LNManifest(
        val appVersion: String,
        val formatVersion: Int,
        val sections: LNSections,
    )

    data class ImportResult(
        val novelCount: Int,
        val categoryCount: Int,
        val errorCount: Int,
        val logFile: File,
        val missingPlugins: List<String> = emptyList(),
        /** Missing plugins that were imported onto a synthetic source ID, so they need a manual migration. */
        val placeholderPlugins: List<String> = emptyList(),
        val skippedCount: Int = 0,
        val installedPluginCount: Int = 0,
        val restoredDownloadCount: Int = 0,
        val restoredCoverCount: Int = 0,
        val restoredCompatibleSettings: Boolean = false,
    )

    data class PreflightSummary(
        val appVersion: String,
        val formatVersion: Int,
        val novelCount: Int,
        val localNovelCount: Int,
        val chapterCount: Int,
        val categoryCount: Int,
        val pluginCount: Int,
        val downloadedChapterCount: Int,
        val hasLibrary: Boolean,
        val hasSettings: Boolean,
        val hasPlugins: Boolean,
        val hasDownloadedFiles: Boolean,
        val hasApiKeys: Boolean,
    )

    data class ImportOptions(
        val restoreNovels: Boolean = true,
        val restoreChapters: Boolean = true,
        val restoreCategories: Boolean = true,
        val restoreHistory: Boolean = true,
        val restorePlugins: Boolean = true,
        val restoreMissingPlugins: Boolean = false,
        val restoreLocalNovels: Boolean = true,
        val restoreDownloadedChapters: Boolean = true,
        val restoreCovers: Boolean = true,
        val restoreCompatibleSettings: Boolean = true,
        val restoreAiApiKeys: Boolean = false,
    )

    suspend fun preflight(uri: Uri): PreflightSummary = withContext(Dispatchers.IO) {
        val summary = LNReaderLibrarySummary()
        val extracted = extractBackupData(
            uri,
            extractPluginArchive = false,
            extractNovelFilesArchive = false,
            librarySummary = summary,
        )
        PreflightSummary(
            appVersion = extracted.manifest.appVersion,
            formatVersion = extracted.manifest.formatVersion,
            novelCount = summary.novelCount,
            localNovelCount = summary.localNovelCount,
            chapterCount = summary.chapterCount,
            categoryCount = summary.categoryCount,
            pluginCount = summary.pluginCount,
            downloadedChapterCount = summary.downloadedChapterCount,
            hasLibrary = extracted.manifest.sections.library,
            hasSettings = extracted.manifest.sections.settings && summary.hasSettings,
            hasPlugins = extracted.manifest.sections.plugins && extracted.hasPluginArchive,
            hasDownloadedFiles = extracted.manifest.sections.downloadedFiles && extracted.hasNovelFilesArchive,
            hasApiKeys = summary.hasApiKeys,
        )
    }

    /**
     * Import an LNReader backup from the given URI.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun import(uri: Uri, options: ImportOptions = ImportOptions()): ImportResult {
        errors.clear()
        var novelCount = 0
        var categoryCount = 0
        var skippedCount = 0
        var installedPluginCount = 0
        var restoredDownloadCount = 0
        var restoredCoverCount = 0
        var restoredCompatibleSettings = false
        val missingPlugins = mutableSetOf<String>()
        val placeholderPlugins = mutableSetOf<String>()

        // Validate the manifest and required sections before writing to the library.
        val extracted = extractBackupData(
            uri = uri,
            extractPluginArchive = options.restorePlugins || options.restoreNovels,
            extractNovelFilesArchive = options.restoreNovels &&
                (options.restoreDownloadedChapters || options.restoreCovers || options.restoreLocalNovels),
        )
        val categories = extracted.categories

        try {
            // Step 2: Restore categories FIRST
            val backupCategories = categories.map { lnCat ->
                BackupCategory(
                    name = lnCat.name,
                    order = lnCat.sort.toLong(),
                    flags = 0,
                    contentType = Category.CONTENT_TYPE_NOVEL,
                )
            }
            if (options.restoreNovels && options.restoreCategories) {
                categoriesRestorer(backupCategories)
                categoryCount = categories.size
            }

            // Step 3: Install plugins
            val backupPluginMetadata = extracted.pluginMetadata
                .associateBy { normalizePluginId(it.id) }
                .toMutableMap()
            if (options.restorePlugins && extracted.repositories.isNotEmpty()) {
                notifier?.showRestoreProgress("Restoring novel extension repositories", 0, 1)
                jsPluginManager.restoreRepositories(extracted.repositories)
            }
            val pluginMetadata = (jsPluginManager.availablePlugins.value + extracted.pluginMetadata)
                .associateBy { normalizePluginId(it.id) }
                .values
                .toList()
            if ((options.restorePlugins || options.restoreNovels) && extracted.pluginArchiveFile != null) {
                notifier?.showRestoreProgress("Restoring plugins", indeterminate = true)
                val restoredPlugins = installPluginsFromArchive(
                    extracted.pluginArchiveFile,
                    extracted.manifest.formatVersion,
                    pluginMetadata,
                    install = options.restorePlugins,
                )
                installedPluginCount = restoredPlugins.installedCount
                backupPluginMetadata.putAll(restoredPlugins.metadataById)
            }
            if (options.restoreCompatibleSettings && extracted.settings != null) {
                notifier?.showRestoreProgress("Restoring compatible settings", indeterminate = true)
                settingsRestorer.restore(
                    settings = extracted.settings,
                    apiKeys = extracted.apiKeys,
                    restoreApiKeys = options.restoreAiApiKeys,
                )
                restoredCompatibleSettings = true
            }

            // Step 4: Build plugin mapping
            val pluginIdToSourceId = buildPluginMapping().toMutableMap()

            val novelIdToCategoryNames = mutableMapOf<Int, MutableList<String>>()
            categories.forEach { category ->
                category.novelIds.forEach { id ->
                    novelIdToCategoryNames.getOrPut(id) { mutableListOf() }.add(category.name)
                }
            }

            // Metadata is validated first. Decode, persist and release one novel at a time.
            if (options.restoreNovels) {
                extracted.novelFilesArchiveFile?.let { java.util.zip.ZipFile(it) }.use { assetsZip ->
                    val assetsByNovel = assetsZip?.indexNovelAssets(extracted.manifest.formatVersion)
                        ?.groupBy { normalizePluginId(it.pluginId) to it.novelId }.orEmpty()
                    val assetBudget = ArchiveSizeBudget(MAX_NOVEL_ASSET_TOTAL_BYTES)
                    val jsonBudget = ArchiveSizeBudget(MAX_OUTER_EXPANDED_BYTES)
                    notifier?.showRestoreProgress("Restoring novels", 0, extracted.novelFileCount)
                    var processed = 0
                    var lastProgress = 0L
                    forEachLnReaderBackupEntry(context, uri) { entryName, isDirectory, input ->
                        val name = validateLnReaderArchivePath(entryName, isDirectory)
                        if (isDirectory || !name.startsWith("NovelAndChapters/") || !name.endsWith(".json")) {
                            return@forEachLnReaderBackupEntry
                        }
                        try {
                            val novel = json.decodeFromStream<LNNovel>(
                                limitedEntryStream(input, MAX_JSON_BYTES, jsonBudget),
                            )
                            if (novel.name.isBlank()) return@forEachLnReaderBackupEntry
                            val novelAssets = assetsByNovel[normalizePluginId(novel.pluginId) to novel.id].orEmpty()
                            if (novel.isLocal) {
                                if (options.restoreLocalNovels && assetsZip != null) {
                                    val baseDir = localNovelFileSystem.getBaseDirectory()
                                        ?: error("Local novel storage directory is not configured")
                                    if (restoreLocalNovel(
                                            assetsZip,
                                            baseDir,
                                            novel,
                                            novelAssets,
                                            assetBudget,
                                            novelIdToCategoryNames,
                                            backupCategories,
                                            options,
                                        )
                                    ) {
                                        novelCount++
                                    }
                                }
                                return@forEachLnReaderBackupEntry
                            }

                            var sourceId = resolveSourceId(pluginIdToSourceId, novel.pluginId)
                            if (sourceId == null) {
                                missingPlugins.add(novel.pluginId)
                                val metadata = backupPluginMetadata[normalizePluginId(novel.pluginId)]
                                if (metadata == null && !options.restoreMissingPlugins) {
                                    skippedCount++
                                    errors.add(
                                        Date() to
                                            "${novel.name}: exact source ID for plugin '${novel.pluginId}' is unavailable; skipped",
                                    )
                                    return@forEachLnReaderBackupEntry
                                }
                                sourceId = metadata?.sourceId() ?: generateStubSourceId(novel.pluginId)
                                stubSourceRepository.upsertStubSource(
                                    id = sourceId,
                                    lang = metadata?.lang ?: "unknown",
                                    name = metadata?.name ?: "${novel.pluginId} (Missing)",
                                    isNovel = true,
                                    isJs = true,
                                )
                                pluginIdToSourceId[normalizePluginId(novel.pluginId)] = sourceId
                                if (metadata == null) placeholderPlugins.add(novel.pluginId)
                            }
                            mangaRestorer.restore(
                                convertNovel(
                                    novel,
                                    sourceId,
                                    novelIdToCategoryNames,
                                    backupCategories,
                                    includeChapters = options.restoreChapters,
                                    includeHistory = options.restoreHistory,
                                    includeCategories = options.restoreCategories,
                                ),
                                backupCategories,
                            )
                            novelCount++
                            if (assetsZip != null && (options.restoreDownloadedChapters || options.restoreCovers)) {
                                val restored = restoreDownloadedAssets(
                                    assetsZip,
                                    novel,
                                    novelAssets,
                                    sourceId,
                                    options,
                                    assetBudget,
                                )
                                restoredDownloadCount += restored.first
                                restoredCoverCount += restored.second
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) { "LNReaderImport: Failed to restore $name" }
                            errors.add(Date() to "$name: ${e.message}")
                        } finally {
                            processed++
                            val now = System.currentTimeMillis()
                            if (now - lastProgress >= 500 || processed == extracted.novelFileCount) {
                                notifier?.showRestoreProgress(
                                    "Restoring novels ($processed/${extracted.novelFileCount})",
                                    processed,
                                    extracted.novelFileCount,
                                )
                                lastProgress = now
                            }
                        }
                    }
                }
            }
            if (options.restoreNovels) {
                downloadCache.invalidateCache()
                getLibraryManga.refreshForced()
            }
        } finally {
            listOf(extracted.pluginArchiveFile, extracted.novelFilesArchiveFile)
                .distinct()
                .forEach { it?.delete() }
        }

        val logFile = writeErrorLog()
        return ImportResult(
            novelCount = novelCount,
            categoryCount = categoryCount,
            errorCount = errors.size,
            logFile = logFile,
            missingPlugins = missingPlugins.toList(),
            placeholderPlugins = placeholderPlugins.toList(),
            skippedCount = skippedCount,
            installedPluginCount = installedPluginCount,
            restoredDownloadCount = restoredDownloadCount,
            restoredCoverCount = restoredCoverCount,
            restoredCompatibleSettings = restoredCompatibleSettings,
        )
    }

    private fun buildPluginMapping(): Map<String, Long> {
        val plugins = jsPluginManager.installedPlugins.value
        val mapping = plugins.associate { installed ->
            normalizePluginId(installed.plugin.id) to installed.plugin.sourceId()
        }.toMutableMap()
        return mapping
    }

    /**
     * Validated metadata and staged asset archives; chapter models are never retained here.
     */
    data class ExtractedBackup(
        val manifest: LNManifest,
        val novelFileCount: Int,
        val categories: List<LNCategory>,
        val repositories: List<JsPluginRepository>,
        val pluginMetadata: List<JsPlugin>,
        val settings: JsonObject?,
        val apiKeys: Map<String, String>,
        val pluginArchiveFile: File?,
        val novelFilesArchiveFile: File?,
        val hasPluginArchive: Boolean,
        val hasNovelFilesArchive: Boolean,
    )

    private suspend fun extractBackupData(
        uri: Uri,
        extractPluginArchive: Boolean,
        extractNovelFilesArchive: Boolean,
        librarySummary: LNReaderLibrarySummary? = null,
    ): ExtractedBackup {
        var novelFileCount = 0
        var categories = emptyList<LNCategory>()
        var version: LNVersion? = null
        var repositories = emptyList<JsPluginRepository>()
        var pluginMetadata = emptyList<JsPlugin>()
        var settings: JsonObject? = null
        var apiKeys = emptyMap<String, String>()
        var legacyArchiveFile: File? = null
        var pluginArchiveFile: File? = null
        var novelFilesArchiveFile: File? = null
        var hasLegacyArchive = false
        var hasPluginArchive = false
        var hasNovelFilesArchive = false
        var hasPluginMetadataFile = false
        var lastNotifyTime = 0L
        val stagedArchives = mutableListOf<File>()
        val outerBudget = ArchiveSizeBudget(MAX_OUTER_EXPANDED_BYTES)

        try {
            var processedCount = 0
            forEachLnReaderBackupEntry(context, uri) { entryName, isDirectory, zip ->
                currentCoroutineContext().ensureActive()
                check(processedCount < MAX_OUTER_ENTRIES) { "LNReader backup has too many entries" }
                val name = validateLnReaderArchivePath(entryName, isDirectory)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNotifyTime > 500) {
                    notifier?.showRestoreProgress(
                        "Reading backup metadata ($processedCount entries)",
                        indeterminate = true,
                    )
                    lastNotifyTime = currentTime
                }

                when {
                    name == "Version.json" -> {
                        version =
                            json.decodeFromString<LNVersion>(readEntryText(zip, MAX_JSON_BYTES, outerBudget))
                    }
                    name == "Category.json" -> {
                        if (librarySummary != null) {
                            librarySummary.categoryCount =
                                readLnReaderMetadataCount(limitedEntryStream(zip, MAX_JSON_BYTES, outerBudget))
                        } else {
                            categories = json.decodeFromString(readEntryText(zip, MAX_JSON_BYTES, outerBudget))
                        }
                    }
                    !isDirectory && name.startsWith("NovelAndChapters/") && name.endsWith(".json") -> {
                        novelFileCount++
                        if (librarySummary != null) {
                            librarySummary.add(
                                readLnReaderNovelSummary(limitedEntryStream(zip, MAX_JSON_BYTES, outerBudget)),
                            )
                        }
                    }
                    name == "Repository.json" && librarySummary == null -> {
                        repositories = json.decodeFromString<List<LNRepository>>(
                            readEntryText(zip, MAX_JSON_BYTES, outerBudget),
                        )
                            .map { JsPluginRepository(JsPluginRepository.nameFromUrl(it.url), it.url) }
                    }
                    name == "Plugins.json" -> {
                        hasPluginMetadataFile = true
                        if (librarySummary != null) {
                            librarySummary.pluginCount =
                                readLnReaderMetadataCount(limitedEntryStream(zip, MAX_JSON_BYTES, outerBudget))
                        } else {
                            pluginMetadata = json.decodeFromString(readEntryText(zip, MAX_JSON_BYTES, outerBudget))
                        }
                    }
                    name == "Setting.json" -> {
                        if (librarySummary != null) {
                            readLnReaderMetadataCount(
                                limitedEntryStream(zip, MAX_JSON_BYTES, outerBudget),
                                objectRoot = true,
                            )
                            librarySummary.hasSettings = true
                        } else {
                            settings =
                                json.parseToJsonElement(readEntryText(zip, MAX_JSON_BYTES, outerBudget)).jsonObject
                        }
                    }
                    name == "ApiKeys.json" -> {
                        if (librarySummary != null) {
                            librarySummary.hasApiKeys = readLnReaderMetadataCount(
                                limitedEntryStream(zip, MAX_JSON_BYTES, outerBudget),
                                objectRoot = true,
                            ) > 0
                        } else {
                            apiKeys = json.decodeFromString(readEntryText(zip, MAX_JSON_BYTES, outerBudget))
                        }
                    }
                    name == "download.zip" -> {
                        hasLegacyArchive = true
                        if (extractPluginArchive || extractNovelFilesArchive) {
                            notifier?.showRestoreProgress("Extracting downloaded files", indeterminate = true)
                            val tempFile = File.createTempFile(
                                "lnreader-download-",
                                ".zip",
                                context.cacheDir,
                            ).also(stagedArchives::add)
                            copyEntryToFile(zip, tempFile, MAX_NESTED_ARCHIVE_BYTES, outerBudget)
                            legacyArchiveFile = tempFile
                        }
                    }
                    name == "plugins.zip" -> {
                        hasPluginArchive = true
                        if (extractPluginArchive) {
                            val tempFile = File.createTempFile(
                                "lnreader-plugins-",
                                ".zip",
                                context.cacheDir,
                            ).also(stagedArchives::add)
                            copyEntryToFile(zip, tempFile, MAX_NESTED_ARCHIVE_BYTES, outerBudget)
                            pluginArchiveFile = tempFile
                        }
                    }
                    name == "novel-files.zip" -> {
                        hasNovelFilesArchive = true
                        if (extractNovelFilesArchive) {
                            notifier?.showRestoreProgress("Extracting downloaded files", 0, 1)
                            val tempFile = File.createTempFile(
                                "lnreader-novel-files-",
                                ".zip",
                                context.cacheDir,
                            ).also(stagedArchives::add)
                            copyEntryToFile(zip, tempFile, MAX_NESTED_ARCHIVE_BYTES, outerBudget)
                            novelFilesArchiveFile = tempFile
                        }
                    }
                }
                processedCount++
            }
            val parsedVersion = version ?: error("LNReader backup is missing Version.json")
            val appVersion = parsedVersion.appVersion ?: parsedVersion.version
                ?: error("LNReader backup has an invalid Version.json")
            require(isSupportedLnReaderVersion(appVersion)) {
                "LNReader $appVersion is not supported; version 2.0.2 or newer is required"
            }
            val formatVersion = parsedVersion.formatVersion ?: 1
            require(formatVersion in 1..2) { "Unsupported LNReader backup format: $formatVersion" }
            val sections = parsedVersion.sections ?: LNSections()
            if (formatVersion == 2) {
                require(!sections.plugins || (hasPluginMetadataFile && hasPluginArchive)) {
                    "LNReader backup declares plugins but Plugins.json/plugins.zip is missing"
                }
                require(!sections.downloadedFiles || hasNovelFilesArchive) {
                    "LNReader backup declares downloaded files but novel-files.zip is missing"
                }
            } else {
                pluginArchiveFile = legacyArchiveFile.takeIf { extractPluginArchive }
                novelFilesArchiveFile = legacyArchiveFile.takeIf { extractNovelFilesArchive }
                hasPluginArchive = hasLegacyArchive
                hasNovelFilesArchive = hasLegacyArchive
            }

            stagedArchives
                .filterNot { it == pluginArchiveFile || it == novelFilesArchiveFile }
                .forEach { it.delete() }
            return ExtractedBackup(
                manifest = LNManifest(appVersion, formatVersion, sections),
                novelFileCount = novelFileCount,
                categories = categories,
                repositories = repositories,
                pluginMetadata = pluginMetadata,
                settings = settings,
                apiKeys = apiKeys,
                pluginArchiveFile = pluginArchiveFile,
                novelFilesArchiveFile = novelFilesArchiveFile,
                hasPluginArchive = hasPluginArchive,
                hasNovelFilesArchive = hasNovelFilesArchive,
            )
        } catch (e: Exception) {
            stagedArchives.forEach { it.delete() }
            throw e
        }
    }

    private data class PluginFiles(
        var code: String? = null,
        var customJs: String? = null,
        var customCss: String? = null,
    )

    private data class PluginRestoreResult(
        val installedCount: Int,
        val metadataById: Map<String, JsPlugin>,
    )

    private suspend fun installPluginsFromArchive(
        zipFile: File,
        formatVersion: Int,
        metadata: List<JsPlugin>,
        install: Boolean,
    ): PluginRestoreResult {
        val filesByPlugin = mutableMapOf<String, PluginFiles>()
        val archiveBudget = ArchiveSizeBudget(MAX_PLUGIN_ARCHIVE_EXPANDED_BYTES)
        java.util.zip.ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size <= MAX_NESTED_ENTRIES) { "Plugin archive has too many entries" }
            entries.filterNot { it.isDirectory }.forEach { entry ->
                currentCoroutineContext().ensureActive()
                val path = validateLnReaderArchivePath(entry.name, false)
                val parts = path.split('/')
                val offset = if (formatVersion == 1 && parts.firstOrNull().equals("Plugins", true)) 1 else 0
                if (parts.size != offset + 2) return@forEach
                val pluginId = parts[offset]
                require(JsPluginManager.isSafePluginId(pluginId)) { "Unsafe plugin id: $pluginId" }
                val text = zip.getInputStream(entry).use { readEntryText(it, MAX_PLUGIN_BYTES, archiveBudget) }
                val files = filesByPlugin.getOrPut(pluginId) { PluginFiles() }
                when (parts.last().lowercase(Locale.ROOT)) {
                    "index.js" -> files.code = text
                    "custom.js" -> files.customJs = text
                    "custom.css" -> files.customCss = text
                }
            }
        }

        val metadataById = metadata.associateBy { normalizePluginId(it.id) }
        val resolvedMetadata = metadataById.toMutableMap()
        var installedCount = 0
        filesByPlugin.forEach { (pluginId, files) ->
            val code = files.code ?: return@forEach
            try {
                val inspectedMetadata = if (metadataById[normalizePluginId(pluginId)] == null) {
                    jsPluginManager.inspectBackupPlugin(code, pluginId)
                } else {
                    null
                }
                val exactMetadata = metadataById[normalizePluginId(pluginId)]
                    ?: inspectedMetadata?.let { metadataById[normalizePluginId(it.id)] }
                    ?: inspectedMetadata
                    ?: error("Plugin metadata is unavailable")
                resolvedMetadata[normalizePluginId(pluginId)] = exactMetadata
                resolvedMetadata[normalizePluginId(exactMetadata.id)] = exactMetadata
                if (install) {
                    val result = jsPluginManager.installPluginFromBackup(
                        plugin = exactMetadata,
                        code = code,
                        customJs = files.customJs,
                        customCss = files.customCss,
                        repositoryUrl = exactMetadata.repositoryUrl,
                    )
                    if (result.installed) installedCount++
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "LNReaderImport: Failed to install plugin '$pluginId'" }
                errors.add(Date() to "Failed to install plugin '$pluginId': ${e.message}")
            }
        }
        return PluginRestoreResult(installedCount, resolvedMetadata)
    }

    private suspend fun restoreDownloadedAssets(
        zip: java.util.zip.ZipFile,
        novel: LNNovel,
        assets: List<NovelAsset>,
        sourceId: Long,
        options: ImportOptions,
        budget: ArchiveSizeBudget,
    ): Pair<Int, Int> {
        if (assets.isEmpty()) return 0 to 0
        val manga = getMangaByUrlAndSourceId.await(novel.path, sourceId) ?: return 0 to 0
        var covers = 0
        var chapters = 0
        val cachedCover = coverCache.getCustomCoverFile(manga.id)
        if (options.restoreCovers && (!cachedCover.exists() || cachedCover.length() == 0L)) {
            val cover = assets.firstOrNull {
                it.relativePath.size == 1 && it.relativePath.first().startsWith("cover.", true)
            }
            if (cover != null &&
                zip.getInputStream(cover.entry).use { restoreCoverToCache(novel, manga, it, budget) }
            ) {
                covers++
            }
        }
        if (options.restoreDownloadedChapters) {
            val mangaDir = downloadProvider.getMangaDir(manga.title, sourceManager.getOrStub(sourceId)).getOrNull()
                ?: return 0 to covers
            val downloaded = novel.chapters.filter { it.isDownloaded }.associateBy { it.id }
            assets.filter { it.relativePath.size >= 2 }
                .groupBy { it.relativePath.first().toIntOrNull() }
                .forEach { (id, chapterAssets) ->
                    currentCoroutineContext().ensureActive()
                    val chapter = downloaded[id] ?: return@forEach
                    if (restoreDownloadedChapterArchive(zip, mangaDir, chapter, chapterAssets, budget)) chapters++
                }
        }
        return chapters to covers
    }

    private suspend fun restoreLocalNovel(
        zip: java.util.zip.ZipFile,
        baseDir: UniFile,
        novel: LNNovel,
        assets: List<NovelAsset>,
        budget: ArchiveSizeBudget,
        novelIdToCategoryNames: Map<Int, List<String>>,
        backupCategories: List<BackupCategory>,
        options: ImportOptions,
    ): Boolean {
        // A chapter directory is named after the chapter id, not its index in the novel.
        val chapterHtml = assets.filter { it.isChapterHtml() }
            .groupBy { it.relativePath.first().toIntOrNull() }
            .filterKeys { it != null }
        // LNReader keeps the library row after its files are gone, so a novel without any chapter file
        // is a dead entry rather than a novel worth importing.
        if (chapterHtml.isEmpty()) {
            errors.add(Date() to "${novel.name} [local]: no chapter files in the backup; skipped")
            return false
        }

        val novelDir = requireNotNull(baseDir.createDirectory(DiskUtil.buildValidFilename(novel.name))) {
            "Failed to create the local novel directory for '${novel.name}'"
        }
        val novelDirName = requireNotNull(novelDir.name) { "The local novel directory has no name" }

        // Only the novel root holds shared assets. Flattening a chapter directory's own files here would
        // collapse same-named files from different chapters onto one, so they are left where they are.
        val assetNames = mutableMapOf<String, String>()
        val usedNames = mutableSetOf<String>()
        assets.filter { it.relativePath.size == 1 && !it.isHtml() }.forEach { asset ->
            val originalName = asset.relativePath.last()
            val base = DiskUtil.buildValidFilename(originalName)
            var target = base
            var suffix = 1
            while (!usedNames.add(target)) target = "${suffix++}_$base"
            val file = novelDir.replaceFile(target) ?: return@forEach
            zip.getInputStream(asset.entry).use { input ->
                file.openOutputStream().use { copyWithLimit(input, it, MAX_ASSET_BYTES, budget) }
            }
            // Storage providers rename freely - they drop trailing dots and disambiguate collisions - so
            // the chapter HTML is pointed at the name the file actually got rather than the one asked for.
            assetNames[originalName] = file.name ?: target
        }

        val written = mutableListOf<LNChapter>()
        normalizeNovelChapters(novel).chapters.forEach { normalized ->
            val chapter = normalized.chapter
            val entries = chapterHtml[chapter.id] ?: return@forEach
            val fileName = DiskUtil.buildValidFilename(
                "%04d - %s".format(Locale.US, written.size + 1, chapter.name),
                DiskUtil.MAX_FILE_NAME_BYTES - HTML_EXTENSION_BYTES,
            ) + ".html"
            val file = novelDir.replaceFile(fileName) ?: return@forEach
            val content = entries.sortedBy { it.relativePath.last() }
                .map { entry ->
                    zip.getInputStream(entry.entry).use {
                        rewriteLnReaderLocalAssetUrls(readEntryText(it, MAX_ASSET_BYTES, budget), assetNames)
                    }
                }
                .joinToString("\n\n")
            file.openOutputStream().use { it.write(content.toByteArray()) }
            // The local source derives a chapter URL from the file name, and reads a directory as one flat
            // list, so the imported rows have to use that same name and carry no volume of their own.
            written += chapter.copy(path = "$novelDirName/${file.name}", page = "")
        }
        if (written.isEmpty()) {
            errors.add(Date() to "${novel.name} [local]: no chapter file could be written; skipped")
            return false
        }

        mangaRestorer.restore(
            convertNovel(
                // On disk the novel is one flat directory, so it carries no pages of its own either.
                novel.copy(path = novelDirName, totalPages = 0, chapters = written),
                LocalNovelSource.ID,
                novelIdToCategoryNames,
                backupCategories,
                includeChapters = options.restoreChapters,
                includeHistory = options.restoreHistory,
                includeCategories = options.restoreCategories,
            ),
            backupCategories,
        )
        return true
    }

    private fun java.util.zip.ZipFile.indexNovelAssets(formatVersion: Int): List<NovelAsset> {
        val assets = entries().asSequence()
            .filterNot { it.isDirectory }
            .mapNotNull { entry ->
                val path = validateLnReaderArchivePath(entry.name, false)
                if (path.endsWith(".nomedia", true)) return@mapNotNull null
                val parts = path.split('/')
                val offset = if (formatVersion == 1 && parts.firstOrNull().equals("Novels", true)) 1 else 0
                if (parts.size < offset + 3) return@mapNotNull null
                val pluginId = parts[offset]
                val novelId = parts[offset + 1].toIntOrNull() ?: return@mapNotNull null
                NovelAsset(pluginId, novelId, parts.drop(offset + 2), entry)
            }
            .take(MAX_NESTED_ENTRIES + 1)
            .toList()
        require(assets.size <= MAX_NESTED_ENTRIES) { "Novel files archive has too many entries" }
        return assets
    }

    private data class NovelAsset(
        val pluginId: String,
        val novelId: Int,
        val relativePath: List<String>,
        val entry: java.util.zip.ZipEntry,
    )

    private suspend fun restoreDownloadedChapterArchive(
        zip: java.util.zip.ZipFile,
        mangaDir: UniFile,
        chapter: LNChapter,
        assets: List<NovelAsset>,
        archiveBudget: ArchiveSizeBudget,
    ): Boolean {
        val targetName = downloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.path)
        val existing = listOf(targetName, "$targetName.zip", "$targetName.cbz")
            .firstNotNullOfOrNull(mangaDir::findFile)
        if (existing?.hasUsableChapterContent() == true) return false

        val stageName = "$targetName.zip_tmp"
        val finalName = "$targetName.zip"
        val backupName = "$targetName.zip_old"
        mangaDir.findFile(stageName)?.delete()
        val stage = mangaDir.createFile(stageName) ?: return false
        var backup: UniFile? = null
        return try {
            require(assets.any { it.isChapterHtml() }) { "Downloaded chapter has no HTML file" }
            val writtenPaths = mutableSetOf<String>()
            ZipOutputStream(stage.openOutputStream().buffered()).use { output ->
                output.setLevel(Deflater.NO_COMPRESSION)
                assets.forEach { asset ->
                    val relativePath = asset.relativePath.drop(1).joinToString("/")
                    if (relativePath.isEmpty()) return@forEach
                    require(writtenPaths.add(relativePath)) { "Duplicate chapter asset: $relativePath" }
                    output.putNextEntry(ZipEntry(relativePath))
                    zip.getInputStream(asset.entry).use { input ->
                        if (asset.isChapterHtml()) {
                            val content = readEntryText(input, MAX_ASSET_BYTES, archiveBudget)
                            output.write(rewriteLnReaderChapterAssetUrls(content).toByteArray())
                        } else {
                            copyWithLimit(input, output, MAX_ASSET_BYTES, archiveBudget)
                        }
                    }
                    output.closeEntry()
                }
            }
            mangaDir.findFile(finalName)?.let { current ->
                mangaDir.findFile(backupName)?.delete()
                check(current.renameTo(backupName)) { "Failed to preserve existing downloaded chapter" }
                backup = mangaDir.findFile(backupName)
            }
            check(stage.renameTo(finalName)) { "Failed to commit downloaded chapter" }
            listOf(targetName, "$targetName.cbz").forEach { mangaDir.findFile(it)?.delete() }
            backup?.delete()
            true
        } catch (e: Exception) {
            stage.delete()
            if (mangaDir.findFile(finalName) == null) {
                backup?.renameTo(finalName)
            }
            logcat(LogPriority.WARN, e) { "Failed to restore downloaded chapter '${chapter.name}'" }
            false
        }
    }

    private fun UniFile.hasUsableChapterContent(): Boolean {
        if (isFile) {
            return runCatching {
                archiveReader(context).use { archive ->
                    archive.useEntries { entries ->
                        entries.any { entry ->
                            entry.isFile && entry.name.substringAfterLast('.', "").lowercase() in HTML_EXTENSIONS
                        }
                    }
                }
            }.getOrDefault(false)
        }
        return listFiles().orEmpty().any { file ->
            if (file.isDirectory) {
                file.hasUsableChapterContent()
            } else {
                file.isFile && file.name.orEmpty().substringAfterLast('.', "").lowercase() in HTML_EXTENSIONS
            }
        }
    }

    /**
     * Creates [name] in this directory, replacing any file already there.
     *
     * A plain overwrite is not enough: SAF's write mode does not truncate on every provider, so a
     * shorter second import would leave the tail of the first one behind.
     */
    private fun UniFile.replaceFile(name: String): UniFile? {
        findFile(name)?.delete()
        return createFile(name)
    }

    private fun NovelAsset.isHtml(): Boolean {
        return relativePath.last().substringAfterLast('.', "").lowercase() in HTML_EXTENSIONS
    }

    /** An HTML file inside a chapter directory, as opposed to one sitting at the novel root. */
    private fun NovelAsset.isChapterHtml(): Boolean = relativePath.size >= 2 && isHtml()

    private suspend fun restoreCoverToCache(
        novel: LNNovel,
        manga: tachiyomi.domain.manga.model.Manga,
        inputStream: InputStream,
        budget: ArchiveSizeBudget,
    ): Boolean {
        val staged = context.createFileInCacheDir("lnreader-cover-${manga.id}")
        return try {
            staged.outputStream().use { copyWithLimit(inputStream, it, MAX_COVER_BYTES, budget) }
            staged.inputStream().use { coverCache.setCustomCoverToCache(manga, it) }
            true
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "LNReaderImport: Failed to restore cached cover for '${novel.name}'" }
            false
        } finally {
            staged.delete()
        }
    }

    private fun normalizePluginId(pluginId: String): String {
        return pluginId.lowercase(Locale.ROOT).replace('-', '_')
    }

    private fun resolveSourceId(pluginIdToSourceId: Map<String, Long>, pluginId: String): Long? {
        val normalized = normalizePluginId(pluginId)
        return pluginIdToSourceId[normalized] ?: pluginIdToSourceId[normalized.replace('_', '-')]
    }

    /**
     * Placeholder source ID for a plugin the backup carries no metadata for. The 5e9 band is free:
     * [JsPlugin.sourceId] is a masked Int hash, so it lands either below 2^31 or near [Long.MAX_VALUE].
     */
    private fun generateStubSourceId(pluginId: String): Long {
        return 5_000_000_000L + (pluginId.hashCode().toLong() and 0x7FFFFFFF)
    }

    private fun writeErrorLog(): File {
        val logFile = File(context.cacheDir, "lnreader_import_errors.txt")
        logFile.printWriter().use { writer ->
            if (errors.isEmpty()) {
                writer.println("No errors encountered during import.")
            } else {
                writer.println("Errors encountered during import:")
                errors.forEach { (date, message) ->
                    val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)
                    writer.println("[$formattedDate] $message")
                }
            }
        }
        return logFile
    }

    private fun convertNovel(
        novel: LNNovel,
        sourceId: Long,
        novelIdToCategoryNames: Map<Int, List<String>>,
        backupCategories: List<BackupCategory>,
        includeChapters: Boolean = true,
        includeHistory: Boolean = true,
        includeCategories: Boolean = true,
    ): BackupManga {
        val normalized = normalizeNovelChapters(novel)
        val backupChapters = if (includeChapters) {
            normalized.chapters.mapIndexed { normalizedIndex, item ->
                val ch = item.chapter
                BackupChapter(
                    url = ch.path,
                    name = ch.name,
                    scanlator = ch.scanlator,
                    read = !ch.unread,
                    bookmark = ch.bookmark,
                    lastPageRead = ch.progress?.toLong() ?: 0L,
                    dateFetch = 0L,
                    dateUpload = parseDate(ch.releaseTime) ?: 0L,
                    chapterNumber = ch.chapterNumber ?: (normalizedIndex + 1).toFloat(),
                    sourceOrder = when (normalized.layout) {
                        NovelLayout.PAGED -> pagedSourceOrder(
                            novel.totalPages.toLong(),
                            item.pageNumber!!,
                            item.indexInSection,
                        )
                        else -> (normalized.chapters.lastIndex - normalizedIndex).toLong()
                    },
                )
            }
        } else {
            emptyList()
        }

        val backupHistory = if (includeHistory) {
            novel.chapters
                .filter { it.readTime != null }
                .map { ch ->
                    BackupHistory(
                        url = ch.path,
                        lastRead = parseDate(ch.readTime) ?: 0L,
                        readDuration = normalizeLnReaderReadDuration(ch.readDuration, ch.timeSpent),
                    )
                }
        } else {
            emptyList()
        }

        // Map novel ID to category orders (use the BackupCategory.order, not list index)
        val categoryOrders = if (includeCategories) {
            val categoryNames = novelIdToCategoryNames[novel.id].orEmpty()
            categoryNames.mapNotNull { name ->
                backupCategories.firstOrNull { it.name == name }?.order
            }
        } else {
            emptyList()
        }

        val status = statusValue(novel.status)

        return BackupManga(
            source = sourceId,
            url = novel.path,
            title = novel.name,
            artist = novel.artist,
            author = novel.author,
            description = novel.summary,
            genre = novel.genres?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            status = status,
            thumbnailUrl = novel.cover?.let { cover ->
                if (cover.startsWith("/Novels/") || cover.startsWith("/storage/")) {
                    null
                } else {
                    cover
                }
            },
            favorite = novel.inLibrary,
            chapters = backupChapters,
            categories = categoryOrders,
            history = backupHistory,
            dateAdded = System.currentTimeMillis(),
            chapterFlags = with(libraryPreferences) {
                (
                    filterChapterByRead.get() or
                        filterChapterByDownloaded.get() or
                        filterChapterByBookmarked.get() or
                        sortChapterBySourceOrNumber.get() or
                        sortChapterByAscendingOrDescending.get() or
                        displayChapterByNameOrNumber.get()
                    ).toInt()
            },
            isNovel = true,
            novelStructure = if (includeChapters) normalized.structure else null,
        )
    }

    private fun statusValue(status: String?): Int = when (status?.lowercase()) {
        "ongoing" -> 1
        "completed" -> 2
        "licensed" -> 3
        "publishing finished" -> 4
        "cancelled" -> 5
        "on hiatus" -> 6
        else -> 0
    }

    private data class NormalizedChapter(
        val chapter: LNChapter,
        val originalIndex: Int,
        val section: String,
        val pageNumber: Long?,
        val indexInSection: Int,
    )

    private data class NormalizedNovel(
        val layout: NovelLayout,
        val chapters: List<NormalizedChapter>,
        val structure: BackupNovelStructure,
    )

    private fun normalizeNovelChapters(novel: LNNovel): NormalizedNovel {
        require(novel.totalPages >= 0) { "Invalid totalPages for '${novel.name}'" }
        val layout = when {
            novel.totalPages >= 1 -> NovelLayout.PAGED
            novel.chapters.any { !it.page.isNullOrBlank() && !it.page.equals(DEFAULT_SECTION, true) } ->
                NovelLayout.VOLUME
            else -> NovelLayout.FLAT
        }
        val raw = novel.chapters.mapIndexedNotNull { index, chapter ->
            val section = when (layout) {
                NovelLayout.PAGED -> {
                    val value = chapter.page.ifBlank { "1" }
                    val page = value.toLongOrNull()
                    if (page == null || value != page.toString() || page !in 1..novel.totalPages.toLong()) {
                        errors.add(Date() to "${novel.name}: invalid page '$value' for chapter '${chapter.name}'")
                        return@mapIndexedNotNull null
                    }
                    value
                }
                NovelLayout.VOLUME -> chapter.page.trim().ifBlank { DEFAULT_SECTION }
                NovelLayout.FLAT -> DEFAULT_SECTION
            }
            Triple(index, chapter, section)
        }
        val sectionNames = when (layout) {
            NovelLayout.PAGED -> raw.map { it.third }.distinct().sortedBy(String::toLong)
            else -> raw.map { it.third }.distinct()
        }
        val normalized = sectionNames.flatMap { section ->
            val sectionChapters = raw.filter { it.third == section }
            val positions = sectionChapters.map { it.second.position }
            val positionsValid =
                positions.all { it != null && it >= 0 } && positions.filterNotNull().distinct().size == positions.size
            if (!positionsValid && positions.any { it != null }) {
                errors.add(Date() to "${novel.name}: invalid chapter positions in '$section'; JSON order used")
            }
            val ordered = if (positionsValid) sectionChapters.sortedBy { it.second.position } else sectionChapters
            ordered.mapIndexed { indexInSection, (originalIndex, chapter, name) ->
                NormalizedChapter(
                    chapter = chapter,
                    originalIndex = originalIndex,
                    section = name,
                    pageNumber = name.toLongOrNull().takeIf { layout == NovelLayout.PAGED },
                    indexInSection = indexInSection,
                )
            }
        }
        val structure = BackupNovelStructure(
            layout = layout.value,
            totalPages = if (layout == NovelLayout.PAGED) novel.totalPages.toLong() else 0,
            sections = sectionNames.map { section ->
                BackupNovelSection(
                    name = section,
                    pageNumber = section.toLongOrNull().takeIf { layout == NovelLayout.PAGED },
                    chapterUrls = normalized.filter { it.section == section }.map { it.chapter.path },
                )
            },
        )
        return NormalizedNovel(layout, normalized, structure)
    }

    private fun parseDate(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        dateStr.toLongOrNull()?.let { return it }
        return DATE_FORMATS.firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.getDefault()).parse(dateStr)?.time }.getOrNull()
        }
    }

    /** Keep streaming JSON reads cancellable and subject to the same archive limits as extraction. */
    private suspend fun limitedEntryStream(input: InputStream, maxBytes: Long, budget: ArchiveSizeBudget): InputStream {
        val coroutineContext = currentCoroutineContext()
        return object : InputStream() {
            private var consumed = 0L

            override fun read(): Int {
                val buffer = ByteArray(1)
                return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                coroutineContext.ensureActive()
                val read = input.read(buffer, offset, length)
                if (read > 0) {
                    consumed += read
                    require(consumed <= maxBytes) { "Archive entry exceeds size limit" }
                    budget.consume(read.toLong())
                }
                return read
            }
        }
    }

    private suspend fun readEntryText(
        input: InputStream,
        maxBytes: Long,
        budget: ArchiveSizeBudget? = null,
    ): String {
        val output = ByteArrayOutputStream()
        copyWithLimit(input, output, maxBytes, budget)
        return output.toString(Charsets.UTF_8.name())
    }

    private suspend fun copyEntryToFile(
        input: InputStream,
        target: File,
        maxBytes: Long,
        budget: ArchiveSizeBudget? = null,
    ) {
        try {
            target.outputStream().use { copyWithLimit(input, it, maxBytes, budget) }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    private suspend fun copyWithLimit(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        budget: ArchiveSizeBudget? = null,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            require(total <= maxBytes) { "Archive entry exceeds size limit" }
            budget?.consume(read.toLong())
            output.write(buffer, 0, read)
        }
    }

    private class ArchiveSizeBudget(private val maximum: Long) {
        private var consumed = 0L

        fun consume(bytes: Long) {
            consumed = Math.addExact(consumed, bytes)
            require(consumed <= maximum) { "Archive exceeds total extracted size limit" }
        }
    }

    private companion object {
        const val DEFAULT_SECTION = "Default"
        const val LOCAL_PLUGIN_ID = "local"

        /** Room for the `.html` appended after a local chapter file name is trimmed to length. */
        const val HTML_EXTENSION_BYTES = 5
        const val MAX_OUTER_ENTRIES = 100_000
        const val MAX_NESTED_ENTRIES = 200_000
        const val MAX_JSON_BYTES = 64L * 1024 * 1024
        const val MAX_PLUGIN_BYTES = 32L * 1024 * 1024
        const val MAX_ASSET_BYTES = 512L * 1024 * 1024
        const val MAX_COVER_BYTES = 64L * 1024 * 1024
        const val MAX_NESTED_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_OUTER_EXPANDED_BYTES = 5L * 1024 * 1024 * 1024
        const val MAX_PLUGIN_ARCHIVE_EXPANDED_BYTES = 512L * 1024 * 1024
        const val MAX_NOVEL_ASSET_TOTAL_BYTES = 16L * 1024 * 1024 * 1024
        val DATE_FORMATS =
            listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd")
        val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")
    }
}

internal fun isSupportedLnReaderVersion(version: String): Boolean {
    val match = LNREADER_VERSION_CORE.find(version.trim()) ?: return false
    val values = match.groupValues.drop(1).map { it.toInt() }
    return values[0] > 2 ||
        (values[0] == 2 && (values[1] > 0 || (values[1] == 0 && values[2] >= 2)))
}

internal fun normalizeLnReaderReadDuration(readDurationSeconds: Long?, timeSpentMilliseconds: Long?): Long =
    readDurationSeconds?.coerceIn(0L, Long.MAX_VALUE / 1_000L)?.times(1_000L)
        ?: timeSpentMilliseconds?.coerceAtLeast(0L)
        ?: 0L

private val LNREADER_VERSION_CORE = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$")
private val LNREADER_DRIVE_PATH = Regex("^[A-Za-z]:")

internal fun validateLnReaderArchivePath(rawPath: String, isDirectory: Boolean): String {
    require(rawPath.isNotBlank() && '\u0000' !in rawPath && '\\' !in rawPath) { "Unsafe archive path" }
    val path = if (isDirectory) rawPath.trimEnd('/') else rawPath
    require(path.isNotBlank() && !path.startsWith('/') && !LNREADER_DRIVE_PATH.containsMatchIn(path)) {
        "Unsafe archive path: $rawPath"
    }
    require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "Unsafe archive path: $rawPath"
    }
    return path
}

private val LNREADER_FILE_ATTRIBUTE =
    Regex("""(\b(?:src|href|poster)\s*=\s*[\"'])(file://[^\"']+)([\"'])""", RegexOption.IGNORE_CASE)

/** Replaces every `file://` resource reference for which [resolve] yields a replacement. */
private fun rewriteLnReaderFileAttributes(content: String, resolve: (path: String) -> String?): String {
    if (!content.contains("file://", ignoreCase = true)) return content
    return LNREADER_FILE_ATTRIBUTE.replace(content) { match ->
        val fileUrl = match.groupValues[2]
        val path = runCatching { URI(fileUrl.replace(" ", "%20")).path }.getOrNull() ?: return@replace match.value
        val replacement = resolve(path) ?: return@replace match.value
        "${match.groupValues[1]}$replacement${match.groupValues[3]}"
    }
}

/**
 * Points a local novel's chapter at the sibling files the import wrote, keyed by the name the asset had
 * in the backup. A reference the backup has no file for is left alone rather than pointed at nothing.
 */
internal fun rewriteLnReaderLocalAssetUrls(content: String, assetNames: Map<String, String>): String =
    rewriteLnReaderFileAttributes(content) { path -> assetNames[path.substringAfterLast('/')] }

/** Points a downloaded remote chapter at the assets stored alongside it in the chapter archive. */
internal fun rewriteLnReaderChapterAssetUrls(content: String): String =
    rewriteLnReaderFileAttributes(content) { path ->
        val parts = path.split('/').filter(String::isNotBlank)
        val novelsIndex = parts.indexOfFirst { it.equals("Novels", ignoreCase = true) }
        if (novelsIndex < 0) return@rewriteLnReaderFileAttributes null
        val relativePath = parts.drop(novelsIndex + 4).joinToString("/")
        if (relativePath.isBlank()) return@rewriteLnReaderFileAttributes null
        "tsundoku-novel-image://" + URLEncoder.encode(relativePath, StandardCharsets.UTF_8.name())
    }
