package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.background.ReaderBackgroundImage
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun NovelBackgroundButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Icon(Icons.Outlined.Image, contentDescription = null)
        Text(stringResource(TDMR.strings.novel_background_title), Modifier.weight(1f).padding(horizontal = 12.dp))
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
    }
}

@Composable
internal fun NovelBackgroundImageCard(
    image: ReaderBackgroundImage?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    add: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val label = if (add) stringResource(MR.strings.action_add) else image?.name ?: stringResource(MR.strings.none)
    Column(Modifier.width(114.dp).padding(top = 8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(9f / 16f)
                .border(4.dp, if (selected) colors.primary else DividerDefaults.color, RoundedCornerShape(17.dp))
                .padding(4.dp).clip(RoundedCornerShape(13.dp))
                .background(colors.background)
                .selectable(selected, enabled, if (add) Role.Button else Role.RadioButton, onClick = onSelect)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (image == null) {
                Icon(
                    if (add) Icons.Outlined.Add else Icons.Outlined.Block,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = colors.onSurfaceVariant,
                )
            } else {
                AsyncImage(
                    image.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    stringResource(TDMR.strings.novel_background_selected),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp),
                    tint = colors.primary,
                )
            }
            if (image?.custom == true) {
                var menu by remember { mutableStateOf(false) }
                Box(Modifier.align(Alignment.BottomEnd)) {
                    FilledTonalIconButton(onClick = { menu = true }, enabled = enabled) {
                        Icon(Icons.Outlined.MoreVert, stringResource(TDMR.strings.novel_background_image_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(TDMR.strings.novel_background_rename)) },
                            onClick = {
                                menu = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.action_delete)) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
