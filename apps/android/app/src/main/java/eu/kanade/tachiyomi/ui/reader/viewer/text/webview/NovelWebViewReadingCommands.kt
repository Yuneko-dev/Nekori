package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object NovelWebViewReadingCommands {
    fun restoreVertical(progress: Float, token: Int) = command("scroll.restoreVertical") {
        put("progress", progress)
        put("token", token)
    }

    fun revealChapter(chapterId: Long) = command("chapter.reveal") {
        put("chapterId", chapterId.toString())
    }

    fun seekVertical(percent: Int) = command("scroll.seekVertical") {
        put("percent", percent.coerceIn(0, 100))
    }

    fun scrollByViewport(direction: Int, fraction: Float) = command("scroll.byViewport") {
        put("direction", direction.coerceIn(-1, 1))
        put("fraction", fraction.coerceAtLeast(0f))
        put("behavior", "smooth")
    }

    fun startVerticalAutoScroll(pxPerSecond: Int) = command("scroll.autoStart") {
        put("pxPerSecond", pxPerSecond.coerceAtLeast(0))
    }

    val STOP_AUTO_SCROLL = ReaderCommand("scroll.autoStop")

    fun autoScrollStatus() = ReaderCommand("scroll.autoStatus")

    fun prepareCurl(delta: Int) = command("layout.prepareTurn") {
        put("delta", delta.coerceIn(-1, 1))
    }

    fun finishCurl(commit: Boolean) = command("layout.finishTurn") {
        put("commit", commit)
    }

    fun readSelection() = ReaderCommand("selection.read")

    fun readSelectionAnchor() = ReaderCommand("selection.readAnchor")

    fun clearSelection() = ReaderCommand("selection.clear")

    fun blurSelection() = ReaderCommand("selection.blur")

    val ROLLBACK_CURL = finishCurl(commit = false)

    private fun command(method: String, payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        ReaderCommand(method, buildJsonObject(payload))
}
