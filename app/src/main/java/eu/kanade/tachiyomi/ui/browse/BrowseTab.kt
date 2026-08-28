package eu.kanade.tachiyomi.ui.browse

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.extension.NovelExtensionsViewModel
import eu.kanade.tachiyomi.ui.browse.extension.novelExtensionsTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.novelMigrateSourceTab
import eu.kanade.tachiyomi.ui.browse.source.NovelSourcesViewModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.NovelGlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.novelSourcesTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object BrowseTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter)
            return TabOptions(
                index = 4u,
                title = stringResource(MR.strings.browse),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(NovelGlobalSearchScreen())
    }

    private val switchToExtensionTabChannel = Channel<Unit>(1, BufferOverflow.DROP_OLDEST)

    fun showExtension() {
        switchToExtensionTabChannel.trySend(Unit)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val sourcesViewModel = viewModel<NovelSourcesViewModel>()
        val sourcesState by sourcesViewModel.state.collectAsState()
        val extensionsViewModel = viewModel<NovelExtensionsViewModel>()
        val extensionsState by extensionsViewModel.state.collectAsState()
        val sourcesTabIndex = 0
        val extensionsTabIndex = 1
        val tabs = listOf(
            novelSourcesTab(sourcesViewModel),
            novelExtensionsTab(extensionsViewModel),
            novelMigrateSourceTab(),
        )

        val state = rememberPagerState { tabs.size }

        TabbedScreen(
            titleRes = MR.strings.browse,
            tabs = tabs,
            state = state,
            searchQuery = when (state.currentPage) {
                sourcesTabIndex -> sourcesState.searchQuery
                extensionsTabIndex -> extensionsState.searchQuery
                else -> null
            },
            onChangeSearchQuery = { query ->
                when (state.currentPage) {
                    sourcesTabIndex -> sourcesViewModel.search(query)
                    extensionsTabIndex -> extensionsViewModel.search(query)
                    else -> Unit
                }
            },
        )
        LaunchedEffect(Unit) {
            switchToExtensionTabChannel.receiveAsFlow()
                .collectLatest {
                    state.scrollToPage(extensionsTabIndex)
                }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}
