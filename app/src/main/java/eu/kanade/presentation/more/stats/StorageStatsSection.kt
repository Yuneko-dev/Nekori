package eu.kanade.presentation.more.stats

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import tachiyomi.domain.storage.model.StorageStats
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private data class StorageItem(
    val label: StringResource,
    val bytes: Long,
    val color: Color,
)

@Composable
internal fun LazyItemScope.StorageSection(
    data: StorageStats?,
    loading: Boolean,
    error: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.extraLarge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(TDMR.strings.stats_storage),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(
                when {
                    loading -> TDMR.strings.stats_storage_scanning
                    error -> TDMR.strings.stats_storage_scan_failed
                    else -> TDMR.strings.stats_storage_updated
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalIconButton(
            onClick = onRefresh,
            modifier = Modifier
                .padding(start = MaterialTheme.padding.small)
                .size(36.dp),
            enabled = !loading,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(TDMR.strings.stats_storage_refresh),
                )
            }
        }
    }

    SectionCard {
        if (data == null) {
            Text(
                text = stringResource(
                    if (loading) TDMR.strings.stats_storage_scanning else TDMR.strings.stats_storage_unavailable,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.padding.large),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            return@SectionCard
        }

        val context = LocalContext.current
        val colorScheme = MaterialTheme.colorScheme
        val items = listOf(
            StorageItem(MR.strings.downloaded_chapters, data.downloadedChaptersBytes, colorScheme.primary),
            StorageItem(
                TDMR.strings.stats_storage_local_novels,
                data.localNovelsBytes,
                colorScheme.onTertiaryContainer,
            ),
            StorageItem(TDMR.strings.stats_storage_translations, data.translationsBytes, colorScheme.error),
            StorageItem(
                TDMR.strings.stats_storage_plugins_fonts,
                data.pluginsAndFontsBytes,
                colorScheme.onSecondaryContainer,
            ),
            StorageItem(TDMR.strings.stats_storage_other, data.backupsAndOtherBytes, colorScheme.onSurfaceVariant),
        )
        val segments = items.filter { it.bytes > 0L } + listOfNotNull(
            data.availableBytes?.takeIf { it > 0L }?.let {
                StorageItem(TDMR.strings.stats_storage_available, it, colorScheme.outline)
            },
        )

        Text(
            text = stringResource(MR.strings.pref_storage_usage),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = data.path,
            modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MaterialTheme.padding.medium)
                .height(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            segments.forEach { item ->
                Spacer(
                    modifier = Modifier
                        .weight(item.bytes.toFloat())
                        .fillMaxHeight()
                        .background(item.color),
                )
            }
        }
        Text(
            text = stringResource(
                TDMR.strings.stats_storage_total_used,
                Formatter.formatFileSize(context, data.usedBytes),
            ),
            modifier = Modifier.padding(top = MaterialTheme.padding.small),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(modifier = Modifier.padding(top = MaterialTheme.padding.medium)) {
            items.forEach { item -> StorageRow(item, Formatter.formatFileSize(context, item.bytes)) }
            StorageRow(
                item = StorageItem(
                    TDMR.strings.stats_storage_available,
                    data.availableBytes ?: 0L,
                    MaterialTheme.colorScheme.outline,
                ),
                value = data.availableBytes?.let { Formatter.formatFileSize(context, it) } ?: "—",
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.padding.medium))
        DataNote(
            text = stringResource(
                if (data.availableBytes == null) {
                    TDMR.strings.stats_storage_available_unavailable
                } else {
                    TDMR.strings.stats_storage_note
                },
            ),
            modifier = Modifier.padding(top = MaterialTheme.padding.medium),
        )
    }
}

@Composable
private fun StorageRow(item: StorageItem, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(item.color),
        )
        Text(
            text = stringResource(item.label),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MaterialTheme.padding.medium),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(text = value, style = MaterialTheme.typography.labelLarge)
    }
}
