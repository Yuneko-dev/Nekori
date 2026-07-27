package eu.kanade.domain.source.interactor

import eu.kanade.domain.base.BasePreferences
import kotlinx.coroutines.flow.Flow

class GetIncognitoState(
    private val basePreferences: BasePreferences,
) {
    fun await(@Suppress("UNUSED_PARAMETER") sourceId: Long?): Boolean {
        return basePreferences.incognitoMode.get()
    }

    fun subscribe(@Suppress("UNUSED_PARAMETER") sourceId: Long?): Flow<Boolean> {
        return basePreferences.incognitoMode.changes()
    }
}
