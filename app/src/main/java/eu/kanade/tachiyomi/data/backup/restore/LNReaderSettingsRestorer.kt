package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.data.translation.AiSettingsStore
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_ADGUARD
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_GOOGLE
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.translation.model.AIApiMode
import tachiyomi.domain.translation.model.AIHeader
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AIProviderType
import tachiyomi.domain.translation.model.ReasoningEffort
import tachiyomi.domain.translation.model.SystemPrompt
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Restores only LNReader settings with a direct Tsundoku equivalent. */
class LNReaderSettingsRestorer(
    private val json: Json = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val readerPreferences: ReaderPreferences = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val networkPreferences: NetworkPreferences = Injekt.get(),
    private val aiSettingsStore: AiSettingsStore = AiSettingsStore(),
) {
    data class Result(val providerIds: Set<String>, val restoredSettings: Boolean)

    fun restore(settings: JsonObject, apiKeys: Map<String, String>, restoreApiKeys: Boolean): Result {
        restoreAppearance(settings)
        restoreNetwork(settings)
        restoreLibrary(settings.objectValue("LIBRARY_SETTINGS"))
        restoreReader(
            general = settings.objectValue("CHAPTER_GENERAL_SETTINGS"),
            reader = settings.objectValue("CHAPTER_READER_SETTINGS"),
        )
        restoreTranslation(settings.objectValue("TRANSLATE_SETTINGS"))
        val providers = restoreAi(settings)
        if (restoreApiKeys) {
            apiKeys.filterKeys(providers::contains).forEach { (id, key) ->
                if (key.isNotBlank()) {
                    aiSettingsStore.providers().firstOrNull { it.id == id }?.let { provider ->
                        aiSettingsStore.saveProvider(provider, key)
                    }
                }
            }
        }
        return Result(providers, restoredSettings = true)
    }

    private fun restoreAppearance(settings: JsonObject) {
        settings.stringValue("THEME_MODE")?.let { mode ->
            when (mode.lowercase()) {
                "light" -> ThemeMode.LIGHT
                "dark" -> ThemeMode.DARK
                "system" -> ThemeMode.SYSTEM
                else -> null
            }?.let(uiPreferences.themeMode::set)
        }
        settings.booleanValue("AMOLED_BLACK")?.let(uiPreferences.themeDarkAmoled::set)
    }

    private fun restoreNetwork(settings: JsonObject) {
        settings.stringValue("USER_AGENT")?.takeIf { it.isNotBlank() }
            ?.let(networkPreferences.defaultUserAgent::set)
        settings.objectValue("APP_SETTINGS")?.stringValue("dohProvider")?.let { provider ->
            when (provider.lowercase()) {
                "disabled" -> -1
                "cloudflare" -> PREF_DOH_CLOUDFLARE
                "google" -> PREF_DOH_GOOGLE
                "adguard" -> PREF_DOH_ADGUARD
                else -> null
            }?.let(networkPreferences.dohProvider::set)
        }
    }

    private fun restoreLibrary(value: JsonObject?) {
        value ?: return
        value.intValue("displayMode")?.let { mode ->
            when (mode) {
                0 -> LibraryDisplayMode.CompactGrid
                1 -> LibraryDisplayMode.ComfortableGrid
                2 -> LibraryDisplayMode.CoverOnlyGrid
                3 -> LibraryDisplayMode.List
                else -> null
            }?.let(libraryPreferences.displayMode::set)
        }
        value.intValue("novelsPerRow")?.takeIf { it > 0 }?.let {
            libraryPreferences.portraitColumns.set(it)
        }
        value.booleanValue("showDownloadBadges")?.let(libraryPreferences.downloadBadge::set)
        value.booleanValue("showUnreadBadges")?.let(libraryPreferences.unreadBadge::set)
    }

    private fun restoreReader(general: JsonObject?, reader: JsonObject?) {
        general?.booleanValue("keepScreenOn")?.let(readerPreferences.novelKeepScreenOn::set)
        general?.booleanValue("bionicReading")?.let(readerPreferences.novelBionicReading::set)
        general?.booleanValue("TTSEnable")?.let(readerPreferences.novelTtsEnabled::set)
        general?.booleanValue("useVolumeButtons")?.let(readerPreferences.novelVolumeKeysScroll::set)

        reader ?: return
        reader.intValue("textSize")?.takeIf { it > 0 }?.let(readerPreferences.novelFontSize::set)
        reader.stringValue("fontFamily")?.let(readerPreferences.novelFontFamily::set)
        val textColor = reader.stringValue("textColor")?.toLnReaderColor()
        val backgroundColor = reader.stringValue("theme")?.toLnReaderColor()
        textColor?.let(readerPreferences.novelFontColor::set)
        backgroundColor?.let(readerPreferences.novelBackgroundColor::set)
        if (textColor != null || backgroundColor != null) readerPreferences.novelTheme.set("custom")
        reader.floatValue("lineHeight")?.takeIf { it > 0f }?.let(readerPreferences.novelLineHeight::set)
        reader.stringValue("textAlign")?.takeIf { it in TEXT_ALIGNMENTS }?.let(readerPreferences.novelTextAlign::set)
        reader.intValue("padding")?.takeIf { it >= 0 }?.let {
            readerPreferences.novelMarginLeft.set(it)
            readerPreferences.novelMarginRight.set(it)
            readerPreferences.novelMarginTop.set(it)
            readerPreferences.novelMarginBottom.set(it)
        }
        reader.floatValue("paragraphIndent")?.takeIf { it >= 0f }
            ?.let(readerPreferences.novelParagraphIndent::set)
        reader.floatValue("paragraphSpacing")?.takeIf { it >= 0f }
            ?.let(readerPreferences.novelParagraphSpacing::set)
        reader.objectValue("tts")?.let { tts ->
            tts.floatValue("rate")?.takeIf { it > 0f }?.let(readerPreferences.novelTtsSpeed::set)
            tts.floatValue("pitch")?.takeIf { it > 0f }?.let(readerPreferences.novelTtsPitch::set)
            tts.booleanValue("autoPageAdvance")?.let(readerPreferences.novelTtsAutoNextChapter::set)
        }
    }

    private fun restoreTranslation(value: JsonObject?) {
        value ?: return
        value.stringValue("engine")?.let { engine ->
            when (engine) {
                "google-free" -> TranslationEngineId.GOOGLE_FREE
                "llm" -> TranslationEngineId.LLM
                else -> null
            }?.let { translationPreferences.selectedEngineId().set(it.key) }
        }
        value.booleanValue("llmDisableStructuredOutput")?.let {
            translationPreferences.structuredOutput().set(!it)
        }
        val retryEnabled = value.booleanValue("llmRetryEnabled") ?: true
        val retryCount = if (retryEnabled) {
            value.intValue("llmRetryMaxAttempts")?.minus(1)?.coerceIn(0, 5)
        } else {
            0
        }
        retryCount?.let(translationPreferences.requestRetryCount()::set)
        restorePrompts(value)
    }

    private fun restorePrompts(value: JsonObject) {
        val prompts = value.arrayValue("llmSystemPrompts").mapNotNull { element ->
            runCatching {
                val prompt = json.decodeFromJsonElement<LNPrompt>(element)
                SystemPrompt(prompt.id, prompt.title, prompt.content)
            }.getOrNull()?.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
        }
        val restoredIds = prompts.mapNotNullTo(mutableSetOf()) { prompt ->
            runCatching { aiSettingsStore.savePrompt(prompt) }.map { prompt.id }.getOrNull()
        }
        value.stringValue("activeSystemPromptId")
            ?.takeIf { it == SystemPrompt.DEFAULT_ID || it in restoredIds }
            ?.let(aiSettingsStore::setActivePrompt)
    }

    private fun restoreAi(settings: JsonObject): Set<String> {
        val providers = settings.arrayValue("AI_PROVIDERS").mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<LNProvider>(element) }
                .getOrNull()
                ?.toProvider()
        }
        val restoredIds = providers.mapNotNullTo(mutableSetOf()) { provider ->
            runCatching { aiSettingsStore.saveProvider(provider) }.map { provider.id }.getOrNull()
        }
        settings.stringValue("ACTIVE_AI_PROVIDER")
            ?.takeIf(restoredIds::contains)
            ?.let(aiSettingsStore::setActiveProvider)
        return providers.mapTo(mutableSetOf()) { it.id }
    }

    private fun LNProvider.toProvider(): AIProvider? {
        val type = when (provider.lowercase()) {
            "openai" -> AIProviderType.OPENAI
            "deepseek" -> AIProviderType.DEEPSEEK
            "gemini" -> AIProviderType.GEMINI
            "xai" -> AIProviderType.XAI
            "openrouter" -> AIProviderType.OPENROUTER
            "groq" -> AIProviderType.GROQ
            "custom" -> AIProviderType.CUSTOM_OPENAI
            else -> return null
        }
        return AIProvider(
            id = id,
            alias = alias,
            type = type,
            endpoint = endpoint.ifBlank { type.defaultEndpoint },
            model = model,
            apiMode = if (apiMode == "chat-completions") AIApiMode.CHAT_COMPLETIONS else AIApiMode.RESPONSES,
            temperature = temperature.coerceIn(0f, 2f),
            reasoning = enableReasoning,
            reasoningEffort = runCatching { ReasoningEffort.valueOf(reasoningEffort.uppercase()) }
                .getOrDefault(ReasoningEffort.LOW),
            customHeaders = customHeaders.map { AIHeader(it.name, it.value) },
        ).takeIf { it.id.isNotBlank() && it.alias.isNotBlank() && it.model.isNotBlank() }
    }

    @Serializable
    private data class LNProvider(
        val id: String,
        val alias: String,
        val provider: String,
        val endpoint: String = "",
        val model: String,
        val temperature: Float = 0.6f,
        val apiMode: String = "responses",
        val enableReasoning: Boolean = false,
        val reasoningEffort: String = "low",
        val customHeaders: List<LNHeader> = emptyList(),
    )

    @Serializable
    private data class LNHeader(val name: String, val value: String)

    @Serializable
    private data class LNPrompt(val id: String, val title: String, val content: String = "")

    private fun JsonObject.objectValue(key: String): JsonObject? = when (val element = value(key)) {
        is JsonObject -> element
        is JsonPrimitive -> element.contentOrNull?.let { encoded ->
            runCatching { json.parseToJsonElement(encoded) as? JsonObject }.getOrNull()
        }
        else -> null
    }

    private fun JsonObject.arrayValue(key: String): List<JsonElement> = when (val element = value(key)) {
        is JsonArray -> element
        is JsonPrimitive -> element.contentOrNull?.let { encoded ->
            runCatching { json.parseToJsonElement(encoded) as? JsonArray }.getOrNull()
        }.orEmpty()
        else -> emptyList()
    }

    private fun JsonObject.value(key: String): JsonElement? = this[key]
    private fun JsonObject.stringValue(key: String): String? = value(key)
        ?.let { (it as? JsonPrimitive)?.contentOrNull }
        ?.removeSurrounding("\"")
    private fun JsonObject.booleanValue(key: String): Boolean? = (value(key) as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.intValue(key: String): Int? = (value(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.floatValue(key: String): Float? = (value(key) as? JsonPrimitive)?.floatOrNull

    private fun String.toLnReaderColor(): Int? {
        val hex = removePrefix("#")
        val argb = when (hex.length) {
            6 -> "FF$hex"
            8 -> hex.takeLast(2) + hex.dropLast(2)
            else -> return null
        }
        return argb.toLongOrNull(16)?.toInt()
    }

    private companion object {
        val TEXT_ALIGNMENTS = setOf("left", "right", "center", "justify")
    }
}
