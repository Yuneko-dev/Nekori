package eu.kanade.tachiyomi.ui.setting

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.screen.SettingsAppearanceScreen
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.SettingsMainScreen
import eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen
import eu.kanade.presentation.more.settings.screen.SettingsTranslationScreen
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import tachiyomi.presentation.core.components.TwoPanelBox

class SettingsScreen(
    private val destination: Int? = null,
    private val finishActivityOnExit: Boolean = false,
) : Screen() {

    constructor(destination: Destination, finishActivityOnExit: Boolean = false) :
        this(destination.id, finishActivityOnExit)

    @Composable
    override fun Content() {
        val parentNavigator = LocalNavigator.currentOrThrow
        val activity = LocalActivity.current
        if (!isTabletUi()) {
            Navigator(
                screen = when (destination) {
                    Destination.About.id -> AboutScreen
                    Destination.DataAndStorage.id -> SettingsDataScreen
                    Destination.Tracking.id -> SettingsTrackingScreen
                    Destination.Translation.id -> SettingsTranslationScreen
                    else -> SettingsMainScreen
                },
                onBackPressed = null,
            ) {
                val pop: () -> Unit = {
                    if (it.canPop) {
                        it.pop()
                    } else if (finishActivityOnExit) {
                        activity?.finish()
                    } else {
                        parentNavigator.pop()
                    }
                }
                BackHandler(enabled = finishActivityOnExit && !it.canPop) { activity?.finish() }
                CompositionLocalProvider(LocalBackPress provides pop) {
                    DefaultNavigatorScreenTransition(navigator = it)
                }
            }
        } else {
            Navigator(
                screen = when (destination) {
                    Destination.About.id -> AboutScreen
                    Destination.DataAndStorage.id -> SettingsDataScreen
                    Destination.Tracking.id -> SettingsTrackingScreen
                    Destination.Translation.id -> SettingsTranslationScreen
                    else -> SettingsAppearanceScreen
                },
                onBackPressed = null,
            ) {
                val exit: () -> Unit = {
                    if (finishActivityOnExit) activity?.finish() else parentNavigator.pop()
                }
                val pop: () -> Unit = { if (it.canPop) it.pop() else exit() }
                BackHandler(enabled = finishActivityOnExit && !it.canPop) { activity?.finish() }
                val insets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)
                TwoPanelBox(
                    modifier = Modifier
                        .windowInsetsPadding(insets)
                        .consumeWindowInsets(insets),
                    startContent = {
                        CompositionLocalProvider(LocalBackPress provides exit) {
                            SettingsMainScreen.Content(twoPane = true)
                        }
                    },
                    endContent = {
                        CompositionLocalProvider(LocalBackPress provides pop) {
                            DefaultNavigatorScreenTransition(navigator = it)
                        }
                    },
                )
            }
        }
    }

    sealed class Destination(val id: Int) {
        data object About : Destination(0)
        data object DataAndStorage : Destination(1)
        data object Tracking : Destination(2)
        data object Translation : Destination(3)
    }
}
