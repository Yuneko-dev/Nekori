package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import kotlinx.serialization.json.JsonObject

/** Data-only operation executed by the packaged reader runtime. */
internal data class ReaderCommand(
    val method: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)
