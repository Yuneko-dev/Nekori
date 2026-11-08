package eu.kanade.tachiyomi.ui.browse.source

import eu.kanade.presentation.browse.SourceUiModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Source

class SourceSearchFilterTest {

    @Test
    fun `search keeps matching sources and their headers only`() {
        val englishHeader = SourceUiModel.Header("en")
        val alpha = SourceUiModel.Item(source(id = 1, lang = "en", name = "Alpha Novels"))
        val vietnameseHeader = SourceUiModel.Header("vi")
        val beta = SourceUiModel.Item(source(id = 2, lang = "vi", name = "Beta Novels"))

        val result = filterSourceItems(
            items = listOf(englishHeader, alpha, vietnameseHeader, beta),
            query = "  ALPHA  ",
        )

        assertEquals(listOf(englishHeader, alpha), result)
    }

    private fun source(id: Long, lang: String, name: String) = Source(
        id = id,
        lang = lang,
        name = name,
        supportsLatest = false,
        isStub = false,
        isNovelSource = true,
    )
}
