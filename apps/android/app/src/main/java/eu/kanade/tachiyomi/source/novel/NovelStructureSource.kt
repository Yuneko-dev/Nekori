package eu.kanade.tachiyomi.source.novel

import eu.kanade.tachiyomi.source.model.SChapter
import tachiyomi.domain.novel.model.NovelStructure

interface NovelStructureSource {

    fun getNovelStructure(mangaUrl: String): NovelStructure?
}

interface PagedNovelSource : NovelStructureSource {

    suspend fun getPage(
        mangaUrl: String,
        page: String,
        forceRefresh: Boolean = false,
    ): List<SChapter>
}
