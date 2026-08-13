package eu.kanade.tachiyomi.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.stats.AdvancedStatsScreenContent
import eu.kanade.presentation.more.stats.StatsScreenContent
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class StatsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val viewModel = viewModel<StatsViewModel>()
        val state by viewModel.state.collectAsState()
        val useModernStatsPreference = remember { Injekt.get<ReaderPreferences>().useModernStats }
        val useModernStats by useModernStatsPreference.changes().collectAsState(useModernStatsPreference.get())

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_stats),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            if (state is StatsScreenState.Loading) {
                LoadingScreen()
                return@Scaffold
            }

            val success = state as StatsScreenState.Success
            LaunchedEffect(useModernStats, success.advanced == null) {
                if (useModernStats && success.advanced == null) {
                    viewModel.loadAdvancedStats()
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
            ) {
                val contentPadding = PaddingValues(
                    top = MaterialTheme.padding.medium,
                    bottom = paddingValues.calculateBottomPadding(),
                )
                if (!useModernStats) {
                    StatsScreenContent(state = success, paddingValues = contentPadding)
                } else if (success.advanced == null) {
                    LoadingScreen()
                } else {
                    AdvancedStatsScreenContent(
                        state = success,
                        paddingValues = contentPadding,
                        onSelectYear = viewModel::selectYear,
                        onOpenManga = { navigator.push(MangaScreen(it)) },
                    )
                }
            }
        }
    }
}
