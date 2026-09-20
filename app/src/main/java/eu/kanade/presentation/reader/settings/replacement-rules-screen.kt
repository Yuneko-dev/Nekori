@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.reader.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.util.shouldExpandFAB

@Composable
internal fun ReplacementRulesLayout(
    entries: List<RegexReplacement>,
    emptyMessage: String,
    searching: Boolean,
    selecting: Boolean,
    onAdd: () -> Unit,
    toolbar: @Composable () -> Unit,
    row: @Composable (RegexReplacement) -> Unit,
) {
    val listState = rememberLazyListState()
    Scaffold(
        topBar = { toolbar() },
        floatingActionButton = {
            if (entries.isNotEmpty() && !selecting && !searching) {
                SmallExtendedFloatingActionButton(
                    text = { Text(stringResource(TDMR.strings.novel_add_rule)) },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    onClick = onAdd,
                    expanded = listState.shouldExpandFAB(),
                )
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyScreen(
                message = emptyMessage,
                modifier = Modifier.padding(padding),
                actions = if (searching) {
                    null
                } else {
                    listOf(
                        EmptyScreenAction(TDMR.strings.novel_add_rule, Icons.Outlined.Add, onAdd),
                    )
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                state = listState,
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(entries, key = { it.id }) { row(it) }
            }
        }
    }
}
