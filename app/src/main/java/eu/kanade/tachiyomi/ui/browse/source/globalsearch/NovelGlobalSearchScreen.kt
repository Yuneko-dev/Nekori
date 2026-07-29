package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.GlobalSearchScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.presentation.core.screens.LoadingScreen

class NovelGlobalSearchScreen(
    val searchQuery: String = "",
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow

        val screenModel = rememberScreenModel {
            NovelGlobalSearchScreenModel(
                initialQuery = searchQuery,
            )
        }
        val state by screenModel.state.collectAsState()
        GlobalSearchScreen(
            state = state,
            navigateUp = navigator::pop,
            onChangeSearchQuery = screenModel::updateSearchQuery,
            onSearch = { screenModel.search() },
            getManga = { screenModel.getManga(it) },
            onChangeSearchFilter = screenModel::setSourceFilter,
            onToggleResults = screenModel::toggleFilterResults,
            onClickSource = {
                navigator.push(BrowseSourceScreen(it.id, state.searchQuery))
            },
            onClickItem = { navigator.push(MangaScreen(it.id, true)) },
            onLongClickItem = { navigator.push(MangaScreen(it.id, true)) },
        )
    }
}
