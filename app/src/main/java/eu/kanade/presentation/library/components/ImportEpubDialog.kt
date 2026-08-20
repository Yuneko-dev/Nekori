package eu.kanade.presentation.library.components

import android.net.Uri

data class EpubFileInfo(
    val uri: Uri,
    val fileName: String,
    val title: String,
    val author: String?,
    val description: String?,
    val coverUri: Uri? = null,
    val collection: String? = null,
    val collectionPosition: Int? = null,
    val genres: String? = null,
    val tableOfContents: List<String> = emptyList(),
)

data class ImportProgress(
    val current: Int,
    val total: Int,
    val currentFileName: String,
    val isRunning: Boolean,
)

data class ImportResult(
    val successCount: Int,
    val errorCount: Int,
    val errors: List<String>,
)
