package com.example.ai

import com.example.data.GeminiModels
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GeminiPart(
    val text: String = "",
    val thought: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GeminiThinkingConfig(
    val thinkingLevel: String
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int = 4096,
    val thinkingConfig: GeminiThinkingConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiGenerateRequest(
    @Json(name = "system_instruction") val systemInstruction: GeminiContent? = null,
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiPromptFeedback(
    val blockReason: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiGenerateResponse(
    val candidates: List<GeminiCandidate>? = null,
    val promptFeedback: GeminiPromptFeedback? = null
)

@JsonClass(generateAdapter = true)
data class GeminiErrorDetailItem(
    @Json(name = "@type") val type: String? = null,
    val reason: String? = null,
    val domain: String? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiErrorDetail(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
    val details: List<GeminiErrorDetailItem>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiErrorEnvelope(
    val error: GeminiErrorDetail? = null
)

sealed interface GeminiStreamEvent {
    data class Chunk(val textDelta: String) : GeminiStreamEvent
    data class Completed(val finishReason: String? = null) : GeminiStreamEvent
    data class Failure(val error: GeminiCallResult<Nothing>) : GeminiStreamEvent
}

interface GeminiTransport {
    suspend fun generate(
        model: String,
        apiKey: String,
        systemInstruction: String,
        contents: List<GeminiContent>
    ): GeminiCallResult<String>

    fun streamGenerate(
        model: String,
        apiKey: String,
        systemInstruction: String,
        contents: List<GeminiContent>
    ): Flow<GeminiStreamEvent>
}

interface GeminiRetrofitApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body body: GeminiGenerateRequest
    ): Response<GeminiGenerateResponse>

    @Streaming
    @POST("v1beta/models/{model}:streamGenerateContent")
    suspend fun streamGenerateContent(
        @Path("model") model: String,
        @Query("alt") alt: String = "sse",
        @Header("x-goog-api-key") apiKey: String,
        @Body body: GeminiGenerateRequest
    ): Response<ResponseBody>
}

class GeminiApiClient : GeminiTransport {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val requestAdapter = moshi.adapter(GeminiGenerateRequest::class.java)
    private val errorAdapter = moshi.adapter(GeminiErrorEnvelope::class.java)
    private val responseAdapter = moshi.adapter(GeminiGenerateResponse::class.java)

    // Intentionally omits HTTP header/body logging so API keys in x-goog-api-key and payloads are never logged.
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val api: GeminiRetrofitApi = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GeminiRetrofitApi::class.java)

    internal fun buildGenerationConfig(model: String): GeminiGenerationConfig {
        val option = GeminiModels.byId(model)
        val allowSampling = option?.supportsSamplingParams ?: (model != "gemini-4-hydrogen")
        val thinkingLevel = if (option?.supportsThinkingConfig == true) {
            option.thinkingLevel?.takeIf { !it.equals("minimal", ignoreCase = true) }
        } else {
            null
        }
        return GeminiGenerationConfig(
            temperature = if (allowSampling) 0.25 else null,
            topP = null,
            topK = null,
            maxOutputTokens = 4096,
            thinkingConfig = thinkingLevel?.let { GeminiThinkingConfig(thinkingLevel = it) }
        )
    }

    internal fun buildRequest(
        model: String,
        systemInstruction: String,
        contents: List<GeminiContent>
    ): GeminiGenerateRequest {
        return GeminiGenerateRequest(
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemInstruction))),
            contents = contents,
            generationConfig = buildGenerationConfig(model)
        )
    }

    internal fun encodeRequestJson(request: GeminiGenerateRequest): String {
        return requestAdapter.toJson(request)
    }

    override suspend fun generate(
        model: String,
        apiKey: String,
        systemInstruction: String,
        contents: List<GeminiContent>
    ): GeminiCallResult<String> {
        val request = buildRequest(model, systemInstruction, contents)

        val response = api.generateContent(
            model = model,
            apiKey = apiKey,
            body = request
        )

        if (response.isSuccessful) {
            val text = response.body()
                ?.candidates
                ?.firstOrNull()
                ?.content
                ?.parts
                .orEmpty()
                .filter { it.thought != true }
                .joinToString("\n") { it.text }
                .trim()

            return if (text.isNotEmpty()) {
                GeminiCallResult.Success(text, usedKeyId = "", usedKeyLabel = "", usedModel = model)
            } else {
                GeminiCallResult.NetworkOrServerError("Gemini returned an empty response.", response.code())
            }
        }

        return mapHttpError(
            code = response.code(),
            rawError = response.errorBody()?.string().orEmpty()
        )
    }

    override fun streamGenerate(
        model: String,
        apiKey: String,
        systemInstruction: String,
        contents: List<GeminiContent>
    ): Flow<GeminiStreamEvent> = flow {
        val request = buildRequest(model, systemInstruction, contents)

        var body: ResponseBody? = null
        try {
            val response = api.streamGenerateContent(
                model = model,
                alt = "sse",
                apiKey = apiKey,
                body = request
            )

            if (!response.isSuccessful) {
                val errBody = response.errorBody()?.string().orEmpty()
                emit(GeminiStreamEvent.Failure(mapHttpError(response.code(), errBody)))
                return@flow
            }

            body = response.body()
            if (body == null) {
                emit(
                    GeminiStreamEvent.Failure(
                        GeminiCallResult.NetworkOrServerError("Empty SSE response stream", response.code())
                    )
                )
                return@flow
            }

            val dataBuffer = StringBuilder()
            var lastFinishReason: String? = null
            val reader = BufferedReader(InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))

            reader.use { br ->
                while (currentCoroutineContext().isActive) {
                    val line = br.readLine() ?: break
                    if (line.isBlank()) {
                        if (dataBuffer.isNotEmpty()) {
                            val payload = dataBuffer.toString().trim()
                            dataBuffer.clear()
                            if (payload == "[DONE]") {
                                break
                            }
                            val parsedEvent = parseSsePayload(payload)
                            if (parsedEvent != null) {
                                if (parsedEvent.first.isNotEmpty()) {
                                    emit(GeminiStreamEvent.Chunk(parsedEvent.first))
                                }
                                if (parsedEvent.second != null) {
                                    lastFinishReason = parsedEvent.second
                                }
                            }
                        }
                    } else if (line.startsWith("data:")) {
                        val content = line.removePrefix("data:").trimStart()
                        if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                        dataBuffer.append(content)
                    }
                }

                // Flush trailing buffered event if stream closed right after a data line
                if (currentCoroutineContext().isActive && dataBuffer.isNotEmpty()) {
                    val payload = dataBuffer.toString().trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") {
                        val parsedEvent = parseSsePayload(payload)
                        if (parsedEvent != null) {
                            if (parsedEvent.first.isNotEmpty()) {
                                emit(GeminiStreamEvent.Chunk(parsedEvent.first))
                            }
                            if (parsedEvent.second != null) {
                                lastFinishReason = parsedEvent.second
                            }
                        }
                    }
                }
            }

            if (currentCoroutineContext().isActive) {
                emit(GeminiStreamEvent.Completed(lastFinishReason))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: "Streaming connection interrupted"
            emit(GeminiStreamEvent.Failure(GeminiCallResult.NetworkOrServerError(message)))
        } finally {
            runCatching { body?.close() }
        }
    }.flowOn(Dispatchers.IO)

    internal fun parseSsePayload(jsonPayload: String): Pair<String, String?>? {
        val parsed = runCatching { responseAdapter.fromJson(jsonPayload) }.getOrNull() ?: return null
        val candidate = parsed.candidates?.firstOrNull()
        val text = candidate?.content?.parts
            .orEmpty()
            .filter { it.thought != true }
            .joinToString("") { it.text }
        val finish = candidate?.finishReason ?: parsed.promptFeedback?.blockReason
        if (candidate == null && finish == null) return null
        return text to finish
    }

    internal fun mapHttpError(code: Int, rawError: String): GeminiCallResult<Nothing> {
        val parsed = runCatching { errorAdapter.fromJson(rawError) }.getOrNull()?.error
        val rawDetailMessage = parsed?.message?.takeIf { it.isNotBlank() }
            ?: "HTTP $code from Gemini API"
        val detailMessage = SensitiveDataRedactor.redact(rawDetailMessage)
        val statusString = parsed?.status.orEmpty()

        val isQuota = code == 429 ||
            statusString.equals("RESOURCE_EXHAUSTED", ignoreCase = true) ||
            detailMessage.contains("quota", ignoreCase = true) ||
            detailMessage.contains("rate limit", ignoreCase = true)

        val hasInvalidKeyReason = parsed?.details.orEmpty().any {
            it.reason.equals("API_KEY_INVALID", ignoreCase = true)
        }
        val hasInvalidKeyMessage = detailMessage.contains("API key not valid", ignoreCase = true) ||
            detailMessage.contains("API key expired", ignoreCase = true)
        val isAuthFailure = code == 401 ||
            statusString.equals("UNAUTHENTICATED", ignoreCase = true) ||
            (code == 400 && (hasInvalidKeyReason || hasInvalidKeyMessage))

        return when {
            isQuota -> GeminiCallResult.QuotaExceeded(detailMessage, code)
            isAuthFailure -> GeminiCallResult.AuthFailure(detailMessage, code)
            code == 403 || statusString.equals("PERMISSION_DENIED", ignoreCase = true) -> {
                GeminiCallResult.ForbiddenRetryable(detailMessage, code)
            }
            code == 404 || statusString.equals("NOT_FOUND", ignoreCase = true) -> {
                GeminiCallResult.ModelUnavailable(detailMessage, code)
            }
            else -> GeminiCallResult.NetworkOrServerError(detailMessage, code)
        }
    }
}
