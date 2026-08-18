package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.rateLimitExempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.translation.model.AIApiFamily
import tachiyomi.domain.translation.model.AIApiMode
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AiErrorCode
import tachiyomi.domain.translation.model.AiExecutionConfig
import tachiyomi.domain.translation.model.LlmGenerationRequest
import tachiyomi.domain.translation.model.LlmResult
import tachiyomi.domain.translation.model.mergeHeaders
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Talks to an OpenAI-compatible or Gemini provider, with no idea what the text is for.
 *
 * Everything provider-shaped lives here - endpoints, auth, custom headers, response extraction and
 * failure classification - so a new AI task only has to write a prompt and read [LlmResult].
 */
class LlmGenerator(
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val json: Json = Injekt.get(),
    private val preferences: TranslationPreferences = Injekt.get(),
) {
    /** Requests-per-minute pacing, applied before a call is issued rather than inside it. */
    private val pacer = RequestPacer()

    private var cachedClient: Pair<Long, OkHttpClient>? = null

    /**
     * Cached rather than rebuilt per call so the connection pool and the dispatcher's concurrency
     * budget survive between requests. Keyed by timeout alone - the RPM limit no longer lives on
     * the client, so changing it costs nothing here.
     */
    @Synchronized
    private fun client(): OkHttpClient {
        val timeoutMs = preferences.translationTimeoutMs().get()
        cachedClient?.takeIf { it.first == timeoutMs }?.let { return it.second }
        val built = networkHelper.client.withTranslationTimeout(timeoutMs).rateLimitExempt()
        cachedClient = timeoutMs to built
        return built
    }

    suspend fun generate(config: AiExecutionConfig, request: LlmGenerationRequest): LlmResult =
        withContext(Dispatchers.IO) {
            if (!config.isComplete) {
                val provider = config.provider
                    ?: return@withContext LlmResult.Failure(
                        "No AI provider configured",
                        AiErrorCode.REQUEST_INVALID,
                    )
                val message = when {
                    provider.endpoint.isBlank() -> "Provider endpoint is missing for ${provider.alias}"
                    provider.model.isBlank() -> "Model is missing for ${provider.alias}"
                    else -> "API key is missing for ${provider.alias}"
                }
                return@withContext LlmResult.Failure(
                    message,
                    if (provider.requiresApiKey && config.apiKey.isBlank()) {
                        AiErrorCode.API_KEY_MISSING
                    } else {
                        AiErrorCode.REQUEST_INVALID
                    },
                )
            }
            val provider = requireNotNull(config.provider)
            try {
                LlmResult.Success(execute(provider, config.apiKey, request))
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmHttpException) {
                LlmResult.Failure(e.message.orEmpty(), e.errorCode)
            } catch (e: IllegalArgumentException) {
                LlmResult.Failure(e.message ?: "Invalid provider request", AiErrorCode.REQUEST_INVALID)
            } catch (e: InterruptedIOException) {
                LlmResult.Failure(e.message ?: "Request timed out", AiErrorCode.TIMEOUT)
            } catch (e: IOException) {
                LlmResult.Failure(e.message ?: "Network request failed", AiErrorCode.NETWORK_ERROR)
            } catch (e: Exception) {
                LlmResult.Failure(e.message ?: "Request failed", AiErrorCode.UNKNOWN)
            }
        }

    suspend fun loadModels(provider: AIProvider, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val path = if (provider.type.apiFamily == AIApiFamily.GEMINI) "/v1beta/models" else "/models"
        val request = requestBuilder(provider, apiKey)
            .url(resolveProviderUrl(provider, path))
            .get()
            .build()
        parseProviderModels(json, call(request))
    }

    suspend fun testConnection(provider: AIProvider, apiKey: String) {
        loadModels(provider, apiKey)
    }

    private suspend fun execute(provider: AIProvider, apiKey: String, request: LlmGenerationRequest): String {
        val wire = LlmRequestFactory.create(provider, request)
        val httpRequest = requestBuilder(provider, apiKey)
            .url(resolveProviderUrl(provider, wire.path))
            .post(wire.body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return extractContent(provider, json.parseToJsonElement(call(httpRequest)))
    }

    private suspend fun call(request: Request): String {
        pacer.acquire(preferences.aiRpmLimit().get())
        val response = client().newCall(request).await()
        val body = response.use { it.body.string() }
        if (!response.isSuccessful) {
            throw LlmHttpException("HTTP ${response.code}: $body", AiErrorCode.fromHttpStatus(response.code))
        }
        return body
    }

    private fun requestBuilder(provider: AIProvider, apiKey: String): Request.Builder {
        val authHeaders = buildMap {
            put("Content-Type", "application/json")
            when {
                apiKey.isBlank() -> Unit
                // Gemini takes the key as a query parameter too; the header keeps it out of URLs.
                provider.type.apiFamily == AIApiFamily.GEMINI -> put("x-goog-api-key", apiKey)
                else -> put("Authorization", "Bearer $apiKey")
            }
        }
        val customHeaders = provider.customHeaders.takeIf {
            provider.type.apiFamily == AIApiFamily.OPENAI_COMPATIBLE
        }.orEmpty()
        return Request.Builder().apply {
            mergeHeaders(authHeaders, customHeaders).forEach(::header)
        }
    }

    private fun extractContent(provider: AIProvider, root: JsonElement): String {
        val obj = root.jsonObject
        return when {
            provider.type.apiFamily == AIApiFamily.GEMINI -> obj["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            provider.apiMode == AIApiMode.RESPONSES ->
                obj["output_text"]?.jsonPrimitive?.contentOrNull ?: obj["output"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")
                    ?.jsonPrimitive?.contentOrNull
            else -> obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")
                ?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        } ?: throw LlmHttpException("Provider returned no text", AiErrorCode.SERVICE_UNAVAILABLE)
    }

    private class LlmHttpException(message: String, val errorCode: AiErrorCode) : IOException(message)
}

/** The key rides in the `x-goog-api-key`/`Authorization` header, so no family needs it in the URL. */
internal fun resolveProviderUrl(provider: AIProvider, path: String): HttpUrl =
    "${provider.endpoint.trimEnd('/')}$path".toHttpUrl()

internal fun parseProviderModels(json: Json, body: String): List<String> {
    val root = json.parseToJsonElement(body).jsonObject
    val entries = root["data"]?.jsonArray ?: root["models"]?.jsonArray.orEmpty()
    return entries.mapNotNull {
        it.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('/')
    }.sorted()
}
