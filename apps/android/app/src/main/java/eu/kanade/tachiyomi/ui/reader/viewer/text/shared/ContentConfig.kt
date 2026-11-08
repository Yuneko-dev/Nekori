package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget

/**
 * [TEXT_VIEW]: TextView renders scripts/styles as visible text, so they are always stripped.
 * [WEB_VIEW]: embedded CSS/JS can be preserved per user preferences.
 */
enum class RenderTarget { TEXT_VIEW, WEB_VIEW }

data class ContentConfig(
    val chapterUrl: String?,
    val chapterName: String,
    val target: RenderTarget,
    val hideTitle: Boolean = false,
    val forceLowercase: Boolean = false,
    val blockMedia: Boolean = false,
    val keepEmbeddedCss: Boolean = true,
    val keepEmbeddedJs: Boolean = false,
    val replacementTarget: ReplacementTarget? = null,
) {
    companion object {
        fun from(
            preferences: ReaderPreferences,
            target: RenderTarget,
            chapterUrl: String?,
            chapterName: String,
            replacementTarget: ReplacementTarget? = null,
        ): ContentConfig = ContentConfig(
            chapterUrl = chapterUrl,
            chapterName = chapterName,
            replacementTarget = replacementTarget,
            target = target,
            hideTitle = preferences.novelHideChapterTitle.get(),
            forceLowercase = preferences.novelForceTextLowercase.get(),
            blockMedia = preferences.novelBlockMedia.get(),
            // Note: preferences are named "epub" for historical reasons but apply
            // to all HTML-format chapters, not only EPUB sources.
            keepEmbeddedCss = preferences.enableEpubStyles.get(),
            keepEmbeddedJs = preferences.enableEpubJs.get(),
        )
    }
}
