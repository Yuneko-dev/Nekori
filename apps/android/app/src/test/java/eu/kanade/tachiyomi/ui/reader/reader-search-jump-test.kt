@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader

import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ReaderSearchJumpTest {
    private var initialJobs = emptySet<Job>()

    @Test
    fun `origin survives one shot position consumption and restoration but completed guard stays cleared`() = runTest {
        val snapshot = ReaderViewModelTestFixture(testScheduler, 0.96f).use { fixture ->
            assertTrue(fixture.model.isSearchJumpGuarded(1))
            assertFalse(fixture.model.isSearchJumpGuarded(2))
            assertEquals(0.96f, fixture.model.takeInitialPosition(1))
            assertNull(fixture.model.takeInitialPosition(1))
            fixture.savedState.keys().associateWith { fixture.savedState.get<Any?>(it) }
        }
        ReaderViewModelTestFixture(testScheduler, restoredState = snapshot).use { fixture ->
            assertTrue(fixture.model.isSearchJumpGuarded(1))
            assertNull(fixture.model.takeInitialPosition(1))
        }
        ReaderViewModelTestFixture(
            testScheduler,
            0.96f,
            snapshot + ("search_jump_guard_chapter" to null),
        ).use { fixture -> assertFalse(fixture.model.isSearchJumpGuarded(1)) }
        ReaderViewModelTestFixture(testScheduler, 0.4f, mapOf("chapter" to 2L)).use { fixture ->
            assertTrue(fixture.model.isSearchJumpGuarded(2))
            assertFalse(fixture.model.isSearchJumpGuarded(1))
        }
    }

    @Test
    fun `zero and invalid launch positions preserve ordinary opening behavior`() = runTest {
        for (position in listOf(null, 0f, -0.1f, Float.NaN, Float.POSITIVE_INFINITY, 1.01f)) {
            ReaderViewModelTestFixture(testScheduler, position).use { fixture ->
                assertFalse(fixture.model.isSearchJumpGuarded(1))
                if (position == 0f) assertEquals(0f, fixture.model.takeInitialPosition(1))
            }
        }
    }

    @Test
    fun `final done marks once even when final chunk start already saved one hundred`() = runTest {
        ReaderViewModelTestFixture(testScheduler, 0.96f).use { fixture ->
            fixture.installChapter()
            runCurrent()
            val model = withReadHooks(fixture.model)
            val saves = AtomicInteger()
            coEvery { fixture.updateChapter.await(any()) } coAnswers {
                saves.incrementAndGet()
                Unit
            }
            model.saveNovelProgress(fixture.chapter, 100)
            awaitSave(saves, 1, model)
            assertFalse(fixture.chapter.chapter.read)
            model.saveNovelProgress(fixture.chapter, 100, reachedEnd = true)
            awaitSave(saves, 2, model)
            assertTrue(fixture.chapter.chapter.read)
            assertFalse(model.isSearchJumpGuarded(1))
            model.saveNovelProgress(fixture.chapter, 100, reachedEnd = true)
            verify(exactly = 1) { model["updateTrackChapterRead"](fixture.chapter) }
            verify(exactly = 1) { model["deleteChapterIfNeeded"](fixture.chapter) }
            verify(exactly = 1) { model["maybeTriggerSyncOnChapterRead"]() }
            assertNull(fixture.savedState.get<Long>("search_jump_guard_chapter"))
        }
    }

    @Test
    fun `normal and zero openings retain threshold and already read chapter stays read`() = runTest {
        for (position in listOf(null, 0f)) {
            ReaderViewModelTestFixture(testScheduler, position).use { fixture ->
                fixture.installChapter()
                runCurrent()
                val model = withReadHooks(fixture.model)
                fixture.model.readerPreferences.novelMarkAsReadThreshold.set(90)
                val saves = AtomicInteger()
                coEvery { fixture.updateChapter.await(any()) } coAnswers {
                    saves.incrementAndGet()
                    Unit
                }
                model.saveNovelProgress(fixture.chapter, 96)
                awaitSave(saves, 1, model)
                assertTrue(fixture.chapter.chapter.read)
            }
        }
        ReaderViewModelTestFixture(testScheduler, 0.96f).use { fixture ->
            fixture.installChapter()
            runCurrent()
            val model = withReadHooks(fixture.model)
            fixture.chapter.chapter.read = true
            val saves = AtomicInteger()
            coEvery { fixture.updateChapter.await(any()) } coAnswers {
                saves.incrementAndGet()
                Unit
            }
            model.saveNovelProgress(fixture.chapter, 96)
            awaitSave(saves, 1, model)
            assertTrue(fixture.chapter.chapter.read)
            verify(exactly = 0) { model["updateTrackChapterRead"](any<ReaderChapter>()) }
        }
    }

    @Test
    fun `checkpoint preview and incognito do not consume guard or write chapter progress`() = runTest {
        for (mode in listOf("checkpoint", "preview", "incognito")) {
            ReaderViewModelTestFixture(testScheduler, 0.96f).use { fixture ->
                if (mode == "preview") {
                    ReaderViewModel::class.java.getDeclaredField("isPreview")
                        .apply { isAccessible = true }.setBoolean(fixture.model, true)
                }
                if (mode == "checkpoint") fixture.chapter.isCheckpoint = true
                if (mode == "incognito") every { fixture.policy.isBlocked(any(), any()) } returns true
                fixture.model.saveNovelProgress(fixture.chapter, 100, reachedEnd = true)
                runCurrent()
                assertFalse(fixture.chapter.chapter.read)
                assertTrue(fixture.model.isSearchJumpGuarded(1))
                io.mockk.coVerify(exactly = 0) { fixture.updateChapter.await(any()) }
            }
        }
    }

    private fun withReadHooks(model: ReaderViewModel) = spyk(model, recordPrivateCalls = true).also {
        initialJobs = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
        every { it["updateTrackChapterRead"](any<ReaderChapter>()) } returns Unit
        every { it["deleteChapterIfNeeded"](any<ReaderChapter>()) } returns Unit
        every { it["maybeTriggerSyncOnChapterRead"]() } returns Unit
        every { it["maybeTriggerSyncOnChapterOpen"]() } returns Unit
    }

    private suspend fun kotlinx.coroutines.test.TestScope.awaitSave(
        saves: AtomicInteger,
        count: Int,
        model: ReaderViewModel,
    ) {
        // IO saves inherit a Main continuation. Wait for their actual completion before resetting Main.
        val jobs = model.viewModelScope.coroutineContext[Job]!!.children.toList().filter {
            it !in initialJobs
        }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (saves.get() < count || jobs.any { it.isActive }) {
            check(System.nanoTime() < deadline) { "Progress was not saved" }
            runCurrent()
            withContext(Dispatchers.Default) { Thread.yield() }
        }
    }

    @Test
    fun `saving a search result near the end does not mark it read or start read side effects`() = runTest {
        ReaderViewModelTestFixture(testScheduler, 0.96f).use { fixture ->
            fixture.installChapter()
            runCurrent()
            val model = withReadHooks(fixture.model)
            every { model["updateTrackChapterRead"](any<ReaderChapter>()) } returns Unit
            every { model["deleteChapterIfNeeded"](any<ReaderChapter>()) } returns Unit
            every { model["maybeTriggerSyncOnChapterRead"]() } returns Unit
            every { model["maybeTriggerSyncOnChapterOpen"]() } returns Unit
            fixture.model.readerPreferences.novelMarkAsReadThreshold.set(90)
            val saves = AtomicInteger()
            coEvery { fixture.updateChapter.await(any()) } coAnswers {
                saves.incrementAndGet()
                Unit
            }
            model.takeInitialPosition(1)
            model.saveNovelProgress(fixture.chapter, 96)
            awaitSave(saves, 1, model)
            assertEquals(96, fixture.chapter.chapter.last_page_read)
            assertFalse(fixture.chapter.chapter.read)
            verify(exactly = 0) { model["updateTrackChapterRead"](any<ReaderChapter>()) }
            verify(exactly = 0) { model["deleteChapterIfNeeded"](any<ReaderChapter>()) }
            verify(exactly = 0) { model["maybeTriggerSyncOnChapterRead"]() }
        }
    }
}
