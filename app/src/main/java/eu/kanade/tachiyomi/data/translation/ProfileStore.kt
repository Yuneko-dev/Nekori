package eu.kanade.tachiyomi.data.translation

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.model.DEFAULT_PROFILE_ID
import tachiyomi.domain.translation.model.Profile
import tachiyomi.domain.translation.model.Purpose

/**
 * Named profiles plus which purpose uses which, both stored as JSON in preferences.
 *
 * The default profile is synthesized on read rather than written by an upgrade step, which is what
 * makes profiles migration-free: before the user touches anything it mirrors the globally active
 * settings, so behaviour is unchanged. Unreadable JSON degrades to that same default rather than
 * throwing - a corrupt preference must not make the feature unusable.
 */
abstract class ProfileStore<P : Profile, K : Purpose>(
    private val json: Json,
    private val purposes: List<K>,
) {
    protected abstract val serializer: KSerializer<P>
    protected abstract val profilesPreference: Preference<String>
    protected abstract val assignmentsPreference: Preference<String>

    /** The profile to use before the user has created any, built from the active settings. */
    protected abstract fun synthesizeDefault(): P

    /** All profiles, with the default guaranteed to exist and to come first. */
    fun profiles(): List<P> {
        val stored = decodeProfiles()
        return listOf(stored.firstOrNull { it.id == DEFAULT_PROFILE_ID } ?: synthesizeDefault()) +
            stored.filterNot { it.id == DEFAULT_PROFILE_ID }
    }

    fun profile(id: String): P? = profiles().firstOrNull { it.id == id }

    /** The profile assigned to [purpose], falling back to the default when unassigned or dangling. */
    fun profileFor(purpose: K): P {
        val all = profiles()
        val assignedId = assignments()[purpose]
        return all.firstOrNull { it.id == assignedId } ?: all.first()
    }

    fun assign(purpose: K, profileId: String) {
        writeAssignments(assignments() + (purpose to profileId))
    }

    fun save(profile: P) {
        require(profile.id.isNotBlank()) { "Profile id must not be blank" }
        require(profile.id == DEFAULT_PROFILE_ID || profile.name.isNotBlank()) {
            "Profile name must not be blank"
        }
        val profiles = profiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) profiles[index] = profile else profiles += profile
        profilesPreference.set(json.encodeToString(ListSerializer(serializer), profiles))
    }

    fun delete(id: String) {
        require(id != DEFAULT_PROFILE_ID) { "The default profile cannot be deleted" }
        profilesPreference.set(
            json.encodeToString(ListSerializer(serializer), profiles().filterNot { it.id == id }),
        )
        // Drop assignments pointing at it so profileFor() falls back to the default instead of
        // silently resolving nothing.
        writeAssignments(assignments().filterValues { it != id })
    }

    private fun assignments(): Map<K, String> = runCatching {
        json.decodeFromString(assignmentSerializer, assignmentsPreference.get())
    }.getOrDefault(emptyMap())
        .mapNotNull { (key, id) -> purposes.firstOrNull { it.key == key }?.let { it to id } }
        .toMap()

    private fun writeAssignments(value: Map<K, String>) {
        assignmentsPreference.set(json.encodeToString(assignmentSerializer, value.mapKeys { it.key.key }))
    }

    private fun decodeProfiles(): List<P> = runCatching {
        json.decodeFromString(ListSerializer(serializer), profilesPreference.get())
    }.getOrDefault(emptyList())

    private val assignmentSerializer = MapSerializer(String.serializer(), String.serializer())
}
