package tachiyomi.domain.novel.repository

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.novel.model.NovelStructure

interface NovelStructureRepository {

    suspend fun replace(
        mangaId: Long,
        structure: NovelStructure,
        chapters: List<Chapter>,
    )
}
