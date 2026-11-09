package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

enum class ReaderNavigationSource {
    USER,
    AUTOMATIC,
}

class ReaderNavigationRequest internal constructor(val id: Long)

class ReaderNavigationGuard {

    private var nextId = 0L
    private var currentId: Long? = null
    private var manualInFlightId: Long? = null
    private val manualIdle = MutableStateFlow(true)

    @Synchronized
    fun begin(source: ReaderNavigationSource): ReaderNavigationRequest? {
        if (source == ReaderNavigationSource.AUTOMATIC && manualInFlightId != null) {
            return null
        }

        val request = ReaderNavigationRequest(++nextId)
        currentId = request.id
        if (source == ReaderNavigationSource.USER) {
            manualInFlightId = request.id
            manualIdle.value = false
        }
        return request
    }

    suspend fun beginAutomaticWhenIdle(): ReaderNavigationRequest {
        while (true) {
            manualIdle.first { it }
            begin(ReaderNavigationSource.AUTOMATIC)?.let { return it }
        }
    }

    @Synchronized
    fun isCurrent(request: ReaderNavigationRequest): Boolean = currentId == request.id

    @Synchronized
    fun finish(request: ReaderNavigationRequest) {
        if (currentId == request.id) {
            currentId = null
        }
        if (manualInFlightId == request.id) {
            manualInFlightId = null
            manualIdle.value = true
        }
    }
}
