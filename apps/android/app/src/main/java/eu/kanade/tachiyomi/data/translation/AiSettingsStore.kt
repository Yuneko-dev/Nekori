package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.preference.ContentPreferencesLock
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AiExecutionConfig
import tachiyomi.domain.translation.model.NovelAiTask
import tachiyomi.domain.translation.model.NovelGuidelinesAssignment
import tachiyomi.domain.translation.model.NovelGuidelinesAssignments
import tachiyomi.domain.translation.model.NovelGuidelinesChoice
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
    @Volatile
    private var guidelinesCache: Pair<String, List<UserGuidelines>>? = null

    @Volatile
    private var assignmentsCache: Pair<String, List<NovelGuidelinesAssignment>>? = null

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
        val raw = preferences.userGuidelinesJson().get()
        return guidelinesCache?.takeIf { it.first == raw }?.second
            ?: runCatching { UserGuidelines.decode(raw) }
                .getOrElse { throw IllegalArgumentException("Invalid saved guidelines library") }
                .also { guidelinesCache = raw to it }
    }

    fun saveGuidelines(value: UserGuidelines, originalJson: String? = null): Boolean = synchronized(
        ContentPreferencesLock,
    ) {
        require(value.id.isNotBlank() && value.name.isNotBlank())
        if (originalJson != null && originalJson != preferences.userGuidelinesJson().get()) return@synchronized false
        val all = guidelines().toMutableList()
        val index = all.indexOfFirst { it.id == value.id }
        if (index >= 0) all[index] = value else all += value
        preferences.userGuidelinesJson().set(json.encodeToString(all))
        true
    }

    fun deleteGuidelines(id: String, originalJson: String? = null): Boolean = synchronized(ContentPreferencesLock) {
        require(id != UserGuidelines.DEFAULT_ID)
        if (originalJson != null && originalJson != preferences.userGuidelinesJson().get()) return@synchronized false
        preferences.userGuidelinesJson().set(json.encodeToString(guidelines().filterNot { it.id == id }))
        true
    }

    fun assignments(): List<NovelGuidelinesAssignment> {
        val raw = preferences.novelGuidelinesJson().get()
        return assignmentsCache?.takeIf { it.first == raw }?.second
            ?: runCatching { NovelGuidelinesAssignments.decode(raw) }
                .getOrElse { throw IllegalArgumentException("Invalid saved novel guidelines") }
                .also { assignmentsCache = raw to it }
    }

    fun choice(target: ReplacementTarget, task: NovelAiTask): NovelGuidelinesChoice? =
        assignments().firstOrNull { it.target == target && it.task == task }?.choice

    /** Null removes the override; the original array check protects edits to other novels too. */
    fun saveChoice(
        target: ReplacementTarget,
        task: NovelAiTask,
        choice: NovelGuidelinesChoice?,
        originalJson: String,
    ): Boolean = synchronized(ContentPreferencesLock) {
        if (preferences.novelGuidelinesJson().get() != originalJson) return@synchronized false
        require(target.path.isNotBlank())
        choice?.validate()
        val all = assignments().toMutableList()
        val index = all.indexOfFirst { it.target == target && it.task == task }
        if (choice == null) {
            if (index >= 0) all.removeAt(index)
        } else {
            val value = NovelGuidelinesAssignment(target, task, choice)
            if (index >= 0) all[index] = value else all += value
        }
        preferences.novelGuidelinesJson().set(NovelGuidelinesAssignments.encode(all))
        true
    }

    /** Read only dependencies the effective choice needs; custom text does not depend on the library. */
    fun effectiveGuidelines(
        globalId: String?,
        target: ReplacementTarget? = null,
        task: NovelAiTask = NovelAiTask.TRANSLATION,
    ): String {
        val choice = target?.let { choice(it, task) }
        return when (choice?.mode) {
            "none" -> ""
            "custom" -> requireNotNull(choice.text)
            "shared" -> guidelines().resolve(choice.guidelinesId).guidelines
            null -> if (globalId.isNullOrBlank()) "" else guidelines().resolve(globalId).guidelines
            else -> error("Unsupported novel guidelines choice")
        }
    }

    /**
     * Everything one LLM call needs, for a task that named a provider and guidelines - or named
     * neither, and gets the first provider with no guidelines.
     *
     * The API key is read here from private preferences and never leaves in a loggable form.
     */
    fun resolveConfig(
        providerId: String?,
        guidelinesId: String?,
        target: ReplacementTarget? = null,
        task: NovelAiTask = NovelAiTask.TRANSLATION,
    ): AiExecutionConfig {
        val provider = providers().resolve(providerId)
        return AiExecutionConfig(
            provider = provider,
            apiKey = provider?.let { apiKey(it.id) }.orEmpty(),
            guidelines = effectiveGuidelines(guidelinesId, target, task),
        )
    }

    private inline fun <reified T> decode(value: String, fallback: T): T =
        runCatching { json.decodeFromString<T>(value) }.getOrDefault(fallback)
}
