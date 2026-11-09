package eu.kanade.presentation.reader.settings

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScreen
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.tachiyomi.data.background.ReaderBackgroundImage
import eu.kanade.tachiyomi.ui.reader.setting.NovelBackgroundSettings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.setBackgroundSettings
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelBackgroundManagerScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val preferences = remember { Injekt.get<ReaderPreferences>() }
        NovelBackgroundScreen(preferences) { navigator.pop() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelBackgroundScreen(preferences: ReaderPreferences, onBack: () -> Unit) {
    val model = viewModel<ReaderBackgroundViewModel>()
    val images by model.images.collectAsState()
    val busy by model.busy.collectAsState()
    val failed by model.failed.collectAsState()
    val raw by preferences.novelBackground.collectAsState()
    val settings = NovelBackgroundSettings.decode(raw)
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ReaderBackgroundImage?>(null) }
    var deleting by remember { mutableStateOf<ReaderBackgroundImage?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(model::importImage)
        }
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose {}
    }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Box {
                AppBar(stringResource(TDMR.strings.novel_background_title), navigateUp = onBack, actions = {
                    IconButton(enabled = !busy, onClick = {
                        model.openFolder { uri ->
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                            ).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(intent) }
                                .onFailure { context.toast(TDMR.strings.novel_background_folder_error) }
                        }
                    }) {
                        Icon(Icons.Outlined.FolderOpen, stringResource(TDMR.strings.novel_background_open_folder))
                    }
                })
                // Loading must not insert a preference row and recreate the image carousel.
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
                }
            }
        },
    ) { padding ->
        PreferenceScreen(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            items = buildList {
                if (failed) {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(TDMR.strings.novel_background_operation_failed),
                            onClick = model::refresh,
                        ),
                    )
                }
                add(
                    Preference.PreferenceItem.CustomPreference("") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PrefsHorizontalPadding, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "none") {
                                NovelBackgroundImageCard(
                                    null,
                                    settings.image.isEmpty(),
                                    !busy,
                                    onSelect = { preferences.setBackgroundSettings(settings.copy(image = "")) },
                                )
                            }
                            items(images, key = { it.id }) { image ->
                                NovelBackgroundImageCard(
                                    image,
                                    settings.image == image.id,
                                    !busy,
                                    onSelect = { preferences.setBackgroundSettings(settings.copy(image = image.id)) },
                                    onEdit = { editing = image },
                                    onDelete = { deleting = image },
                                )
                            }
                            item(key = "add") {
                                NovelBackgroundImageCard(
                                    null,
                                    false,
                                    !busy,
                                    add = true,
                                    onSelect = { picker.launch(arrayOf("image/*", "image/svg+xml")) },
                                )
                            }
                        }
                    },
                )
                if (!busy && settings.image.isNotEmpty() && images.none { it.id == settings.image }) {
                    add(
                        Preference.PreferenceItem.InfoPreference(
                            stringResource(TDMR.strings.novel_background_unavailable),
                        ),
                    )
                }
                if (settings.image.isNotEmpty()) {
                    addAll(backgroundControlPreferences(settings, preferences::setBackgroundSettings))
                }
                add(
                    Preference.PreferenceItem.InfoPreference(
                        stringResource(TDMR.strings.novel_background_import_summary),
                    ),
                )
            },
        )
    }
    editing?.let { image ->
        NovelBackgroundEditDialog(image.name, onDismiss = { editing = null }, onConfirm = { name ->
            model.rename(image, name)
            editing = null
        })
    }
    deleting?.let { image ->
        NovelBackgroundDeleteDialog(image.name, onDismiss = { deleting = null }, onConfirm = {
            model.delete(image)
            deleting = null
        })
    }
}
