package eu.kanade.tachiyomi.ui.browse.extension

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.ExtensionScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun novelExtensionsTab(
    extensionsViewModel: NovelExtensionsViewModel,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()

    val state by extensionsViewModel.state.collectAsState()

    return TabContent(
        titleRes = MR.strings.label_extensions,
        badgeNumber = state.updates.takeIf { it > 0 },
        searchEnabled = true,
        actions = listOf(
            AppBar.OverflowAction(
                title = stringResource(MR.strings.action_filter),
                onClick = { navigator.push(ExtensionFilterScreen()) },
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.label_extension_repos),
                onClick = { navigator.push(NovelExtensionReposScreen()) },
            ),
        ),
        content = { contentPadding, _ ->
            ExtensionScreen(
                state = state,
                contentPadding = contentPadding,
                searchQuery = state.searchQuery,
                onLongClickItem = { extension ->
                    if (extension is Extension.JsPlugin) {
                        if (extension.isInstalled) {
                            extensionsViewModel.uninstallExtension(extension)
                        } else {
                            extensionsViewModel.installJsPlugin(extension)
                        }
                    }
                },
                onClickItemCancel = {},
                onClickUpdateAll = extensionsViewModel::updateAllExtensions,
                onOpenWebView = { extension ->
                    if (extension is Extension.JsPlugin) {
                        extension.sources.firstOrNull()?.let { source ->
                            scope.launch {
                                navigator.push(
                                    WebViewScreen(
                                        url = extensionsViewModel.resolveWebViewUrl(extension),
                                        initialTitle = source.name,
                                        sourceId = source.id,
                                    ),
                                )
                            }
                        }
                    }
                },
                onInstallExtension = {
                    if (it is Extension.JsPlugin) extensionsViewModel.installJsPlugin(it)
                },
                onOpenExtension = {
                    if (it is Extension.JsPlugin) {
                        navigator.push(
                            eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen(it.pkgName),
                        )
                    }
                },
                onTrustExtension = {},
                onUninstallExtension = {
                    if (it is Extension.JsPlugin) extensionsViewModel.uninstallExtension(it)
                },
                onUpdateExtension = {
                    if (it is Extension.JsPlugin) extensionsViewModel.installJsPlugin(it)
                },
                onRefresh = extensionsViewModel::findAvailableExtensions,
                onEmptyReposAction = { navigator.push(NovelExtensionReposScreen()) },
                emptyReposLabel = TDMR.strings.pref_novel_extension_repos,
            )
        },
    )
}
