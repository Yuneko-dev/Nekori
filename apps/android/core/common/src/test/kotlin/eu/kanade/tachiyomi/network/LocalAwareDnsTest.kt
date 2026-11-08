package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import okhttp3.Dns
import org.junit.jupiter.api.Test
import java.net.InetAddress

class LocalAwareDnsTest {

    private val systemAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val dohAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))

    @Test
    fun `local names and IP literals use system DNS`() {
        val calls = mutableListOf<String>()
        val dns = LocalAwareDns(
            delegate = recordingDns("doh", dohAddress, calls),
            system = recordingDns("system", systemAddress, calls),
        )

        dns.lookup("reader.local") shouldBe listOf(systemAddress)
        dns.lookup("192.168.1.5") shouldBe listOf(systemAddress)
        calls shouldBe listOf("system:reader.local", "system:192.168.1.5")
    }

    @Test
    fun `public names use configured DoH`() {
        val calls = mutableListOf<String>()
        val dns = LocalAwareDns(
            delegate = recordingDns("doh", dohAddress, calls),
            system = recordingDns("system", systemAddress, calls),
        )

        dns.lookup("example.com") shouldBe listOf(dohAddress)
        calls shouldBe listOf("doh:example.com")
    }

    private fun recordingDns(
        name: String,
        address: InetAddress,
        calls: MutableList<String>,
    ) = Dns { hostname ->
        calls += "$name:$hostname"
        listOf(address)
    }
}
