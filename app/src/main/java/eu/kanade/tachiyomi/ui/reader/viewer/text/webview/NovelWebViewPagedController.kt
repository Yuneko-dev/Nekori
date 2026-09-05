package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.content.Context
import android.view.View
import android.webkit.WebView
import eu.kanade.tachiyomi.ui.reader.setting.NovelPageEffect
import eu.kanade.tachiyomi.ui.reader.setting.NovelPagePosition
import eu.kanade.tachiyomi.ui.reader.setting.NovelPageSpread
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_OBJECT_NAME
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns ephemeral horizontal-page state and the small command surface sent to the WebView. */
internal class NovelWebViewPagedController(
    private val context: Context,
    private val webView: WebView,
    private val preferences: ReaderPreferences,
    private val evaluateJs: (String) -> Unit,
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
        val facade = NovelWebViewJsAssets.loadWith(
            context,
            "reader-layout.js",
            mapOf("TSUNDOKU_OBJECT_NAME" to TSUNDOKU_OBJECT_NAME),
        )
        val driver = NovelWebViewJsAssets.load(context, "page-reader.js")
        evaluateJs("$facade\n$driver")
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
        evaluateJs(
            "window.$TSUNDOKU_OBJECT_NAME?.runtime?.readerLayout?.moveBy?.(${delta.coerceIn(-1, 1)}, 'instant');",
        )
    }

    fun seekUnit(unitIndex: Int) {
        if (!enabled) return
        evaluateJs(
            "window.$TSUNDOKU_OBJECT_NAME?.runtime?.readerLayout?.seekUnit?.(${unitIndex.coerceAtLeast(0)});",
        )
    }

    fun seekPercent(percent: Int) {
        if (!enabled) return
        evaluateJs(
            "window.$TSUNDOKU_OBJECT_NAME?.runtime?.readerLayout?.seekPercent?.(${percent.coerceIn(0, 100)});",
        )
    }

    fun reflow() {
        if (enabled) evaluateJs("window.$TSUNDOKU_OBJECT_NAME?.runtime?.readerLayout?.reflow?.();")
    }

    fun effectiveEffect(): NovelPageEffect = if (preferences.flashOnPageChange.get()) {
        NovelPageEffect.NONE
    } else {
        preferences.novelPageEffect.get()
    }

    fun isDoubleSpread(): Boolean = effectiveSpread() == NovelPageSpread.DOUBLE

    private fun effectiveSpread(): NovelPageSpread = when (val setting = preferences.novelPageSpread.get()) {
        NovelPageSpread.AUTO -> {
            val widthDp = webView.width / context.resources.displayMetrics.density
            if (widthDp >= DOUBLE_PAGE_MIN_WIDTH_DP) NovelPageSpread.DOUBLE else NovelPageSpread.SINGLE
        }
        else -> setting
    }

    private fun applyConfig() {
        applyOverScrollMode()
        val spread = effectiveSpread()
        appliedSpread = spread
        val threshold = preferences.novelAutoLoadNextChapterAt.get().coerceIn(0, 100) / 100.0
        evaluateJs(
            "window.$TSUNDOKU_OBJECT_NAME.runtime.readerLayout.configure({" +
                "enabled:$enabled,spread:'${spread.name.lowercase()}',direction:'${direction.htmlValue}'," +
                "infinite:$infinite,threshold:$threshold,chapterId:'$chapterId'});",
        )
    }

    private fun applyOverScrollMode() {
        webView.overScrollMode = if (enabled) View.OVER_SCROLL_NEVER else View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private companion object {
        const val DOUBLE_PAGE_MIN_WIDTH_DP = 600f
    }
}
