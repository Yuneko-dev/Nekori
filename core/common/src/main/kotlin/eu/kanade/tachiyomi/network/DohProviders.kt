package eu.kanade.tachiyomi.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * Based on https://github.com/square/okhttp/blob/ef5d0c83f7bbd3a0c0534e7ca23cbc4ee7550f3b/okhttp-dnsoverhttps/src/test/java/okhttp3/dnsoverhttps/DohProviders.java
 */

const val PREF_DOH_CLOUDFLARE = 1
const val PREF_DOH_GOOGLE = 2
const val PREF_DOH_ADGUARD = 3
const val PREF_DOH_QUAD9 = 4
const val PREF_DOH_ALIDNS = 5
const val PREF_DOH_DNSPOD = 6
const val PREF_DOH_360 = 7
const val PREF_DOH_QUAD101 = 8
const val PREF_DOH_MULLVAD = 9
const val PREF_DOH_CONTROLD = 10
const val PREF_DOH_NJALLA = 11
const val PREF_DOH_SHECAN = 12

fun OkHttpClient.Builder.dohCloudflare() = doh(
    "https://cloudflare-dns.com/dns-query",
    "162.159.36.1",
    "162.159.46.1",
    "1.1.1.1",
    "1.0.0.1",
    "162.159.132.53",
    "2606:4700:4700::1111",
    "2606:4700:4700::1001",
    "2606:4700:4700::0064",
    "2606:4700:4700::6400",
)

fun OkHttpClient.Builder.dohGoogle() = doh(
    "https://dns.google/dns-query",
    "8.8.4.4",
    "8.8.8.8",
    "2001:4860:4860::8888",
    "2001:4860:4860::8844",
)

// AdGuard "Default" DNS works too but for the sake of making sure no site is blacklisted,
// we use "Unfiltered"
fun OkHttpClient.Builder.dohAdGuard() = doh(
    "https://dns-unfiltered.adguard.com/dns-query",
    "94.140.14.140",
    "94.140.14.141",
    "2a10:50c0::1:ff",
    "2a10:50c0::2:ff",
)

fun OkHttpClient.Builder.dohQuad9() = doh(
    "https://dns.quad9.net/dns-query",
    "9.9.9.9",
    "149.112.112.112",
    "2620:fe::fe",
    "2620:fe::9",
)

fun OkHttpClient.Builder.dohAliDNS() = doh(
    "https://dns.alidns.com/dns-query",
    "223.5.5.5",
    "223.6.6.6",
    "2400:3200::1",
    "2400:3200:baba::1",
)

fun OkHttpClient.Builder.dohDNSPod() = doh(
    "https://doh.pub/dns-query",
    "1.12.12.12",
    "120.53.53.53",
)

fun OkHttpClient.Builder.doh360() = doh(
    "https://doh.360.cn/dns-query",
    "101.226.4.6",
    "218.30.118.6",
    "123.125.81.6",
    "140.207.198.6",
    "180.163.249.75",
    "101.199.113.208",
    "36.99.170.86",
)

fun OkHttpClient.Builder.dohQuad101() = doh(
    "https://dns.twnic.tw/dns-query",
    "101.101.101.101",
    "2001:de4::101",
    "2001:de4::102",
)

/*
 * Mullvad DoH
 * without ad blocking option
 * Source: https://mullvad.net/en/help/dns-over-https-and-dns-over-tls
 */
fun OkHttpClient.Builder.dohMullvad() = doh(
    "https://dns.mullvad.net/dns-query",
    "194.242.2.2",
    "2a07:e340::2",
)

/*
 * Control D
 * unfiltered option
 * Source: https://controld.com/free-dns/?
 */
fun OkHttpClient.Builder.dohControlD() = doh(
    "https://freedns.controld.com/p0",
    "76.76.2.0",
    "76.76.10.0",
    "2606:1a40::",
    "2606:1a40:1::",
)

/*
 * Njalla
 * Non logging and uncensored
 */
fun OkHttpClient.Builder.dohNajalla() = doh(
    "https://dns.njal.la/dns-query",
    "95.215.19.53",
    "2001:67c:2354:2::53",
)

/**
 * Source: https://shecan.ir/
 */
fun OkHttpClient.Builder.dohShecan() = doh(
    "https://free.shecan.ir/dns-query",
    "178.22.122.100",
    "185.51.200.2",
)

private fun OkHttpClient.Builder.doh(
    url: String,
    vararg bootstrapHosts: String,
): OkHttpClient.Builder {
    val resolver = DnsOverHttps.Builder()
        .client(build())
        .url(url.toHttpUrl())
        .bootstrapDnsHosts(*bootstrapHosts.map(InetAddress::getByName).toTypedArray())
        .build()
    return dns(LocalAwareDns(resolver))
}
