package eu.kanade.tachiyomi.ui.reader

data class NovelFindInPageState(
    val query: String = "",
    val activeMatchOrdinal: Int = 0,
    val numberOfMatches: Int = 0,
    val isDoneCounting: Boolean = true,
    val focusRequestId: Int = 0,
) {
    val hasMatches: Boolean
        get() = query.isNotEmpty() && numberOfMatches > 0

    val isNoMatch: Boolean
        get() = query.isNotEmpty() && isDoneCounting && !hasMatches

    val statusText: String
        get() = when {
            query.isEmpty() || (!isDoneCounting && !hasMatches) -> ""
            else -> "${if (hasMatches) activeMatchOrdinal + 1 else 0}/$numberOfMatches"
        }

    fun withQuery(query: String): NovelFindInPageState = copy(
        query = query,
        activeMatchOrdinal = 0,
        numberOfMatches = 0,
        isDoneCounting = query.isEmpty(),
    )

    fun withResult(
        activeMatchOrdinal: Int,
        numberOfMatches: Int,
        isDoneCounting: Boolean,
    ): NovelFindInPageState {
        val safeCount = numberOfMatches.coerceAtLeast(0)
        return copy(
            activeMatchOrdinal = if (safeCount > 0) {
                activeMatchOrdinal.coerceIn(0, safeCount - 1)
            } else {
                0
            },
            numberOfMatches = safeCount,
            isDoneCounting = isDoneCounting,
        )
    }
}
