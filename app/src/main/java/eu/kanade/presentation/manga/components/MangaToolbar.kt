package eu.kanade.presentation.manga.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DownloadDropdownMenu
import eu.kanade.presentation.manga.DownloadAction
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MangaToolbar(
    title: String,
    navigateUp: () -> Unit,
    onClickShare: (() -> Unit)?,
    onClickDownload: ((DownloadAction) -> Unit)?,
    onClickEditCategory: (() -> Unit)?,
    onClickRemove: (() -> Unit)?,
    onClickRefresh: () -> Unit,
    onClickMigrate: (() -> Unit)?,
    onClickSimilarNovels: (() -> Unit)?,
    onClickFindDuplicates: (() -> Unit)?,
    onClickEditNotes: () -> Unit,
    onClickEdit: (() -> Unit)?,
    onClickClearCustomInfo: (() -> Unit)? = null,
    onClickTranslate: (() -> Unit)? = null,
    onClickTranslateDownloaded: (() -> Unit)? = null,
    onClickExportEpub: (() -> Unit)? = null,
    onClickScrollToTop: (() -> Unit)? = null,
    onClickScrollToBottom: (() -> Unit)? = null,

    // For action mode
    actionModeCounter: Int,
    onCancelActionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,

    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val isActionMode = actionModeCounter > 0
    AppBar(
        titleContent = {
            if (isActionMode) {
                AppBarTitle(actionModeCounter.toString())
            } else {
                AppBarTitle(title, modifier = Modifier.alpha(titleAlphaProvider()))
            }
        },
        modifier = modifier,
        backgroundColor = MaterialTheme.colorScheme
            .surfaceColorAtElevation(3.dp)
            .copy(alpha = if (isActionMode) 1f else backgroundAlphaProvider()),
        navigateUp = navigateUp,
        actions = {
            var downloadExpanded by remember { mutableStateOf(false) }
            if (onClickDownload != null) {
                val onDismissRequest = { downloadExpanded = false }
                DownloadDropdownMenu(
                    expanded = downloadExpanded,
                    onDismissRequest = onDismissRequest,
                    onDownloadClicked = onClickDownload,
                )
            }

            AppBarActions(
                actions = buildList {
                    if (isActionMode) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_all),
                                icon = Icons.Outlined.SelectAll,
                                onClick = onSelectAll,
                            ),
                        )
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_select_inverse),
                                icon = Icons.Outlined.FlipToBack,
                                onClick = onInvertSelection,
                            ),
                        )
                        return@buildList
                    }
                    if (onClickDownload != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.manga_download),
                                icon = Icons.Outlined.Download,
                                onClick = { downloadExpanded = !downloadExpanded },
                            ),
                        )
                    }
                    if (onClickScrollToTop != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(TDMR.strings.action_scroll_to_top),
                                icon = Icons.Outlined.ArrowUpward,
                                onClick = onClickScrollToTop,
                            ),
                        )
                    }
                    if (onClickScrollToBottom != null) {
                        add(
                            AppBar.Action(
                                title = stringResource(TDMR.strings.action_scroll_to_bottom),
                                icon = Icons.Outlined.ArrowDownward,
                                onClick = onClickScrollToBottom,
                            ),
                        )
                    }
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_webview_refresh),
                            onClick = onClickRefresh,
                        ),
                    )
                    if (onClickEditCategory != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_edit_categories),
                                onClick = onClickEditCategory,
                            ),
                        )
                    }
                    if (onClickRemove != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_remove),
                                onClick = onClickRemove,
                            ),
                        )
                    }
                    if (onClickMigrate != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_migrate),
                                onClick = onClickMigrate,
                            ),
                        )
                    }
                    if (onClickSimilarNovels != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(TDMR.strings.manga_similar_novels),
                                onClick = onClickSimilarNovels,
                            ),
                        )
                    }
                    if (onClickEdit != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_edit),
                                onClick = onClickEdit,
                            ),
                        )
                    }
                    if (onClickClearCustomInfo != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(TDMR.strings.action_clear_custom_metadata),
                                onClick = onClickClearCustomInfo,
                            ),
                        )
                    }
                    if (onClickFindDuplicates != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(TDMR.strings.duplicate_find_duplicates),
                                onClick = onClickFindDuplicates,
                            ),
                        )
                    }
                    if (onClickShare != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_share),
                                onClick = onClickShare,
                            ),
                        )
                    }
                    if (onClickTranslate != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(TDMR.strings.action_translate),
                                onClick = onClickTranslate,
                            ),
                        )
                    }
                    if (onClickTranslateDownloaded != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(TDMR.strings.action_translate_downloaded),
                                onClick = onClickTranslateDownloaded,
                            ),
                        )
                    }
                    if (onClickExportEpub != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(TDMR.strings.action_export_epub),
                                onClick = onClickExportEpub,
                            ),
                        )
                    }
                    add(
                        AppBar.OverflowAction(
                            title = stringResource(MR.strings.action_notes),
                            onClick = onClickEditNotes,
                        ),
                    )
                },
            )
        },
        isActionMode = isActionMode,
        onCancelActionMode = onCancelActionMode,
    )
}
