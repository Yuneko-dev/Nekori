package eu.kanade.tachiyomi.data.backup.restore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LNReaderBackupVersionTest {
    @Test
    fun `rejects backups older than 2 0 2`() {
        assertFalse(isSupportedLnReaderVersion("2.0.1"))
        assertFalse(isSupportedLnReaderVersion("1.9.9"))
        assertFalse(isSupportedLnReaderVersion("not-a-version"))
    }

    @Test
    fun `accepts 2 0 2 and newer including prerelease suffixes`() {
        assertTrue(isSupportedLnReaderVersion("2.0.2"))
        assertTrue(isSupportedLnReaderVersion("2.0.3-beta.1"))
        assertTrue(isSupportedLnReaderVersion("3.0.0+build.7"))
    }

    @Test
    fun `archive paths reject traversal absolute drive and empty segments`() {
        assertThrows<IllegalArgumentException> { validateLnReaderArchivePath("../secret", false) }
        assertThrows<IllegalArgumentException> { validateLnReaderArchivePath("/absolute", false) }
        assertThrows<IllegalArgumentException> { validateLnReaderArchivePath("C:/secret", false) }
        assertThrows<IllegalArgumentException> { validateLnReaderArchivePath("a//b", false) }
        assertThrows<IllegalArgumentException> { validateLnReaderArchivePath("a\\b", false) }
        assertEquals("a/b", validateLnReaderArchivePath("a/b", false))
        assertEquals("a/b", validateLnReaderArchivePath("a/b/", true))
    }

    @Test
    fun `rewrites LNReader file assets to the reader image scheme`() {
        val html =
            """<img src="file:///storage/emulated/0/Android/data/app/files/Novels/ln.hako/3/474/images/0.b64.png">"""

        assertEquals(
            """<img src="tsundoku-novel-image://images%2F0.b64.png">""",
            rewriteLnReaderChapterAssetUrls(html),
        )
    }

    @Test
    fun `does not rewrite unrelated file URLs`() {
        val html = """<img src="file:///storage/emulated/0/Pictures/cover.png">"""

        assertEquals(html, rewriteLnReaderChapterAssetUrls(html))
    }

    @Test
    fun `points local novel assets at the file names the import actually wrote`() {
        val html = """
            <link href="file:///storage/emulated/0/Android/data/app/files/Novels/local/22/style.css">
            <img src="file:///storage/emulated/0/Android/data/app/files/Novels/local/22/07627654-1c39.">
            <img src="file:///storage/emulated/0/Android/data/app/files/Novels/local/22/absent.png">
        """.trimIndent()

        val rewritten = rewriteLnReaderLocalAssetUrls(
            html,
            mapOf("style.css" to "style.css", "07627654-1c39." to "07627654-1c39"),
        )

        assertEquals(
            """
            <link href="style.css">
            <img src="07627654-1c39">
            <img src="file:///storage/emulated/0/Android/data/app/files/Novels/local/22/absent.png">
            """.trimIndent(),
            rewritten,
        )
    }

    @Test
    fun `normalizes read duration units from both LNReader formats`() {
        assertEquals(12_000L, normalizeLnReaderReadDuration(readDurationSeconds = 12L, timeSpentMilliseconds = 999L))
        assertEquals(999L, normalizeLnReaderReadDuration(readDurationSeconds = null, timeSpentMilliseconds = 999L))
    }
}
