package eu.kanade.tachiyomi.data.translation.engine

import eu.kanade.tachiyomi.data.translation.withTranslationTimeout
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.rateLimitExempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.parser.Parser
import tachiyomi.domain.translation.model.AiErrorCode
import tachiyomi.domain.translation.model.LanguageCodes
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.io.InterruptedIOException

class GoogleTranslateScraperEngine : TranslationEngine {
    override val id = TranslationEngineId.GOOGLE_FREE
    override val name = "Google Translate (Free)"
    override val requiresApiKey = false
    override val isRateLimited = true
    override val supportedLanguages = LanguageCodes.GOOGLE_TRANSLATE_LANGUAGES

    private val network: NetworkHelper by injectLazy()
    private val preferences: TranslationPreferences by injectLazy()
    private val client
        get() = network.client
            .withTranslationTimeout(preferences.translationTimeoutMs().get())
            .rateLimitExempt()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun translate(request: TranslationRequest): TranslationResult = withContext(Dispatchers.IO) {
        try {
            val translated = request.texts.toMutableList()
            chunk(request.texts).forEach { chunk ->
                val response = translateHtml(chunk.texts, request.sourceLanguage, request.targetLanguage)
                if (response.size != chunk.indices.size) {
                    error("Google Translate returned ${response.size} results for ${chunk.indices.size} inputs")
                }
                chunk.indices.forEachIndexed { index, originalIndex -> translated[originalIndex] = response[index] }
            }
            TranslationResult.Success(translated)
        } catch (e: GoogleTranslateHttpException) {
            TranslationResult.Error(e.message.orEmpty(), e.errorCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedIOException) {
            TranslationResult.Error(e.message ?: "Request timed out", AiErrorCode.TIMEOUT)
        } catch (e: IOException) {
            TranslationResult.Error(e.message ?: "Network request failed", AiErrorCode.NETWORK_ERROR)
        } catch (e: Exception) {
            TranslationResult.Error(e.message ?: "Google Translate failed", AiErrorCode.UNKNOWN)
        }
    }

    private suspend fun translateHtml(texts: List<String>, source: String, target: String): List<String> {
        val body = buildJsonArray {
            add(
                buildJsonArray {
                    add(JsonArray(texts.map(::JsonPrimitive)))
                    add(JsonPrimitive(source))
                    add(JsonPrimitive(target))
                },
            )
            add(JsonPrimitive("te"))
        }.toString()
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(body.toRequestBody(PROTOBUF_JSON))
            .header("x-client-data", "CIH/ygE=")
            .header("x-goog-api-key", API_KEY)
            .build()
        val response = client.newCall(request).await()
        val responseBody = response.use { it.body.string() }
        if (!response.isSuccessful) throw GoogleTranslateHttpException(response.code)

        return json.parseToJsonElement(responseBody).let { root ->
            (root as? JsonArray)?.firstOrNull()?.let { it as? JsonArray }
                ?.map { Parser.unescapeEntities((it as JsonPrimitive).content, false).trim() }
                ?: error("Google Translate returned an invalid response")
        }
    }

    private fun chunk(texts: List<String>): List<TextChunk> {
        val chunks = mutableListOf<TextChunk>()
        var values = mutableListOf<String>()
        var indices = mutableListOf<Int>()
        var length = 0

        fun flush() {
            if (values.isEmpty()) return
            chunks += TextChunk(values, indices)
            values = mutableListOf()
            indices = mutableListOf()
            length = 0
        }

        texts.forEachIndexed { index, text ->
            if (text.isBlank()) return@forEachIndexed
            if (values.isNotEmpty() && length + text.length > MAX_CHUNK_LENGTH) flush()
            values += text
            indices += index
            length += text.length
        }
        flush()
        return chunks
    }

    private data class TextChunk(val texts: List<String>, val indices: List<Int>)

    private class GoogleTranslateHttpException(code: Int) : IOException("Google Translate HTTP $code") {
        val errorCode = when (code) {
            408 -> AiErrorCode.TIMEOUT
            425, 429 -> AiErrorCode.RATE_LIMITED
            in 500..599 -> AiErrorCode.SERVICE_UNAVAILABLE
            else -> AiErrorCode.REQUEST_INVALID
        }
    }

    private companion object {
        const val ENDPOINT = "https://translate-pa.googleapis.com/v1/translateHtml"
        const val API_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520"
        const val MAX_CHUNK_LENGTH = 10_000
        val PROTOBUF_JSON = "application/json+protobuf".toMediaType()
    }
}
