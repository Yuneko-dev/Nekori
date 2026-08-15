package eu.kanade.presentation.more.settings.screen.advanced

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.DomainForwarding
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun DomainForwardingDialog(
    manager: DomainForwarding,
    initialSource: String? = null,
    lockSource: Boolean = false,
    onDismissRequest: () -> Unit,
) {
    val mappings by manager.mappings.collectAsState()
    val normalizedInitial = remember(initialSource) { initialSource?.let { DomainForwarding.normalizeOrigin(it) } }
    val initialMapping = mappings.firstOrNull { it.source == normalizedInitial }
    var source by remember(normalizedInitial) { mutableStateOf(normalizedInitial.orEmpty()) }
    var target by remember(normalizedInitial) { mutableStateOf(initialMapping?.target.orEmpty()) }
    var global by remember(normalizedInitial) { mutableStateOf(initialMapping?.global == true) }
    val normalizedSource = DomainForwarding.normalizeOrigin(source)
    val valid = normalizedSource != null && DomainForwarding.normalizeOrigin(target) != null

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(TDMR.strings.domain_forwarding_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { global = !global },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(scopeLabel(global))
                    Switch(checked = global, onCheckedChange = { global = it })
                }

                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(TDMR.strings.domain_forwarding_source)) },
                    enabled = !lockSource || normalizedInitial == null,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(TDMR.strings.domain_forwarding_target)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (normalizedInitial != null && normalizedInitial != normalizedSource) {
                        manager.remove(normalizedInitial)
                    }
                    manager.put(source, target, global)
                    onDismissRequest()
                },
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initialMapping != null) {
                    TextButton(
                        onClick = {
                            manager.remove(initialMapping.source)
                            onDismissRequest()
                        },
                    ) {
                        Text(stringResource(MR.strings.action_delete))
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            }
        },
    )
}

object DomainForwardingScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { Injekt.get<NetworkHelper>().domainForwarding }
        val mappings by manager.mappings.collectAsState()
        var editorSource by remember { mutableStateOf<String?>(null) }
        var showEditor by remember { mutableStateOf(false) }

        fun openEditor(source: String? = null) {
            editorSource = source
            showEditor = true
        }

        if (showEditor) {
            DomainForwardingDialog(
                manager = manager,
                initialSource = editorSource,
                onDismissRequest = { showEditor = false },
            )
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(TDMR.strings.domain_forwarding_title),
                    navigateUp = { navigator.pop() },
                )
            },
        ) { padding ->
            if (mappings.isEmpty()) {
                EmptyScreen(
                    stringRes = TDMR.strings.domain_forwarding_empty,
                    modifier = Modifier.padding(padding),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.action_add,
                            icon = Icons.Outlined.Add,
                            onClick = { openEditor() },
                        ),
                    ),
                )
                return@Scaffold
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(mappings, key = { it.source }) { mapping ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openEditor(mapping.source) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(mapping.source, style = MaterialTheme.typography.bodyLarge)
                            Text(mapping.target)
                            Text(scopeLabel(mapping.global))
                        }
                        IconButton(onClick = { manager.remove(mapping.source) }) {
                            Icon(Icons.Outlined.Delete, stringResource(MR.strings.action_delete))
                        }
                    }
                }
                item {
                    Button(
                        onClick = { openEditor() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(stringResource(MR.strings.action_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun scopeLabel(global: Boolean) = stringResource(
    if (global) TDMR.strings.domain_forwarding_scope_global else TDMR.strings.domain_forwarding_scope_js,
)
