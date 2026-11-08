package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NovelWebViewReadingCommandsTest {

    @Test
    fun progressAndNavigationAreStructuredCommands() {
        val restore = NovelWebViewReadingCommands.restoreVertical(0.45f, 9)
        val reveal = NovelWebViewReadingCommands.revealChapter(1002)
        val seek = NovelWebViewReadingCommands.seekVertical(140)

        assertEquals("scroll.restoreVertical", restore.method)
        assertEquals(JsonPrimitive(0.45f), restore.payload["progress"])
        assertEquals(JsonPrimitive(9), restore.payload["token"])
        assertEquals(JsonPrimitive("1002"), reveal.payload["chapterId"])
        assertEquals(JsonPrimitive(100), seek.payload["percent"])
    }

    @Test
    fun layoutAndSelectionCommandsUseTheSharedCommandVocabulary() {
        assertEquals("layout.prepareTurn", NovelWebViewReadingCommands.prepareCurl(-1).method)
        assertEquals("layout.finishTurn", NovelWebViewReadingCommands.finishCurl(true).method)
        assertEquals("selection.read", NovelWebViewReadingCommands.readSelection().method)
        assertEquals("selection.readAnchor", NovelWebViewReadingCommands.readSelectionAnchor().method)
        assertEquals("selection.clear", NovelWebViewReadingCommands.clearSelection().method)
        assertEquals("selection.blur", NovelWebViewReadingCommands.blurSelection().method)
    }
}
