package mihon.core.archive

import java.net.URLEncoder

/**
 * URL scheme for local-novel images resolved through the app's page loaders rather than the
 * network. Declared here in core.archive because it is the lowest module shared by every
 * producer/consumer (EpubReader/EpubWriter here, source-local's asset rewriter, and the app's
 * WebView/text-view image loaders), so they can't drift on the literal.
 */
const val NOVEL_IMAGE_SCHEME = "tsundoku-novel-image://"

/** Internal EPUB chapter link handled by the native reader instead of Chromium. */
const val NOVEL_EPUB_CHAPTER_SCHEME = "tsundoku-epub://chapter/"

// "%20" instead of URLEncoder's "+", since android.net.Uri.decode and java.net.URLDecoder disagree on "+".
fun novelImageUrl(path: String): String =
    NOVEL_IMAGE_SCHEME + URLEncoder.encode(path, "UTF-8").replace("+", "%20")
