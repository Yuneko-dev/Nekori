@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.reader.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
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
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementRulesDraft
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun ReplacementRulesScreen(
    screenModel: ReaderSettingsViewModel,
    target: ReplacementTarget?,
    novelTitle: String,
    onBack: () -> Unit,
) {
    val preferences = screenModel.preferences
    val original = remember { screenModel.replacementRulesDraft?.original ?: preferences.novelRegexReplacements.get() }
    val decoded by produceState<Result<List<RegexReplacement>>?>(null, original) {
        value = screenModel.replacementRulesDraft?.let { Result.success(it.initial) }
            ?: withContext(Dispatchers.Default) { runCatching { RegexReplacement.decode(original) } }
    }
    BackHandler(onBack = onBack)
    val result = decoded
    when {
        result == null -> {
            Surface { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        }
        result.isFailure -> AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(TDMR.strings.novel_regex_find_replace)) },
            text = { Text(stringResource(TDMR.strings.replacement_load_error)) },
            confirmButton = { TextButton(onClick = onBack) { Text(stringResource(MR.strings.action_close)) } },
        )
        else -> {
            val draft = remember {
                screenModel.replacementRulesDraft ?: ReplacementRulesDraft(original, result.getOrThrow()).also {
                    screenModel.replacementRulesDraft = it
                }
            }
            ReplacementRulesContent(preferences, draft, target, novelTitle) {
                screenModel.replacementRulesDraft = null
                onBack()
            }
        }
    }
}

@Composable
private fun ReplacementRulesContent(
    preferences: ReaderPreferences,
    draft: ReplacementRulesDraft,
    target: ReplacementTarget?,
    novelTitle: String,
    onBack: () -> Unit,
) {
    val original = draft.original
    val initial = draft.initial
    var rules by draft.rules
    var scope by rememberSaveable {
        mutableStateOf(
            if (target == null) {
                RegexReplacement.GLOBAL
            } else {
                RegexReplacement.NOVEL
            },
        )
    }
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var editingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(emptySet<String>()) }
    var conflict by remember { mutableStateOf(false) }
    val scoped = remember(rules, scope, target) {
        rules.filter { it.scope == scope && it.appliesTo(target) }
    }
    val visible = remember(scoped, query) {
        scoped.filter { rule ->
            query.isNullOrBlank() ||
                listOf(rule.title, rule.pattern, rule.replacement).any {
                    it.contains(query.orEmpty(), ignoreCase = true)
                }
        }
    }
    fun close() {
        // Commit once on return, so multi-edit sessions rebuild the chapter only once.
        if (rules != initial) {
            if (preferences.novelRegexReplacements.get() != original) {
                conflict = true
                return
            }
            preferences.novelRegexReplacements.set(Json.encodeToString(rules))
        }
        onBack()
    }
    fun toggleSelection(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }
    fun setEnabled(ids: Set<String>, enabled: Boolean) {
        rules = rules.map { if (it.id in ids) it.copy(enabled = enabled) else it }
        selected = emptySet()
    }
    BackHandler {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            query != null -> query = null
            else -> close()
        }
    }
    Surface(Modifier.fillMaxSize()) {
        ReplacementRulesLayout(
            entries = visible,
            searching = query != null,
            selecting = selected.isNotEmpty(),
            onAdd = {
                editingJson = Json.encodeToString(
                    RegexReplacement(
                        "",
                        "",
                        "",
                        isRegex = false,
                        scope = scope,
                        target = target.takeIf {
                            scope == RegexReplacement.NOVEL
                        },
                    ),
                )
            },
            emptyMessage = stringResource(
                when {
                    !query.isNullOrBlank() -> MR.strings.no_results_found
                    scope == RegexReplacement.NOVEL -> TDMR.strings.replacement_empty_novel
                    else -> TDMR.strings.replacement_empty_global
                },
            ),
            toolbar = {
                ReplacementRulesToolbar(
                    scope = scope,
                    hasNovel = target != null,
                    novelTitle = novelTitle,
                    query = query,
                    selectedCount = selected.size,
                    hasRules = scoped.isNotEmpty(),
                    onBack = ::close,
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
                        selected =
                            if (selected.size == visible.size) emptySet() else visible.map { it.id }.toSet()
                    },
                    onEnable = { setEnabled(selected, true) },
                    onDisable = { setEnabled(selected, false) },
                    onDelete = { deleting = selected },
                )
            },
        ) { rule ->
            ReplacementRuleRow(
                rule = rule,
                selected = rule.id in selected,
                selecting = selected.isNotEmpty(),
                onClick = {
                    if (selected.isNotEmpty()) {
                        toggleSelection(rule.id)
                    } else {
                        editingJson = Json.encodeToString(rule)
                    }
                },
                onSelect = { toggleSelection(rule.id) },
                onEnable = { setEnabled(setOf(rule.id), it) },
                onDelete = { deleting = setOf(rule.id) },
                canMoveUp = query.isNullOrBlank() && scoped.firstOrNull()?.id != rule.id,
                canMoveDown = query.isNullOrBlank() && scoped.lastOrNull()?.id != rule.id,
                onMove = { offset ->
                    val neighbor = scoped[scoped.indexOf(rule) + offset]
                    rules = rules.toMutableList().apply {
                        val from = indexOfFirst { it.id == rule.id }
                        val to = indexOfFirst { it.id == neighbor.id }
                        this[from] = neighbor
                        this[to] = rule
                    }
                },
            )
        }
    }
    editingJson?.let { json ->
        val editing = remember(json) { Json.decodeFromString<RegexReplacement>(json) }
        ReplacementRuleEditor(editing, target, onDismiss = { editingJson = null }) { updated ->
            rules = if (rules.any { it.id == updated.id }) {
                rules.map { if (it.id == updated.id) updated else it }
            } else {
                rules + updated
            }
            editingJson = null
        }
    }
    if (deleting.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleting = emptySet() },
            title = { Text(stringResource(MR.strings.action_delete)) },
            text = { Text(stringResource(TDMR.strings.replacement_delete_selected, deleting.size)) },
            confirmButton = {
                TextButton(onClick = {
                    rules = rules.filterNot { it.id in deleting }
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
