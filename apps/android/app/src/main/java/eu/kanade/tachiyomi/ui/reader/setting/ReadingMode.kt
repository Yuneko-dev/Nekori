package eu.kanade.tachiyomi.ui.reader.setting

import androidx.annotation.DrawableRes
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import tachiyomi.i18n.novel.TDMR

enum class ReadingMode(
    val stringRes: StringResource,
    @DrawableRes val iconRes: Int,
    val flagValue: Int,
) {
    NOVEL(
        TDMR.strings.novel_viewer,
        R.drawable.ic_reader_default_24dp,
        0x00000006,
    ),
    ;

    companion object {
        const val MASK = 0x00000007

        fun fromPreference(preference: Int?): ReadingMode = NOVEL
    }
}
