package eu.kanade.tachiyomi.network.interceptor

/**
 * Resolves the rate-limit spec to apply to requests for a given host. Kept in core/common (no
 * domain dependency) so [PerHostDynamicRateLimitInterceptor] can be wired into NetworkHelper's
 * client without core/common depending on the domain module; the real implementation is
 * registered via Injekt from the app module instead.
 */
fun interface RequestRateLimitPolicy {
    /** Rate-limit spec for [host]; [RateLimitSpec.NONE] means no throttling. */
    fun specFor(host: String): RateLimitSpec

    /**
     * Whether pacing applies only to requests issued by plugin JavaScript (see [JsPluginOrigin]).
     * When true, everything else the app sends through the shared client - covers, trackers,
     * translations, the plugin repo itself - goes out unpaced regardless of [specFor].
     */
    fun jsPluginOnly(): Boolean = false
}
