package tachiyomi.core.common.util.system

import java.io.InputStream

/**
 * Reads an image's format out of its header.
 *
 * This replaces `tachiyomi.decoder.ImageDecoder.findType`, whose native library was 4.9 MB of
 * JXL/AVIF/HEIF decoders — all of it packaged so that a handful of call sites could name a file
 * extension. Magic bytes need no decoder. Decoding stays with the platform and Coil, so a format
 * the device cannot render is still reported here; every caller wants the type, not a bitmap.
 *
 * Kept separate from [ImageUtil] so the byte parsing stays directly testable on the JVM.
 */
internal object ImageHeaderSniffer {

    /** Enough for every signature below; the furthest is WebP's ANIM flag at byte 20. */
    private const val HEADER_BYTES = 32

    private val HEIF_SEQUENCE_BRANDS = setOf("hevc", "hevx", "hevm", "hevs", "msf1")
    private val HEIF_BRANDS = HEIF_SEQUENCE_BRANDS + setOf("heic", "heix", "heim", "heis", "mif1")

    class Result(val type: ImageUtil.ImageType, val isAnimated: Boolean)

    fun sniff(stream: InputStream): Result? {
        val bytes = ByteArray(HEADER_BYTES)

        val length = if (stream.markSupported()) {
            stream.mark(bytes.size)
            stream.read(bytes, 0, bytes.size).also { stream.reset() }
        } else {
            stream.read(bytes, 0, bytes.size)
        }

        if (length <= 0) {
            return null
        }

        return identify(bytes, length)
    }

    fun identify(bytes: ByteArray, length: Int): Result? {
        fun hasBytes(offset: Int, vararg expected: Int): Boolean =
            length >= offset + expected.size &&
                expected.withIndex().all { (index, value) -> bytes[offset + index].toInt() and 0xFF == value }

        fun hasText(offset: Int, expected: String): Boolean =
            length >= offset + expected.length &&
                expected.indices.all { bytes[offset + it].toInt().toChar() == expected[it] }

        fun textAt(offset: Int, size: Int): String? =
            if (length >= offset + size) String(bytes, offset, size, Charsets.ISO_8859_1) else null

        return when {
            hasBytes(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> Result(ImageUtil.ImageType.PNG, false)
            hasBytes(0, 0xFF, 0xD8, 0xFF) -> Result(ImageUtil.ImageType.JPEG, false)
            // Reported as animated whether or not the file holds more than one frame, which is what
            // the decoder this replaced did.
            hasText(0, "GIF8") -> Result(ImageUtil.ImageType.GIF, true)
            hasText(0, "RIFF") && hasText(8, "WEBP") -> {
                // Animation requires the extended file format, whose VP8X chunk carries an ANIM flag
                // in bit 1 of its first byte.
                val animated = hasText(12, "VP8X") && length > 20 && bytes[20].toInt() and 0x02 != 0
                Result(ImageUtil.ImageType.WEBP, animated)
            }
            // JPEG XL, as a bare codestream and inside its ISOBMFF container.
            hasBytes(0, 0xFF, 0x0A) -> Result(ImageUtil.ImageType.JXL, false)
            hasBytes(0, 0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A) ->
                Result(ImageUtil.ImageType.JXL, false)
            // The rest of the ISOBMFF family is told apart by major brand; the sequence brands are
            // the animated ones.
            hasText(4, "ftyp") -> when (val brand = textAt(8, 4)) {
                null -> null
                "avif", "avis" -> Result(ImageUtil.ImageType.AVIF, brand == "avis")
                in HEIF_BRANDS -> Result(ImageUtil.ImageType.HEIF, brand in HEIF_SEQUENCE_BRANDS)
                else -> null
            }
            else -> null
        }
    }
}
