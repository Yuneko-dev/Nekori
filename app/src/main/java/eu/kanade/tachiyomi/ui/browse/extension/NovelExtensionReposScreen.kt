package eu.kanade.tachiyomi.ui.browse.extension

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.jsplugin.model.JsPluginRepository
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

class NovelExtensionReposScreen(
    private val url: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = viewModel<NovelExtensionReposViewModel>()
        val state by screenModel.state.collectAsState()

        LaunchedEffect(url) {
            url?.let(screenModel::addFromDeeplink)
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.extensionStores),
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = screenModel::refreshRepos) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(MR.strings.action_webview_refresh),
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { screenModel.showDialog(NovelRepoDialog.Create()) }) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(MR.strings.action_add),
                    )
                }
            },
        ) { contentPadding ->
            when (val current = state) {
                NovelRepoScreenState.Loading -> LoadingScreen()
                is NovelRepoScreenState.Success -> {
                    if (current.repositories.isEmpty()) {
                        EmptyScreen(
                            stringRes = MR.strings.extensionStoresScreen_emptyLabel,
                            modifier = Modifier.padding(contentPadding),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = contentPadding,
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        ) {
                            items(current.repositories, key = { it.url }) { repo ->
                                RepositoryItem(
                                    repo = repo,
                                    onSetEnabled = { screenModel.setRepoEnabled(repo.url, it) },
                                    onDelete = { screenModel.showDialog(NovelRepoDialog.Delete(repo)) },
                                )
                            }
                        }
                    }

                    when (val dialog = current.dialog) {
                        null -> Unit
                        is NovelRepoDialog.Create -> RepositoryCreateDialog(
                            existingRepositoryUrls = current.repositories.map { it.url }.toSet(),
                            processing = dialog.processing,
                            errorMessage = dialog.errorMessage,
                            onDismissRequest = screenModel::dismissDialog,
                            onCreate = screenModel::createRepo,
                        )
                        is NovelRepoDialog.Confirm -> RepositoryConfirmDialog(
                            dialog = dialog,
                            onDismissRequest = screenModel::dismissDialog,
                            onConfirm = { screenModel.confirmDeeplink(dialog.url) },
                        )
                        is NovelRepoDialog.Delete -> RepositoryDeleteDialog(
                            repo = dialog.repo,
                            onDismissRequest = screenModel::dismissDialog,
                            onDelete = { screenModel.deleteRepo(dialog.repo.url) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RepositoryItem(
    repo: JsPluginRepository,
    onSetEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    ElevatedCard(
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.AutoMirrored.Outlined.Label, contentDescription = null)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MaterialTheme.padding.medium),
            ) {
                Text(text = repo.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = repo.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = repo.enabled, onCheckedChange = onSetEnabled)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo.url)))
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(MR.strings.action_open_in_browser),
                )
            }
            IconButton(onClick = { context.copyToClipboard(repo.url, repo.url) }) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                )
            }
        }
    }
}

@Composable
private fun RepositoryCreateDialog(
    existingRepositoryUrls: Set<String>,
    processing: Boolean,
    errorMessage: String?,
    onDismissRequest: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val normalizedUrl = url.trim().trimEnd('/')
    val alreadyExists = normalizedUrl.isNotBlank() && existingRepositoryUrls.any {
        it.trim().trimEnd('/') == normalizedUrl
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.extensionStoresScreen_addStore_title)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(text = stringResource(TDMR.strings.js_plugin_repo_url)) },
                supportingText = {
                    Text(
                        text = when {
                            errorMessage != null -> errorMessage
                            alreadyExists -> stringResource(MR.strings.extensionStoresScreen_addStore_alreadyExists)
                            else -> stringResource(MR.strings.information_required_plain)
                        },
                    )
                },
                isError = errorMessage != null || alreadyExists,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(url) },
                enabled = !processing && normalizedUrl.isNotBlank() && !alreadyExists,
            ) {
                Text(
                    text = stringResource(
                        if (processing) {
                            MR.strings.extensionStoresScreen_addStore_processing
                        } else {
                            MR.strings.action_add
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun RepositoryConfirmDialog(
    dialog: NovelRepoDialog.Confirm,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.extensionStoresScreen_addStore_title)) },
        text = {
            Column {
                Text(text = stringResource(MR.strings.extensionStoresScreen_addStoreDeeplink_bodyText))
                OutlinedTextField(
                    value = dialog.url,
                    onValueChange = {},
                    readOnly = true,
                    supportingText = when {
                        dialog.errorMessage != null -> ({ Text(dialog.errorMessage) })
                        dialog.alreadyExists -> (
                            {
                                Text(stringResource(MR.strings.extensionStoresScreen_addStore_alreadyExists))
                            }
                            )
                        else -> null
                    },
                    isError = dialog.errorMessage != null || dialog.alreadyExists,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !dialog.processing && !dialog.alreadyExists,
            ) {
                Text(
                    text = stringResource(
                        if (dialog.processing) {
                            MR.strings.extensionStoresScreen_addStore_processing
                        } else {
                            MR.strings.action_add
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun RepositoryDeleteDialog(
    repo: JsPluginRepository,
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.extensionStoresScreen_deleteStore_title)) },
        text = {
            Text(
                text = stringResource(
                    MR.strings.extensionStoresScreen_deleteStore_body,
                    repo.name,
                    repo.url,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(text = stringResource(MR.strings.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
