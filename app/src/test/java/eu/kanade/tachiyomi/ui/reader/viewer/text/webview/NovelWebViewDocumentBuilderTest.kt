package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import androidx.core.os.LocaleListCompat
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class NovelWebViewDocumentBuilderTest {
    @Test
    fun videoScriptsAreAbsentFromOrdinaryTextDocuments() {
        val normal = Jsoup.parse(NovelWebViewDocumentBuilder.assemble(minimalInput()))
        assertTrue(normal.select("script[src$=videojs.min.js], script[src$=hls.min.js]").isEmpty())
    }

    @Test
    fun downloadedLocalVideoStillSuppressesSourcePluginJavaScript() {
        val html = NovelWebViewDocumentBuilder.assemble(
            minimalInput().copy(
                readerConfig = buildJsonObject { put("pluginJavaScript", "window.shouldNotRun = true") },
                chapterDirectives = NovelWebViewChapterDirectives(localVideo = "video.mp4"),
            ),
        )
        val config = Json.parseToJsonElement(Jsoup.parse(html).selectFirst("#nekori-reader-config")!!.data()).jsonObject
        assertEquals("\"\"", config["pluginJavaScript"].toString())
    }

    @Test
    fun documentBootsClassicRuntimeAfterHeadConfigAndBeforeSourceScripts() {
        val html = NovelWebViewDocumentBuilder.assemble(
            minimalInput().copy(
                documentId = "doc-7",
                processed = ProcessedContent(
                    text = "<p>Text</p><script>window.sourceScriptRan = true</script>",
                    isPlainText = false,
                    chapterUrl = null,
                ),
                readerConfig = buildJsonObject {
                    put("mode", "reader")
                    put("pluginJavaScript", "window.custom = true")
                },
            ),
        )
        val document = Jsoup.parse(html)
        val config = Json.parseToJsonElement(document.selectFirst("#nekori-reader-config")!!.data()).jsonObject

        assertEquals("doc-7", config["documentId"]?.toString()?.trim('"'))
        assertEquals("window.custom = true", config["pluginJavaScript"]?.toString()?.trim('"'))
        val configScript = document.selectFirst("#nekori-reader-config")!!
        val runtimeScript = document.selectFirst("script[src='https://nekori.reader/assets/reader.js']")!!
        val sourceScript = document.selectFirst("#nekori-reader script:not([src]):not([type])")!!
        assertTrue(runtimeScript.attr("type").isEmpty())
        assertFalse(runtimeScript.hasAttr("defer"))
        assertFalse(runtimeScript.hasAttr("async"))
        assertTrue(document.head().children().indexOf(configScript) < document.head().children().indexOf(runtimeScript))
        assertTrue(
            html.indexOf("nekori-reader-config") < html.indexOf("src=\"https://nekori.reader/assets/reader.js\""),
        )
        assertTrue(html.indexOf("reader.js") < html.indexOf("window.sourceScriptRan"))
        assertEquals("nekori-reader", document.selectFirst("[data-nekori-reader-root]")?.id())
        assertEquals("nekori-reader", document.selectFirst("[data-nekori-reader-root]")?.className())
        assertEquals("div", document.selectFirst("[data-nekori-reader-root]")?.tagName())
        assertTrue(document.select("#LNReader-chapter").isEmpty())
        assertEquals(document.selectFirst("#nekori-reader"), sourceScript.parent())
        assertEquals(1, document.select("script:not([src]):not([type=application/json])").size)
    }

    @Test
    fun plainTextIsSafeConfigDataAndDoesNotGenerateDomJavascript() {
        val source = "First <paragraph>\n\nSecond & paragraph"
        val html = NovelWebViewDocumentBuilder.assemble(
            minimalInput(text = source, isPlainText = true).copy(
                documentId = "doc-8",
                readerConfig = buildJsonObject { put("mode", "reader") },
            ),
        )
        val document = Jsoup.parse(html)
        val config = Json.parseToJsonElement(document.selectFirst("#nekori-reader-config")!!.data()).jsonObject
        val initialContent = config["initialContent"]!!.jsonObject

        assertEquals("plainText", initialContent["kind"]?.toString()?.trim('"'))
        assertEquals(Json.parseToJsonElement("\"$source\""), initialContent["text"])
        assertTrue(document.select("script:not([src]):not([type=application/json])").isEmpty())
        assertTrue(document.selectFirst("#nekori-reader")!!.text().isEmpty())
    }

    @Test
    fun htmlSourceContentAndEmbeddedStylesRemainInDocument() {
        val html = NovelWebViewDocumentBuilder.assemble(
            minimalInput(
                "<html><head><style>.source { color: red }</style></head><body><p class='source'>Text</p></body></html>",
            ),
        )
        val document = Jsoup.parse(html)

        assertEquals("Text", document.selectFirst("#nekori-reader p")?.text())
        assertTrue(html.contains(".source { color: red }"))
    }

    @Test
    fun ordinaryScrollingHtmlBelongsToItsChapterForTtsAndSelection() {
        val html = NovelWebViewDocumentBuilder.assemble(
            minimalInput("<p>First</p><p>Second</p>").copy(chapter = readerChapter(7, "opaque//chapter")),
        )
        val document = Jsoup.parse(html)
        val chapter = document.selectFirst("nekori-chapter[data-nekori-chapter-id='7']")!!
        assertEquals(listOf("First", "Second"), chapter.select("p").map { it.text() })
        assertTrue(document.select(".nekori-chapter-divider").isEmpty())
        assertTrue(document.select("#nekori-reader > p").isEmpty())
    }

    @Test
    fun errorDocumentUsesTheSameDataBootWithoutInlineExecutableScripts() {
        val html = NovelWebViewDocumentBuilder.assembleErrorDocument(
            "error-1",
            buildJsonObject { put("mode", "error") },
            ThemeUtils.ThemeTokens(":root { --md-sys-color-error: #b3261e; }", "{}"),
        )
        val document = Jsoup.parse(html)
        val config = Json.parseToJsonElement(document.selectFirst("#nekori-reader-config")!!.data()).jsonObject

        assertEquals("error-1", config["documentId"]?.toString()?.trim('"'))
        assertEquals("error", config["mode"]?.toString()?.trim('"'))
        assertTrue(document.select("style").any { it.data().contains("--md-sys-color-error: #b3261e") })
        assertTrue(document.body().hasAttr("data-nekori-error-document"))
        val configElement = document.selectFirst("head > #nekori-reader-config")!!
        val runtimeElement = document.selectFirst(
            "head > script[src='https://nekori.reader/assets/reader.js']:not([type]):not([defer]):not([async])",
        )!!
        assertTrue(
            document.head().children().indexOf(configElement) < document.head().children().indexOf(runtimeElement),
        )
        assertTrue(document.select("#nekori-reader[data-nekori-reader-root]").isNotEmpty())
        assertTrue(document.select("#LNReader-chapter").isEmpty())
        assertTrue(document.select("script:not([src]):not([type=application/json])").isEmpty())
    }

    @Test
    fun customCssIsEscapedAndReaderAssetsUsePrivateOrigin() {
        val html = NovelWebViewDocumentBuilder.assemble(minimalInput(css = "body{} </style><script>bad</script>"))

        assertFalse(html.contains("https://tsundoku.reader"))
        assertTrue(html.contains("https://nekori.reader/assets/reader.css"))
        assertFalse(html.contains("core-player.js"))
        assertFalse(html.contains("core-player.css"))
        assertFalse(html.contains("reader-gestures.js"))
        assertTrue(html.contains("<\\/style>"))
    }

    @Test
    fun playbackLoadsOnlyVendorMediaAssetsAndCompiledReaderRuntime() {
        val html = NovelWebViewDocumentBuilder.assemble(
            minimalInput().copy(chapterDirectives = NovelWebViewChapterDirectives(video = VideoChapter())),
        )
        val document = Jsoup.parse(html)
        val scripts = document.select("script[src]").map { it.attr("src") }

        assertEquals(
            listOf(
                "https://nekori.reader/assets/hls.min.js",
                "https://nekori.reader/assets/videojs.min.js",
                "https://nekori.reader/assets/reader.js",
            ),
            scripts,
        )
        assertTrue(
            document.select("link[rel=stylesheet]").map { it.attr("href") }
                .all { it == "https://nekori.reader/assets/reader.css" },
        )
        assertFalse(html.contains("type=\"module\""))
    }

    @Test
    fun infiniteAndPagedChaptersKeepTheirSemanticWrappers() {
        val infinite = NovelWebViewDocumentBuilder.assemble(
            minimalInput(infiniteScrollEnabled = true).copy(chapter = readerChapter(3, "/c3")),
        )
        val paged = NovelWebViewDocumentBuilder.assemble(
            minimalInput(pagedLayoutEnabled = true).copy(
                chapter = readerChapter(4, "/c4"),
                chapterDirection = NovelContentDirection.RTL,
                chapterLanguage = "ar",
            ),
        )

        assertEquals("nekori-chapter", Jsoup.parse(infinite).selectFirst("nekori-chapter")?.tagName())
        assertTrue(Jsoup.parse(infinite).select(".nekori-chapter-divider").isNotEmpty())
        assertEquals("rtl", Jsoup.parse(paged).selectFirst("nekori-chapter")?.attr("dir"))
        assertEquals("ar", Jsoup.parse(paged).selectFirst("nekori-chapter")?.attr("lang"))
        assertTrue(Jsoup.parse(paged).select(".nekori-chapter-divider").isEmpty())
    }

    @Test
    fun extractBodyRetainsContentAndHeadStyles() {
        val result = NovelWebViewDocumentBuilder.extractBodyOrFallback(
            "<html><head><style>.chapter { color: red }</style></head><body><p>Content</p></body></html>",
        )
        assertTrue(result.contains(".chapter { color: red }"))
        assertTrue(result.contains("<p>Content</p>"))
        assertFalse(result.contains("<body>"))
    }

    @Test
    fun readerLanguageUsesAppLocaleThenSystemLocale() {
        assertEquals("vi", readerLanguageTag(LocaleListCompat.forLanguageTags("vi"), Locale.ENGLISH))
        assertEquals("pt-BR", readerLanguageTag(LocaleListCompat.getEmptyLocaleList(), Locale.forLanguageTag("pt-BR")))
        assertEquals("en", readerLanguageTag(LocaleListCompat.getEmptyLocaleList(), Locale.forLanguageTag("")))
    }

    private fun minimalInput(
        text: String = "<p>Hello</p>",
        isPlainText: Boolean = false,
        css: String = "body { color: black; }",
        infiniteScrollEnabled: Boolean = false,
        pagedLayoutEnabled: Boolean = false,
        blockMedia: Boolean = false,
    ) = NovelWebViewDocumentBuilder.DocumentInput(
        documentId = "doc",
        readerConfig = buildJsonObject { put("mode", "reader") },
        processed = ProcessedContent(text = text, isPlainText = isPlainText, chapterUrl = null),
        chapter = null,
        style = NovelWebViewStyler.CustomStylePayload(css, "", 0xFFFFFFFF.toInt()),
        themeTokens = ThemeUtils.ThemeTokens(cssVariables = ":root {}", jsObject = "{}"),
        infiniteScrollEnabled = infiniteScrollEnabled,
        pagedLayoutEnabled = pagedLayoutEnabled,
        blockMedia = blockMedia,
    )

    private fun readerChapter(id: Long, path: String) = ReaderChapter(
        ChapterImpl().apply {
            this.id = id
            url = path
            name = "Chapter $id"
            chapter_number = id.toFloat()
        },
    )
}
