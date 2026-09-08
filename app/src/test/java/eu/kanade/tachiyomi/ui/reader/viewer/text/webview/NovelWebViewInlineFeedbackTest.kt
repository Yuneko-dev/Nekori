package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NovelWebViewInlineFeedbackTest {

    @Test
    fun retryRunsOnceAndClearDiscardsThePreviousDocumentRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var retries = 0
            val feedback = NovelWebViewInlineFeedback(ContextWrapper(null), this) { }
            feedback.showInlineError(IllegalStateException("failed")) { retries++ }
            feedback.retryPendingError()
            feedback.retryPendingError()
            org.junit.jupiter.api.Assertions.assertEquals(1, retries)

            feedback.showInlineError(IllegalStateException("stale")) { retries++ }
            feedback.clear()
            feedback.retryPendingError()
            runCurrent()
            org.junit.jupiter.api.Assertions.assertEquals(1, retries)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun clearSuppressesQueuedRenderForThePreviousDocument() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val rendered = mutableListOf<String>()
            val feedback = NovelWebViewInlineFeedback(
                context = ContextWrapper(null),
                scope = this,
                evaluateJs = rendered::add,
            )

            feedback.showInlineError("stale")
            feedback.clear()
            runCurrent()

            assertTrue(rendered.none { it.contains("stale") }, rendered.joinToString())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
