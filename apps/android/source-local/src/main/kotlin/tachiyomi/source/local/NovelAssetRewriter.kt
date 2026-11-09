package tachiyomi.source.local

import mihon.core.archive.HtmlAssetRewriter
import mihon.core.archive.NOVEL_EPUB_CHAPTER_SCHEME
import mihon.core.archive.NOVEL_IMAGE_SCHEME
import mihon.core.archive.isResolvableAssetRef
import mihon.core.archive.novelImageUrl
import mihon.core.archive.relativeAssetScheme

internal object NovelAssetRewriter {

    const val SCHEME = NOVEL_IMAGE_SCHEME

    private val MD_IMAGE_REGEX = Regex("""(!\[[^\]]*]\()([^)\s]+)""")

    // Anchor rewriting is local-novel only (EPUB internal chapter links), so it keeps its own narrow
    // tag/attribute pair rather than widening HtmlAssetRewriter's resource-attribute set.
    private val ANCHOR_TAG_REGEX = Regex(
        "<a\\b(?:\"[^\"]*\"|'[^']*'|[^>])*>",
        RegexOption.IGNORE_CASE,
    )
    private val HREF_ATTR_REGEX = Regex(
        "(?<![\\w:-])(href)(\\s*=\\s*)(?:([\"'])(.*?)\\3|([^\\s\"'>]+))",
        RegexOption.IGNORE_CASE,
    )
    private val ABSOLUTE_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:|^//")

    fun rewrite(content: String, ext: String, toScheme: (String) -> String?): String {
        return when (ext.lowercase()) {
            "html", "htm", "xhtml" -> HtmlAssetRewriter.rewriteHtml(content, toScheme)
            "md", "markdown" ->
                HtmlAssetRewriter.rewriteHtml(rewriteMarkdownImages(content, toScheme), toScheme)
            else -> content
        }
    }

    fun rewriteEpubChapterLinks(content: String, currentHref: String): String {
        val currentPath = decodePath(currentHref.substringBefore('#')).replace('\\', '/')
        val baseDir = currentPath.substringBeforeLast('/', "")
        return ANCHOR_TAG_REGEX.replace(content) { tag ->
            HREF_ATTR_REGEX.replace(tag.value) { attr ->
                val quote = attr.groupValues[3]
                val rawHref = if (quote.isNotEmpty()) attr.groupValues[4] else attr.groupValues[5]
                val target = resolveEpubChapterHref(baseDir, currentPath, rawHref) ?: return@replace attr.value
                val encoded = java.net.URLEncoder.encode(target, "UTF-8")
                "${attr.groupValues[1]}${attr.groupValues[2]}$quote$NOVEL_EPUB_CHAPTER_SCHEME$encoded$quote"
            }
        }
    }

    private fun resolveEpubChapterHref(baseDir: String, currentPath: String, rawHref: String): String? {
        val href = decodePath(rawHref.trim()).replace('\\', '/')
        if (href.isBlank() || href.startsWith("//") || ABSOLUTE_SCHEME_REGEX.containsMatchIn(href)) return null

        val fragment = href.substringAfter('#', "").takeIf { '#' in href }
        val rawPath = href.substringBefore('#').substringBefore('?')
        val resolvedPath = if (rawPath.isBlank()) {
            currentPath
        } else {
            resolveArchivePath(if (rawPath.startsWith('/')) "" else baseDir, rawPath.removePrefix("/"))
                ?: return null
        }
        return if (fragment != null) "$resolvedPath#$fragment" else resolvedPath
    }

    private fun rewriteMarkdownImages(content: String, toScheme: (String) -> String?): String {
        return MD_IMAGE_REGEX.replace(content) { m ->
            "${m.groupValues[1]}${toScheme(m.groupValues[2]) ?: m.groupValues[2]}"
        }
    }

    // Root-absolute refs in a saved site point at the site root, which for a local novel is the
    // chapter's own base directory, so they resolve like relative refs once the leading slash is dropped.
    fun isResolvableRef(ref: String): Boolean = isResolvableAssetRef(ref)

    fun relativeScheme(ref: String): String? = relativeAssetScheme(ref)

    fun archiveScheme(baseDir: String, ref: String): String? {
        val v = ref.trim()
        if (!isResolvableRef(v)) return null
        val decoded = decodePath(v.substringBefore('?').substringBefore('#'))
        val effectiveBase = if (decoded.startsWith("/")) "" else baseDir
        val path = resolveArchivePath(effectiveBase, decoded) ?: return null
        if (path.isBlank()) return null
        return novelImageUrl(path)
    }

    // Saved web pages write pre-encoded refs; decode before re-encoding so "%20" doesn't become "%2520".
    // URLDecoder is form-decoding and maps "+" to space, but "+" is a literal in a URL path, so
    // shield it as "%2B" first to keep filenames like "a+b.png" intact.
    private fun decodePath(path: String): String =
        runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)

    // Returns null when a ".." escapes the archive root, matching LocalNovelSource.resolveRelativeFile
    // (both refuse to resolve an out-of-bounds ref rather than silently clamping to a wrong file).
    fun resolveArchivePath(baseDir: String, ref: String): String? {
        val stack = ArrayDeque<String>()
        baseDir.split('/').filter { it.isNotEmpty() }.forEach { stack.addLast(it) }
        for (segment in ref.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return null else stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return stack.joinToString("/")
    }
}
