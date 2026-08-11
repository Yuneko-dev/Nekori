@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.presentation.more.settings.screen

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.advanced.ClearDatabaseScreen
import eu.kanade.presentation.more.settings.screen.debug.DebugInfoScreen
import eu.kanade.tachiyomi.data.database.DatabaseMaintenanceJob
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.library.MetadataUpdateJob
import eu.kanade.tachiyomi.data.storage.QuotesPortableMigrator
import eu.kanade.tachiyomi.data.storage.TranslationsPortableMigrator
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_360
import eu.kanade.tachiyomi.network.PREF_DOH_ADGUARD
import eu.kanade.tachiyomi.network.PREF_DOH_ALIDNS
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_CONTROLD
import eu.kanade.tachiyomi.network.PREF_DOH_DNSPOD
import eu.kanade.tachiyomi.network.PREF_DOH_GOOGLE
import eu.kanade.tachiyomi.network.PREF_DOH_MULLVAD
import eu.kanade.tachiyomi.network.PREF_DOH_NJALLA
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD101
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import eu.kanade.tachiyomi.network.PREF_DOH_SHECAN
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.NovelWebViewNetworkMode
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.powerManager
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.Headers
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseMaintenance
import tachiyomi.domain.download.service.NovelDownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import tachiyomi.core.common.i18n.stringResource as contextStringResource

object SettingsAdvancedScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_advanced

    @Composable
    override fun getPreferences(): List<Preference> {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val networkPreferences = remember { Injekt.get<NetworkPreferences>() }
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
        val novelDownloadPreferences = remember { Injekt.get<NovelDownloadPreferences>() }

        val libraryPageSize by libraryPreferences.experimentalLibraryPageSize.collectAsState()

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_dump_crash_logs),
                subtitle = stringResource(MR.strings.pref_dump_crash_logs_summary),
                onClick = {
                    scope.launch {
                        CrashLogUtil(context).dumpLogs()
                    }
                },
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = networkPreferences.verboseLogging,
                title = stringResource(MR.strings.pref_verbose_logging),
                subtitle = stringResource(MR.strings.pref_verbose_logging_summary),
                onValueChanged = {
                    context.toast(MR.strings.requires_app_restart)
                    true
                },
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = libraryPreferences.showMangaSourceName,
                title = stringResource(TDMR.strings.pref_show_manga_source_name),
                subtitle = stringResource(TDMR.strings.pref_show_manga_source_name_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = libraryPreferences.experimentalLibraryPagination,
                title = stringResource(TDMR.strings.pref_experimental_library_pagination),
                subtitle = stringResource(TDMR.strings.pref_experimental_library_pagination_summary),
                onValueChanged = {
                    context.toast(MR.strings.requires_app_restart)
                    true
                },
            ),
            Preference.PreferenceItem.SliderPreference(
                value = libraryPageSize,
                valueRange = 50..10000 step 500,
                steps = 18,
                title = stringResource(TDMR.strings.pref_experimental_library_page_size),
                subtitle = stringResource(TDMR.strings.pref_experimental_library_page_size_summary, libraryPageSize),
                valueString = libraryPageSize.toString(),
                enabled = libraryPreferences.experimentalLibraryPagination.get(),
                onValueChanged = {
                    libraryPreferences.experimentalLibraryPageSize.set(it)
                    context.toast(MR.strings.requires_app_restart)
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_debug_info),
                onClick = { navigator.push(DebugInfoScreen()) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_onboarding_guide),
                onClick = { navigator.push(OnboardingScreen()) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_manage_notifications),
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                },
            ),
            getBackgroundActivityGroup(),
            getDataGroup(),
            getNetworkGroup(networkPreferences = networkPreferences),
            getLibraryGroup(libraryPreferences = libraryPreferences),
            getNovelReaderGroup(),
            getMassImportGroup(novelDownloadPreferences = novelDownloadPreferences),
        )
    }

    @Composable
    private fun getMassImportGroup(
        novelDownloadPreferences: NovelDownloadPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_category_mass_import),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelDownloadPreferences.massImportSeparateFilePerBatch(),
                    title = stringResource(TDMR.strings.pref_mass_import_separate_file_per_batch),
                    subtitle = stringResource(TDMR.strings.pref_mass_import_separate_file_per_batch_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelDownloadPreferences.massImportSplitByDomain(),
                    title = stringResource(TDMR.strings.pref_mass_import_split_by_domain),
                    subtitle = stringResource(TDMR.strings.pref_mass_import_split_by_domain_summary),
                ),
            ),
        )
    }

    @Composable
    private fun getNovelReaderGroup(): Preference.PreferenceGroup {
        val readerPreferences = remember { Injekt.get<ReaderPreferences>() }
        val devToolsEnabled by readerPreferences.novelWebViewDevTools.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_category_novel_webview),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.novelWebViewNetworkMode,
                    entries = mapOf(
                        NovelWebViewNetworkMode.CHROMIUM to
                            stringResource(TDMR.strings.pref_novel_webview_network_chromium),
                        NovelWebViewNetworkMode.NETWORK_HELPER to
                            stringResource(TDMR.strings.pref_novel_webview_network_interceptor),
                    ),
                    title = stringResource(TDMR.strings.pref_novel_webview_network_mode),
                    subtitleProvider = { value, _ ->
                        stringResource(
                            when (value) {
                                NovelWebViewNetworkMode.CHROMIUM ->
                                    TDMR.strings.pref_novel_webview_network_chromium_summary
                                NovelWebViewNetworkMode.NETWORK_HELPER ->
                                    TDMR.strings.pref_novel_webview_network_interceptor_summary
                            },
                        )
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelWebViewLocalProxyEnabled,
                    title = stringResource(TDMR.strings.pref_novel_webview_local_proxy),
                    subtitle = stringResource(TDMR.strings.pref_novel_webview_local_proxy_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelWebViewRemoteDebugging,
                    title = stringResource(TDMR.strings.pref_novel_webview_remote_debugging),
                    subtitle = stringResource(TDMR.strings.pref_novel_webview_remote_debugging_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelWebViewDevTools,
                    title = stringResource(TDMR.strings.pref_novel_webview_devtools),
                    subtitle = stringResource(TDMR.strings.pref_novel_webview_devtools_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.novelConsoleErrorToast,
                    title = stringResource(TDMR.strings.pref_novel_console_error_toast),
                    subtitle = stringResource(TDMR.strings.pref_novel_console_error_toast_summary),
                    enabled = devToolsEnabled,
                ),
            ),
        )
    }

    @Composable
    private fun getBackgroundActivityGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_background_activity),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_disable_battery_optimization),
                    subtitle = stringResource(MR.strings.pref_disable_battery_optimization_summary),
                    onClick = {
                        val packageName: String = context.packageName
                        if (!context.powerManager.isIgnoringBatteryOptimizations(packageName)) {
                            try {
                                @SuppressLint("BatteryLife")
                                val intent = Intent().apply {
                                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                    data = "package:$packageName".toUri()
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                context.toast(MR.strings.battery_optimization_setting_activity_not_found)
                            }
                        } else {
                            context.toast(MR.strings.battery_optimization_disabled)
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Don't kill my app!",
                    subtitle = stringResource(MR.strings.about_dont_kill_my_app),
                    onClick = { uriHandler.openUri("https://dontkillmyapp.com/") },
                ),
            ),
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
        var showDeleteTranslationsDialog by remember { mutableStateOf(false) }
        var portableMigrationRunning by remember { mutableStateOf(false) }
        var showResetSettingsDialog by remember { mutableStateOf(false) }

        if (showDeleteTranslationsDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteTranslationsDialog = false },
                title = { Text(text = stringResource(MR.strings.pref_delete_all_translations)) },
                text = {
                    Text(text = stringResource(MR.strings.pref_delete_all_translations_confirm))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                Injekt.get<TranslatedChapterRepository>().deleteAll()
                                context.toast(MR.strings.pref_all_translations_deleted)
                                showDeleteTranslationsDialog = false
                            }
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteTranslationsDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        if (showResetSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showResetSettingsDialog = false },
                title = { Text(text = stringResource(MR.strings.pref_reset_settings)) },
                text = {
                    Text(text = stringResource(MR.strings.pref_reset_settings_confirm))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                                if (prefsDir.exists()) {
                                    prefsDir.listFiles()?.forEach { file ->
                                        val prefName = file.nameWithoutExtension
                                        // Skip source-specific preferences
                                        if (prefName.startsWith("source_")) return@forEach
                                        context.getSharedPreferences(prefName, 0)
                                            .edit()
                                            .clear()
                                            .apply()
                                    }
                                }
                                showResetSettingsDialog = false
                                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                                Runtime.getRuntime().exit(0)
                            }
                        },
                    ) {
                        Text(text = stringResource(MR.strings.pref_reset_settings_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetSettingsDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_data),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_invalidate_download_cache),
                    subtitle = stringResource(MR.strings.pref_invalidate_download_cache_summary),
                    onClick = {
                        Injekt.get<DownloadCache>().invalidateCache()
                        context.toast(MR.strings.download_cache_invalidated)
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_database),
                    subtitle = stringResource(MR.strings.pref_clear_database_summary),
                    onClick = { navigator.push(ClearDatabaseScreen()) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_db_statistics),
                    subtitle = stringResource(MR.strings.pref_db_statistics_subtitle),
                    onClick = {
                        scope.launch {
                            try {
                                val stats = Injekt.get<DatabaseMaintenance>().getDetailedDatabaseStats()

                                val totalSize = (stats["total_size_bytes"] as? Long) ?: 0L
                                val freelistSize = (stats["freelist_size_bytes"] as? Long) ?: 0L

                                @Suppress("UNCHECKED_CAST")
                                val tableCounts = (stats["table_row_counts"] as? Map<String, Long>) ?: emptyMap()

                                @Suppress("UNCHECKED_CAST")
                                val tableSizes = (stats["table_sizes_bytes"] as? Map<String, Long>) ?: emptyMap()

                                @Suppress("UNCHECKED_CAST")
                                val indexSizes = (stats["index_sizes_bytes"] as? Map<String, Long>) ?: emptyMap()
                                val avgChapterBytes = (stats["avg_chapter_text_bytes"] as? Double) ?: 0.0
                                val avgDescBytes = (stats["avg_manga_description_bytes"] as? Double) ?: 0.0

                                fun formatSize(bytes: Long): String = when {
                                    bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
                                    bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
                                    bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
                                    else -> "$bytes B"
                                }

                                val report = buildString {
                                    appendLine("=== Database Statistics ===")
                                    appendLine("Total size: ${formatSize(totalSize)}")
                                    appendLine("Freelist (reclaimable): ${formatSize(freelistSize)}")
                                    appendLine()
                                    appendLine("--- Row Counts ---")
                                    tableCounts.entries.sortedByDescending { it.value }.forEach { (table, count) ->
                                        appendLine("$table: ${"%,d".format(count)}")
                                    }
                                    if (tableSizes.isNotEmpty()) {
                                        appendLine()
                                        appendLine("--- Table Sizes ---")
                                        tableSizes.entries.sortedByDescending { it.value }.forEach { (table, size) ->
                                            appendLine("$table: ${formatSize(size)}")
                                        }
                                    }
                                    if (indexSizes.isNotEmpty()) {
                                        appendLine()
                                        appendLine("--- Index Sizes ---")
                                        val totalIndexSize = indexSizes.values.sum()
                                        appendLine("Total indexes: ${formatSize(totalIndexSize)}")
                                        indexSizes.entries.sortedByDescending {
                                            it.value
                                        }.take(10).forEach { (idx, size) ->
                                            appendLine("$idx: ${formatSize(size)}")
                                        }
                                    }
                                    appendLine()
                                    appendLine("--- Averages ---")
                                    appendLine("Avg chapter text: %.1f bytes".format(avgChapterBytes))
                                    appendLine("Avg novel description: %.1f bytes".format(avgDescBytes))

                                    // Size estimation breakdown
                                    val chapterCount = tableCounts["chapters"] ?: 0L
                                    val mangaCount = tableCounts["mangas"] ?: 0L
                                    if (chapterCount > 0) {
                                        appendLine()
                                        appendLine("--- Estimated Breakdown ---")
                                        // Each chapter row ~= fixed columns (~80 bytes) + text data
                                        val estChapterRowSize = 80 + avgChapterBytes
                                        val estChapterTableSize = (chapterCount * estChapterRowSize).toLong()
                                        appendLine("Chapters data: ~${formatSize(estChapterTableSize)}")

                                        // 6 indexes on chapters table, each ~16-40 bytes per row
                                        val estChapterIndexSize = chapterCount * 150 // ~150 bytes per row for all indexes
                                        appendLine("Chapter indexes: ~${formatSize(estChapterIndexSize)}")

                                        if (avgDescBytes > 0 && mangaCount > 0) {
                                            val estMangaSize = (mangaCount * (200 + avgDescBytes)).toLong()
                                            appendLine("Novel data: ~${formatSize(estMangaSize)}")
                                        }
                                    }
                                }

                                withUIContext {
                                    context.copyToClipboard("Database Stats", report)
                                    context.toast("Statistics copied to clipboard")
                                }
                                logcat(LogPriority.INFO) { report }
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR, e) { "Failed to get database stats" }
                                withUIContext {
                                    context.toast("Error: ${e.message}")
                                }
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_db_maintenance),
                    subtitle = stringResource(MR.strings.pref_db_maintenance_subtitle),
                    onClick = {
                        if (DatabaseMaintenanceJob.isRunning(context)) {
                            context.toast(context.contextStringResource(TDMR.strings.db_maintenance_already_running))
                        } else {
                            DatabaseMaintenanceJob.start(context)
                            context.toast(context.contextStringResource(TDMR.strings.db_maintenance_started))
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_delete_all_translations),
                    subtitle = stringResource(MR.strings.pref_delete_all_translations_subtitle),
                    onClick = { showDeleteTranslationsDialog = true },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_migrate_quotes_portable),
                    subtitle = stringResource(MR.strings.pref_migrate_quotes_portable_subtitle),
                    onClick = {
                        if (!portableMigrationRunning) {
                            portableMigrationRunning = true
                            scope.launch {
                                val count = try {
                                    QuotesPortableMigrator.run()
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e)
                                    withUIContext { context.toast(MR.strings.pref_portable_migration_error) }
                                    return@launch
                                } finally {
                                    portableMigrationRunning = false
                                }
                                withUIContext {
                                    context.toast(
                                        context.contextStringResource(MR.strings.pref_portable_migration_done, count),
                                    )
                                }
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_migrate_translations_portable),
                    subtitle = stringResource(MR.strings.pref_migrate_translations_portable_subtitle),
                    onClick = {
                        if (!portableMigrationRunning) {
                            portableMigrationRunning = true
                            scope.launch {
                                val count = try {
                                    TranslationsPortableMigrator.run()
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e)
                                    withUIContext { context.toast(MR.strings.pref_portable_migration_error) }
                                    return@launch
                                } finally {
                                    portableMigrationRunning = false
                                }
                                withUIContext {
                                    context.toast(
                                        context.contextStringResource(MR.strings.pref_portable_migration_done, count),
                                    )
                                }
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_temp_files),
                    subtitle = stringResource(MR.strings.pref_clear_temp_files_subtitle),
                    onClick = {
                        scope.launch {
                            var clearedSize = 0L
                            try {
                                // Clear network cache
                                val networkCacheDir = File(context.cacheDir, "network_cache")
                                if (networkCacheDir.exists()) {
                                    clearedSize +=
                                        networkCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                                    networkCacheDir.deleteRecursively()
                                }

                                // Clear epub export temp files
                                context.cacheDir.listFiles()?.filter { it.name.startsWith("epub_export_") }?.forEach {
                                    clearedSize += it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() }
                                    it.deleteRecursively()
                                }

                                // Clear mass import temp files
                                context.cacheDir.listFiles()?.filter { it.name.startsWith("mass_import_") }?.forEach {
                                    clearedSize += it.length()
                                    it.delete()
                                }

                                // Clear font temp files
                                context.cacheDir.listFiles()?.filter {
                                    it.name.startsWith("font_") &&
                                        it.extension == "ttf"
                                }?.forEach {
                                    clearedSize += it.length()
                                    it.delete()
                                }

                                // Clear update error files
                                context.cacheDir.listFiles()?.filter { it.name.contains("update_errors") }?.forEach {
                                    clearedSize += it.length()
                                    it.delete()
                                }

                                // Clear translation temp (.tmp) files
                                try {
                                    val translationRepo = Injekt.get<TranslatedChapterRepository>()
                                    clearedSize += translationRepo.clearTmpFiles()
                                } catch (_: Exception) { }

                                val sizeString = when {
                                    clearedSize >= 1024 * 1024 -> "%.2f MB".format(clearedSize / (1024.0 * 1024.0))
                                    clearedSize >= 1024 -> "%.2f KB".format(clearedSize / 1024.0)
                                    else -> "$clearedSize bytes"
                                }
                                withUIContext {
                                    context.toast(
                                        context.contextStringResource(MR.strings.pref_temp_files_cleared, sizeString),
                                    )
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR, e)
                                withUIContext {
                                    context.toast(MR.strings.pref_temp_files_error)
                                }
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Normalize tags",
                    subtitle = "Trims whitespace and removes duplicate tags (case-insensitive)",
                    onClick = {
                        scope.launch {
                            val count = Injekt.get<MangaRepository>().normalizeAllTags()
                            withUIContext {
                                context.toast("Normalized tags for $count novels")
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.normalizeTagsOnUpdate,
                    title = "Normalize tags when updating entry",
                    subtitle = "Apply the same normalization automatically when entry details are " +
                        "fetched/updated or tags are edited",
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Reset settings to default",
                    subtitle = "Resets all app settings to their default values (library data is preserved)",
                    onClick = { showResetSettingsDialog = true },
                ),
            ),
        )
    }

    @Composable
    private fun getNetworkGroup(
        networkPreferences: NetworkPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val networkHelper = remember { Injekt.get<NetworkHelper>() }

        val userAgentPref = networkPreferences.defaultUserAgent
        val userAgent by userAgentPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_network),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_cookies),
                    onClick = {
                        networkHelper.cookieJar.removeAll()
                        context.toast(MR.strings.cookies_cleared)
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_webview_data),
                    onClick = {
                        try {
                            WebView(context).run {
                                setDefaultSettings()
                                clearCache(true)
                                clearFormData()
                                clearHistory()
                                clearSslPreferences()
                            }
                            WebStorage.getInstance().deleteAllData()
                            context.applicationInfo?.dataDir?.let { File("$it/app_webview/").deleteRecursively() }
                            context.toast(MR.strings.webview_data_deleted)
                        } catch (e: Throwable) {
                            logcat(LogPriority.ERROR, e)
                            context.toast(MR.strings.cache_delete_error)
                        }
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = networkPreferences.dohProvider,
                    entries = mapOf(
                        -1 to stringResource(MR.strings.disabled),
                        PREF_DOH_CLOUDFLARE to "Cloudflare",
                        PREF_DOH_GOOGLE to "Google",
                        PREF_DOH_ADGUARD to "AdGuard",
                        PREF_DOH_QUAD9 to "Quad9",
                        PREF_DOH_ALIDNS to "AliDNS",
                        PREF_DOH_DNSPOD to "DNSPod",
                        PREF_DOH_360 to "360",
                        PREF_DOH_QUAD101 to "Quad 101",
                        PREF_DOH_MULLVAD to "Mullvad",
                        PREF_DOH_CONTROLD to "Control D",
                        PREF_DOH_NJALLA to "Njalla",
                        PREF_DOH_SHECAN to "Shecan",
                    ),
                    title = stringResource(MR.strings.pref_dns_over_https),
                    onValueChanged = {
                        context.toast(MR.strings.requires_app_restart)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = networkPreferences.dpiBypass,
                    title = stringResource(TDMR.strings.pref_bypass_dpi),
                    subtitle = stringResource(TDMR.strings.pref_bypass_dpi_summary),
                    onValueChanged = {
                        withIOContext { networkHelper.client.connectionPool.evictAll() }
                        true
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = userAgentPref,
                    title = stringResource(MR.strings.pref_user_agent_string),
                    onValueChanged = {
                        try {
                            // OkHttp checks for valid values internally
                            Headers.Builder().add("User-Agent", it)
                            context.toast(MR.strings.requires_app_restart)
                        } catch (_: IllegalArgumentException) {
                            context.toast(MR.strings.error_user_agent_string_invalid)
                            return@EditTextPreference false
                        }
                        true
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_reset_user_agent_string),
                    enabled = remember(userAgent) { userAgent != userAgentPref.defaultValue() },
                    onClick = {
                        userAgentPref.delete()
                        context.toast(MR.strings.requires_app_restart)
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(TDMR.strings.pref_clear_rate_limit_history),
                    subtitle = stringResource(TDMR.strings.pref_clear_rate_limit_history_summary),
                    onClick = {
                        networkHelper.rateLimitInterceptor.clearState()
                        context.toast(TDMR.strings.rate_limit_history_cleared)
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getLibraryGroup(
        libraryPreferences: LibraryPreferences,
    ): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_library),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_refresh_library_covers),
                    onClick = { MetadataUpdateJob.startNow(context) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_reset_viewer_flags),
                    subtitle = stringResource(MR.strings.pref_reset_viewer_flags_summary),
                    onClick = {
                        scope.launchNonCancellable {
                            val success = Injekt.get<ResetViewerFlags>().await()
                            withUIContext {
                                val message = if (success) {
                                    MR.strings.pref_reset_viewer_flags_success
                                } else {
                                    MR.strings.pref_reset_viewer_flags_error
                                }
                                context.toast(message)
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.updateMangaTitles,
                    title = stringResource(MR.strings.pref_update_library_manga_titles),
                    subtitle = stringResource(MR.strings.pref_update_library_manga_titles_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.disallowNonAsciiFilenames,
                    title = stringResource(MR.strings.pref_disallow_non_ascii_filenames),
                    subtitle = stringResource(MR.strings.pref_disallow_non_ascii_filenames_details),
                ),

            ),
        )
    }
}
