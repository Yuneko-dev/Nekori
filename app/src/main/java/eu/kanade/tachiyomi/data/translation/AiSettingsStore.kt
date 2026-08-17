package eu.kanade.tachiyomi.data.translation

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AiExecutionConfig
import tachiyomi.domain.translation.model.UserGuidelines
import tachiyomi.domain.translation.model.normalized
import tachiyomi.domain.translation.model.resolve
import tachiyomi.domain.translation.model.validateCustomHeaders
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AiSettingsStore(
    private val preferences: TranslationPreferences = Injekt.get(),
    private val json: Json = Injekt.get(),
) {
    fun providers(): List<AIProvider> = decode(preferences.aiProvidersJson().get(), emptyList())

    fun saveProvider(provider: AIProvider, apiKey: String? = null) {
        val value = provider.normalized()
        require(
            value.id.isNotBlank() && value.alias.isNotBlank() &&
                value.endpoint.isNotBlank() && value.model.isNotBlank(),
        )
        require(value.endpoint.toHttpUrlOrNull() != null) { "Invalid provider endpoint" }
        require(validateCustomHeaders(value.customHeaders).isValid)
        val providers = providers().toMutableList()
        val index = providers.indexOfFirst { it.id == value.id }
        if (index >= 0) providers[index] = value else providers += value
        preferences.aiProvidersJson().set(json.encodeToString(providers))
        apiKey?.let { preferences.aiProviderApiKey(value.id).set(it) }
    }

    fun deleteProvider(id: String) {
        preferences.aiProvidersJson().set(json.encodeToString(providers().filterNot { it.id == id }))
        preferences.aiProviderApiKey(id).delete()
    }

    fun apiKey(providerId: String): String = preferences.aiProviderApiKey(providerId).get()

    fun guidelines(): List<UserGuidelines> {
        val decoded = decode(preferences.userGuidelinesJson().get(), listOf(UserGuidelines.DEFAULT))
        return if (decoded.any { it.id == UserGuidelines.DEFAULT_ID }) {
            decoded
        } else {
            listOf(UserGuidelines.DEFAULT) + decoded
        }
    }

    fun saveGuidelines(value: UserGuidelines) {
        require(value.id.isNotBlank() && value.name.isNotBlank())
        val all = guidelines().toMutableList()
        val index = all.indexOfFirst { it.id == value.id }
        if (index >= 0) all[index] = value else all += value
        preferences.userGuidelinesJson().set(json.encodeToString(all))
    }

    fun deleteGuidelines(id: String) {
        require(id != UserGuidelines.DEFAULT_ID)
        preferences.userGuidelinesJson().set(json.encodeToString(guidelines().filterNot { it.id == id }))
    }

    /**
     * Everything one LLM call needs, for a task that named a provider and guidelines - or named
     * neither, and gets the first provider with no guidelines.
     *
     * The API key is read here from private preferences and never leaves in a loggable form.
     */
    fun resolveConfig(providerId: String?, guidelinesId: String?): AiExecutionConfig {
        val provider = providers().resolve(providerId)
        return AiExecutionConfig(
            provider = provider,
            apiKey = provider?.let { apiKey(it.id) }.orEmpty(),
            guidelines = guidelines().resolve(guidelinesId).guidelines,
        )
    }

    private inline fun <reified T> decode(value: String, fallback: T): T =
        runCatching { json.decodeFromString<T>(value) }.getOrDefault(fallback)
}
