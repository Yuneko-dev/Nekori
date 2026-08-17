package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.UserGuidelines
import tachiyomi.domain.translation.model.resolve
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The list-and-add screen every named-entry manager uses: AI providers and user guidelines. They
 * differ only in what a row shows and where the add button leads.
 */
@Composable
internal fun <T> ManagerScreen(
    title: String,
    entries: List<T>,
    entryKey: (T) -> Any,
    addLabel: String,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    emptyMessage: String? = null,
    row: @Composable (T) -> Unit,
) {
    Scaffold(topBar = { AppBar(title, navigateUp = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            if (entries.isEmpty() && emptyMessage != null) {
                item { EmptyManagerMessage(emptyMessage) }
            } else {
                items(entries, key = entryKey) { row(it) }
            }
            item {
                Button(
                    onClick = onAdd,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Icon(Icons.Outlined.Add, null)
                    Text(addLabel, Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
internal fun ManagerRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.padding(4.dp)) { icon() }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            onDelete != null -> IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    stringResource(MR.strings.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            trailing != null -> trailing()
        }
    }
}

@Composable
private fun EmptyManagerMessage(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
internal fun ManagerTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    readOnly: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        readOnly = readOnly,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ManagerSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}
