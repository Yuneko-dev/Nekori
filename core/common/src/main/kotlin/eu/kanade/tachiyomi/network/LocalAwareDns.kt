package eu.kanade.tachiyomi.network

import okhttp3.Dns
import java.net.InetAddress

/**
 * Keeps IP literals and local network names on Android's resolver while public
 * names use the configured DNS-over-HTTPS provider.
 */
internal class LocalAwareDns(
    private val delegate: Dns,
    private val system: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> =
        if (LocalAddressClassifier.isLocalHostname(hostname) ||
            LocalAddressClassifier.isIpLiteral(hostname)
        ) {
            system.lookup(hostname)
        } else {
            delegate.lookup(hostname)
        }
}
