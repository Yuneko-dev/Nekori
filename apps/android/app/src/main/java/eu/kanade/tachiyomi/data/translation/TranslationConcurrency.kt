package eu.kanade.tachiyomi.data.translation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class TranslationRequestTracker {
    private val generation = AtomicLong()

    fun begin(): Long = generation.incrementAndGet()
    fun invalidate() {
        generation.incrementAndGet()
    }

    fun canCommit(request: Long): Boolean = generation.get() == request
}

class PendingTitleTranslations {
    private val ids = ConcurrentHashMap.newKeySet<Long>()

    fun add(id: Long): Boolean = ids.add(id)
    fun complete(completed: Collection<Long>) = ids.removeAll(completed.toSet())
    fun clear() = ids.clear()
}
