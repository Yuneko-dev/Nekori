package eu.kanade.tachiyomi.data.translation

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.SystemPrompt
import tachiyomi.domain.translation.model.normalized
import tachiyomi.domain.translation.model.validateCustomHeaders
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AiSettingsStore(
    private val preferences: TranslationPreferences = Injekt.get(),
    private val json: Json = Injekt.get(),
) {
    fun providers(): List<AIProvider> = decode(preferences.aiProvidersJson().get(), emptyList())

    fun activeProvider(): AIProvider? = providers().firstOrNull { it.id == preferences.activeAiProviderId().get() }

    fun saveProvider(provider: AIProvider, apiKey: String? = null) {
        val value = provider.normalized()
        require(
            value.id.isNotBlank() && value.alias.isNotBlank() &&
                value.endpoint.isNotBlank() && value.model.isNotBlank(),
        )
        require(value.endpoint.toHttpUrlOrNull() != null) { "Invalid provider endpoint" }
        require(validateCustomHeaders(value.customHeaders).isValid)
        val providers = providers().toMutableList()
        val isFirstProvider = providers.isEmpty()
        val index = providers.indexOfFirst { it.id == value.id }
        if (index >= 0) providers[index] = value else providers += value
        preferences.aiProvidersJson().set(json.encodeToString(providers))
        if (isFirstProvider && preferences.activeAiProviderId().get().isBlank()) {
            preferences.activeAiProviderId().set(value.id)
        }
        apiKey?.let { preferences.aiProviderApiKey(value.id).set(it) }
    }

    fun deleteProvider(id: String) {
        preferences.aiProvidersJson().set(json.encodeToString(providers().filterNot { it.id == id }))
        preferences.aiProviderApiKey(id).delete()
        if (preferences.activeAiProviderId().get() == id) preferences.activeAiProviderId().set("")
    }

    fun setActiveProvider(id: String) {
        require(providers().any { it.id == id })
        preferences.activeAiProviderId().set(id)
    }

    fun apiKey(providerId: String): String = preferences.aiProviderApiKey(providerId).get()

    fun prompts(): List<SystemPrompt> {
        val decoded = decode(preferences.systemPromptsJson().get(), listOf(SystemPrompt.DEFAULT))
        return if (decoded.any { it.id == SystemPrompt.DEFAULT_ID }) decoded else listOf(SystemPrompt.DEFAULT) + decoded
    }

    fun activePrompt(): SystemPrompt = prompts().firstOrNull {
        it.id == preferences.activeSystemPromptId().get()
    } ?: SystemPrompt.DEFAULT

    fun savePrompt(prompt: SystemPrompt) {
        require(prompt.id.isNotBlank() && prompt.name.isNotBlank())
        val prompts = prompts().toMutableList()
        val index = prompts.indexOfFirst { it.id == prompt.id }
        if (index >= 0) prompts[index] = prompt else prompts += prompt
        preferences.systemPromptsJson().set(json.encodeToString(prompts))
    }

    fun deletePrompt(id: String) {
        require(id != SystemPrompt.DEFAULT_ID)
        preferences.systemPromptsJson().set(json.encodeToString(prompts().filterNot { it.id == id }))
        if (preferences.activeSystemPromptId().get() == id) {
            preferences.activeSystemPromptId().set(SystemPrompt.DEFAULT_ID)
        }
    }

    fun setActivePrompt(id: String) {
        require(prompts().any { it.id == id })
        preferences.activeSystemPromptId().set(id)
    }

    private inline fun <reified T> decode(value: String, fallback: T): T =
        runCatching { json.decodeFromString<T>(value) }.getOrDefault(fallback)
}
