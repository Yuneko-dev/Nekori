package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.translation.AiSettingsStore
import eu.kanade.tachiyomi.data.translation.LlmGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.translation.model.AIApiMode
import tachiyomi.domain.translation.model.AIHeader
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.ReasoningEffort
import tachiyomi.domain.translation.model.validateCustomHeaders
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

object AiProviderManagerScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val back = LocalBackPress.currentOrThrow
        val store = remember { Injekt.get<AiSettingsStore>() }
        val preferences = remember { Injekt.get<TranslationPreferences>() }
        val providersJson by preferences.aiProvidersJson().collectAsState()
        val providers = remember(providersJson) { store.providers() }

        ManagerScreen(
            title = stringResource(TDMR.strings.pref_ai_providers),
            entries = providers,
            entryKey = { it.id },
            addLabel = stringResource(TDMR.strings.pref_ai_add_provider),
            onAdd = { navigator.push(AiProviderEditorScreen()) },
            onBack = back::invoke,
            emptyMessage = stringResource(TDMR.strings.pref_ai_no_providers),
        ) { provider ->
            ManagerRow(
                title = provider.alias,
                subtitle = listOfNotNull(provider.type.displayName, provider.model.ifBlank { null })
                    .joinToString(" • "),
                icon = { Icon(Icons.Outlined.SmartToy, null) },
                onClick = { navigator.push(AiProviderEditorScreen(provider.id)) },
                onDelete = { store.deleteProvider(provider.id) },
            )
        }
    }
}

data class AiProviderEditorScreen(private val providerId: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val back = LocalBackPress.currentOrThrow
        val store = remember { Injekt.get<AiSettingsStore>() }
        val generator = remember { Injekt.get<LlmGenerator>() }
        val original = remember(providerId) { store.providers().firstOrNull { it.id == providerId } }
        var type by remember { mutableStateOf(original?.type ?: AIProviderType.OPENAI) }
        var alias by remember { mutableStateOf(original?.alias ?: "LLM") }
        var endpoint by remember { mutableStateOf(original?.endpoint ?: type.defaultEndpoint) }
        var model by remember { mutableStateOf(original?.model.orEmpty()) }
        var apiKey by remember { mutableStateOf(original?.let { store.apiKey(it.id) }.orEmpty()) }
        var apiMode by remember { mutableStateOf(original?.apiMode ?: AIApiMode.CHAT_COMPLETIONS) }
        var temperature by remember { mutableStateOf(original?.temperature ?: 0.6f) }
        var reasoning by remember { mutableStateOf(original?.reasoning ?: false) }
        var effort by remember { mutableStateOf(original?.reasoningEffort ?: ReasoningEffort.LOW) }
        var headers by remember { mutableStateOf(original?.customHeaders.orEmpty()) }
        var status by remember { mutableStateOf<String?>(null) }
        var statusIsError by remember { mutableStateOf(false) }
        var busyAction by remember { mutableStateOf<ProviderAction?>(null) }
        var models by remember { mutableStateOf(emptyList<String>()) }
        var showModelPicker by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val id = original?.id ?: remember { UUID.randomUUID().toString() }
        val connectionSuccessful = stringResource(TDMR.strings.pref_ai_connection_ok)
        val apiKeyRequired = stringResource(TDMR.strings.pref_ai_provider_api_key_required)
        val noModelsFound = stringResource(TDMR.strings.pref_ai_no_models)

        fun provider() = AIProvider(
            id = id,
            alias = alias,
            type = type,
            endpoint = endpoint,
            model = model,
            apiMode = apiMode,
            temperature = temperature,
            reasoning = reasoning,
            reasoningEffort = effort,
            customHeaders = headers,
        )

        fun runAction(action: ProviderAction, block: suspend () -> Unit) {
            if (busyAction != null) return
            scope.launch {
                busyAction = action
                status = null
                try {
                    if (provider().requiresApiKey && apiKey.isBlank()) error(apiKeyRequired)
                    block()
                    statusIsError = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status = e.message
                    statusIsError = true
                } finally {
                    busyAction = null
                }
            }
        }

        fun save() {
            val value = provider()
            val validation = validateCustomHeaders(value.customHeaders)
            when {
                value.requiresApiKey && apiKey.isBlank() -> status = apiKeyRequired
                !validation.isValid -> status = validation.errors.joinToString("\n")
                else -> runCatching {
                    store.saveProvider(value, apiKey)
                    navigator.pop()
                }.onFailure { status = it.message }
            }
            statusIsError = status != null
        }

        Scaffold(topBar = {
            AppBar(
                title = stringResource(
                    if (original == null) TDMR.strings.pref_ai_add_provider else TDMR.strings.pref_ai_edit_provider,
                ),
                navigateUp = back::invoke,
                actions = {
                    TextButton(onClick = ::save, enabled = busyAction == null) {
                        Text(stringResource(MR.strings.action_save))
                    }
                },
            )
        }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    end = 16.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SettingsDropdownField(
                        label = stringResource(TDMR.strings.pref_ai_provider_type),
                        value = type,
                        values = AIProviderType.entries,
                        valueLabel = AIProviderType::displayName,
                    ) { selected ->
                        if (selected != type) {
                            type = selected
                            endpoint = selected.defaultEndpoint
                            models = emptyList()
                            status = null
                        }
                    }
                }
                item { ManagerTextField(alias, { alias = it }, stringResource(TDMR.strings.pref_ai_provider_alias)) }
                item {
                    ManagerTextField(
                        endpoint,
                        { endpoint = it },
                        stringResource(TDMR.strings.pref_ai_provider_endpoint),
                        readOnly = !type.endpointEditable,
                    )
                }
                item {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(TDMR.strings.pref_ai_provider_api_key)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text(stringResource(TDMR.strings.pref_ai_provider_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(
                                enabled = busyAction == null,
                                onClick = {
                                    runAction(ProviderAction.LOAD_MODELS) {
                                        models = generator.loadModels(provider(), apiKey)
                                        if (models.isEmpty()) {
                                            error(noModelsFound)
                                        }
                                        showModelPicker = true
                                    }
                                },
                            ) {
                                if (busyAction == ProviderAction.LOAD_MODELS) {
                                    CircularProgressIndicator(Modifier.padding(4.dp))
                                } else {
                                    Icon(
                                        Icons.Outlined.Refresh,
                                        contentDescription = stringResource(TDMR.strings.pref_ai_load_models),
                                    )
                                }
                            }
                        },
                    )
                }
                if (type.supportsApiMode) {
                    item {
                        SettingsDropdownField(
                            label = stringResource(TDMR.strings.pref_ai_provider_api_mode),
                            value = apiMode,
                            values = AIApiMode.entries,
                            valueLabel = AIApiMode::displayName,
                            onSelected = { apiMode = it },
                        )
                    }
                }
                if (type.supportsTemperature) {
                    item {
                        val label = stringResource(TDMR.strings.pref_ai_provider_temperature)
                        Text(
                            "$label: ${"%.1f".format(temperature)}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Slider(
                            value = temperature,
                            onValueChange = { temperature = (it * 10).toInt() / 10f },
                            valueRange = 0f..2f,
                            steps = 19,
                        )
                    }
                }
                item {
                    ManagerSwitchRow(stringResource(TDMR.strings.pref_ai_provider_reasoning), reasoning) {
                        reasoning = it
                    }
                }
                if (reasoning) {
                    item {
                        SettingsDropdownField(
                            label = stringResource(TDMR.strings.pref_ai_provider_reasoning_effort),
                            value = effort,
                            values = ReasoningEffort.entries,
                            valueLabel = { it.name.lowercase() },
                            onSelected = { effort = it },
                        )
                    }
                }
                if (type.supportsApiMode) {
                    item {
                        Text(
                            stringResource(TDMR.strings.pref_ai_provider_custom_headers),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(TDMR.strings.pref_ai_provider_headers_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    itemsIndexed(headers) { index, header ->
                        HeaderRow(header, { updated ->
                            headers = headers.toMutableList().also { it[index] = updated }
                        }) {
                            headers = headers.toMutableList().also { it.removeAt(index) }
                        }
                    }
                    item {
                        TextButton(onClick = { headers = headers + AIHeader("", "") }) {
                            Icon(Icons.Outlined.Add, null)
                            Text(stringResource(TDMR.strings.pref_ai_add_header), Modifier.padding(start = 8.dp))
                        }
                    }
                }
                item {
                    FilledTonalButton(
                        enabled = busyAction == null,
                        onClick = {
                            runAction(ProviderAction.TEST_CONNECTION) {
                                generator.testConnection(provider(), apiKey)
                                status = connectionSuccessful
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busyAction == ProviderAction.TEST_CONNECTION) {
                            CircularProgressIndicator(Modifier.padding(4.dp))
                        } else {
                            Icon(Icons.Outlined.CheckCircleOutline, null)
                        }
                        Text(stringResource(TDMR.strings.pref_ai_test_connection), Modifier.padding(start = 8.dp))
                    }
                }
                status?.let { message ->
                    item {
                        Text(
                            message,
                            color = if (statusIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        if (showModelPicker) {
            ModelPickerDialog(
                models = models,
                selected = model,
                onSelect = {
                    model = it
                    showModelPicker = false
                },
                onDismiss = { showModelPicker = false },
            )
        }
    }
}

private enum class ProviderAction { LOAD_MODELS, TEST_CONNECTION }

@Composable
private fun HeaderRow(header: AIHeader, onChange: (AIHeader) -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = header.name,
            onValueChange = { onChange(header.copy(name = it)) },
            label = { Text(stringResource(TDMR.strings.pref_ai_header_name)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = header.value,
            onValueChange = { onChange(header.copy(value = it)) },
            label = { Text(stringResource(TDMR.strings.pref_ai_header_value)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                stringResource(MR.strings.action_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.pref_ai_provider_model)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(models, key = { it }) { model ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(model) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(model, Modifier.weight(1f))
                        if (model == selected) Icon(Icons.Outlined.CheckCircleOutline, null)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

internal val AIProviderType.displayName: String
    get() = when (this) {
        AIProviderType.OPENAI -> "OpenAI"
        AIProviderType.DEEPSEEK -> "DeepSeek"
        AIProviderType.GEMINI -> "Gemini"
        AIProviderType.XAI -> "xAI"
        AIProviderType.OPENROUTER -> "OpenRouter"
        AIProviderType.GROQ -> "Groq"
        AIProviderType.CUSTOM_OPENAI -> "OpenAI-compatible (custom)"
    }

private val AIApiMode.displayName: String
    get() = when (this) {
        AIApiMode.RESPONSES -> "Responses"
        AIApiMode.CHAT_COMPLETIONS -> "Chat Completions"
    }
