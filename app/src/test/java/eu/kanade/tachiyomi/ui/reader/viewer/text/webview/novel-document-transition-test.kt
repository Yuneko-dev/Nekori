@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class NovelDocumentTransitionTest {
    @Test
    fun `ready from the previous bridge cannot commit a document still being prepared`() {
        val viewer = mockk<NovelWebViewViewer>(relaxed = true)
        every { viewer["beginDocumentTransition"](any<Boolean>()) } answers { callOriginal() }
        every { viewer["handleReaderReady"]() } answers { callOriginal() }
        every { viewer["endEditingForDocumentChange"]() } returns Unit
        every { viewer["finishRealChapterLoad"]() } returns Unit

        invoke(viewer, "beginDocumentTransition", true)
        invoke(viewer, "handleReaderReady")
        verify(exactly = 0) { viewer["finishRealChapterLoad"]() }

        val state = NovelWebViewViewer::class.java.getDeclaredField("docState").apply { isAccessible = true }
        state.set(viewer, state.type.enumConstants.first { (it as Enum<*>).name == "LOADING_REAL" })
        invoke(viewer, "handleReaderReady")
        verify(exactly = 1) { viewer["finishRealChapterLoad"]() }
    }

    private fun invoke(viewer: NovelWebViewViewer, name: String, vararg values: Any) {
        val method = NovelWebViewViewer::class.java.declaredMethods.single { it.name == name }
        method.isAccessible = true
        method.invoke(viewer, *values)
    }
}
