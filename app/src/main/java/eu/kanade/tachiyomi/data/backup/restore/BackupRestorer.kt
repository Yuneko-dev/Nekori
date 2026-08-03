package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.BackupProtoMigration
import eu.kanade.tachiyomi.data.backup.BackupProtoReader
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupJsPluginRepository
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.jsplugin.model.JsPluginRepository
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val mangaRestorer: MangaRestorer = MangaRestorer(),
    private val jsPluginManager: JsPluginManager = Injekt.get(),
    private val parser: ProtoBuf = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val stubSourceRepository: StubSourceRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    private var restoreAmount = 0
    private val restoreProgress = AtomicInt(0)
    private val errors = CopyOnWriteArrayList<Pair<Date, String>>()
    private val restoredMangaIds = CopyOnWriteArrayList<Long>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options)

        if (options.libraryEntries && restoredMangaIds.isNotEmpty()) {
            try {
                getLibraryManga.refreshForcedScoped(restoredMangaIds.distinct())
            } catch (_: Exception) {
            }
        } else if (options.libraryEntries || options.categories) {
            try {
                getLibraryManga.refreshForced()
            } catch (_: Exception) {
            }
        }

        // Invalidate download cache to ensure UI reflects any restored downloads
        if (options.libraryEntries) {
            try {
                Injekt.get<DownloadCache>().invalidateCache()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to invalidate download cache after restore" }
            }
        }

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val summary = readBackupSummary(uri)

        // Store source mapping for error messages
        sourceMapping = summary.backupSources.associate { it.sourceId to it.name }
        if (options.libraryEntries) {
            restoreNovelSourceStubs(summary.backupSources, summary.novelSourceIds)
        }

        if (options.libraryEntries) {
            restoreAmount += summary.mangaCount
        }
        if (options.categories) {
            restoreAmount += 1
        }
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionRepositories && summary.backupJsPluginRepositories.isNotEmpty()) {
            restoreAmount += 1
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }

        coroutineScope {
            // Categories MUST be restored before preferences because preference restoration
            // maps backup category IDs → current DB category IDs by name.  Running both
            // concurrently causes restoreAppPreferences to see an empty categories table.
            val categoriesJob = if (options.categories) {
                restoreCategories(summary.backupCategories)
            } else {
                null
            }
            categoriesJob?.join()

            if (options.appSettings) {
                restoreAppPreferences(summary.backupPreferences, summary.backupCategories.takeIf { options.categories })
            }
            if (options.sourceSettings) {
                restoreSourcePreferences(summary.backupSourcePreferences, summary.jsSourceKeys)
            }
            if (options.libraryEntries) {
                restoreMangaStream(uri, if (options.categories) summary.backupCategories else emptyList())
            }
            if (options.extensionRepositories) {
                restoreJsPluginRepositories(summary.backupJsPluginRepositories)
            }

            // TODO: optionally trigger online library + tracker update
        }
    }

    private suspend fun readBackupSummary(uri: Uri): BackupSummary {
        val backupCategories = mutableListOf<BackupCategory>()
        val backupSources = mutableListOf<BackupSource>()
        val backupPreferences = mutableListOf<BackupPreference>()
        val backupSourcePreferences = mutableListOf<BackupSourcePreferences>()
        val backupJsPluginRepositories = mutableListOf<BackupJsPluginRepository>()
        val novelCategoryOrders = mutableSetOf<Long>()
        val novelSourceIds = mutableSetOf<Long>()
        var mangaCount = 0

        val reader = BackupProtoReader(context)
        reader.read(uri) { fieldNumber, data ->
            when (fieldNumber) {
                1 -> {
                    val manga = parser.decodeFromByteArray(
                        BackupManga.serializer(),
                        BackupProtoMigration.migrateManga(data),
                    )
                    if (manga.isNovel) {
                        mangaCount++
                        novelCategoryOrders += manga.categories
                        novelSourceIds += manga.source
                    }
                }
                2 -> backupCategories.add(parser.decodeFromByteArray(BackupCategory.serializer(), data))
                101 -> backupSources.add(parser.decodeFromByteArray(BackupSource.serializer(), data))
                104 -> backupPreferences.add(parser.decodeFromByteArray(BackupPreference.serializer(), data))
                105 -> backupSourcePreferences.add(
                    parser.decodeFromByteArray(BackupSourcePreferences.serializer(), data),
                )
                106 -> {
                    // Keep decoding the upstream field for file compatibility. Kotlin extension
                    // repository restore is intentionally disabled in this novel-only build.
                    parser.decodeFromByteArray(
                        BackupExtensionStore.serializer(),
                        BackupProtoMigration.migrateExtensionStore(data),
                    )
                }
                9000 -> backupJsPluginRepositories.add(
                    parser.decodeFromByteArray(BackupJsPluginRepository.serializer(), data),
                )
            }
        }

        val novelCategories = backupCategories.mapNotNull { category ->
            when (category.contentType) {
                Category.CONTENT_TYPE_NOVEL -> category
                Category.CONTENT_TYPE_ALL -> category.takeIf { it.order in novelCategoryOrders }
                else -> null
            }?.also { it.contentType = Category.CONTENT_TYPE_NOVEL }
        }
        val repositories = backupJsPluginRepositories.ifEmpty {
            backupPreferences.firstOrNull { it.key == JS_REPOSITORIES_PREFERENCE_KEY }
                ?.value
                ?.let { it as? eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue }
                ?.value
                ?.let { value ->
                    runCatching {
                        json.decodeFromString<List<JsPluginRepository>>(value).map {
                            BackupJsPluginRepository(it.name, it.url, it.enabled)
                        }
                    }.getOrDefault(emptyList())
                }
                .orEmpty()
        }
        val jsSourceKeys = backupSources
            .filter { it.sourceId in novelSourceIds && it.isJs }
            .mapTo(mutableSetOf()) { "source_${it.sourceId}" }

        return BackupSummary(
            mangaCount = mangaCount,
            backupCategories = novelCategories,
            backupSources = backupSources,
            backupPreferences = backupPreferences,
            backupSourcePreferences = backupSourcePreferences,
            backupJsPluginRepositories = repositories,
            jsSourceKeys = jsSourceKeys,
            novelSourceIds = novelSourceIds,
        )
    }

    private fun CoroutineScope.restoreMangaStream(
        uri: Uri,
        backupCategories: List<BackupCategory>,
    ) = launch {
        val reader = BackupProtoReader(context)
        reader.read(uri) { fieldNumber, data ->
            if (fieldNumber != 1) return@read
            ensureActive()

            val backupManga = parser.decodeFromByteArray(
                BackupManga.serializer(),
                BackupProtoMigration.migrateManga(data),
            )
            if (backupManga.isNovel) {
                try {
                    restoredMangaIds.add(mangaRestorer.restore(backupManga, backupCategories))
                } catch (e: Exception) {
                    val sourceName = sourceMapping[backupManga.source] ?: backupManga.source.toString()
                    errors.add(Date() to "${backupManga.title} [$sourceName]: ${e.message}")
                }
                val progress = restoreProgress.incrementAndFetch()
                notifier.showRestoreProgress(backupManga.title, progress, restoreAmount, isSync)
            }
        }
    }

    private data class BackupSummary(
        val mangaCount: Int,
        val backupCategories: List<BackupCategory>,
        val backupSources: List<BackupSource>,
        val backupPreferences: List<BackupPreference>,
        val backupSourcePreferences: List<BackupSourcePreferences>,
        val backupJsPluginRepositories: List<BackupJsPluginRepository>,
        val jsSourceKeys: Set<String>,
        val novelSourceIds: Set<Long>,
    )

    private fun CoroutineScope.restoreCategories(backupCategories: List<BackupCategory>) = launch {
        ensureActive()
        categoriesRestorer(backupCategories)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.categories),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.app_settings),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreSourcePreferences(
        preferences: List<BackupSourcePreferences>,
        allowedSourceKeys: Set<String>,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences.filter { it.sourceKey in allowedSourceKeys })

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.source_settings),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreJsPluginRepositories(
        repositories: List<BackupJsPluginRepository>,
    ) = launch {
        if (repositories.isEmpty()) return@launch
        ensureActive()
        jsPluginManager.restoreRepositories(repositories.map(BackupJsPluginRepository::toRepository))
        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(tachiyomi.i18n.novel.TDMR.strings.pref_novel_extension_repos),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("tsundoku_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // Empty
        }
        return File("")
    }

    private suspend fun restoreNovelSourceStubs(sources: List<BackupSource>, novelSourceIds: Set<Long>) {
        sources.filter {
            it.sourceId in novelSourceIds && sourceManager.get(it.sourceId).let { current ->
                current == null || current is StubSource
            }
        }.forEach { source ->
            stubSourceRepository.upsertStubSource(
                id = source.sourceId,
                lang = source.lang,
                name = source.name,
                isNovel = true,
                isJs = source.isJs,
            )
        }
    }

    private companion object {
        const val JS_REPOSITORIES_PREFERENCE_KEY = "js_plugin_repositories_backup"
    }
}
