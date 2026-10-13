package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.content.Context
import android.view.View
import android.webkit.WebView
import eu.kanade.tachiyomi.ui.reader.setting.NovelPageEffect
import eu.kanade.tachiyomi.ui.reader.setting.NovelPagePosition
import eu.kanade.tachiyomi.ui.reader.setting.NovelPageSpread
import eu.kanade.tachiyomi.ui.reader.setting.NovelReadingLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Owns native page state and sends structured commands to the reader runtime. */
internal class NovelWebViewPagedController(
    private val context: Context,
    private val webView: WebView,
    private val preferences: ReaderPreferences,
    private val executeCommand: (ReaderCommand) -> Unit,
) {
    private val _position = MutableStateFlow<NovelPagePosition?>(null)
    val position: StateFlow<NovelPagePosition?> = _position.asStateFlow()

    var enabled: Boolean = false
        private set
    private var direction = NovelContentDirection.LTR
    private var infinite = false
    private var chapterId = -1L
    private var appliedSpread: NovelPageSpread? = null

    init {
        webView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (enabled && preferences.novelPageSpread.get() == NovelPageSpread.AUTO &&
                right - left != oldRight - oldLeft && effectiveSpread() != appliedSpread
            ) {
                applyConfig()
            }
        }
    }

    fun install(direction: NovelContentDirection, infinite: Boolean, chapterId: Long, enabled: Boolean) {
        this.enabled = enabled
        this.direction = direction
        this.infinite = infinite
        this.chapterId = chapterId
        _position.value = null
        applyOverScrollMode()
        applyConfig()
    }

    fun disable() {
        enabled = false
        _position.value = null
        applyOverScrollMode()
    }

    fun onPosition(
        chapterId: Long,
        unitIndex: Int,
        unitCount: Int,
        firstPage: Int,
        lastPage: Int,
        totalPages: Int,
    ) {
        if (!enabled || unitCount < 1 || totalPages < 1) return
        _position.value = NovelPagePosition(
            chapterId = chapterId,
            firstPage = firstPage.coerceIn(1, totalPages),
            lastPage = lastPage.coerceIn(1, totalPages),
            totalPages = totalPages,
            unitIndex = unitIndex.coerceIn(0, unitCount - 1),
            unitCount = unitCount,
        )
    }

    fun moveVisualUnit(delta: Int) {
        if (!enabled || delta == 0) return
        executeCommand(ReaderCommand("layout.move", buildJsonObject { put("delta", delta.coerceIn(-1, 1)) }))
    }

    fun seekUnit(unitIndex: Int) {
        if (!enabled) return
        executeCommand(
            ReaderCommand(
                "layout.seekUnit",
                buildJsonObject {
                    put("unitIndex", unitIndex.coerceAtLeast(0))
                },
            ),
        )
    }

    fun seekPercent(percent: Int) {
        if (!enabled) return
        executeCommand(
            ReaderCommand(
                "layout.seekPercent",
                buildJsonObject {
                    put("percent", percent.coerceIn(0, 100))
                },
            ),
        )
    }

    fun reflow() {
        if (enabled) executeCommand(ReaderCommand("layout.reflow"))
    }

    fun effectiveEffect(): NovelPageEffect = if (preferences.flashOnPageChange.get()) {
        NovelPageEffect.NONE
    } else {
        preferences.novelPageEffect.get()
    }

    fun isDoubleSpread(): Boolean = effectiveSpread() == NovelPageSpread.DOUBLE

    private fun effectiveSpread(): NovelPageSpread {
        if (preferences.novelReadingLayout.get() == NovelReadingLayout.VERTICAL) return NovelPageSpread.SINGLE
        return when (val setting = preferences.novelPageSpread.get()) {
            NovelPageSpread.AUTO -> {
                val widthDp = webView.width / context.resources.displayMetrics.density
                if (widthDp >= DOUBLE_PAGE_MIN_WIDTH_DP) NovelPageSpread.DOUBLE else NovelPageSpread.SINGLE
            }
            else -> setting
        }
    }

    private fun applyConfig() {
        applyOverScrollMode()
        val spread = effectiveSpread()
        appliedSpread = spread
        val threshold = preferences.novelAutoLoadNextChapterAt.get().coerceIn(0, 100) / 100.0
        val vertical = preferences.novelReadingLayout.get() == NovelReadingLayout.VERTICAL
        executeCommand(
            ReaderCommand(
                "layout.configure",
                buildJsonObject {
                    put("enabled", enabled)
                    put("spread", spread.name.lowercase())
                    put("direction", direction.htmlValue)
                    put("vertical", vertical)
                    put("infinite", infinite)
                    put("threshold", threshold)
                    put("chapterId", chapterId.toString())
                },
            ),
        )
    }

    private fun applyOverScrollMode() {
        webView.overScrollMode = if (enabled) View.OVER_SCROLL_NEVER else View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private companion object {
        const val DOUBLE_PAGE_MIN_WIDTH_DP = 600f
    }
}
