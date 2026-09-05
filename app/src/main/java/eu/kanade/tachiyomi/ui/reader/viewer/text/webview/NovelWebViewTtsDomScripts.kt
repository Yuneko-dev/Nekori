package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_ID_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TAG_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_CHAPTER_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_OBJECT_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.quoteForJson

internal object NovelWebViewTtsDomScripts {
    fun extractText(chapterId: Long?) = """
        (function() {
            $HELPERS
            var root = ttsChapterRoot(${chapterId ?: "null"});
            if (!root) return '';
            return ttsReadableElements(root)
                .map(function(element) { return ttsNormalizeText(element.innerText); })
                .filter(function(text) { return !!text; })
                .join('\n');
        })();
    """.trimIndent()

    fun firstVisibleParagraph(chapterId: Long?) = """
        (function() {
            $HELPERS
            var root = ttsChapterRoot(${chapterId ?: "null"});
            var elements = root ? ttsReadableElements(root).filter(function(element) {
                return !!ttsNormalizeText(element.innerText);
            }) : [];
            var viewportHeight = window.innerHeight || document.documentElement.clientHeight;
            for (var i = 0; i < elements.length; i++) {
                var rect = elements[i].getBoundingClientRect();
                if (rect.bottom > 0 && rect.top < viewportHeight) return i;
            }
            return 0;
        })();
    """.trimIndent()

    fun taggedParagraph(chapterId: Long?) = """
        (function() {
            $HELPERS
            var marked = document.querySelector('[data-td-tts-target]');
            if (marked) marked.removeAttribute('data-td-tts-target');
            var root = ttsChapterRoot(${chapterId ?: "null"});
            if (!marked || !root) return -1;
            var elements = ttsReadableElements(root).filter(function(element) {
                return !!ttsNormalizeText(element.innerText);
            });
            for (var element = marked; element; element = element.parentElement) {
                var index = elements.indexOf(element);
                if (index >= 0) return index;
            }
            return -1;
        })();
    """.trimIndent()

    fun highlight(
        chapterId: Long?,
        paragraphIndex: Int,
        backgroundColor: String,
        textColor: String,
        style: String,
        keepInView: Boolean,
    ) = """
        (function() {
            var state = window.__tdTtsState || (window.__tdTtsState = {});
            if (!state.styleEl) {
                state.styleEl = document.createElement('style');
                state.styleEl.id = 'td-tts-highlight-style';
                state.styleEl.textContent =
                    '.td-tts-highlight-bg{background-color:var(--td-tts-highlight-bg)!important;color:var(--td-tts-highlight-text)!important;}' +
                    '.td-tts-highlight-underline{text-decoration:underline 2px var(--td-tts-highlight-bg)!important;text-underline-offset:0.2em;}' +
                    '.td-tts-highlight-outline{outline:2px solid var(--td-tts-highlight-bg)!important;outline-offset:2px;}';
                document.head.appendChild(state.styleEl);
            }
            document.documentElement.style.setProperty('--td-tts-highlight-bg', '$backgroundColor');
            document.documentElement.style.setProperty('--td-tts-highlight-text', '$textColor');
            $HELPERS
            var root = ttsChapterRoot(${chapterId ?: "null"});
            var paragraphs = root ? ttsReadableElements(root).filter(function(element) {
                return !!ttsNormalizeText(element.innerText);
            }) : [];
            if (state.currentEl) {
                state.currentEl.classList.remove('td-tts-highlight-bg', 'td-tts-highlight-underline', 'td-tts-highlight-outline');
            }
            var targetIndex = Math.min(Math.max($paragraphIndex, 0), Math.max(paragraphs.length - 1, 0));
            var target = paragraphs[targetIndex];
            if (!target) { state.currentEl = null; return; }
            var style = ${quoteForJson(style)};
            if (style === 'underline') target.classList.add('td-tts-highlight-underline');
            else if (style === 'outline') target.classList.add('td-tts-highlight-outline');
            else target.classList.add('td-tts-highlight-bg');
            state.currentEl = target;
            if ($keepInView) {
                var layout = window.$TSUNDOKU_OBJECT_NAME?.runtime?.readerLayout;
                if (layout?.enabled) layout.revealElement(target);
                else target.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
            }
        })();
    """.trimIndent()

    const val CLEAR_HIGHLIGHT = """
        (function() {
            var state = window.__tdTtsState;
            if (state && state.currentEl) {
                state.currentEl.classList.remove('td-tts-highlight-bg', 'td-tts-highlight-underline', 'td-tts-highlight-outline');
                state.currentEl = null;
            }
        })();
    """

    private const val HELPERS = """
        var ttsReadableNodeNames = ['#text', 'B', 'I', 'SPAN', 'EM', 'BR', 'STRONG', 'A'];
        var ttsInternalElementIds = ['LNReader-title-novel'];
        var ttsSkippedNodeNames = ['STYLE', 'SCRIPT', 'NOSCRIPT', 'TEMPLATE', 'IFRAME'];
        function ttsReadable(element) {
            if (!element || ttsInternalElementIds.includes(element.id)) return false;
            if (ttsSkippedNodeNames.includes(element.nodeName)) return false;
            if (ttsReadableNodeNames.includes(element.nodeName) || !element.hasChildNodes()) return false;
            for (var i = 0; i < element.childNodes.length; i++) {
                if (!ttsReadableNodeNames.includes(element.childNodes.item(i).nodeName)) return false;
            }
            return true;
        }
        function ttsReadableElements(root) {
            var elements = [];
            function traverse(element) {
                if (!element) return;
                if (ttsReadable(element)) elements.push(element);
                for (var i = 0; i < element.children.length; i++) traverse(element.children[i]);
            }
            traverse(root);
            return elements;
        }
        function ttsChapterRoot(chapterId) {
            if (chapterId != null) {
                var chapter = document.querySelector('$CHAPTER_TAG_NAME[$CHAPTER_ID_ATTR="' + chapterId + '"]');
                if (chapter) return chapter;
                if (document.querySelector('$CHAPTER_TAG_NAME[$TSUNDOKU_CHAPTER_ATTR="1"]')) return null;
            }
            return document.getElementById('LNReader-chapter');
        }
        function ttsNormalizeText(text) {
            if (!text) return '';
            return text.replace(/^["'“”‘’]+|["'“”‘’]+$/g, '')
                .replace(/\s+/g, ' ').replace(/\s*([.,!?;:])\s*/g, '${'$'}1 ').trim();
        }
    """
}
