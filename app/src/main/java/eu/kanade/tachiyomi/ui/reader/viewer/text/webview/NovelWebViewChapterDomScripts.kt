package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_DIVIDER_CLASS
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_ID_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_NUMBER_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_PATH_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TAG_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TITLE_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_URL_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_CHAPTER_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.quoteForJson

internal object NovelWebViewChapterDomScripts {
    fun append(chapter: ScriptChapter, processed: ProcessedContent): String {
        val content = NovelWebViewDocumentBuilder.appendProcessedContentScript("chapterElement", processed)
        return """
            (function() {
                var chaptersContainer = document.getElementById('LNReader-chapter');
                if (!chaptersContainer) return;
                ${createDivider(chapter)}
                chaptersContainer.appendChild(divider);
                ${createChapterElement(chapter)}
                $content
                chaptersContainer.appendChild(chapterElement);
                window.updateChapterBoundaries?.();
                requestAnimationFrame(function() {
                    window.updateChapterBoundaries?.();
                    window.Android?.onInfiniteScrollAppendComplete?.(${chapter.id});
                });
            })();
        """.trimIndent()
    }

    data class ScriptChapter(
        val id: Long,
        val name: String,
        val number: Float,
        val path: String,
        val absoluteUrl: String,
        val direction: NovelContentDirection,
        val language: String,
    )

    private fun createChapterElement(chapter: ScriptChapter) = """
        var chapterElement = document.createElement('$CHAPTER_TAG_NAME');
        chapterElement.setAttribute('$CHAPTER_ID_ATTR', '${chapter.id}');
        chapterElement.setAttribute('$TSUNDOKU_CHAPTER_ATTR', '1');
        chapterElement.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapter.name)});
        chapterElement.setAttribute('$CHAPTER_NUMBER_ATTR', '${chapter.number}');
        chapterElement.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapter.path)});
        chapterElement.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(chapter.absoluteUrl)});
        chapterElement.setAttribute('dir', '${chapter.direction.htmlValue}');
        chapterElement.setAttribute('lang', ${quoteForJson(chapter.language)});
    """.trimIndent()

    private fun createDivider(chapter: ScriptChapter) = """
        var divider = document.createElement('div');
        divider.className = '$CHAPTER_DIVIDER_CLASS';
        divider.setAttribute('$CHAPTER_ID_ATTR', '${chapter.id}');
        divider.setAttribute('$TSUNDOKU_CHAPTER_ATTR', '1');
        divider.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapter.name)});
        divider.setAttribute('$CHAPTER_NUMBER_ATTR', '${chapter.number}');
        divider.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapter.path)});
        divider.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(chapter.absoluteUrl)});
    """.trimIndent()
}
