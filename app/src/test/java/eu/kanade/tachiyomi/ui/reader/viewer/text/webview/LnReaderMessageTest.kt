package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LnReaderMessageTest {

    @Test
    fun `supported reader messages parse and video progress is clamped`() {
        assertEquals(LnReaderMessage.Save(100), LnReaderMessage.parse("""{"type":"save","data":180}"""))
        assertEquals(LnReaderMessage.Save(0), LnReaderMessage.parse("""{"type":"save","data":-2}"""))
        assertEquals(LnReaderMessage.Refetch, LnReaderMessage.parse("""{"type":"refetch"}"""))
        assertEquals(LnReaderMessage.Next, LnReaderMessage.parse("""{"type":"next"}"""))
    }

    @Test
    fun `malformed and unsupported messages are ignored`() {
        assertNull(LnReaderMessage.parse("not json"))
        assertNull(LnReaderMessage.parse("""{"type":"save","data":"80"}"""))
        assertNull(LnReaderMessage.parse("""{"type":"unknown"}"""))
        assertNull(LnReaderMessage.parse("""[]"""))
    }
}
