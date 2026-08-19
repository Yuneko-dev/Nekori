package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

class LNReaderImportJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = BackupNotifier(context)

    override suspend fun doWork(): Result {
        val uri = inputData.getString(LOCATION_URI_KEY)?.toUri() ?: return Result.failure()

        setForegroundSafely()

        return try {
            logcat(LogPriority.INFO) { "LNReaderImport: Worker started" }
            val importer = LNReaderBackupImporter(context, notifier)
            val options = LNReaderBackupImporter.ImportOptions(
                restoreNovels = inputData.getBoolean(KEY_RESTORE_NOVELS, true),
                restoreChapters = inputData.getBoolean(KEY_RESTORE_CHAPTERS, true),
                restoreCategories = inputData.getBoolean(KEY_RESTORE_CATEGORIES, true),
                restoreHistory = inputData.getBoolean(KEY_RESTORE_HISTORY, true),
                restorePlugins = inputData.getBoolean(KEY_RESTORE_PLUGINS, true),
                restoreMissingPlugins = inputData.getBoolean(KEY_RESTORE_MISSING_PLUGINS, false),
                restoreLocalNovels = inputData.getBoolean(KEY_RESTORE_LOCAL_NOVELS, true),
                restoreDownloadedChapters = inputData.getBoolean(KEY_RESTORE_DOWNLOADED_CHAPTERS, true),
                restoreCovers = inputData.getBoolean(KEY_RESTORE_COVERS, true),
                restoreCompatibleSettings = inputData.getBoolean(KEY_RESTORE_COMPATIBLE_SETTINGS, true),
                restoreAiApiKeys = inputData.getBoolean(KEY_RESTORE_AI_API_KEYS, false),
            )
            val startTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { importer.import(uri, options) }

            val missingSuffix = if (result.missingPlugins.isNotEmpty()) {
                " (Missing: ${result.missingPlugins.size})"
            } else {
                ""
            }
            val placeholderSuffix = if (result.placeholderPlugins.isNotEmpty()) {
                " - novels from ${result.placeholderPlugins.joinToString()} use a placeholder source and " +
                    "must be migrated manually"
            } else {
                ""
            }
            val summaryMessage = "Completed - ${result.novelCount} novels, ${result.categoryCount} categories, " +
                "${result.installedPluginCount} plugins, ${result.restoredDownloadCount} chapters, " +
                "${result.restoredCoverCount} covers, " +
                "${result.skippedCount} skipped, ${result.errorCount} errors" +
                missingSuffix + placeholderSuffix

            notifier.showRestoreComplete(
                time = System.currentTimeMillis() - startTime,
                errorCount = result.errorCount,
                path = result.logFile.parent,
                file = result.logFile.name,
                sync = false,
                customMessage = summaryMessage,
            )

            logcat(LogPriority.INFO) {
                "LNReaderImport: $summaryMessage"
            }

            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) {
                notifier.showRestoreError(context.stringResource(MR.strings.restoring_backup_canceled))
                Result.success()
            } else {
                logcat(LogPriority.ERROR, e)
                notifier.showRestoreError("LNReader import failed: ${e.message}")
                Result.failure()
            }
        } finally {
            logcat(LogPriority.INFO) { "LNReaderImport: Worker finished" }
            context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_RESTORE_PROGRESS,
            notifier.showRestoreProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG)
        }

        fun start(
            context: Context,
            uri: Uri,
            restoreNovels: Boolean = true,
            restoreChapters: Boolean = true,
            restoreCategories: Boolean = true,
            restoreHistory: Boolean = true,
            restorePlugins: Boolean = true,
            restoreMissingPlugins: Boolean = false,
            restoreLocalNovels: Boolean = true,
            restoreDownloadedChapters: Boolean = true,
            restoreCovers: Boolean = true,
            restoreCompatibleSettings: Boolean = true,
            restoreAiApiKeys: Boolean = false,
        ) {
            val inputData = workDataOf(
                LOCATION_URI_KEY to uri.toString(),
                KEY_RESTORE_NOVELS to restoreNovels,
                KEY_RESTORE_CHAPTERS to restoreChapters,
                KEY_RESTORE_CATEGORIES to restoreCategories,
                KEY_RESTORE_HISTORY to restoreHistory,
                KEY_RESTORE_PLUGINS to restorePlugins,
                KEY_RESTORE_MISSING_PLUGINS to restoreMissingPlugins,
                KEY_RESTORE_LOCAL_NOVELS to restoreLocalNovels,
                KEY_RESTORE_DOWNLOADED_CHAPTERS to restoreDownloadedChapters,
                KEY_RESTORE_COVERS to restoreCovers,
                KEY_RESTORE_COMPATIBLE_SETTINGS to restoreCompatibleSettings,
                KEY_RESTORE_AI_API_KEYS to restoreAiApiKeys,
            )
            val request = OneTimeWorkRequestBuilder<LNReaderImportJob>()
                .addTag(TAG)
                .setInputData(inputData)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}

private const val TAG = "LNReaderImport"
private const val LOCATION_URI_KEY = "location_uri"
private const val KEY_RESTORE_NOVELS = "restore_novels"
private const val KEY_RESTORE_CHAPTERS = "restore_chapters"
private const val KEY_RESTORE_CATEGORIES = "restore_categories"
private const val KEY_RESTORE_HISTORY = "restore_history"
private const val KEY_RESTORE_PLUGINS = "restore_plugins"
private const val KEY_RESTORE_MISSING_PLUGINS = "restore_missing_plugins"
private const val KEY_RESTORE_LOCAL_NOVELS = "restore_local_novels"
private const val KEY_RESTORE_DOWNLOADED_CHAPTERS = "restore_downloaded_chapters"
private const val KEY_RESTORE_COVERS = "restore_covers"
private const val KEY_RESTORE_COMPATIBLE_SETTINGS = "restore_compatible_settings"
private const val KEY_RESTORE_AI_API_KEYS = "restore_ai_api_keys"
