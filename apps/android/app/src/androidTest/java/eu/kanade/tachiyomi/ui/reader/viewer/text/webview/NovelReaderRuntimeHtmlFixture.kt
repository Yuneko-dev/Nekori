package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Minimal production-shaped document shell that loads the packaged runtime from its asset origin. */
internal object NovelReaderRuntimeHtmlFixture {
    fun build(documentId: String, chapterId: String, nextChapterId: String, sourceText: String): String {
        val config = buildJsonObject {
            put("documentId", documentId)
            put("mode", "reader")
            putJsonObject("novel") {
                put("id", "1")
                put("name", "Fixture")
                put("path", "/novel")
            }
            putJsonObject("chapter") {
                put("id", chapterId)
                put("name", "Chapter")
                put("path", "/chapter")
                put("progress", 0)
            }
            put("nextChapter", JsonNull)
            put(
                "chapters",
                buildJsonArray {
                    add(chapter(chapterId, "Chapter"))
                    add(chapter(nextChapterId, "Next"))
                },
            )
            put("strings", buildJsonObject { })
            put("autoSaveInterval", 5000)
            put("pluginJavaScript", "window.__sourcePluginRan = true")
            putJsonObject("runtime") {
                put("editing", false)
                put("infinite", false)
                put("textSelectionBlocked", false)
                put("forcedLowercase", false)
                put("menuVisible", true)
                put("immersive", false)
                put("ttsState", "stopped")
                put("loadingChapter", false)
                put("loadingNext", false)
                put("noMoreChapters", true)
            }
            putJsonObject("initialContent") {
                put("kind", "plainText")
                put("text", sourceText)
            }
        }
        val safeConfig = config.toString().replace("<", "\\u003c")
        return """
            <!doctype html>
            <html>
            <head>
                <meta charset="utf-8">
                <script id="nekori-reader-config" type="application/json">$safeConfig</script>
                <script src="https://nekori.reader/assets/reader.js"></script>
            </head>
            <body>
                <div id="nekori-reader" data-nekori-reader-root>
                    <nekori-chapter data-nekori-chapter-id="$chapterId"></nekori-chapter>
                    <nekori-chapter data-nekori-chapter-id="$nextChapterId">
                        <p id="selection-text">Select this phrase</p>
                        <p>Next chapter paragraph</p>
                    </nekori-chapter>
                </div>
                <script>window.__sourceSawRuntime=!!window.nekori;</script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun chapter(id: String, name: String) = buildJsonObject {
        put("id", id)
        put("name", name)
        put("path", "/$id")
        put("progress", 0)
    }
}
