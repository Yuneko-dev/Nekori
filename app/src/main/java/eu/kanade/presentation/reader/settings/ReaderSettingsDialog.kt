package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import eu.kanade.presentation.components.TabTitle
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogContent
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.components.toTabTitles
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.AdaptiveSheet
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderSettingsDialog(
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    screenModel: ReaderSettingsViewModel,
    isNovelMode: Boolean = false,
) {
    if (isNovelMode) {
        NovelReaderSettingsDialog(
            onDismissRequest = onDismissRequest,
            onShowMenus = onShowMenus,
            screenModel = screenModel,
        )
    } else {
        MangaReaderSettingsDialog(
            onDismissRequest = onDismissRequest,
            onShowMenus = onShowMenus,
            onHideMenus = onHideMenus,
            screenModel = screenModel,
        )
    }
}

@Composable
private fun MangaReaderSettingsDialog(
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    screenModel: ReaderSettingsViewModel,
) {
    val tabTitles = listOf(
        stringResource(MR.strings.pref_category_reading_mode),
        stringResource(MR.strings.pref_category_general),
        stringResource(MR.strings.custom_filter),
    ).toTabTitles()
    val pagerState = rememberPagerState { tabTitles.size }

    BoxWithConstraints {
        TabbedDialog(
            modifier = Modifier.heightIn(max = maxHeight * 0.75f),
            onDismissRequest = {
                onDismissRequest()
                onShowMenus()
            },
            tabTitles = tabTitles,
            pagerState = pagerState,
        ) { page ->
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window

            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage == 2) {
                    window?.setDimAmount(0f)
                    onHideMenus()
                } else {
                    window?.setDimAmount(0.5f)
                    onShowMenus()
                }
            }

            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (page) {
                    0 -> ReadingModePage(screenModel)
                    1 -> GeneralPage(screenModel)
                    2 -> ColorFilterPage(screenModel)
                }
            }
        }
    }
}

@Composable
private fun NovelReaderSettingsDialog(
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    screenModel: ReaderSettingsViewModel,
) {
    var showRules by rememberSaveable { mutableStateOf(false) }
    val manga by screenModel.mangaFlow.collectAsState()
    val tabTitles = listOf(
        TabTitle.Icon(imageVector = Icons.Outlined.TextFields), // Reading
        TabTitle.Icon(imageVector = Icons.Outlined.Palette), // Appearance
        TabTitle.Icon(imageVector = Icons.Outlined.Swipe), // Controls
        TabTitle.Icon(imageVector = Icons.Outlined.RecordVoiceOver), // TTS
        TabTitle.Icon(imageVector = Icons.Outlined.Code), // Advanced
    )
    val pagerState = rememberPagerState { tabTitles.size }
    val dismiss = {
        onDismissRequest()
        onShowMenus()
    }
    Dialog(
        onDismissRequest = { if (!showRules) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !showRules),
    ) {
        if (showRules) {
            ReplacementRulesScreen(
                screenModel = screenModel,
                target = manga?.let { ReplacementTarget(it.source, it.url) },
                novelTitle = manga?.title.orEmpty(),
                onBack = { showRules = false },
            )
        } else {
            BoxWithConstraints {
                AdaptiveSheet(
                    isTabletUi = isTabletUi(),
                    enableImplicitDismiss = true,
                    modifier = Modifier.heightIn(max = maxHeight * 0.75f),
                    onDismissRequest = dismiss,
                ) {
                    TabbedDialogContent(tabTitles = tabTitles, pagerState = pagerState) { page ->
                        Column(
                            modifier = Modifier
                                .padding(vertical = TabbedDialogPaddings.Vertical)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            when (page) {
                                0 -> NovelReadingTab(screenModel)
                                1 -> NovelAppearanceTab(screenModel)
                                2 -> NovelControlsTab(screenModel)
                                3 -> NovelTtsTab(screenModel)
                                4 -> NovelAdvancedTab(
                                    screenModel = screenModel,
                                    target = manga?.let { ReplacementTarget(it.source, it.url) },
                                    onManageRules = { showRules = true },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
