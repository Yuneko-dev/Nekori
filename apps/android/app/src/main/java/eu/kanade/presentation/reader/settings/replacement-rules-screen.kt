@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.shouldExpandFAB

@Composable
internal fun <T> ScopedContentLayout(
    entries: List<T>,
    entryKey: (T) -> String,
    addLabel: StringResource,
    emptyMessage: String,
    searching: Boolean,
    selecting: Boolean,
    onAdd: () -> Unit,
    onReorder: (List<T>) -> Unit,
    toolbar: @Composable () -> Unit,
    row: @Composable ReorderableCollectionItemScope.(T, Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val entriesState = remember { entries.toMutableStateList() }
    Scaffold(
        topBar = { toolbar() },
        floatingActionButton = {
            if (entries.isNotEmpty() && !selecting && !searching) {
                SmallExtendedFloatingActionButton(
                    text = { Text(stringResource(addLabel)) },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    onClick = onAdd,
                    expanded = listState.shouldExpandFAB(),
                )
            }
        },
    ) { padding ->
        val reorder = rememberReorderableLazyListState(listState, padding) { from, to ->
            if (!searching && !selecting) {
                val fromIndex = entriesState.indexOfFirst { entryKey(it) == from.key }
                val toIndex = entriesState.indexOfFirst { entryKey(it) == to.key }
                if (fromIndex >= 0 && toIndex >= 0) {
                    val entry = entriesState.removeAt(fromIndex)
                    entriesState.add(toIndex, entry)
                    onReorder(entriesState.toList())
                }
            }
        }
        LaunchedEffect(entries) {
            if (!reorder.isAnyItemDragging) {
                entriesState.clear()
                entriesState.addAll(entries)
            }
        }
        if (entriesState.isEmpty()) {
            EmptyScreen(
                message = emptyMessage,
                modifier = Modifier.padding(padding),
                actions = if (searching) {
                    null
                } else {
                    listOf(
                        EmptyScreenAction(addLabel, Icons.Outlined.Add, onAdd),
                    )
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entriesState, key = entryKey) { entry ->
                    ReorderableItem(reorder, key = entryKey(entry)) {
                        row(entry, Modifier.animateItem())
                    }
                }
            }
        }
    }
}
