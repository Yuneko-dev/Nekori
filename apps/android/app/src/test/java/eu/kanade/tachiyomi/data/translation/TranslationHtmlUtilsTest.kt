package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TranslationHtmlUtilsTest {

    @Test
    fun `translation plan preserves container styles inline tags and media`() {
        val plan = TranslationHtmlUtils.prepareTranslation(
            """<div class="chapter"><p style="color:red">Hello <strong>world</strong></p><img src="cover.jpg"></div>""",
        )

        plan.texts shouldContainExactly listOf("Hello <strong>world</strong>")

        val translated = plan.apply(listOf("Xin chào <strong>thế giới</strong>"))

        translated shouldBe
            """<div class="chapter"><p style="color:red">Xin chào <strong>thế giới</strong></p><img src="cover.jpg"></div>"""
    }

    @Test
    fun `translation plan preserves nested blocks and translates direct text nodes`() {
        val plan = TranslationHtmlUtils.prepareTranslation("<div>Intro<p>Body</p>Outro</div>")

        plan.texts shouldContainExactly listOf("Intro", "Body", "Outro")

        plan.apply(listOf("Mở đầu", "Nội dung", "Kết")) shouldBe
            "<div>Mở đầu<p>Nội dung</p>Kết</div>"
    }

    @Test
    fun `translation plan skips segments without letters`() {
        val plan = TranslationHtmlUtils.prepareTranslation("<p>* * *</p><p>Hello</p><p>123</p>")

        plan.texts shouldContainExactly listOf("Hello")

        plan.apply(listOf("Xin chào")) shouldBe "<p>* * *</p><p>Xin chào</p><p>123</p>"
    }

    @Test
    fun `partial translation resume ignores title and preserves paragraph breaks`() {
        val html = TranslationHtmlUtils.buildTranslatedHtml("Chapter", listOf("One\nTwo", "Three"))

        TranslationHtmlUtils.extractTranslatedParagraphs(html) shouldContainExactly
            listOf("One\nTwo", "Three")
    }
}
