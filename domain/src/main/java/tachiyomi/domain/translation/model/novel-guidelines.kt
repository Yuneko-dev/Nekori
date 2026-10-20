@file:Suppress("ktlint:standard:filename")

package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** Portable identity; source paths are opaque and must remain verbatim. */
@Serializable
data class NovelTarget(val source: Long, val path: String)

@Serializable
enum class NovelAiTask {
    TRANSLATION,
    CHAPTER_SUMMARY,
}

@Serializable
data class NovelGuidelinesChoice(
    val mode: String,
    val guidelinesId: String? = null,
    val text: String? = null,
) {
    fun validate() {
        require(
            when (mode) {
                "none" -> guidelinesId == null && text == null
                "shared" -> !guidelinesId.isNullOrBlank() && text == null
                "custom" -> text != null && guidelinesId == null
                else -> false
            },
        ) { "Unsupported or invalid novel guidelines choice" }
    }
}

@Serializable
data class NovelGuidelinesAssignment(
    val target: NovelTarget,
    val task: NovelAiTask,
    val choice: NovelGuidelinesChoice,
)

object NovelGuidelinesAssignments {
    const val PREFERENCE_KEY = "novel_ai_guidelines"

    fun decode(raw: String): List<NovelGuidelinesAssignment> {
        val array = requireNotNull(Json.parseToJsonElement(raw) as? JsonArray) {
            "Novel guidelines must be an array"
        }
        array.forEach { element ->
            val entry = requireNotNull(element as? JsonObject) { "Invalid novel guidelines assignment" }
            val target = requireNotNull(entry["target"] as? JsonObject) { "Invalid novel target" }
            val source = requireNotNull(target["source"] as? JsonPrimitive) { "Invalid novel source" }
            require(!source.isString) { "Novel source must be a JSON number" }
        }
        val assignments = Json.decodeFromJsonElement<List<NovelGuidelinesAssignment>>(array)
        assignments.forEachIndexed { index, assignment ->
            val fields = ((array[index] as JsonObject).getValue("choice") as JsonObject).keys
            val expected = when (assignment.choice.mode) {
                "none" -> setOf("mode")
                "shared" -> setOf("mode", "guidelinesId")
                "custom" -> setOf("mode", "text")
                else -> emptySet()
            }
            require(fields == expected) { "Unsupported or invalid novel guidelines choice" }
        }
        validate(assignments)
        return assignments
    }

    fun encode(assignments: List<NovelGuidelinesAssignment>): String {
        validate(assignments)
        return Json.encodeToString(assignments)
    }

    private fun validate(assignments: List<NovelGuidelinesAssignment>) {
        require(assignments.all { it.target.path.isNotBlank() }) { "Invalid novel target" }
        require(assignments.map { it.target to it.task }.distinct().size == assignments.size) {
            "Duplicate novel guidelines assignment"
        }
        assignments.forEach { it.choice.validate() }
    }
}
