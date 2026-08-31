package eu.kanade.tachiyomi.ui.reader.setting

sealed interface NovelTtsEngine {
    val preferenceValue: String

    data object SystemDefault : NovelTtsEngine {
        override val preferenceValue = SYSTEM_DEFAULT
    }

    data class Android(val packageName: String) : NovelTtsEngine {
        override val preferenceValue = "$ANDROID_PREFIX$packageName"
    }

    data object TikTok : NovelTtsEngine {
        override val preferenceValue = TIKTOK
    }

    companion object {
        private const val SYSTEM_DEFAULT = "system-default"
        private const val ANDROID_PREFIX = "android:"
        private const val TIKTOK = "bundled:tiktok"

        fun fromPreference(value: String): NovelTtsEngine = when {
            value == SYSTEM_DEFAULT -> SystemDefault
            value == TIKTOK -> TikTok
            value.startsWith(ANDROID_PREFIX) -> value.removePrefix(ANDROID_PREFIX)
                .takeIf(String::isNotBlank)
                ?.let(::Android)
                ?: SystemDefault
            else -> SystemDefault
        }
    }
}
