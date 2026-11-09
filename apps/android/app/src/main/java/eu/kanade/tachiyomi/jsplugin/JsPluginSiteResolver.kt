package eu.kanade.tachiyomi.jsplugin

internal fun resolveJsPluginSite(metadataSite: String?, code: String): String {
    val siteFromMetadata = metadataSite.orEmpty().trim()
    if (siteFromMetadata.isNotBlank()) {
        return siteFromMetadata.trimEnd('/')
    }

    // Direct single-source pattern: this.site = "https://example.com"
    val directSite = """this\.site\s*=\s*[\"']([^\"']+)[\"']""".toRegex()
        .find(code)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.trimEnd('/')
    if (!directSite.isNullOrBlank()) {
        return directSite
    }

    // Multisrc pattern: new Source({ sourceSite: "https://example.com/", ... })
    val sourceSite = """sourceSite\s*:\s*[\"']([^\"']+)[\"']""".toRegex()
        .find(code)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.trimEnd('/')
    if (!sourceSite.isNullOrBlank()) {
        return sourceSite
    }

    return ""
}
