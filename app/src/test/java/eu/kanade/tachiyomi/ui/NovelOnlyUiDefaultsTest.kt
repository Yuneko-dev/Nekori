package eu.kanade.tachiyomi.ui

import eu.kanade.tachiyomi.ui.history.HistoryFilter
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.updates.UpdatesFilter
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelOnlyUiDefaultsTest {

    @Test
    fun `updates only show novels by default`() {
        assertEquals(UpdatesFilter.NOVELS, UpdatesViewModel.State().filter)
    }

    @Test
    fun `history only shows novels by default`() {
        assertEquals(HistoryFilter.NOVELS, HistoryViewModel.State().filter)
    }
}
