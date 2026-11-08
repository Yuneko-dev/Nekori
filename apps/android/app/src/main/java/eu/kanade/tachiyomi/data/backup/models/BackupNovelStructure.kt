package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.novel.model.NovelLayout

@Serializable
data class BackupNovelStructure(
    @ProtoNumber(1) val layout: Long,
    @ProtoNumber(2) val totalPages: Long,
    @ProtoNumber(3) val sections: List<BackupNovelSection>,
) {
    fun novelLayout(): NovelLayout = NovelLayout.entries.firstOrNull { it.value == layout }
        ?: error("Unknown novel layout: $layout")
}

@Serializable
data class BackupNovelSection(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val pageNumber: Long? = null,
    @ProtoNumber(3) val path: String? = null,
    @ProtoNumber(4) val cover: String? = null,
    @ProtoNumber(5) val chapterUrls: List<String> = emptyList(),
)

@Serializable
data class BackupReadingSession(
    @ProtoNumber(1) val startedAt: Long,
    @ProtoNumber(2) val endedAt: Long,
    @ProtoNumber(3) val readDuration: Long,
)
