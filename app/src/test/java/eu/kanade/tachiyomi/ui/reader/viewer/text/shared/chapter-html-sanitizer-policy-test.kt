@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterHtmlSanitizerPolicyTest {
    @Test
    fun `an unmatched escaped comment marker is prose and must not trigger plaintext fallback`() {
        val out = webView("<p>Use &lt;!-- to start a comment</p><img src='/cover.jpg'>", false)
        val doc = org.jsoup.Jsoup.parseBodyFragment(out)
        assertEquals("Use <!-- to start a comment", doc.selectFirst("p")?.text())
        assertEquals("/cover.jpg", doc.selectFirst("img")?.attr("src"))
    }

    @Test
    fun `JS CSS and media policies remain independent for both render targets`() {
        val html = "<style>p{color:red}</style>" +
            "<p style='color:blue' onclick='run()'>Text <ruby>字<rt>zi</rt></ruby></p>" +
            "<script>run()</script><img src='cover.jpg' loading='lazy'><video src='clip.mp4'></video>" +
            "<iframe src='https://example.invalid/' loading='lazy'></iframe>" +
            "<meta name='lnreader-video-type' content='iframe'>"
        for (target in RenderTarget.entries) {
            for (js in listOf(false, true)) {
                for (css in listOf(false, true)) {
                    for (block in listOf(false, true)) {
                        val out = HtmlUtils.sanitizeForRender(html, target, css, js, block)
                        val doc = org.jsoup.Jsoup.parseBodyFragment(out)
                        val keepJs = target == RenderTarget.WEB_VIEW && js
                        val keepCss = target == RenderTarget.WEB_VIEW && css
                        assertEquals(keepJs, doc.select("script").isNotEmpty())
                        assertEquals(keepJs, doc.selectFirst("p")!!.hasAttr("onclick"))
                        assertEquals(keepJs, doc.select("iframe").isNotEmpty())
                        assertEquals(keepCss, doc.select("style").isNotEmpty())
                        if (target == RenderTarget.WEB_VIEW) {
                            assertEquals(keepCss, doc.selectFirst("p")!!.hasAttr("style"))
                        }
                        assertEquals(!block, doc.select("img").isNotEmpty())
                        assertEquals(!block, doc.select("video").isNotEmpty())
                        assertEquals("字zi", doc.selectFirst("ruby")!!.text().replace(" ", ""))
                        assertEquals("iframe", doc.selectFirst("meta[name=lnreader-video-type]")?.attr("content"))
                        if (!block && target == RenderTarget.WEB_VIEW) {
                            assertEquals("eager", doc.selectFirst("img")?.attr("loading"))
                        }
                    }
                }
            }
        }
    }

    private fun webView(html: String, keepJs: Boolean) = HtmlUtils.sanitizeForRender(
        html,
        RenderTarget.WEB_VIEW,
        keepEmbeddedCss = true,
        keepEmbeddedJs = keepJs,
        blockMedia = false,
    )

    @Test
    fun `sanitizeForRender removes svg animations that can set javascript links when js is off`() {
        val out = webView(
            "<svg><a><animate attributeName=\"href\" values=\"javascript:alert(1)\"/><text>x</text></a>" +
                "<set attributeName=\"xlink:href\" to=\"javascript:a()\"/>" +
                "<animateMotion dur=\"1s\"/><animateTransform attributeName=\"transform\" from=\"0\"/></svg>",
            keepJs = false,
        )
        val doc = org.jsoup.Jsoup.parseBodyFragment(out)
        assertTrue(doc.select("animate, set, animatemotion, animatetransform").isEmpty())
        assertFalse(out.contains("javascript:", ignoreCase = true))
        assertTrue(out.contains("<text>x</text>"))
    }

    @Test
    fun `sanitizeForRender keeps svg animations when js is on`() {
        val out = webView("<svg><animate attributeName=\"opacity\" values=\"0;1\"/></svg>", keepJs = true)
        assertTrue(out.contains("<animate"))
    }

    @Test
    fun `sanitizeForRender removes embedded documents when js is off`() {
        val out = webView(
            "<iframe src=\"https://example.com/x.html\"></iframe><object data=\"https://example.com/x.swf\"></object>" +
                "<embed src=\"https://example.com/x.html\">" +
                "<frameset><frame src=\"https://example.com/f.html\"></frameset>" +
                "<p>text</p>",
            keepJs = false,
        )
        val doc = org.jsoup.Jsoup.parseBodyFragment(out)
        assertTrue(doc.select("iframe, frame, frameset, object, embed").isEmpty())
        assertFalse(out.contains("example.com"))
        assertTrue(out.contains("<p>text</p>"))
    }

    @Test
    fun `sanitizeForRender keeps iframes when js is on`() {
        val out = webView("<iframe src=\"https://example.com/v\"></iframe>", keepJs = true)
        assertTrue(out.contains("<iframe"))
    }

    @Test
    fun `sanitizeForRender always removes base`() {
        val html = "<base href=\"https://example.com/\"><img src=\"/a.jpg\">"
        assertFalse(webView(html, keepJs = false).contains("<base"))
        assertFalse(webView(html, keepJs = true).contains("<base"))
    }

    @Test
    fun `sanitizeForRender output stays clean when reparsed after foreign content mutation`() {
        val payloads = listOf(
            "<img src=\"/a.jpg\" loading=\"lazy\"><math><mtext><table><mglyph><style><img src=x onerror=alert(1)>",
            "<!-- x --><form><math><mtext></form><form><mglyph><style></math><img src onerror=alert(1)>",
        )
        for (payload in payloads) {
            val out = webView(payload, keepJs = false)
            val reparsed = org.jsoup.Jsoup.parseBodyFragment(out).apply { outputSettings().prettyPrint(false) }
            assertTrue(reparsed.select("[onerror]").isEmpty(), out)
            assertEquals(out, reparsed.body().html())
        }
    }

    @Test
    fun `sanitizeForRender drops an unterminated tag when js is off`() {
        val out = webView("<img src=x onerror=Android.x() ", keepJs = false)
        assertFalse(out.contains("onerror"))
    }
}
