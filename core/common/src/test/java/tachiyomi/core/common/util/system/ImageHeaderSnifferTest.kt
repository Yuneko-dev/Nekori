package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class ImageHeaderSnifferTest {

    private fun header(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun identify(bytes: ByteArray) = ImageHeaderSniffer.identify(bytes, bytes.size)

    @Test
    fun `identifies png`() {
        val png = header(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)
        assertEquals(ImageUtil.ImageType.PNG, identify(png)?.type)
    }

    @Test
    fun `identifies jpeg`() {
        assertEquals(ImageUtil.ImageType.JPEG, identify(header(0xFF, 0xD8, 0xFF, 0xE0))?.type)
    }

    @Test
    fun `identifies gif and reports it as animated`() {
        val gif = identify(ascii("GIF89a") + ByteArray(8))
        assertEquals(ImageUtil.ImageType.GIF, gif?.type)
        assertTrue(gif!!.isAnimated)
    }

    @Test
    fun `identifies still webp`() {
        // "RIFF" + size + "WEBP" + a lossy chunk, which carries no animation.
        val webp = ascii("RIFF") + ByteArray(4) + ascii("WEBP") + ascii("VP8 ") + ByteArray(12)
        val result = identify(webp)
        assertEquals(ImageUtil.ImageType.WEBP, result?.type)
        assertFalse(result!!.isAnimated)
    }

    @Test
    fun `reads the anim flag out of an extended webp`() {
        val animFlag = header(0x02) + ByteArray(11)
        val webp = ascii("RIFF") + ByteArray(4) + ascii("WEBP") + ascii("VP8X") + ByteArray(4) + animFlag
        val result = identify(webp)
        assertEquals(ImageUtil.ImageType.WEBP, result?.type)
        assertTrue(result!!.isAnimated)
    }

    @Test
    fun `an extended webp without the anim flag is still`() {
        val webp = ascii("RIFF") + ByteArray(4) + ascii("WEBP") + ascii("VP8X") + ByteArray(16)
        assertFalse(identify(webp)!!.isAnimated)
    }

    @Test
    fun `identifies jxl as a bare codestream and in its container`() {
        assertEquals(ImageUtil.ImageType.JXL, identify(header(0xFF, 0x0A, 0x00, 0x00))?.type)

        val container = header(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A)
        assertEquals(ImageUtil.ImageType.JXL, identify(container)?.type)
    }

    @Test
    fun `tells avif and heif apart by brand`() {
        val avif = ByteArray(4) + ascii("ftyp") + ascii("avif") + ByteArray(8)
        assertEquals(ImageUtil.ImageType.AVIF, identify(avif)?.type)
        assertFalse(identify(avif)!!.isAnimated)

        val heif = ByteArray(4) + ascii("ftyp") + ascii("heic") + ByteArray(8)
        assertEquals(ImageUtil.ImageType.HEIF, identify(heif)?.type)
        assertFalse(identify(heif)!!.isAnimated)
    }

    @Test
    fun `sequence brands are animated`() {
        val avis = ByteArray(4) + ascii("ftyp") + ascii("avis") + ByteArray(8)
        assertTrue(identify(avis)!!.isAnimated)

        val msf1 = ByteArray(4) + ascii("ftyp") + ascii("msf1") + ByteArray(8)
        val result = identify(msf1)
        assertEquals(ImageUtil.ImageType.HEIF, result?.type)
        assertTrue(result!!.isAnimated)
    }

    @Test
    fun `rejects an unknown ftyp brand`() {
        val mp4 = ByteArray(4) + ascii("ftyp") + ascii("isom") + ByteArray(8)
        assertNull(identify(mp4))
    }

    @Test
    fun `rejects what is not an image, and headers too short to tell`() {
        assertNull(identify(ascii("<!DOCTYPE html>")))
        assertNull(identify(header(0xFF)))
        assertNull(identify(ByteArray(0)))
        // A truncated PNG signature must not pass as one.
        assertNull(identify(header(0x89, 0x50, 0x4E)))
    }

    @Test
    fun `leaves the stream where it found it`() {
        val png = header(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)
        val stream = ByteArrayInputStream(png)

        assertEquals(ImageUtil.ImageType.PNG, ImageHeaderSniffer.sniff(stream)?.type)
        // ByteArrayInputStream supports marking, so a caller reading afterwards still sees the
        // header — the callers here hand the same stream on to a decoder.
        assertEquals(0x89, stream.read())
    }

    @Test
    fun `an empty stream is not an image`() {
        assertNull(ImageHeaderSniffer.sniff(ByteArrayInputStream(ByteArray(0))))
    }
}
