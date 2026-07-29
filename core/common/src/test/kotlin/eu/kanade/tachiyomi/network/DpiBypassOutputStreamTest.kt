package eu.kanade.tachiyomi.network

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class DpiBypassOutputStreamTest {

    @Test
    fun `splits and normalizes the first HTTP request`() {
        val output = RecordingOutputStream()
        val stream = BypassOutputStream(output, delay = {})
        val request = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        stream.write(request)
        stream.flush()

        output.chunks.first().size shouldBe 1
        output.text().lowercase() shouldContain "host: example.com.\r\n"
    }

    @Test
    fun `splits an unknown first payload without changing it`() {
        val output = RecordingOutputStream()
        val stream = BypassOutputStream(output, delay = {})
        val payload = byteArrayOf(1, 2, 3, 4)

        stream.write(payload)
        stream.flush()

        output.chunks shouldHaveSize 2
        output.chunks.first() shouldBe byteArrayOf(1)
        output.bytes() shouldBe payload
    }

    @Test
    fun `splits TLS ClientHello into valid records with unchanged payload`() {
        val output = RecordingOutputStream()
        val stream = BypassOutputStream(output, delay = {})
        val clientHello = clientHello("example.com")

        stream.write(clientHello)
        stream.flush()

        val records = tlsRecords(output.bytes())
        (records.size > 1) shouldBe true
        records.flatMap(ByteArray::asIterable).toByteArray() shouldBe clientHello.copyOfRange(5, clientHello.size)
    }

    private fun clientHello(hostname: String): ByteArray {
        val host = hostname.toByteArray(Charsets.US_ASCII)
        val extensionsLength = 9 + host.size
        val payloadLength = 47 + extensionsLength
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x16, 0x03, 0x01, (payloadLength shr 8).toByte(), payloadLength.toByte()))
            write(byteArrayOf(0x01, 0x00, 0x00, (payloadLength - 4).toByte()))
            write(byteArrayOf(0x03, 0x03))
            write(ByteArray(32))
            write(0)
            write(byteArrayOf(0, 2, 0x13, 0x01))
            write(byteArrayOf(1, 0))
            write(byteArrayOf((extensionsLength shr 8).toByte(), extensionsLength.toByte()))
            write(byteArrayOf(0, 0, 0, (5 + host.size).toByte()))
            write(byteArrayOf(0, (3 + host.size).toByte(), 0, 0, host.size.toByte()))
            write(host)
        }.toByteArray()
    }

    private fun tlsRecords(bytes: ByteArray): List<ByteArray> {
        val records = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            bytes[offset] shouldBe 0x16.toByte()
            val length = ((bytes[offset + 3].toInt() and 0xFF) shl 8) or
                (bytes[offset + 4].toInt() and 0xFF)
            records += bytes.copyOfRange(offset + 5, offset + 5 + length)
            offset += 5 + length
        }
        return records
    }

    private class RecordingOutputStream : OutputStream() {
        val chunks = mutableListOf<ByteArray>()

        override fun write(value: Int) {
            chunks += byteArrayOf(value.toByte())
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            chunks += bytes.copyOfRange(offset, offset + length)
        }

        fun bytes() = chunks.flatMap(ByteArray::asIterable).toByteArray()

        fun text() = bytes().toString(Charsets.ISO_8859_1)
    }
}
