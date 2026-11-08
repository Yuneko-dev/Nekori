package tachiyomi.core.common.util.system

import java.io.InputStream

object ImageUtil {

    fun isImage(name: String?, openStream: (() -> InputStream)? = null): Boolean {
        if (name == null) return false

        val extension = name.substringAfterLast('.')
        return ImageType.entries.any { it.extension == extension } || openStream?.let { findImageType(it) } != null
    }

    fun findImageType(openStream: () -> InputStream): ImageType? = openStream().use(::findImageType)

    fun findImageType(stream: InputStream): ImageType? = try {
        ImageHeaderSniffer.sniff(stream)?.type
    } catch (_: Exception) {
        null
    }

    fun getExtensionFromMimeType(mime: String?, openStream: () -> InputStream): String {
        val type = mime?.let { value -> ImageType.entries.find { it.mime == value } } ?: findImageType(openStream)
        return type?.extension ?: "jpg"
    }

    enum class ImageType(val mime: String, val extension: String) {
        AVIF("image/avif", "avif"),
        GIF("image/gif", "gif"),
        HEIF("image/heif", "heif"),
        JPEG("image/jpeg", "jpg"),
        JXL("image/jxl", "jxl"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp"),
    }
}
