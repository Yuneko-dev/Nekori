package eu.kanade.tachiyomi.data.font

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class GoogleFontsCatalogTest {

    @Test
    fun `Literata matches partial case insensitive trimmed query`() = runTest {
        val catalog = GoogleFontsCatalog { METADATA }

        assertEquals(listOf("Literata"), catalog.search("  lItEr  ").map { it.family })
        assertTrue(catalog.search("not an installed family").isEmpty())
    }

    @Test
    fun `website metadata ignores extra fields and preserves variant keys`() = runTest {
        val catalog = GoogleFontsCatalog { ")]}'\n$METADATA" }
        val result = catalog.search("Literata").single()

        assertEquals("Literata", result.family)
        assertEquals("serif", result.category)
        assertEquals(setOf("400", "400i", "700"), result.variants.toSet())
    }

    @Test
    fun `blank query returns sorted unique catalog without limiting families`() = runTest {
        val catalog = GoogleFontsCatalog { METADATA }

        assertEquals(listOf("Alpha", "Literata", "Zilla Slab"), catalog.search(" \t ").map { it.family })
    }

    @Test
    fun `successful catalog is reused for different searches including no matches`() = runTest {
        var fetches = 0
        val catalog = GoogleFontsCatalog {
            fetches++
            METADATA
        }

        catalog.search("missing")
        catalog.search("Literata")
        catalog.search("")

        assertEquals(1, fetches)
    }

    @Test
    fun `concurrent searches share one successful fetch with independent filtering`() = runTest {
        var fetches = 0
        val response = CompletableDeferred<String>()
        val catalog = GoogleFontsCatalog {
            fetches++
            response.await()
        }
        val first = async { catalog.search("liter") }
        val second = async { catalog.search("zilla") }
        runCurrent()

        assertEquals(1, fetches)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        response.complete(METADATA)

        assertEquals(listOf("Literata"), first.await().map { it.family })
        assertEquals(listOf("Zilla Slab"), second.await().map { it.family })
        assertEquals(1, fetches)
    }

    @Test
    fun `transport failure propagates and later search retries`() = runTest {
        var fetches = 0
        val failure = IOException("catalog unavailable")
        val catalog = GoogleFontsCatalog {
            if (++fetches == 1) throw failure
            METADATA
        }

        assertSame(failure, runCatching { catalog.search("Literata") }.exceptionOrNull())
        assertEquals("Literata", catalog.search("Literata").single().family)
        assertEquals(2, fetches)
    }

    @Test
    fun `malformed empty or blank family metadata cannot poison successful retry`() = runTest {
        val invalidBodies = listOf(
            "not JSON",
            "{}",
            """{"familyMetadataList": []}""",
            """{"familyMetadataList": [{"family": "   "}]}""",
        )
        for (invalid in invalidBodies) {
            var fetches = 0
            val catalog = GoogleFontsCatalog { if (++fetches == 1) invalid else METADATA }

            assertTrue(runCatching { catalog.search("") }.isFailure, invalid)
            assertEquals("Literata", catalog.search("Literata").single().family)
            assertEquals(2, fetches)
        }
    }

    @Test
    fun `fetch cancellation is not converted to an empty search result`() = runTest {
        val cancellation = CancellationException("search superseded")
        val catalog = GoogleFontsCatalog { throw cancellation }

        assertSame(cancellation, runCatching { catalog.search("Literata") }.exceptionOrNull())
    }

    @Test
    fun `cancelled fetch propagates cancellation and releases catalog for retry`() = runTest {
        var fetches = 0
        val catalog = GoogleFontsCatalog {
            if (++fetches == 1) awaitCancellation()
            METADATA
        }
        val cancelled = launch { catalog.search("Literata") }
        runCurrent()
        cancelled.cancelAndJoin()

        assertTrue(cancelled.isCancelled)
        assertEquals("Literata", catalog.search("Literata").single().family)
        assertEquals(2, fetches)
    }

    private companion object {
        // Google website metadata shape; unconsumed fields deliberately remain in this fixture.
        val METADATA = """
            {
              "axisRegistry": [],
              "familyMetadataList": [
                {"family":"Zilla Slab","category":"SERIF","fonts":{"400":{}}},
                {"family":"Literata","category":"SERIF","fonts":{"400":{},"400i":{},"700":{}},
                 "designers":["TypeTogether"],"subsets":["latin","vietnamese"]},
                {"family":"Alpha","category":"SANS_SERIF","fonts":{"400":{}}},
                {"family":"Literata","category":"SERIF","fonts":{"400":{}}}
              ]
            }
        """.trimIndent()
    }
}
