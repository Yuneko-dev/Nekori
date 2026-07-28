package eu.kanade.tachiyomi.source.novel

import tachiyomi.domain.novel.model.NovelStructure

interface NovelStructureSource {

    fun getNovelStructure(mangaUrl: String): NovelStructure?
}
