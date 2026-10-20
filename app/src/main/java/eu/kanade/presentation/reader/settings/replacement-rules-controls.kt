@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.more.settings.screen.ManagerRow
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import sh.calvin.reorderable.ReorderableCollectionItemScope
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ScopedContentToolbar(
    scope: String,
    hasNovel: Boolean,
    title: String,
    novelTitle: String,
    query: String?,
    selectedCount: Int,
    hasEntries: Boolean,
    onBack: () -> Unit,
    onClearSelection: () -> Unit,
    onScope: (String) -> Unit,
    onQuery: (String?) -> Unit,
    onSelectAll: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        if (selectedCount > 0) {
            AppBar(
                title = null,
                actionModeCounter = selectedCount,
                onCancelActionMode = onClearSelection,
                actionModeActions = {
                    IconButton(onClick = onSelectAll) {
                        Icon(Icons.Outlined.SelectAll, stringResource(MR.strings.action_select_all))
                    }
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Outlined.MoreVert, stringResource(MR.strings.pref_reader_actions))
                        }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.on)) },
                                onClick = {
                                    expanded = false
                                    onEnable()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_disable)) },
                                onClick = {
                                    expanded = false
                                    onDisable()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_delete)) },
                                onClick = {
                                    expanded = false
                                    onDelete()
                                },
                            )
                        }
                    }
                },
            )
        } else {
            SearchToolbar(
                searchQuery = query,
                onChangeSearchQuery = onQuery,
                navigateUp = onBack,
                searchEnabled = hasEntries,
                actions = {
                    if (!hasEntries) {
                        IconButton(onClick = {}, enabled = false) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(MR.strings.action_search))
                        }
                    }
                },
                titleContent = {
                    AppBarTitle(
                        title = title,
                        subtitle = novelTitle.takeIf { scope == RegexReplacement.NOVEL },
                    )
                },
            )
        }
        PrimaryTabRow(selectedTabIndex = if (scope == RegexReplacement.NOVEL) 0 else 1) {
            Tab(
                selected = scope == RegexReplacement.NOVEL,
                onClick = { onScope(RegexReplacement.NOVEL) },
                enabled = hasNovel,
                text = { Text(stringResource(TDMR.strings.replacement_scope_novel)) },
            )
            Tab(
                selected = scope == RegexReplacement.GLOBAL,
                onClick = { onScope(RegexReplacement.GLOBAL) },
                text = { Text(stringResource(TDMR.strings.replacement_scope_global)) },
            )
        }
    }
}

@Composable
internal fun ReorderableCollectionItemScope.ScopedContentRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    dragEnabled: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onEnable: (Boolean) -> Unit,
    onDelete: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(modifier = modifier) {
        ManagerRow(
            title = title,
            subtitle = subtitle,
            icon = if (selecting) {
                { Checkbox(selected, onCheckedChange = { onSelect() }) }
            } else if (dragEnabled) {
                {
                    Icon(
                        Icons.Outlined.DragHandle,
                        contentDescription = stringResource(TDMR.strings.label_drag_to_reorder),
                        modifier = Modifier.draggableHandle(),
                    )
                }
            } else {
                null
            },
            onClick = onClick,
            onLongClick = onSelect,
            trailing = {
                if (!selecting) {
                    Row {
                        Switch(enabled, onCheckedChange = onEnable)
                        IconButton(onClick = {
                            expanded = true
                        }) { Icon(Icons.Outlined.MoreVert, stringResource(MR.strings.pref_reader_actions)) }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(TDMR.strings.novel_snippet_move_up)) },
                                enabled = canMoveUp,
                                onClick = {
                                    expanded = false
                                    onMove(-1)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(TDMR.strings.novel_snippet_move_down)) },
                                enabled = canMoveDown,
                                onClick = {
                                    expanded = false
                                    onMove(1)
                                },
                            )
                            DropdownMenuItem(text = { Text(stringResource(MR.strings.action_delete)) }, onClick = {
                                expanded = false
                                onDelete()
                            })
                        }
                    }
                }
            },
        )
    }
}
