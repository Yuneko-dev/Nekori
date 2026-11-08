package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object NovelWebViewChapterDomScripts {
    fun append(chapter: ScriptChapter, processed: ProcessedContent): ReaderCommand =
        ReaderCommand(
            method = "chapter.append",
            payload = buildJsonObject {
                put(
                    "chapter",
                    buildJsonObject {
                        put("id", chapter.id.toString())
                        put("name", chapter.name)
                        put("number", chapter.number)
                        put("path", chapter.path)
                        put("absoluteUrl", chapter.absoluteUrl)
                        put("direction", chapter.direction.htmlValue)
                        put("language", chapter.language)
                    },
                )
                put(
                    "content",
                    buildJsonObject {
                        if (processed.isPlainText) {
                            put("kind", "plainText")
                            put("text", processed.text)
                        } else {
                            put("kind", "html")
                            put("html", NovelWebViewDocumentBuilder.extractBodyOrFallback(processed.text))
                        }
                    },
                )
            },
        )

    data class ScriptChapter(
        val id: Long,
        val name: String,
        val number: Float,
        val path: String,
        val absoluteUrl: String,
        val direction: NovelContentDirection,
        val language: String,
    )
}
