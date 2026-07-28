package tachiyomi.domain.novel.repository

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.novel.model.NovelStructure
import tachiyomi.domain.novel.model.NovelStructureSnapshot

interface NovelStructureRepository {

    suspend fun get(mangaId: Long): NovelStructureSnapshot?

    suspend fun replace(
        mangaId: Long,
        structure: NovelStructure,
        chapters: List<Chapter>,
    )

    suspend fun reconcilePage(
        mangaId: Long,
        page: String,
        chapters: List<Chapter>,
    )
}
