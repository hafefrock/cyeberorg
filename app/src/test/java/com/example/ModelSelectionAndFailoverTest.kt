package com.example

import com.example.ai.AiAssistantRepository
import com.example.ai.AiPromptKind
import com.example.ai.AiPromptRequest
import com.example.ai.AiStreamUpdate
import com.example.ai.GeminiApiClient
import com.example.ai.GeminiCallResult
import com.example.ai.GeminiContent
import com.example.ai.GeminiPart
import com.example.ai.GeminiStreamEvent
import com.example.ai.GeminiTransport
import com.example.ai.KeyHealthStatus
import com.example.ai.KeyManager
import com.example.data.ApiKeyMetadata
import com.example.data.AppLanguage
import com.example.data.AppSettings
import com.example.data.ExtraKeysLayout
import com.example.data.FallbackModelEntry
import com.example.data.GeminiModels
import com.example.data.KeyRoutingStrategy
import com.example.data.SettingsStore
import com.example.data.VaultStatus
import com.example.ui.UiText
import com.example.ui.theme.AppThemePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionAndFailoverTest {

    private class TestSettingsStore(
        initialModel: String? = null,
        initialFallback: List<FallbackModelEntry> = emptyList()
    ) : SettingsStore {
        private val secrets = mutableMapOf<String, String>()
        private val state = MutableStateFlow(
            AppSettings(
                aiConsentAccepted = true,
                selectedModel = GeminiModels.normalizeSelectedModel(initialModel),
                fallbackModels = initialFallback,
                routingStrategy = KeyRoutingStrategy.FAILOVER,
                cooldownSeconds = 60
            )
        )
        override val settingsFlow: Flow<AppSettings> = state.asStateFlow()
        override val vaultStatus: StateFlow<VaultStatus> =
            MutableStateFlow<VaultStatus>(VaultStatus.Ready).asStateFlow()

        override suspend fun setAppTheme(theme: AppThemePreset) {}
        override suspend fun setReduceEffects(enabled: Boolean) {}
        override suspend fun setAppLanguage(language: AppLanguage) {}
        override suspend fun setOnboardingCompleted(completed: Boolean) {}
        override suspend fun setFontSizeSp(sizeSp: Float) {}
        override suspend fun setExtraKeysLayout(layout: ExtraKeysLayout) {}
        override suspend fun setAiEnabled(enabled: Boolean) {}
        override suspend fun setSelectedModel(modelId: String?) {
            state.update { it.copy(selectedModel = GeminiModels.normalizeSelectedModel(modelId)) }
        }
        override suspend fun setFallbackModels(entries: List<FallbackModelEntry>) {
            state.update { it.copy(fallbackModels = entries) }
        }
        override suspend fun setRoutingStrategy(strategy: KeyRoutingStrategy) {
            state.update { it.copy(routingStrategy = strategy) }
        }
        override suspend fun setCooldownSeconds(seconds: Int) {}
        override suspend fun addApiKey(label: String, rawKey: String): Result<ApiKeyMetadata> {
            val id = "k_${state.value.apiKeys.size + 1}"
            secrets[id] = rawKey
            val meta = ApiKeyMetadata(id = id, label = label, maskedPreview = "AIza••••")
            state.update { it.copy(apiKeys = it.apiKeys + meta) }
            return Result.success(meta)
        }
        override suspend fun removeApiKey(id: String) {}
        override suspend fun setApiKeyEnabled(id: String, enabled: Boolean) {}
        override suspend fun updateApiKeyLabel(id: String, newLabel: String) {}
        override fun getSecretKey(id: String): String? = secrets[id]
    }

    @Test
    fun `selectedModel defaults to null and unknown legacy model IDs resolve as unselected`() = runTest {
        assertNull(AppSettings().selectedModel)
        assertNull(GeminiModels.normalizeSelectedModel(null))
        assertNull(GeminiModels.normalizeSelectedModel(""))
        assertNull(GeminiModels.normalizeSelectedModel("gemini-2.5-flash"))
        assertNull(GeminiModels.normalizeSelectedModel("gemini-2.0-flash"))
        assertEquals("gemini-4-hydrogen", GeminiModels.normalizeSelectedModel("gemini-4-hydrogen"))
        assertEquals("gemini-3.8-flash", GeminiModels.normalizeSelectedModel("gemini-3.8-flash"))
        assertEquals("gemini-3.6-flash", GeminiModels.normalizeSelectedModel("gemini-3.6-flash"))
        assertEquals("gemini-3.5-flash-lite", GeminiModels.normalizeSelectedModel("gemini-3.5-flash-lite"))

        val store = TestSettingsStore(initialModel = "gemini-2.5-flash")
        store.addApiKey("Primary", "secret-1")

        var transportCalled = false
        val transport = object : GeminiTransport {
            override suspend fun generate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): GeminiCallResult<String> {
                transportCalled = true
                return GeminiCallResult.Success("ok", "", "")
            }

            override fun streamGenerate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): Flow<GeminiStreamEvent> = flow {
                transportCalled = true
            }
        }

        val repo = AiAssistantRepository(KeyManager(store), transport, store)
        val updates = repo.streamPrompt(
            AiPromptRequest(kind = AiPromptKind.CHAT, userPrompt = "hello")
        ).toList()

        assertFalse(transportCalled)
        assertEquals(1, updates.size)
        val failed = updates.first() as AiStreamUpdate.Failed
        assertEquals(UiText.Resource(R.string.ai_err_select_model_first), failed.errorUiText)
    }

    @Test
    fun `model failover strictly follows user fallbackModels order and stops with quota error when empty`() = runTest {
        val store = TestSettingsStore(
            initialModel = "gemini-4-hydrogen",
            initialFallback = emptyList()
        )
        store.addApiKey("Key-1", "secret-1")
        store.addApiKey("Key-2", "secret-2")

        val calls = mutableListOf<Pair<String, String>>()
        val transport = object : GeminiTransport {
            override suspend fun generate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): GeminiCallResult<String> = GeminiCallResult.Success("ok", "", "")

            override fun streamGenerate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): Flow<GeminiStreamEvent> = flow {
                calls.add(model to apiKey)
                if (model == "gemini-4-hydrogen" || model == "gemini-3.6-flash") {
                    emit(GeminiStreamEvent.Failure(GeminiCallResult.QuotaExceeded("Quota exhausted", 429)))
                } else {
                    emit(GeminiStreamEvent.Chunk("Answer from $model"))
                    emit(GeminiStreamEvent.Completed("STOP"))
                }
            }
        }

        val keyManager = KeyManager(store, clockMillis = { 500_000L })
        val repo = AiAssistantRepository(keyManager, transport, store)

        // 1) With empty fallbackModels -> tries both keys on primary only, then surfaces quota error without switching
        val noFallbackUpdates = repo.streamPrompt(
            AiPromptRequest(kind = AiPromptKind.CHAT, userPrompt = "first")
        ).toList()
        assertEquals(
            listOf("gemini-4-hydrogen" to "secret-1", "gemini-4-hydrogen" to "secret-2"),
            calls
        )
        assertTrue(noFallbackUpdates.last() is AiStreamUpdate.Failed)

        // 2) Configure ordered fallbackModels with a disabled entry first, then 3.6 (429), then 3.5-flash-lite (succeeds)
        calls.clear()
        store.setFallbackModels(
            listOf(
                FallbackModelEntry("gemini-3.8-flash", enabled = false),
                FallbackModelEntry("gemini-3.6-flash", enabled = true),
                FallbackModelEntry("gemini-3.5-flash-lite", enabled = true)
            )
        )
        val withFallbackUpdates = repo.streamPrompt(
            AiPromptRequest(kind = AiPromptKind.CHAT, userPrompt = "second")
        ).toList()

        // Keys are STILL on cooldown specifically for gemini-4-hydrogen (per (key, model)), so it skips straight to
        // enabled fallback gemini-3.6-flash (bypassing disabled 3.8-flash), fails all keys on 3.6-flash, and wins on 3.5-flash-lite!
        assertEquals(
            listOf(
                "gemini-3.6-flash" to "secret-1",
                "gemini-3.6-flash" to "secret-2",
                "gemini-3.5-flash-lite" to "secret-1"
            ),
            calls
        )
        val completed = withFallbackUpdates.last() as AiStreamUpdate.Completed
        assertEquals("gemini-3.5-flash-lite", completed.modelUsed)
        assertEquals("Key-1", completed.keyLabel)
    }

    @Test
    fun `http 400 API_KEY_INVALID marks AUTH_ERROR and fails over to next key`() = runTest {
        val client = GeminiApiClient()
        val raw400 = """
            {
              "error": {
                "code": 400,
                "message": "API key not valid. Please pass a valid API key.",
                "status": "INVALID_ARGUMENT",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "API_KEY_INVALID",
                    "domain": "googleapis.com"
                  }
                ]
              }
            }
        """.trimIndent()
        val mapped = client.mapHttpError(400, raw400)
        assertTrue(mapped is GeminiCallResult.AuthFailure)

        val store = TestSettingsStore(initialModel = "gemini-3.8-flash")
        val k1 = store.addApiKey("BadKey", "bad-secret").getOrThrow()
        val k2 = store.addApiKey("GoodKey", "good-secret").getOrThrow()

        val keyManager = KeyManager(store, clockMillis = { 10_000L })
        val transport = object : GeminiTransport {
            override suspend fun generate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): GeminiCallResult<String> = GeminiCallResult.Success("ok", "", "")

            override fun streamGenerate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): Flow<GeminiStreamEvent> = flow {
                if (apiKey == "bad-secret") {
                    emit(GeminiStreamEvent.Failure(mapped))
                } else {
                    emit(GeminiStreamEvent.Chunk("Recovered"))
                    emit(GeminiStreamEvent.Completed("STOP"))
                }
            }
        }

        val repo = AiAssistantRepository(keyManager, transport, store)
        val updates = repo.streamPrompt(
            AiPromptRequest(kind = AiPromptKind.CHAT, userPrompt = "check key")
        ).toList()

        val completed = updates.last() as AiStreamUpdate.Completed
        assertEquals("GoodKey", completed.keyLabel)
        val snap = keyManager.snapshot.value
        assertEquals(KeyHealthStatus.AUTH_ERROR, snap.keys.first { it.metadata.id == k1.id }.status)
        assertEquals(KeyHealthStatus.ACTIVE, snap.keys.first { it.metadata.id == k2.id }.status)
    }

    @Test
    fun `http 404 marks key-model pair unavailable and falls back without raw 404 crash`() = runTest {
        val client = GeminiApiClient()
        val mapped404 = client.mapHttpError(404, """{"error":{"code":404,"message":"models/gemini-4-hydrogen is not found","status":"NOT_FOUND"}}""")
        assertTrue(mapped404 is GeminiCallResult.ModelUnavailable)

        val store = TestSettingsStore(
            initialModel = "gemini-4-hydrogen",
            initialFallback = listOf(FallbackModelEntry("gemini-3.8-flash", enabled = true))
        )
        val k1 = store.addApiKey("Primary", "secret-1").getOrThrow()
        val keyManager = KeyManager(store, clockMillis = { 20_000L })

        val transport = object : GeminiTransport {
            override suspend fun generate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): GeminiCallResult<String> = GeminiCallResult.Success("ok", "", "")

            override fun streamGenerate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): Flow<GeminiStreamEvent> = flow {
                if (model == "gemini-4-hydrogen") {
                    emit(GeminiStreamEvent.Failure(mapped404))
                } else {
                    emit(GeminiStreamEvent.Chunk("Served by $model"))
                    emit(GeminiStreamEvent.Completed("STOP"))
                }
            }
        }

        val repo = AiAssistantRepository(keyManager, transport, store)
        val updates = repo.streamPrompt(
            AiPromptRequest(kind = AiPromptKind.CHAT, userPrompt = "test 404")
        ).toList()

        val completed = updates.last() as AiStreamUpdate.Completed
        assertEquals("gemini-3.8-flash", completed.modelUsed)
        assertEquals(
            KeyHealthStatus.MODEL_UNAVAILABLE,
            keyManager.snapshot.value.keys.first { it.metadata.id == k1.id }.status
        )
    }

    @Test
    fun `candidate without parts preserves finishReason and thought parts are filtered out`() {
        val client = GeminiApiClient()

        val noPartsMaxTokens = """{"candidates":[{"content":{},"finishReason":"MAX_TOKENS"}]}"""
        assertEquals("" to "MAX_TOKENS", client.parseSsePayload(noPartsMaxTokens))

        val missingContentStop = """{"candidates":[{"finishReason":"SAFETY"}]}"""
        assertEquals("" to "SAFETY", client.parseSsePayload(missingContentStop))

        val withThoughtAndAnswer = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    {"text": "internal chain of thought", "thought": true},
                    {"text": "Visible answer"},
                    {"text": " continued", "thought": false}
                  ]
                },
                "finishReason": "STOP"
              }]
            }
        """.trimIndent()
        assertEquals("Visible answer continued" to "STOP", client.parseSsePayload(withThoughtAndAnswer))
    }

    @Test
    fun `gemini-4-hydrogen request sets maxOutputTokens 4096, thinkingLevel low, and omits temperature`() {
        val client = GeminiApiClient()
        val hydrogenJson = client.encodeRequestJson(
            client.buildRequest(
                model = "gemini-4-hydrogen",
                systemInstruction = "sys",
                contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart("hi"))))
            )
        )
        assertTrue(hydrogenJson.contains("\"maxOutputTokens\":4096"))
        assertTrue(hydrogenJson.contains("\"thinkingLevel\":\"low\""))
        assertFalse(hydrogenJson.contains("\"minimal\""))
        assertFalse(hydrogenJson.contains("\"temperature\""))
        assertFalse(hydrogenJson.contains("\"topP\""))
        assertFalse(hydrogenJson.contains("\"topK\""))

        val flashJson = client.encodeRequestJson(
            client.buildRequest(
                model = "gemini-3.8-flash",
                systemInstruction = "sys",
                contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart("hi"))))
            )
        )
        assertTrue(flashJson.contains("\"maxOutputTokens\":4096"))
        assertTrue(flashJson.contains("\"temperature\":0.25"))
        assertFalse(flashJson.contains("\"thinkingConfig\""))
    }

    @Test
    fun `finishReason MAX_TOKENS sets isTruncated true on completed stream update`() = runTest {
        val store = TestSettingsStore(initialModel = "gemini-4-hydrogen")
        store.addApiKey("Primary", "secret-1")
        val keyManager = KeyManager(store, clockMillis = { 90_000L })

        val transport = object : GeminiTransport {
            override suspend fun generate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): GeminiCallResult<String> = GeminiCallResult.Success("ok", "", "")

            override fun streamGenerate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): Flow<GeminiStreamEvent> = flow {
                emit(GeminiStreamEvent.Chunk("Long partial output..."))
                emit(GeminiStreamEvent.Completed(finishReason = "MAX_TOKENS"))
            }
        }

        val repo = AiAssistantRepository(keyManager, transport, store)
        val updates = repo.streamPrompt(
            AiPromptRequest(kind = AiPromptKind.CHAT, userPrompt = "explain everything")
        ).toList()

        val completed = updates.last() as AiStreamUpdate.Completed
        assertTrue(completed.isTruncated)
        assertEquals("gemini-4-hydrogen", completed.modelUsed)
        assertEquals(UiText.Dynamic("Long partial output..."), completed.text)
    }
}
