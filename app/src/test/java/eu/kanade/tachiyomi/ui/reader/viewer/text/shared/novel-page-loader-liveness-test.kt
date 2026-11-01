@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NovelPageLoaderLivenessTest {
    @Test
    fun `a normally completed load that leaves queue is not repeatedly relaunched`() = runBlocking {
        val completed = CountDownLatch(1)
        val loads = AtomicInteger()
        val page = spyk(ReaderPage(0, "/c"))
        val loader = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages() = emptyList<ReaderPage>()
            override suspend fun loadPage(page: ReaderPage) {
                loads.incrementAndGet()
                currentCoroutineContext()[Job]!!.invokeOnCompletion { completed.countDown() }
            }
        }
        every { page.statusFlow } answers {
            check(completed.await(3, TimeUnit.SECONDS))
            callOriginal()
        }
        val failure = async(Dispatchers.Default) {
            runCatching { NovelPageLoader.awaitPageText("test", page, loader, 500, this@runBlocking) }.exceptionOrNull()
        }.await()

        assertTrue(failure is TimeoutCancellationException, "An unfinished page keeps the existing timeout behavior")
        assertEquals(1, loads.get(), "A normal return is not a cancelled owner's released claim")
    }
}
