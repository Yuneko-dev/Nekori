package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NovelReaderProtocolTest {
    @Test
    fun `envelopes retain decimal identifiers and reject stale sessions`() {
        val wire = """{"protocolVersion":1,"documentId":"current","kind":"event","method":"progressChanged","payload":
            |{"chapterId":"9223372036854775807","progress":52}}
        """.trimMargin()
        val decoded = NovelReaderProtocol.decode(wire, "current")
        assertEquals("progressChanged", decoded?.method)
        assertEquals("9223372036854775807", decoded?.payload?.get("chapterId")?.toString()?.trim('"'))
        assertNull(NovelReaderProtocol.decode(wire, "previous"))
    }

    @Test
    fun `invalid message shapes and versions are rejected`() {
        listOf(
            "not JSON",
            "[]",
            """{"protocolVersion":2,"documentId":"current","kind":"boot"}""",
            """{"protocolVersion":"1","documentId":"current","kind":"boot"}""",
            """{"protocolVersion":1,"documentId":"current","kind":"event","payload":[]}""",
            """{"protocolVersion":1,"documentId":"current","kind":"eval","method":"arbitrary"}""",
        ).forEach { assertNull(NovelReaderProtocol.decode(it, "current"), it) }
    }

    @Test
    fun `origin comparison uses scheme host and effective port only`() {
        assertEquals("https://source.example", NovelReaderProtocol.origin("https://source.example/book?q=1"))
        assertEquals("https://source.example", NovelReaderProtocol.origin("https://SOURCE.example:443/book"))
        assertEquals("http://source.example:8080", NovelReaderProtocol.origin("http://source.example:8080/book"))
        assertNull(NovelReaderProtocol.origin("file:///book.html"))
        assertNull(NovelReaderProtocol.origin("https://user:password@source.example/"))
    }

    @Test
    fun `the exact minimum provider build is accepted`() {
        assertEquals(false, NovelReaderProtocol.supportsVersion("138.0.7204.180"))
        assertEquals(true, NovelReaderProtocol.supportsVersion("138.0.7204.181"))
        assertEquals(true, NovelReaderProtocol.supportsVersion("139.0.0.0"))
        assertEquals(false, NovelReaderProtocol.supportsVersion(null))
    }
}
