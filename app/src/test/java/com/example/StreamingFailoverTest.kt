package com.example

import com.example.ai.AiAssistantRepository
import com.example.ai.AiPromptContext
import com.example.ai.AiPromptKind
import com.example.ai.AiPromptRequest
import com.example.ai.AiStreamUpdate
import com.example.ai.GeminiApiClient
import com.example.ai.GeminiCallResult
import com.example.ai.GeminiContent
import com.example.ai.GeminiStreamEvent
import com.example.ai.GeminiTransport
import com.example.ai.KeyHealthStatus
import com.example.ai.KeyManager
import com.example.data.ApiKeyMetadata
import com.example.data.AppLanguage
import com.example.data.AppSettings
import com.example.data.ExtraKeysLayout
import com.example.data.KeyRoutingStrategy
import com.example.data.SettingsStore
import com.example.data.VaultStatus
import com.example.terminal.TerminalErrorContext
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
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingFailoverTest {

    private class FakeSettingsStore : SettingsStore {
        private val secrets = mutableMapOf<String, String>()
        private val state = MutableStateFlow(
            AppSettings(
                aiConsentAccepted = true,
                selectedModel = "gemini-3.8-flash",
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
            state.update { it.copy(selectedModel = com.example.data.GeminiModels.normalizeSelectedModel(modelId)) }
        }
        override suspend fun setFallbackModels(entries: List<com.example.data.FallbackModelEntry>) {
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
    fun `streaming fails over to backup key when first key returns 429 or 403 before first chunk`() = runTest {
        val store = FakeSettingsStore()
        store.addApiKey("Primary", "secret-1")
        store.addApiKey("Secondary", "secret-2")
        store.addApiKey("Tertiary", "secret-3")

        val keyManager = KeyManager(settingsStore = store, clockMillis = { 100_000L })
        val attemptedKeys = mutableListOf<String>()

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
                attemptedKeys.add(apiKey)
                when (apiKey) {
                    "secret-1" -> emit(GeminiStreamEvent.Failure(GeminiCallResult.QuotaExceeded("Rate limit", 429)))
                    "secret-2" -> emit(GeminiStreamEvent.Failure(GeminiCallResult.ForbiddenRetryable("Delayed", 403)))
                    else -> {
                        emit(GeminiStreamEvent.Chunk("Run this:\n"))
                        emit(GeminiStreamEvent.Chunk("```bash\nls -la\n```"))
                        emit(GeminiStreamEvent.Completed("STOP"))
                    }
                }
            }
        }

        val repo = AiAssistantRepository(keyManager, transport, store)
        val updates = repo.streamPrompt(
            AiPromptRequest(kind = AiPromptKind.CHAT, userPrompt = "list files")
        ).toList()

        assertEquals(listOf("secret-1", "secret-2", "secret-3"), attemptedKeys)
        assertTrue(updates.any { it is AiStreamUpdate.Delta })
        val finalUpdate = updates.last() as AiStreamUpdate.Completed
        assertEquals("Tertiary", finalUpdate.keyLabel)
        assertEquals(1, finalUpdate.suggestions.size)
        assertEquals("ls -la", finalUpdate.suggestions.first().command)

        val snap = keyManager.snapshot.value
        assertEquals(KeyHealthStatus.COOLDOWN, snap.keys.first { it.metadata.label == "Primary" }.status)
        assertEquals(KeyHealthStatus.FORBIDDEN_DELAY, snap.keys.first { it.metadata.label == "Secondary" }.status)
        assertEquals(KeyHealthStatus.ACTIVE, snap.keys.first { it.metadata.label == "Tertiary" }.status)
    }

    @Test
    fun `mid-stream break preserves partial text and emits Interrupted for Retry`() = runTest {
        val store = FakeSettingsStore()
        store.addApiKey("Primary", "secret-1")
        store.addApiKey("Backup", "secret-2")

        val keyManager = KeyManager(settingsStore = store, clockMillis = { 200_000L })
        val attemptedKeys = mutableListOf<String>()

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
                attemptedKeys.add(apiKey)
                emit(GeminiStreamEvent.Chunk("Partial explanation "))
                emit(GeminiStreamEvent.Chunk("before socket drop."))
                emit(GeminiStreamEvent.Failure(GeminiCallResult.NetworkOrServerError("Connection reset")))
            }
        }

        val repo = AiAssistantRepository(keyManager, transport, store)
        val updates = repo.streamPrompt(
            AiPromptRequest(kind = AiPromptKind.CHAT, userPrompt = "help")
        ).toList()

        // Must NOT silently switch keys and discard partial output once first chunk arrived
        assertEquals(listOf("secret-1"), attemptedKeys)
        val interrupted = updates.last() as AiStreamUpdate.Interrupted
        assertEquals(
            UiText.Dynamic("Partial explanation before socket drop."),
            interrupted.partialText
        )
        assertEquals("Primary", interrupted.keyLabel)
    }

    @Test
    fun `buildPromptText strictly omits disabled context fields`() {
        val store = FakeSettingsStore()
        val keyManager = KeyManager(settingsStore = store)
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
            ): Flow<GeminiStreamEvent> = flow {}
        }
        val repo = AiAssistantRepository(keyManager, transport, store)

        val promptOnlySelected = repo.buildPromptText(
            AiPromptRequest(
                kind = AiPromptKind.CHAT,
                userPrompt = "Analyze this",
                context = AiPromptContext(
                    cwd = null,
                    lastError = null,
                    selectedText = "permission denied",
                    recentOutput = null
                )
            )
        )

        assertTrue(promptOnlySelected.contains("Analyze this"))
        assertTrue(promptOnlySelected.contains("permission denied"))
        assertFalse(promptOnlySelected.contains("Current working directory:"))
        assertFalse(promptOnlySelected.contains("Last failed command:"))
        assertFalse(promptOnlySelected.contains("Recent terminal output:"))

        val fullContext = repo.buildPromptText(
            AiPromptRequest(
                kind = AiPromptKind.FIX_ERROR,
                userPrompt = "sh deploy.sh",
                context = AiPromptContext(
                    cwd = "/data/data/com.termux/files/home",
                    lastError = TerminalErrorContext("s1", "sh deploy.sh", 1, "Keystore missing", "~"),
                    selectedText = null,
                    recentOutput = "line1\nline2"
                )
            )
        )
        assertTrue(fullContext.contains("Current working directory: /data/data/com.termux/files/home"))
        assertTrue(fullContext.contains("Last failed command: `sh deploy.sh`"))
        assertTrue(fullContext.contains("line1\nline2"))
    }

    @Test
    fun `streaming SAFETY, BLOCKED, or empty completion shows blocked-or-empty message without key penalty`() = runTest {
        val store = FakeSettingsStore()
        store.addApiKey("Primary", "secret-1")
        store.addApiKey("Backup", "secret-2")

        val keyManager = KeyManager(settingsStore = store, clockMillis = { 300_000L })
        var mode = "SAFETY"

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
                when (mode) {
                    "SAFETY" -> emit(GeminiStreamEvent.Completed(finishReason = "SAFETY"))
                    "BLOCKED" -> emit(GeminiStreamEvent.Completed(finishReason = "BLOCKED"))
                    else -> emit(GeminiStreamEvent.Completed(finishReason = "STOP"))
                }
            }
        }

        val repo = AiAssistantRepository(keyManager, transport, store)

        val safetyUpdates = repo.streamPrompt(
            AiPromptRequest(kind = AiPromptKind.CHAT, userPrompt = "test safety")
        ).toList()
        val safetyCompleted = safetyUpdates.last() as AiStreamUpdate.Completed
        assertEquals(UiText.Resource(R.string.ai_response_blocked_or_empty), safetyCompleted.text)

        mode = "BLOCKED"
        val blockedUpdates = repo.streamPrompt(
            AiPromptRequest(kind = AiPromptKind.CHAT, userPrompt = "test blocked")
        ).toList()
        val blockedCompleted = blockedUpdates.last() as AiStreamUpdate.Completed
        assertEquals(UiText.Resource(R.string.ai_response_blocked_or_empty), blockedCompleted.text)

        mode = "EMPTY"
        val emptyUpdates = repo.streamPrompt(
            AiPromptRequest(kind = AiPromptKind.CHAT, userPrompt = "test empty")
        ).toList()
        val emptyCompleted = emptyUpdates.last() as AiStreamUpdate.Completed
        assertEquals(UiText.Resource(R.string.ai_response_blocked_or_empty), emptyCompleted.text)

        val snap = keyManager.snapshot.value
        val primary = snap.keys.first { it.metadata.label == "Primary" }
        assertEquals(0, primary.errorCount)
        assertEquals(0, primary.cooldownRemainingSec)
        assertEquals(KeyHealthStatus.ACTIVE, primary.status)

        val client = GeminiApiClient()
        val parsedBlocked = client.parseSsePayload("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        assertEquals("" to "SAFETY", parsedBlocked)
    }
}
