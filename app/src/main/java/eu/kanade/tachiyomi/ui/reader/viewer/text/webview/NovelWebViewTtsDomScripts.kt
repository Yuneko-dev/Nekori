package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object NovelWebViewTtsDomScripts {
    fun extractText(chapterId: Long?) = command("tts.extractText") {
        putChapterId(chapterId)
    }

    fun firstVisibleParagraph(chapterId: Long?) = command("tts.firstVisibleParagraph") {
        putChapterId(chapterId)
    }

    fun taggedParagraph(chapterId: Long?) = command("tts.taggedParagraph") {
        putChapterId(chapterId)
    }

    fun highlight(
        chapterId: Long?,
        paragraphIndex: Int,
        backgroundColor: String,
        textColor: String,
        style: String,
        keepInView: Boolean,
    ) = ReaderCommand(
        "tts.highlight",
        buildJsonObject {
            putChapterId(chapterId)
            put("paragraphIndex", paragraphIndex.coerceAtLeast(0))
            put("backgroundColor", backgroundColor)
            put("textColor", textColor)
            put("style", style)
            put("keepInView", keepInView)
        },
    )

    val CLEAR_HIGHLIGHT = ReaderCommand("tts.clearHighlight")

    private fun command(method: String, payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        ReaderCommand(method, buildJsonObject(payload))

    private fun kotlinx.serialization.json.JsonObjectBuilder.putChapterId(chapterId: Long?) {
        if (chapterId == null) {
            put("chapterId", kotlinx.serialization.json.JsonNull)
        } else {
            put("chapterId", chapterId.toString())
        }
    }
}
