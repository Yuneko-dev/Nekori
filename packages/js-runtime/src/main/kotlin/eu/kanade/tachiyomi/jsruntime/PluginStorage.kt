package eu.kanade.tachiyomi.jsruntime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class PluginStorage(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(pluginId: String): String {
        requireSafePluginId(pluginId)
        val prefix = databasePrefix(pluginId)
        val values = JSONObject()
        preferences.all.forEach { (key, value) ->
            if (key.startsWith(prefix) && value is String) {
                values.put(key.removePrefix(prefix), value)
            }
        }
        return JSONObject()
            .put("database", values)
            .put("localStorage", preferences.getString("${pluginId}_LocalStorage", "{}"))
            .put("sessionStorage", preferences.getString("${pluginId}_SessionStorage", "{}"))
            .toString()
    }

    fun apply(pluginId: String, mutationJson: String) {
        requireSafePluginId(pluginId)
        val prefix = databasePrefix(pluginId)
        val mutations = JSONArray(mutationJson)
        val editor = preferences.edit()
        for (index in 0 until mutations.length()) {
            val mutation = mutations.getJSONObject(index)
            when (mutation.getString("type")) {
                "set" -> editor.putString(
                    prefix + requireSafeKey(mutation.getString("key")),
                    mutation.getString("value"),
                )
                "delete" -> editor.remove(prefix + requireSafeKey(mutation.getString("key")))
                "clear" ->
                    preferences.all.keys
                        .filter { it.startsWith(prefix) }
                        .forEach { editor.remove(it) }
                "webStorage" -> {
                    editor.putString("${pluginId}_LocalStorage", mutation.getString("localStorage"))
                    editor.putString("${pluginId}_SessionStorage", mutation.getString("sessionStorage"))
                }
                else -> error("Unknown plugin storage mutation: ${mutation.getString("type")}")
            }
        }
        check(editor.commit()) { "Could not persist plugin storage for $pluginId" }
    }

    private fun requireSafePluginId(pluginId: String) {
        require(pluginId.isNotBlank() && ".." !in pluginId && '/' !in pluginId && '\\' !in pluginId) {
            "Unsafe plugin id"
        }
    }

    private fun requireSafeKey(key: String): String {
        require(key.isNotBlank()) { "Plugin storage key must not be blank" }
        return key
    }

    private fun databasePrefix(pluginId: String) = "${pluginId}_DB_"

    private companion object {
        const val PREFERENCES_NAME = "js_plugin_storage"
    }
}
