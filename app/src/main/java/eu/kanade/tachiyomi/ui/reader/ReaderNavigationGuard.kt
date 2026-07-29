package eu.kanade.tachiyomi.ui.reader

enum class ReaderNavigationSource {
    USER,
    AUTOMATIC,
}

class ReaderNavigationRequest internal constructor(val id: Long)

class ReaderNavigationGuard {

    private var nextId = 0L
    private var currentId: Long? = null
    private var manualInFlightId: Long? = null

    @Synchronized
    fun begin(source: ReaderNavigationSource): ReaderNavigationRequest? {
        if (source == ReaderNavigationSource.AUTOMATIC && manualInFlightId != null) {
            return null
        }

        val request = ReaderNavigationRequest(++nextId)
        currentId = request.id
        if (source == ReaderNavigationSource.USER) {
            manualInFlightId = request.id
        }
        return request
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
        }
    }
}
