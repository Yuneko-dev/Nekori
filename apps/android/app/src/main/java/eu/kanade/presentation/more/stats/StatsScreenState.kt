package eu.kanade.presentation.more.stats

import androidx.compose.runtime.Immutable
import eu.kanade.presentation.more.stats.data.StatsData
import tachiyomi.domain.storage.model.StorageStats

sealed interface StatsScreenState {
    @Immutable
    data object Loading : StatsScreenState

    @Immutable
    data class Success(
        val overview: StatsData.Overview,
        val titles: StatsData.Titles,
        val chapters: StatsData.Chapters,
        val trackers: StatsData.Trackers,
        val advanced: StatsData.Advanced? = null,
        val storage: StorageStats? = null,
        val storageLoading: Boolean = false,
        val storageError: Boolean = false,
    ) : StatsScreenState
}
