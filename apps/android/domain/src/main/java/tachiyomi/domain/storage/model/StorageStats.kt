package tachiyomi.domain.storage.model

data class StorageStats(
    val path: String,
    val downloadedChaptersBytes: Long,
    val localNovelsBytes: Long,
    val translationsBytes: Long,
    val pluginsAndFontsBytes: Long,
    val backupsAndOtherBytes: Long,
    val availableBytes: Long?,
) {
    val usedBytes: Long
        get() = downloadedChaptersBytes + localNovelsBytes + translationsBytes +
            pluginsAndFontsBytes + backupsAndOtherBytes
}
