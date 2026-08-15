package tachiyomi.domain.translation.model

/**
 * Why an AI request failed. Shared by every task that talks to a provider - translation today,
 * chapter summaries next - because the failures are the provider's, not the task's.
 *
 * [retryable] lives here rather than in a caller's retry policy so that one classification serves
 * every task: a rate limit is worth another attempt no matter what was being asked for.
 */
enum class AiErrorCode(val retryable: Boolean = false) {
    UNKNOWN,
    NETWORK_ERROR(retryable = true),
    API_KEY_INVALID,
    API_KEY_MISSING,
    RATE_LIMITED(retryable = true),
    QUOTA_EXCEEDED,
    LANGUAGE_NOT_SUPPORTED,
    TEXT_TOO_LONG,
    SERVICE_UNAVAILABLE(retryable = true),
    REQUEST_INVALID,
    STRUCTURED_OUTPUT_INVALID,
    TIMEOUT(retryable = true),
    ;

    companion object {
        /** The classification an HTTP status carries on its own, before any provider-specific body. */
        fun fromHttpStatus(code: Int): AiErrorCode = when (code) {
            401, 403 -> API_KEY_INVALID
            408 -> TIMEOUT
            425, 429 -> RATE_LIMITED
            in 500..599 -> SERVICE_UNAVAILABLE
            in 400..499 -> REQUEST_INVALID
            else -> UNKNOWN
        }
    }
}
