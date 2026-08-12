package eu.kanade.tachiyomi.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.stats.AdvancedStatsScreenContent
import eu.kanade.presentation.more.stats.StatsScreenContent
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

class StatsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val viewModel = viewModel<StatsViewModel>()
        val state by viewModel.state.collectAsState()
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }

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
            LaunchedEffect(selectedTab, success.advanced == null) {
                if (selectedTab == 1 && success.advanced == null) {
                    viewModel.loadAdvancedStats()
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
            ) {
                val tabs = listOf(
                    stringResource(MR.strings.label_default),
                    stringResource(TDMR.strings.stats_tab_advanced),
                )
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            modifier = Modifier.height(48.dp),
                            text = { TabText(title) },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                val contentPadding = PaddingValues(
                    top = MaterialTheme.padding.medium,
                    bottom = paddingValues.calculateBottomPadding(),
                )
                if (selectedTab == 0) {
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
