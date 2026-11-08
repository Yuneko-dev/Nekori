@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.source.model.Page
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.novel.TDMR

class NovelReaderEditPolicyTest {
    @Test
    fun `editing reports localized reasons for error loading and missing chapter`() {
        assertEquals(TDMR.strings.error_cant_edit_error_page, novelEditBlockedReason(true, false, false))
        assertEquals(TDMR.strings.error_cant_edit_while_loading, novelEditBlockedReason(false, false, true))
        assertEquals(TDMR.strings.error_cant_edit_no_chapter, novelEditBlockedReason(false, true, false))
        assertNull(novelEditBlockedReason(false, true, true))
    }

    @Test
    fun `a loaded empty page forces a fresh source request`() {
        for (text in listOf(null, "", "  ")) {
            assertTrue(shouldRetryFromSource(page(Page.State.Ready, text)))
        }
    }

    @Test
    fun `page errors missing pages and processing errors retry the available local copy`() {
        assertFalse(shouldRetryFromSource(null))
        assertFalse(shouldRetryFromSource(page(Page.State.Error(Exception("failed")), null)))
        assertFalse(shouldRetryFromSource(page(Page.State.Ready, "<p>Stored chapter</p>")))
    }

    private fun page(state: Page.State, html: String?) = Page(0, "/chapter").apply {
        status = state
        text = html
    }
}
