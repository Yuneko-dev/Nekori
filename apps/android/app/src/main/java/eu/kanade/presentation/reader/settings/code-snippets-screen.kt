@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.reader.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.reader.setting.CodeSnippetsDraft
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import eu.kanade.tachiyomi.ui.reader.setting.reorderScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeSnippetsScreen(
    preference: Preference<String>,
    isJavaScript: Boolean,
    target: ReplacementTarget? = null,
    novelTitle: String = "",
    draft: CodeSnippetsDraft? = null,
    onDraft: (CodeSnippetsDraft) -> Unit = {},
    onBack: () -> Unit,
) {
    val original = remember { draft?.original ?: preference.get() }
    val decoded by produceState<Result<List<CodeSnippet>>?>(null, original) {
        value = draft?.let { Result.success(it.initial) }
            ?: withContext(Dispatchers.Default) { runCatching { CodeSnippet.decode(original) } }
    }
    val result = decoded
    if (result == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (result.isFailure) {
        AlertDialog(
            onDismissRequest = onBack,
            text = { Text(stringResource(TDMR.strings.replacement_load_error)) },
            confirmButton = { TextButton(onClick = onBack) { Text(stringResource(MR.strings.action_close)) } },
        )
        return
    }
    val session = remember { draft ?: CodeSnippetsDraft(original, result.getOrThrow()).also(onDraft) }
    var snippets by session.snippets
    var scope by rememberSaveable {
        mutableStateOf(
            if (target ==
                null
            ) {
                RegexReplacement.GLOBAL
            } else {
                RegexReplacement.NOVEL
            },
        )
    }
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CodeSnippet?>(null) }
    var deleting by remember { mutableStateOf(emptySet<String>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var conflict by remember { mutableStateOf(false) }
    val scoped = snippets.filter { it.scope == scope && it.appliesTo(target) }
    val visible = scoped.filter {
        query.isNullOrBlank() || it.title.contains(query.orEmpty(), true) ||
            it.code.contains(query.orEmpty(), true)
    }
    val title =
        stringResource(if (isJavaScript) TDMR.strings.pref_novel_js_snippets else TDMR.strings.pref_novel_css_snippets)
    fun save() {
        if (session.save(preference)) onBack() else conflict = true
    }
    fun add() {
        editing = CodeSnippet(
            "",
            "",
            id = java.util.UUID.randomUUID().toString(),
            scope = scope,
            target = target.takeIf { scope == RegexReplacement.NOVEL },
        )
    }
    fun toggleSelection(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }
    fun setEnabled(ids: Set<String>, enabled: Boolean) {
        snippets = snippets.map { if (it.id in ids) it.copy(enabled = enabled) else it }
        selected = emptySet()
    }
    BackHandler {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            query != null -> query = null
            else -> save()
        }
    }
    ScopedContentLayout(
        entries = visible,
        entryKey = { it.id },
        addLabel = TDMR.strings.novel_add_snippet,
        emptyMessage = stringResource(
            if (query.isNullOrBlank()) {
                TDMR.strings.novel_no_snippets
            } else {
                MR.strings.no_results_found
            },
        ),
        searching = query != null,
        selecting = selected.isNotEmpty(),
        onAdd = ::add,
        onReorder = { snippets = reorderScope(snippets, scoped, it) },
        toolbar = {
            ScopedContentToolbar(
                title = title,
                scope = scope,
                hasNovel = target != null,
                novelTitle = novelTitle,
                query = query,
                selectedCount = selected.size,
                hasEntries = scoped.isNotEmpty(),
                onBack = ::save,
                onClearSelection = { selected = emptySet() },
                onScope = {
                    scope = it
                    query = null
                    selected = emptySet()
                },
                onQuery = {
                    query = it
                    selected = emptySet()
                },
                onSelectAll = {
                    selected = if (selected.size == visible.size) emptySet() else visible.map { it.id }.toSet()
                },
                onEnable = { setEnabled(selected, true) },
                onDisable = { setEnabled(selected, false) },
                onDelete = { deleting = selected },
            )
        },
    ) { item, modifier ->
        ScopedContentRow(
            modifier = modifier,
            title = item.title,
            subtitle = item.code,
            enabled = item.enabled,
            dragEnabled = query == null && selected.isEmpty(),
            selected = item.id in selected,
            selecting = selected.isNotEmpty(),
            onClick = { if (selected.isNotEmpty()) toggleSelection(item.id) else editing = item },
            onSelect = { toggleSelection(item.id) },
            onEnable = { setEnabled(setOf(item.id), it) },
            onDelete = { deleting = setOf(item.id) },
            canMoveUp = query == null && scoped.firstOrNull()?.id != item.id,
            canMoveDown = query == null && scoped.lastOrNull()?.id != item.id,
            onMove = { offset ->
                val reordered = scoped.toMutableList().apply {
                    val index = indexOfFirst { it.id == item.id }
                    add(index + offset, removeAt(index))
                }
                snippets = reorderScope(snippets, scoped, reordered)
            },
        )
    }
    editing?.let { item ->
        SnippetEditDialog(
            title = stringResource(
                if (item.code.isEmpty()) {
                    if (isJavaScript) TDMR.strings.novel_add_js_snippet else TDMR.strings.novel_add_css_snippet
                } else {
                    if (isJavaScript) TDMR.strings.novel_edit_js_snippet else TDMR.strings.novel_edit_css_snippet
                },
            ),
            initialSnippet = item,
            focusCodeFieldByDefault = item.code.isNotBlank(),
            showRunOnAppend = isJavaScript,
            existingSafeTitles = snippets.filterNot { it.id == item.id }.mapTo(mutableSetOf()) { it.safeTitle() },
            target = target,
            onDismiss = { editing = null },
            onConfirm = {
                session.update(it)
                editing = null
            },
        )
    }
    if (deleting.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleting = emptySet() },
            text = { Text(stringResource(TDMR.strings.content_delete_selected, deleting.size)) },
            confirmButton = {
                TextButton(onClick = {
                    snippets = snippets.filterNot { it.id in deleting }
                    selected = emptySet()
                    deleting = emptySet()
                }) { Text(stringResource(MR.strings.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = emptySet() }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
    if (conflict) {
        AlertDialog(
            onDismissRequest = { conflict = false },
            text = { Text(stringResource(TDMR.strings.replacement_save_conflict)) },
            confirmButton = { TextButton(onClick = onBack) { Text(stringResource(MR.strings.action_close)) } },
            dismissButton = {
                TextButton(onClick = { conflict = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}
