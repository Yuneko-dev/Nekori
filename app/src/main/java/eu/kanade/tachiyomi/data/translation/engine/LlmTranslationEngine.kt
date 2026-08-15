package eu.kanade.tachiyomi.data.translation.engine

import eu.kanade.tachiyomi.data.translation.InvalidStructuredOutputException
import eu.kanade.tachiyomi.data.translation.LlmPromptBuilder
import eu.kanade.tachiyomi.data.translation.LlmRequestFactory
import eu.kanade.tachiyomi.data.translation.LlmResponseParser
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.translation.model.AIApiFamily
import tachiyomi.domain.translation.model.AIProvider
import tachiyomi.domain.translation.model.AiExecutionConfig
import tachiyomi.domain.translation.model.LanguageCodes
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.model.mergeHeaders
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.SocketTimeoutException

class LlmTranslationEngine(
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val preferences: TranslationPreferences = Injekt.get(),
    private val json: Json = Injekt.get(),
) : TranslationEngine {

    private val client by lazy { networkHelper.client.rateLimitExempt() }

    override val id = TranslationEngineId.LLM
    override val name = "API LLM"
    override val requiresApiKey = true
    override val isRateLimited = true
    override val isOffline = false
    override val supportedLanguages = LanguageCodes.GOOGLE_TRANSLATE_LANGUAGES

    override fun isConfigured(config: AiExecutionConfig?): Boolean =
        config?.provider?.let { provider ->
            provider.endpoint.isNotBlank() && provider.model.isNotBlank() &&
                (!provider.requiresApiKey || config.apiKey.isNotBlank())
        } == true

    override suspend fun translate(request: TranslationRequest): TranslationResult = withContext(Dispatchers.IO) {
        val config = request.config ?: return@withContext TranslationResult.Error(
            "No translation profile configuration",
            TranslationResult.ErrorCode.REQUEST_INVALID,
        )
        val provider = config.provider ?: return@withContext TranslationResult.Error(
            "No AI provider configured",
            TranslationResult.ErrorCode.REQUEST_INVALID,
        )
        val apiKey = config.apiKey
        if (provider.requiresApiKey && apiKey.isBlank()) {
            return@withContext TranslationResult.Error(
                "API key is missing for ${provider.alias}",
                TranslationResult.ErrorCode.API_KEY_MISSING,
            )
        }
        val structured = preferences.structuredOutput().get()
        val prompt = LlmPromptBuilder.build(
            texts = request.texts,
            sourceLanguage = LanguageCodes.getDisplayName(request.sourceLanguage),
            targetLanguage = LanguageCodes.getDisplayName(request.targetLanguage),
            guidelines = config.guidelines.orEmpty(),
            structuredOutput = structured,
            context = request.context,
        )
        try {
            val content = execute(provider, apiKey, prompt.system, prompt.user, structured)
            TranslationResult.Success(LlmResponseParser.parse(content, request.texts, structured))
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidStructuredOutputException) {
            TranslationResult.Error(e.message.orEmpty(), TranslationResult.ErrorCode.STRUCTURED_OUTPUT_INVALID)
        } catch (e: IllegalArgumentException) {
            TranslationResult.Error(
                e.message ?: "Invalid provider request",
                TranslationResult.ErrorCode.REQUEST_INVALID,
            )
        } catch (e: LlmHttpException) {
            TranslationResult.Error(e.message.orEmpty(), e.errorCode)
        } catch (e: SocketTimeoutException) {
            TranslationResult.Error(e.message ?: "Request timed out", TranslationResult.ErrorCode.TIMEOUT)
        } catch (e: IOException) {
            TranslationResult.Error(e.message ?: "Network request failed", TranslationResult.ErrorCode.NETWORK_ERROR)
        } catch (e: Exception) {
            TranslationResult.Error(e.message ?: "Translation failed", TranslationResult.ErrorCode.UNKNOWN)
        }
    }

    suspend fun loadModels(provider: AIProvider, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val path = if (provider.type.apiFamily == AIApiFamily.GEMINI) "/v1beta/models" else "/models"
        val request = requestBuilder(provider, apiKey)
            .url(resolveProviderUrl(provider, path, apiKey))
            .get()
            .build()
        val response = client.newCall(request).await()
        val body = response.use { it.body.string() }
        if (!response.isSuccessful) throw httpError(response.code, body)
        parseProviderModels(json, body)
    }

    suspend fun testConnection(provider: AIProvider, apiKey: String) {
        loadModels(provider, apiKey)
    }

    private suspend fun execute(
        provider: AIProvider,
        apiKey: String,
        system: String,
        user: String,
        structured: Boolean,
    ): String {
        val wire = LlmRequestFactory.create(provider, system, user, structured)
        val request = requestBuilder(provider, apiKey)
            .url(resolveProviderUrl(provider, wire.path, apiKey))
            .post(wire.body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).await()
        val body = response.use { it.body.string() }
        if (!response.isSuccessful) throw httpError(response.code, body)
        return extractContent(provider, json.parseToJsonElement(body))
    }

    private fun requestBuilder(provider: AIProvider, apiKey: String): Request.Builder {
        val authHeaders = if (provider.type.apiFamily == AIApiFamily.GEMINI || apiKey.isBlank()) {
            mapOf("Content-Type" to "application/json")
        } else {
            mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $apiKey")
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
            provider.apiMode == tachiyomi.domain.translation.model.AIApiMode.RESPONSES ->
                obj["output_text"]?.jsonPrimitive?.contentOrNull ?: obj["output"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")
                    ?.jsonPrimitive?.contentOrNull
            else -> obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")
                ?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        } ?: throw LlmHttpException("Provider returned no text", TranslationResult.ErrorCode.SERVICE_UNAVAILABLE)
    }

    private fun httpError(code: Int, body: String): LlmHttpException {
        val errorCode = when (code) {
            401, 403 -> TranslationResult.ErrorCode.API_KEY_INVALID
            408 -> TranslationResult.ErrorCode.TIMEOUT
            425, 429 -> TranslationResult.ErrorCode.RATE_LIMITED
            in 500..599 -> TranslationResult.ErrorCode.SERVICE_UNAVAILABLE
            in 400..499 -> TranslationResult.ErrorCode.REQUEST_INVALID
            else -> TranslationResult.ErrorCode.UNKNOWN
        }
        return LlmHttpException("HTTP $code: $body", errorCode)
    }

    private class LlmHttpException(message: String, val errorCode: TranslationResult.ErrorCode) : IOException(message)
}

internal fun resolveProviderUrl(provider: AIProvider, path: String, apiKey: String): HttpUrl {
    val url = "${provider.endpoint.trimEnd('/')}$path".toHttpUrl()
    return if (provider.type.apiFamily == AIApiFamily.GEMINI) {
        url.newBuilder().addQueryParameter("key", apiKey).build()
    } else {
        url
    }
}

internal fun parseProviderModels(json: Json, body: String): List<String> {
    val root = json.parseToJsonElement(body).jsonObject
    val entries = root["data"]?.jsonArray ?: root["models"]?.jsonArray.orEmpty()
    return entries.mapNotNull {
        it.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('/')
    }.sorted()
}
