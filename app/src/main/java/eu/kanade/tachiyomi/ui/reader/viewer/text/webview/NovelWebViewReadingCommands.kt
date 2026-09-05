package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_DIVIDER_CLASS
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_ID_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TAG_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_OBJECT_NAME

internal object NovelWebViewReadingCommands {
    fun restoreVertical(progress: Float, token: Int) = """
        (function() {
            var target = $progress;
            var token = $token;
            function range() {
                var docHeight = Math.max(document.documentElement.scrollHeight, document.body?.scrollHeight || 0);
                var viewport = window.innerHeight || document.documentElement.clientHeight;
                return docHeight - viewport;
            }
            function done() { window.Android?.onScrollRestoreComplete?.(token); }
            function apply() {
                var available = range();
                if (available > 0) { window.scrollTo(0, available * target); return true; }
                return false;
            }
            if (apply()) {
                requestAnimationFrame(function() { apply(); done(); });
                return;
            }
            if (typeof ResizeObserver === 'function' && document.body) {
                var observer = new ResizeObserver(function() {
                    if (apply()) { observer.disconnect(); requestAnimationFrame(apply); }
                });
                observer.observe(document.body);
            }
            requestAnimationFrame(done);
        })();
    """.trimIndent()

    fun revealChapter(chapterId: Long) = """
        (function() {
            var id = '$chapterId';
            var target = document.querySelector('.$CHAPTER_DIVIDER_CLASS[$CHAPTER_ID_ATTR="' + id + '"]') ||
                document.querySelector('$CHAPTER_TAG_NAME[$CHAPTER_ID_ATTR="' + id + '"]');
            if (!target) return false;
            var layout = window.$TSUNDOKU_OBJECT_NAME?.runtime?.readerLayout;
            if (layout?.enabled) layout.revealElement(target);
            else target.scrollIntoView({ behavior: 'auto', block: 'start', inline: 'nearest' });
            window.updateChapterBoundaries?.();
            return true;
        })();
    """.trimIndent()

    fun seekVertical(percent: Int) = """
        (function() {
            var frac = $percent / 100;
            var viewport = window.innerHeight || document.documentElement.clientHeight;
            var boundaries = window.chapterBoundaries || [];
            if (boundaries.length > 1) {
                var scrollTop = window.scrollY || document.documentElement.scrollTop || 0;
                var idx = 0;
                for (var i = 0; i < boundaries.length; i++) {
                    if (scrollTop >= boundaries[i].startOffset) idx = i; else break;
                }
                var boundary = boundaries[idx];
                var isLast = idx === boundaries.length - 1;
                var height = Math.max(boundary.height - (isLast ? viewport : 0), 1);
                window.scrollTo({ top: boundary.startOffset + height * frac, behavior: 'instant' });
            } else {
                var docHeight = Math.max(document.documentElement.scrollHeight, document.body?.scrollHeight || 0);
                window.scrollTo({ top: (docHeight - viewport) * frac, behavior: 'instant' });
            }
            window.dispatchEvent(new Event('scroll'));
        })();
    """.trimIndent()

    fun startVerticalAutoScroll(pxPerSecond: Int) = """
        (function() {
            var state = window.__tdAutoScroll || (window.__tdAutoScroll = {});
            state.pxPerSec = $pxPerSecond;
            if (state.running) return;
            state.running = true;
            state.last = null;
            state.acc = 0;
            function step(timestamp) {
                if (!state.running) return;
                if (state.last === null) state.last = timestamp;
                var delta = Math.min((timestamp - state.last) / 1000, 0.05);
                state.last = timestamp;
                state.acc += state.pxPerSec * delta;
                var whole = Math.floor(state.acc);
                if (whole > 0) { window.scrollBy(0, whole); state.acc -= whole; }
                state.raf = requestAnimationFrame(step);
            }
            state.raf = requestAnimationFrame(step);
        })();
    """.trimIndent()

    const val STOP_AUTO_SCROLL = """
        (function() {
            var state = window.__tdAutoScroll;
            if (!state) return;
            state.running = false;
            if (state.raf) cancelAnimationFrame(state.raf);
            if (state.timer) clearTimeout(state.timer);
        })();
    """

    fun prepareCurl(delta: Int) = """
        (function() {
            var layout = window.$TSUNDOKU_OBJECT_NAME?.runtime?.readerLayout;
            if (!layout?.enabled) return false;
            layout.prepareSilentTurn(false);
            var moved = layout.moveBy($delta, 'instant');
            if (!moved) layout.finishSilentTurn(false, false);
            return moved;
        })();
    """.trimIndent()

    const val ROLLBACK_CURL = """
        (function() {
            var layout = window.$TSUNDOKU_OBJECT_NAME?.runtime?.readerLayout;
            if (!layout?.enabled) return;
            layout.finishSilentTurn(false, false);
        })();
    """
}
