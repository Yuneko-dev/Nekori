package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderNavigationGuardTest {

    @Test
    fun `newer automatic request makes older automatic request stale`() {
        val guard = ReaderNavigationGuard()
        val first = guard.begin(ReaderNavigationSource.AUTOMATIC)!!
        val second = guard.begin(ReaderNavigationSource.AUTOMATIC)!!

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    @Test
    fun `manual request blocks delayed automatic request until finished`() {
        val guard = ReaderNavigationGuard()
        val manual = guard.begin(ReaderNavigationSource.USER)!!

        assertNull(guard.begin(ReaderNavigationSource.AUTOMATIC))
        assertTrue(guard.isCurrent(manual))

        guard.finish(manual)
        assertNotNull(guard.begin(ReaderNavigationSource.AUTOMATIC))
    }

    @Test
    fun `automatic request waits for latest manual request to finish`() = runTest {
        val guard = ReaderNavigationGuard()
        val staleManual = guard.begin(ReaderNavigationSource.USER)!!
        val latestManual = guard.begin(ReaderNavigationSource.USER)!!

        val automatic = async { guard.beginAutomaticWhenIdle() }
        runCurrent()

        assertFalse(automatic.isCompleted)
        assertTrue(guard.isCurrent(latestManual))

        guard.finish(staleManual)
        runCurrent()

        assertFalse(automatic.isCompleted)
        assertTrue(guard.isCurrent(latestManual))

        guard.finish(latestManual)
        runCurrent()

        assertTrue(automatic.isCompleted)
        assertTrue(guard.isCurrent(automatic.await()))
    }

    @Test
    fun `finishing stale manual request does not clear newer manual barrier`() {
        val guard = ReaderNavigationGuard()
        val first = guard.begin(ReaderNavigationSource.USER)!!
        val second = guard.begin(ReaderNavigationSource.USER)!!

        guard.finish(first)

        assertNull(guard.begin(ReaderNavigationSource.AUTOMATIC))
        assertTrue(guard.isCurrent(second))
    }
}
