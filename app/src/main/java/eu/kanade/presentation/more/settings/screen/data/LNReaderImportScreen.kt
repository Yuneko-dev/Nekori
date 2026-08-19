package eu.kanade.presentation.more.settings.screen.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.InfoWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.restore.LNReaderBackupImporter
import eu.kanade.tachiyomi.data.backup.restore.LNReaderImportJob
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class LNReaderImportScreen(private val uriString: String) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        var state by remember { mutableStateOf<PreflightState>(PreflightState.Loading) }

        LaunchedEffect(uriString) {
            state = runCatching {
                PreflightState.Ready(LNReaderBackupImporter(context).preflight(uriString.toUri()))
            }.getOrElse { PreflightState.Error(it.message ?: it::class.java.simpleName) }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(TDMR.strings.lnreader_import_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when (val current = state) {
                PreflightState.Loading -> Column(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(TDMR.strings.lnreader_import_preflight_loading),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                is PreflightState.Error -> Column(
                    modifier = Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(TDMR.strings.lnreader_import_invalid),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(current.message, modifier = Modifier.padding(top = 8.dp))
                }
                is PreflightState.Ready -> ImportOptions(
                    summary = current.summary,
                    modifier = Modifier.padding(contentPadding),
                    onImport = { options ->
                        LNReaderImportJob.start(
                            context = context,
                            uri = uriString.toUri(),
                            restoreNovels = options.novels,
                            restoreChapters = options.novels && options.chapters,
                            restoreCategories = options.novels && options.categories,
                            restoreHistory = options.novels && options.history,
                            restorePlugins = options.plugins,
                            restoreMissingPlugins = options.novels && options.missingPlugins,
                            restoreLocalNovels = options.novels && options.localNovels,
                            restoreDownloadedChapters = options.novels && options.chapters && options.downloads,
                            restoreCovers = options.novels && options.covers,
                            restoreCompatibleSettings = options.compatibleSettings,
                            restoreAiApiKeys = options.compatibleSettings && options.aiApiKeys,
                        )
                        context.toast(TDMR.strings.lnreader_import_started)
                        navigator.pop()
                    },
                )
            }
        }
    }
}

@Composable
private fun ImportOptions(
    summary: LNReaderBackupImporter.PreflightSummary,
    modifier: Modifier = Modifier,
    onImport: (ImportSelection) -> Unit,
) {
    var selection by remember(summary) {
        mutableStateOf(
            ImportSelection(
                novels = summary.novelCount > 0,
                chapters = summary.novelCount > 0,
                categories = summary.novelCount > 0,
                history = summary.novelCount > 0,
                plugins = summary.hasPlugins,
                missingPlugins = false,
                localNovels = summary.localNovelCount > 0,
                downloads = summary.hasDownloadedFiles,
                covers = summary.hasDownloadedFiles,
                compatibleSettings = summary.hasSettings,
                aiApiKeys = false,
            ),
        )
    }
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Text(
                    text = stringResource(
                        TDMR.strings.lnreader_import_summary,
                        summary.appVersion,
                        summary.formatVersion,
                        summary.novelCount,
                        summary.chapterCount,
                        summary.categoryCount,
                        summary.pluginCount,
                        summary.downloadedChapterCount,
                    ),
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                HorizontalDivider()
            }
            importOption(
                TDMR.strings.lnreader_import_novels,
                selection.novels,
                summary.novelCount > 0,
            ) { selection = selection.copy(novels = it) }
            importOption(
                TDMR.strings.lnreader_import_local_novels,
                selection.localNovels,
                selection.novels && summary.localNovelCount > 0,
                note = TDMR.strings.lnreader_import_local_novels_note,
            ) { selection = selection.copy(localNovels = it) }
            importOption(
                TDMR.strings.lnreader_import_chapters,
                selection.chapters,
                selection.novels,
            ) { selection = selection.copy(chapters = it) }
            importOption(
                MR.strings.categories,
                selection.categories,
                selection.novels,
            ) { selection = selection.copy(categories = it) }
            importOption(
                TDMR.strings.lnreader_import_history,
                selection.history,
                selection.novels,
            ) { selection = selection.copy(history = it) }
            importOption(
                MR.strings.downloaded_chapters,
                selection.downloads,
                selection.novels && selection.chapters && summary.hasDownloadedFiles,
            ) { selection = selection.copy(downloads = it) }
            importOption(
                TDMR.strings.lnreader_import_covers,
                selection.covers,
                selection.novels && summary.hasDownloadedFiles,
            ) { selection = selection.copy(covers = it) }
            importOption(
                TDMR.strings.lnreader_import_plugins,
                selection.plugins,
                summary.hasPlugins,
            ) { selection = selection.copy(plugins = it) }
            importOption(
                TDMR.strings.lnreader_import_missing_plugins,
                selection.missingPlugins,
                selection.novels,
                note = TDMR.strings.lnreader_import_missing_plugins_warning,
            ) { selection = selection.copy(missingPlugins = it) }
            importOption(
                TDMR.strings.lnreader_import_compatible_settings,
                selection.compatibleSettings,
                summary.hasSettings,
            ) { selection = selection.copy(compatibleSettings = it) }
            if (summary.hasApiKeys) {
                importOption(
                    TDMR.strings.lnreader_import_ai_keys,
                    selection.aiApiKeys,
                    selection.compatibleSettings,
                ) { selection = selection.copy(aiApiKeys = it) }
            }
        }
        Button(
            onClick = { onImport(selection) },
            enabled = selection.hasWork,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(stringResource(TDMR.strings.lnreader_import_action))
        }
    }
}

/** One import toggle, plus [note] underneath it while the toggle is both available and ticked. */
private fun LazyListScope.importOption(
    title: StringResource,
    checked: Boolean,
    enabled: Boolean,
    note: StringResource? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    item {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            trailingContent = {
                Checkbox(checked = checked && enabled, enabled = enabled, onCheckedChange = onCheckedChange)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (note != null && checked && enabled) {
        item { InfoWidget(stringResource(note)) }
    }
}

private data class ImportSelection(
    val novels: Boolean,
    val chapters: Boolean,
    val categories: Boolean,
    val history: Boolean,
    val plugins: Boolean,
    val missingPlugins: Boolean,
    val localNovels: Boolean,
    val downloads: Boolean,
    val covers: Boolean,
    val compatibleSettings: Boolean,
    val aiApiKeys: Boolean,
) {
    val hasWork: Boolean get() = novels || plugins || compatibleSettings
}

private sealed interface PreflightState {
    data object Loading : PreflightState
    data class Ready(val summary: LNReaderBackupImporter.PreflightSummary) : PreflightState
    data class Error(val message: String) : PreflightState
}
