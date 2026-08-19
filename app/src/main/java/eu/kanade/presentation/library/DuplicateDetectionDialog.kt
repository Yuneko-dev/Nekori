package eu.kanade.presentation.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.library.LibraryViewModel
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.i18n.stringResource as contextStringResource

@Composable
fun DuplicateDetectionDialog(
    duplicates: List<LibraryViewModel.DuplicateGroup>,
    onDismissRequest: () -> Unit,
    onSelectAllExceptFirst: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sourceManager = remember { Injekt.get<SourceManager>() }
    val getCategories = remember { Injekt.get<GetCategories>() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(TDMR.strings.library_duplicate_dialog_title))
                if (duplicates.isNotEmpty()) {
                    IconButton(onClick = {
                        // Copy all URLs from duplicates
                        val urls = duplicates.flatMap { group ->
                            group.items.map { item ->
                                val manga = item.libraryManga.manga
                                val source = sourceManager.getOrStub(manga.source)
                                if (source is HttpSource) {
                                    try {
                                        source.getMangaUrl(manga.toSManga())
                                    } catch (_: Exception) {
                                        manga.url
                                    }
                                } else {
                                    manga.url
                                }
                            }
                        }.joinToString("\n")
                        clipboardManager.setText(AnnotatedString(urls))
                        context.toast(
                            context.contextStringResource(
                                TDMR.strings.duplicate_urls_copied,
                                duplicates.sumOf { it.items.size },
                            ),
                        )
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(TDMR.strings.library_duplicate_dialog_copy_all_urls),
                        )
                    }
                }
            }
        },
        text = {
            if (duplicates.isEmpty()) {
                Text(stringResource(TDMR.strings.library_duplicate_dialog_none_found))
            } else {
                Column {
                    Text(
                        text = stringResource(TDMR.strings.library_duplicate_dialog_groups_found, duplicates.size),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(duplicates) { group ->
                            DuplicateGroupItem(group, sourceManager)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (duplicates.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSelectAllExceptFirst) {
                        Text(stringResource(TDMR.strings.duplicate_select_all_except_first))
                    }
                    TextButton(onClick = onSelectAll) {
                        Text(stringResource(MR.strings.action_select_all))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_close))
            }
        },
    )
}

@Composable
private fun DuplicateGroupItem(group: LibraryViewModel.DuplicateGroup, sourceManager: SourceManager) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        group.items.forEachIndexed { index, item ->
            val manga = item.libraryManga.manga
            val source = sourceManager.getOrStub(manga.source)
            val chapterCount = item.libraryManga.totalChapters
            val altTitles = manga.alternativeTitles.takeIf { it.isNotEmpty() }?.joinToString(", ")

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (index * 8).dp)
                    .clickable {
                        // Copy URL on click
                        val url = if (source is HttpSource) {
                            try {
                                source.getMangaUrl(manga.toSManga())
                            } catch (_: Exception) {
                                manga.url
                            }
                        } else {
                            manga.url
                        }
                        clipboardManager.setText(AnnotatedString(url))
                        context.toast(context.contextStringResource(TDMR.strings.library_duplicate_dialog_url_copied))
                    },
            ) {
                // Title
                Text(
                    text = manga.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                    color = if (index == 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                // Source & Chapter count
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "$chapterCount ch.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (manga.author != null) {
                        Text(
                            text = manga.author!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                // Alt titles if available
                if (altTitles != null) {
                    Text(
                        text = stringResource(TDMR.strings.library_duplicate_dialog_alt_titles, altTitles),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
