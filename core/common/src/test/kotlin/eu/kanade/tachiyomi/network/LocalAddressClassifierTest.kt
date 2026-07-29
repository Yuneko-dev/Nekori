package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.InetAddress

class LocalAddressClassifierTest {

    @Test
    fun `recognizes local hostnames`() {
        listOf(
            "localhost",
            "printer",
            "reader.local",
            "reader.lan",
            "reader.home",
            "reader.home.arpa",
            "reader.internal",
        ).forEach { LocalAddressClassifier.isLocalHostname(it) shouldBe true }

        LocalAddressClassifier.isLocalHostname("example.com") shouldBe false
    }

    @Test
    fun `recognizes IP literals without resolving them`() {
        LocalAddressClassifier.isIpLiteral("192.168.1.5") shouldBe true
        LocalAddressClassifier.isIpLiteral("[fd00::1]") shouldBe true
        LocalAddressClassifier.isIpLiteral("example.com") shouldBe false
    }

    @Test
    fun `recognizes private and special-use addresses`() {
        listOf(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.1.1",
            "fc00::1",
        ).forEach {
            LocalAddressClassifier.isLocalAddress(InetAddress.getByName(it)) shouldBe true
        }

        LocalAddressClassifier.isLocalAddress(InetAddress.getByName("1.1.1.1")) shouldBe false
    }
}
