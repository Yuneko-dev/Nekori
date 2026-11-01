@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterHtmlSanitizerTest {
    @Test
    fun `sanitizeForRender WEB_VIEW also rewrites unquoted and single-quoted lazy loading`() {
        val out = HtmlUtils.sanitizeForRender(
            "<img src=\"/a.jpg\" loading=lazy><img src='/b.jpg' loading='lazy'>",
            target = RenderTarget.WEB_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = true,
            blockMedia = false,
        )
        assertFalse(out.contains("lazy"))
    }

    private fun webView(html: String, keepJs: Boolean) = HtmlUtils.sanitizeForRender(
        html,
        target = RenderTarget.WEB_VIEW,
        keepEmbeddedCss = true,
        keepEmbeddedJs = keepJs,
        blockMedia = false,
    )

    @Test
    fun `sanitizeForRender strips inline event handlers when js is off`() {
        val out = webView(
            "<img src=\"/x.jpg\" onerror=\"alert(1)\"><p onclick='a()' class=\"c\">t</p><b ONLOAD=a()>b</b>",
            keepJs = false,
        )
        assertFalse(out.contains("onerror", ignoreCase = true))
        assertFalse(out.contains("onclick", ignoreCase = true))
        assertFalse(out.contains("onload", ignoreCase = true))
        assertTrue(out.contains("src=\"/x.jpg\""))
        assertTrue(out.contains("class=\"c\""))
    }

    @Test
    fun `sanitizeForRender strips javascript urls when js is off`() {
        val out = webView(
            "<a href=\"javascript:alert(1)\">a</a><a href=' JavaScript:x()'>b</a><a href=\"https://example.com\">c</a>",
            keepJs = false,
        )
        assertFalse(out.contains("javascript:", ignoreCase = true))
        assertTrue(out.contains("href=\"https://example.com\""))
    }

    @Test
    fun `sanitizeForRender keeps handlers and javascript urls when js is on`() {
        val html = "<img src=\"/x.jpg\" onerror=\"a()\"><a href=\"javascript:b()\">a</a>"
        val out = webView(html, keepJs = true)
        assertTrue(out.contains("onerror=\"a()\""))
        assertTrue(out.contains("javascript:b()"))
    }

    @Test
    fun `sanitizeForRender leaves text that looks like a handler untouched`() {
        val out = webView("<p>use onclick=\"x\" in html</p>", keepJs = false)
        assertTrue(out.contains("use onclick=\"x\" in html"))
    }

    @Test
    fun `sanitizeForRender lazy rewrite does not touch pre or script text`() {
        val out = webView("<pre>loading=\"lazy\"</pre><img src=\"/x.jpg\" data-loading=\"lazy\" loading=lazy>", true)
        assertTrue(out.contains("<pre>loading=\"lazy\"</pre>"))
        assertTrue(out.contains("data-loading=\"lazy\""))
        assertTrue(out.contains("loading=\"eager\""))
    }

    @Test
    fun `sanitizeForRender lazy rewrite covers iframes`() {
        val out = webView("<iframe src=\"https://example.com/v\" loading=\"lazy\"></iframe>", true)
        assertTrue(out.contains("loading=\"eager\""))
    }

    @Test
    fun `sanitizeForRender strips slash separated and entity encoded vectors when js is off`() {
        val out = webView(
            "<svg/onload=a()><img src=x onerror=alert(1)><a href=\"jav&#x61;script:x()\">a</a>" +
                "<a href=\"java&#9;script:y()\">b</a>",
            keepJs = false,
        )
        assertFalse(out.contains("onerror", ignoreCase = true))
        assertFalse(out.contains("onload", ignoreCase = true))
        assertFalse(out.contains("script:", ignoreCase = true))
    }

    @Test
    fun `sanitizeForRender keeps handler-like text inside attribute values when js is off`() {
        val out = webView("<img src=\"/a.jpg\" alt=\"press onclick=x to go\">", keepJs = false)
        assertTrue(out.contains("alt=\"press onclick=x to go\""))
    }

    @Test
    fun `sanitizeForRender lazy rewrite cannot break out of an attribute`() {
        val out = webView("<img src=\"/a.jpg\" alt=\"loading=lazy onerror=Android.foo() x\">", keepJs = false)
        val img = org.jsoup.Jsoup.parseBodyFragment(out).selectFirst("img")!!
        assertEquals(setOf("src", "alt"), img.attributes().asList().map { it.key }.toSet())
    }

    @Test
    fun `sanitizeForRender noscript content cannot become live markup`() {
        val out =
            webView("<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></p></noscript>", keepJs = false)
        assertFalse(out.contains("onerror", ignoreCase = true))
    }

    @Test
    fun `sanitizeForRender comments cannot join attribute fragments into a tag`() {
        val out =
            webView("<p title=\"<!--\">a</p><p title=\"--><img src=x onmouseover=alert(1)>\">b</p>", keepJs = false)
        val doc = org.jsoup.Jsoup.parseBodyFragment(out)
        assertTrue(doc.select("[onmouseover]").isEmpty())
        assertTrue(doc.select("img").isEmpty())
    }

    @Test
    fun `sanitizeForRender strips srcdoc and data html urls when js is off`() {
        val out = webView(
            "<iframe srcdoc=\"<script>Android.x()</script>\"></iframe>" +
                "<iframe src=\"data:text/html,<script>x()</script>\"></iframe>" +
                "<img src=\"data:image/png;base64,AAAA\">",
            keepJs = false,
        )
        assertFalse(out.contains("srcdoc"))
        assertFalse(out.contains("data:text/html"))
        assertTrue(out.contains("data:image/png;base64,AAAA"))
    }

    @Test
    fun `sanitizeForRender script variants are removed`() {
        val out = webView("<script >a()</script ><scr<script></script>ipt>b()</script>", keepJs = false)
        assertTrue(org.jsoup.Jsoup.parseBodyFragment(out).select("script").isEmpty())
    }

    @Test
    fun `sanitizeForRender keeps javascript text in non url attributes and non handler on attributes`() {
        val out =
            webView(
                "<img src=\"/a.jpg\" alt=\"JavaScript: The Good Parts cover\" data-onboarding-step=\"1\">",
                keepJs = false,
            )
        assertTrue(out.contains("alt=\"JavaScript: The Good Parts cover\""))
        assertTrue(out.contains("data-onboarding-step=\"1\""))
    }

    @Test
    fun `sanitizeForRender returns clean chapters verbatim`() {
        val html = "<p class=\"a\">Hello <b>world</b></p>"
        assertEquals(html, webView(html, keepJs = false))
    }
}
