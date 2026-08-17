package eu.kanade.tachiyomi.data.translation.engine

import eu.kanade.tachiyomi.data.translation.withTranslationTimeout
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.rateLimitExempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.translation.model.AiErrorCode
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.io.InterruptedIOException

/**
 * LibreTranslate translation engine.
 * Uses the free and open-source LibreTranslate API.
 * https://github.com/LibreTranslate/LibreTranslate
 */
class LibreTranslateEngine(
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val preferences: TranslationPreferences = Injekt.get(),
) : TranslationEngine {

    private val client: OkHttpClient
        get() = networkHelper.client
            .withTranslationTimeout(preferences.translationTimeoutMs().get())
            .rateLimitExempt()

    override val id = TranslationEngineId.LIBRE
    override val name: String = "LibreTranslate"
    override val requiresApiKey: Boolean = false
    override val isRateLimited: Boolean = true // Public instances have rate limits

    override val supportedLanguages: List<Pair<String, String>> = listOf(
        "auto" to "Auto-detect",
        "ar" to "Arabic",
        "az" to "Azerbaijani",
        "cs" to "Czech",
        "da" to "Danish",
        "de" to "German",
        "el" to "Greek",
        "en" to "English",
        "eo" to "Esperanto",
        "es" to "Spanish",
        "fa" to "Persian",
        "fi" to "Finnish",
        "fr" to "French",
        "ga" to "Irish",
        "he" to "Hebrew",
        "hi" to "Hindi",
        "hu" to "Hungarian",
        "id" to "Indonesian",
        "it" to "Italian",
        "ja" to "Japanese",
        "ko" to "Korean",
        "nl" to "Dutch",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "sk" to "Slovak",
        "sv" to "Swedish",
        "th" to "Thai",
        "tr" to "Turkish",
        "uk" to "Ukrainian",
        "vi" to "Vietnamese",
        "zh" to "Chinese",
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class TranslateRequest(
        val q: List<String>,
        val source: String,
        val target: String,
        val format: String = "html",
        @SerialName("api_key")
        val apiKey: String? = null,
    )

    @Serializable
    private data class TranslateResponse(
        @SerialName("translatedText")
        val translatedText: JsonElement,
        val detectedLanguage: JsonElement? = null,
    )

    @Serializable
    private data class DetectedLanguage(
        val confidence: Double,
        val language: String,
    )

    @Serializable
    private data class ErrorResponse(
        val error: String,
    )

    override suspend fun translate(request: TranslationRequest): TranslationResult = withContext(Dispatchers.IO) {
        val (texts, sourceLanguage, targetLanguage) = request
        try {
            val apiUrl = preferences.libreTranslateUrl().get()
            val apiKey = preferences.libreTranslateApiKey().get().takeIf { it.isNotBlank() }

            val translatedTexts = translateBatch(apiUrl, apiKey, texts, sourceLanguage, targetLanguage)

            val detectedLanguage = if (sourceLanguage == "auto") {
                // Could extract from first response, but keeping simple for now
                null
            } else {
                null
            }

            TranslationResult.Success(translatedTexts, detectedLanguage)
        } catch (e: TranslationException) {
            TranslationResult.Error(e.message ?: "Translation failed", e.errorCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedIOException) {
            TranslationResult.Error(e.message ?: "Request timed out", AiErrorCode.TIMEOUT)
        } catch (e: IOException) {
            TranslationResult.Error(e.message ?: "Network request failed", AiErrorCode.NETWORK_ERROR)
        } catch (e: Exception) {
            TranslationResult.Error(
                e.message ?: "Unknown error",
                AiErrorCode.UNKNOWN,
            )
        }
    }

    private suspend fun translateBatch(
        apiUrl: String,
        apiKey: String?,
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<String> {
        val requestBody = json.encodeToString(
            TranslateRequest.serializer(),
            TranslateRequest(
                q = texts,
                source = sourceLanguage,
                target = targetLanguage,
                apiKey = apiKey,
            ),
        )

        val request = Request.Builder()
            .url(apiUrl)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).await()
        val responseBody = response.use { it.body.string() }

        if (!response.isSuccessful) {
            val errorCode = when (response.code) {
                401, 403 -> AiErrorCode.API_KEY_INVALID
                408 -> AiErrorCode.TIMEOUT
                425, 429 -> AiErrorCode.RATE_LIMITED
                in 500..599 -> AiErrorCode.SERVICE_UNAVAILABLE
                in 400..499 -> AiErrorCode.REQUEST_INVALID
                else -> AiErrorCode.UNKNOWN
            }

            val errorMessage = try {
                json.decodeFromString(ErrorResponse.serializer(), responseBody).error
            } catch (e: Exception) {
                "HTTP ${response.code}: $responseBody"
            }

            throw TranslationException(errorMessage, errorCode)
        }

        val translateResponse = json.decodeFromString(TranslateResponse.serializer(), responseBody)

        return when (val element = translateResponse.translatedText) {
            is kotlinx.serialization.json.JsonArray -> element.map { it.jsonPrimitive.content }
            else -> listOf(element.jsonPrimitive.content)
        }
    }

    @Serializable
    private data class DetectRequest(
        val q: String,
        @SerialName("api_key")
        val apiKey: String? = null,
    )

    @Serializable
    private data class DetectResponse(
        val language: String,
        val confidence: Double,
    )

    suspend fun detectLanguage(text: String): String? = withContext(Dispatchers.IO) {
        try {
            var apiUrl = preferences.libreTranslateUrl().get()
            if (apiUrl.endsWith("/translate")) {
                apiUrl = apiUrl.substringBeforeLast("/translate") + "/detect"
            } else if (!apiUrl.endsWith("/detect")) {
                apiUrl = apiUrl.trimEnd('/') + "/detect"
            }

            val apiKey = preferences.libreTranslateApiKey().get().takeIf { it.isNotBlank() }

            val requestBody = json.encodeToString(
                DetectRequest.serializer(),
                DetectRequest(
                    q = text,
                    apiKey = apiKey,
                ),
            )

            val request = Request.Builder()
                .url(apiUrl)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).await()
            val responseBody = response.use { it.body.string() }

            if (!response.isSuccessful) return@withContext null

            // Response is a list of detections
            val detections = json.decodeFromString<List<DetectResponse>>(responseBody)
            detections.maxByOrNull { it.confidence }?.language
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private class TranslationException(
        message: String,
        val errorCode: AiErrorCode,
    ) : Exception(message)
}
