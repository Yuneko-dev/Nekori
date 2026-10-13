package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.buildChapterJson
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.buildChaptersJson
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.buildNovelJson
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.htmlAttributeEscape
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.resolveEpubChapterUrl
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.resolveWebViewBaseUrl
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.toAbsoluteChapterUrl
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewChapterMetaTest {

    @Test
    fun chapterJsonKeepsIdsAsStringsAndPluginPathOpaque() {
        val chapter = readerChapter(17, "path//with#opaque").apply { chapter.last_page_read = 23 }
        val json = buildChapterJson(chapter, "https://n.com")

        assertEquals(JsonPrimitive("17"), json["id"])
        assertEquals(JsonPrimitive("path//with#opaque"), json["path"])
        assertEquals(JsonPrimitive(23), json["progress"])
        assertEquals(JsonPrimitive("https://n.com/path//with#opaque"), json["url"])
    }

    @Test
    fun missingChapterHasEmptyStringIdAndNoNumericSentinel() {
        val json = buildChapterJson(null, "https://n.com")

        assertEquals(JsonPrimitive(""), json["id"])
        assertEquals(JsonPrimitive(""), json["name"])
        assertEquals(JsonPrimitive(""), json["path"])
        assertEquals(JsonPrimitive(0), json["progress"])
        assertEquals(null, json["number"])
    }

    @Test
    fun novelMetadataUsesStringIdentityAndRawPath() {
        val novel = buildNovelJson("source:17", "Name", "folder//novel#path")

        assertEquals(JsonPrimitive("source:17"), novel["id"])
        assertEquals(JsonPrimitive("folder//novel#path"), novel["path"])
    }

    @Test
    fun chapterListIsStructuredJsonRatherThanSerializedScriptText() {
        val chapters = buildChaptersJson(listOf(readerChapter(1, "one"), readerChapter(2, "two")), null)

        assertEquals(2, chapters.size)
        assertEquals(JsonPrimitive("1"), chapters[0].let { (it as kotlinx.serialization.json.JsonObject)["id"] })
    }

    @Test
    fun relativeChapterUrlResolvesForExternalNavigationWithoutChangingStoredPath() {
        assertEquals("https://example.com/dir/ch1", toAbsoluteChapterUrl("ch1", "https://example.com/dir/"))
        assertEquals("/chapter", toAbsoluteChapterUrl("/chapter", null))
    }

    @Test
    fun privateReaderOriginAndEpubNavigationRemainSupported() {
        assertEquals("https://nekori.reader/", resolveWebViewBaseUrl("/foo", null))
        val nav = mihon.core.archive.NOVEL_EPUB_CHAPTER_SCHEME +
            java.net.URLEncoder.encode("OEBPS/Text/chapter.xhtml#start", "UTF-8")
        assertEquals("Book.epub#OEBPS/Text/chapter.xhtml#start", resolveEpubChapterUrl("Book.epub#old", nav))
        val traversal = mihon.core.archive.NOVEL_EPUB_CHAPTER_SCHEME +
            java.net.URLEncoder.encode("../secret", "UTF-8")
        assertNull(resolveEpubChapterUrl("Book.epub#old", traversal))
    }

    @Test
    fun htmlAttributeEscapeProtectsStaticChapterAttributes() {
        assertEquals("&amp;&quot;&#39;&lt;&gt;", "&\"'<>".htmlAttributeEscape())
        assertTrue(NovelWebViewChapterMeta.CHAPTER_TAG_NAME.startsWith("nekori-"))
    }

    private fun readerChapter(id: Long, path: String) = ReaderChapter(
        ChapterImpl().apply {
            this.id = id
            name = "Chapter $id"
            url = path
            chapter_number = id.toFloat()
        },
    )
}
