package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

/** The id every profile list reserves for the entry synthesized from the globally active settings. */
const val DEFAULT_PROFILE_ID = "default"

/** A named, user-managed configuration that some purpose can be pointed at. */
interface Profile {
    val id: String
    val name: String

    /** The synthesized default is the fallback every assignment relies on, so it cannot be removed. */
    val deletable: Boolean get() = id != DEFAULT_PROFILE_ID
}

/** Something a profile can be assigned to. [key] is what gets serialized, so it must stay stable. */
interface Purpose {
    val key: String
}

/**
 * An AI task that is not translation. Each one supplies its own system prompt and output contract;
 * what the user configures is only which provider runs it and which guidelines it carries.
 *
 * Adding a task is one constant here plus one assignment row in the AI settings.
 */
@Serializable
enum class AiTaskPurpose(override val key: String) : Purpose {
    /** Summarizes the chapter currently open in the reader. */
    CHAPTER_SUMMARY("chapter_summary"),
}

/**
 * A named provider-and-guidelines pair for AI tasks.
 *
 * Carries no engine, prompt, language or output format: those belong to the task, not to the user's
 * configuration of it. A null id means "use the globally active one".
 */
@Serializable
data class AiTaskProfile(
    override val id: String,
    override val name: String,
    val providerId: String? = null,
    val guidelinesId: String? = null,
) : Profile
