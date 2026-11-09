package eu.kanade.tachiyomi.network

import java.net.Inet6Address
import java.net.InetAddress

internal object LocalAddressClassifier {

    private val localDnsSuffixes = listOf(
        ".localhost",
        ".local",
        ".lan",
        ".home",
        ".home.arpa",
        ".internal",
    )

    fun isLocalHostname(value: String): Boolean {
        val host = value.trim().trimEnd('.').lowercase()
        return host == "localhost" ||
            (host.isNotEmpty() && '.' !in host && ':' !in host) ||
            localDnsSuffixes.any(host::endsWith)
    }

    fun isIpLiteral(value: String): Boolean {
        val host = value.trim().removePrefix("[").removeSuffix("]")
        return ':' in host || host.isIpv4Address()
    }

    fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress
        ) {
            return true
        }

        val bytes = address.address
        if (address is Inet6Address) {
            return bytes.isNotEmpty() && (bytes[0].toInt() and 0xFE) == 0xFC
        }
        if (bytes.size != 4) return false

        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 100 && second in 64..127)
    }

    private fun String.isIpv4Address(): Boolean {
        val octets = split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() &&
                octet.length <= 3 &&
                octet.all { it in '0'..'9' } &&
                octet.toInt() in 0..255
        }
    }
}
