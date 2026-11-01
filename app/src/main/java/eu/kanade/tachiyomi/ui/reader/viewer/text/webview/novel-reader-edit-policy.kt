@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.model.Page
import tachiyomi.i18n.novel.TDMR

internal fun novelEditBlockedReason(isError: Boolean, isReady: Boolean, hasChapter: Boolean): StringResource? = when {
    isError -> TDMR.strings.error_cant_edit_error_page
    !isReady -> TDMR.strings.error_cant_edit_while_loading
    !hasChapter -> TDMR.strings.error_cant_edit_no_chapter
    else -> null
}

internal fun shouldRetryFromSource(page: Page?): Boolean = page?.status == Page.State.Ready && page.text.isNullOrBlank()
