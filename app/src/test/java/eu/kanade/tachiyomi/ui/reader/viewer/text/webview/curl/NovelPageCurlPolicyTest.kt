package eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelPageCurlPolicyTest {

    @Test
    fun `shadow fades continuously at both endpoints and survives crossing the spine`() {
        assertEquals(0f, NovelPageCurlGeometry.shadowOpacity(0f))
        assertEquals(0f, NovelPageCurlGeometry.shadowOpacity(1f))
        assertEquals(1f, NovelPageCurlGeometry.shadowOpacity(0.75f))
        assertTrue(NovelPageCurlGeometry.shadowOpacity(0.99f) < 0.01f)
        assertTrue(NovelPageCurlGeometry.shadowOpacity(0.95f) > NovelPageCurlGeometry.shadowOpacity(0.99f))
    }

    @Test
    fun `short remaining travel and fast flings settle sooner`() {
        val full = NovelPageCurlGeometry.settleDuration(0f, true, 0f, 1000f)
        assertTrue(NovelPageCurlGeometry.settleDuration(0.9f, true, 0f, 1000f) < full)
        assertTrue(NovelPageCurlGeometry.settleDuration(0.5f, true, 8000f, 1000f) < full)
        assertTrue(NovelPageCurlGeometry.settleDuration(0.1f, false, 0f, 1000f) < full)
    }

    @Test
    fun `rollback keeps overlay until the WebView is restored`() {
        val events = mutableListOf<String>()
        var restored: (() -> Unit)? = null

        finishPageTurn(
            commit = false,
            clearOverlay = { events += "clear" },
            commitPage = { events += "commit" },
            rollbackPage = { onRestored ->
                events += "rollback"
                restored = onRestored
            },
        )

        assertEquals(listOf("rollback"), events)
        restored?.invoke()
        assertEquals(listOf("rollback", "clear"), events)
    }

    @Test
    fun `commit keeps overlay until the WebView transaction is finished`() {
        val events = mutableListOf<String>()
        var committed: (() -> Unit)? = null

        finishPageTurn(
            commit = true,
            clearOverlay = { events += "clear" },
            commitPage = { onCommitted ->
                events += "commit"
                committed = onCommitted
            },
            rollbackPage = {},
        )

        assertEquals(listOf("commit"), events)
        committed?.invoke()
        assertEquals(listOf("commit", "clear"), events)
    }

    @Test
    fun `restored WebView stays covered until its visual state is drawn`() {
        var visualStateReady: (() -> Unit)? = null
        var nextFrame: (() -> Unit)? = null
        val events = mutableListOf<String>()

        finishAfterVisualState(
            awaitVisualState = { visualStateReady = it },
            postFrame = { nextFrame = it },
        ) { events += "clear" }

        assertTrue(events.isEmpty())
        visualStateReady?.invoke()
        assertTrue(events.isEmpty())
        nextFrame?.invoke()
        assertEquals(listOf("clear"), events)
    }

    @Test
    fun `distance commits at one third of the leaf width`() {
        assertFalse(NovelPageCurlGeometry.shouldCommit(99f, 300f, 0f))
        assertTrue(NovelPageCurlGeometry.shouldCommit(100f, 300f, 0f))
    }

    @Test
    fun `double curl uses viewport travel but leaf width commit threshold`() {
        val widths = NovelPageCurlGeometry.gestureWidths(
            viewportWidth = 400f,
            doubleSpread = true,
            curlEffect = true,
        )

        assertEquals(400f, widths.progress)
        assertEquals(200f, widths.commit)
    }

    @Test
    fun `only velocity toward completion can commit`() {
        assertTrue(NovelPageCurlGeometry.shouldCommit(20f, 300f, 1_200f))
        assertFalse(NovelPageCurlGeometry.shouldCommit(20f, 300f, -1_200f))
    }

    @Test
    fun `directed drag follows the selected physical edge`() {
        assertEquals(60f, NovelPageCurlGeometry.directedDrag(180f, 120f, true), 0.001f)
        assertEquals(60f, NovelPageCurlGeometry.directedDrag(20f, 80f, false), 0.001f)
        assertEquals(0f, NovelPageCurlGeometry.directedDrag(20f, 0f, false), 0.001f)
    }

    @Test
    fun `bitmap size stays within the shared memory budget`() {
        val full = NovelPageCurlGeometry.bitmapSize(1080, 2400)
        val scaled = NovelPageCurlGeometry.bitmapSize(2160, 4800)

        assertEquals(NovelPageCurlBitmapSize(1080, 2400, 1f), full)
        assertTrue(scaled.width < 2160)
        assertTrue(scaled.height < 4800)
        assertTrue(scaled.width.toLong() * scaled.height * 4L * 2L <= 32L * 1024 * 1024)
        assertEquals(2160f / 4800f, scaled.width.toFloat() / scaled.height, 0.001f)
    }
}
