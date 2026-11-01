@file:Suppress("ktlint:standard:filename")

package eu.kanade.domain.source.interactor

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import logcat.LogPriority
import logcat.logcat

internal object FilterPresetState {
    fun serialize(filters: List<Filter<*>>): String = entries(filters).toString()

    private fun entries(filters: List<Filter<*>>): JsonArray = JsonArray(
        filters.mapIndexed { index, filter ->
            buildJsonObject {
                put("index", index)
                put("name", filter.name)
                put("kind", kind(filter))
                put(
                    "state",
                    when (filter) {
                        is Filter.CheckBox -> JsonPrimitive(filter.state)
                        is Filter.TriState -> JsonPrimitive(filter.state)
                        is Filter.Text -> JsonPrimitive(filter.state)
                        is Filter.Select<*> -> JsonPrimitive(filter.state)
                        is Filter.Sort -> buildJsonObject {
                            filter.state?.let {
                                put("index", it.index)
                                put("ascending", it.ascending)
                            }
                        }
                        is Filter.Group<*> -> entries(filter.state.filterIsInstance<Filter<*>>())
                        else -> JsonNull
                    },
                )
                if (filter is Filter.Select<*>) {
                    filter.values.getOrNull(filter.state)?.toString()?.let { put("value", it) }
                }
            }
        },
    )

    fun restore(filters: List<Filter<*>>, json: String) {
        val states = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return
        restoreEntries(filters, states)
    }

    private fun restoreEntries(filters: List<Filter<*>>, entries: JsonArray) {
        val used = mutableSetOf<Int>()
        val saved = entries.filterIsInstance<JsonObject>()
        entries.forEach { element ->
            try {
                val entry = element as? JsonObject ?: return@forEach
                val state = entry["state"] ?: return@forEach
                val candidates = filters.indices.filter { index ->
                    index !in used && matches(filters[index], entry)
                }
                // Identity must be unique on both sides, including when one duplicate was removed.
                val index = candidates.singleOrNull() ?: return@forEach
                if (saved.count { matches(filters[index], it) } != 1) return@forEach
                if (apply(filters[index], entry, state)) used += index
            } catch (_: Exception) {
                logcat(LogPriority.WARN) { "FilterPresets: Skipped invalid saved filter state" }
            }
        }
    }

    private fun matches(filter: Filter<*>, entry: JsonObject): Boolean {
        if (filter.name != entry["name"].string()) return false
        if ("kind" in entry && kind(filter) != entry["kind"].string()) return false
        val state = entry["state"] ?: return false
        return accepts(filter, entry, state)
    }

    private fun apply(filter: Filter<*>, entry: JsonObject, state: JsonElement): Boolean {
        when (filter) {
            is Filter.CheckBox -> filter.state = state.boolean() ?: return false
            is Filter.TriState -> filter.state = state.integer()?.takeIf { it in 0..2 } ?: return false
            is Filter.Text -> filter.state = state.string() ?: return false
            is Filter.Select<*> -> filter.state = selectIndex(filter, entry, state) ?: return false
            is Filter.Sort -> {
                val sort = state as? JsonObject ?: return false
                if (sort.isEmpty()) {
                    filter.state = null
                } else {
                    val index = sort["index"].integer()?.takeIf { it in filter.values.indices } ?: return false
                    val ascending = sort["ascending"].boolean() ?: return false
                    filter.state = Filter.Sort.Selection(index, ascending)
                }
            }
            is Filter.Group<*> -> restoreEntries(filter.state.filterIsInstance<Filter<*>>(), state as JsonArray)
            else -> return false
        }
        return true
    }

    private fun selectIndex(filter: Filter.Select<*>, entry: JsonObject, state: JsonElement): Int? {
        val index = state.integer()?.takeIf { it in filter.values.indices }
        val name = entry["value"].string() ?: return if ("value" in entry) null else index
        if (index != null && filter.values[index].toString() == name) return index
        val matches = filter.values.indices.filter { filter.values[it].toString() == name }
        return matches.singleOrNull() ?: index?.takeIf { identityName.matches(name) }
    }

    private fun accepts(filter: Filter<*>, entry: JsonObject, state: JsonElement): Boolean = when (filter) {
        is Filter.CheckBox -> state.boolean() != null
        is Filter.TriState -> state.integer() in 0..2
        is Filter.Text -> state.string() != null
        is Filter.Select<*> -> state.integer() != null && selectIndex(filter, entry, state) != null
        is Filter.Sort -> state is JsonObject && (
            state.isEmpty() ||
                (state["index"].integer() in filter.values.indices && state["ascending"].boolean() != null)
            )
        is Filter.Group<*> -> state is JsonArray
        else -> false
    }

    private fun kind(filter: Filter<*>): String = when (filter) {
        is Filter.CheckBox -> "checkbox"
        is Filter.TriState -> "tristate"
        is Filter.Text -> "text"
        is Filter.Select<*> -> "select"
        is Filter.Sort -> "sort"
        is Filter.Group<*> -> "group"
        else -> "stateless"
    }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.integer(): Int? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    private fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private val identityName = Regex("""[\w.$]+@[0-9a-f]+""")
}
