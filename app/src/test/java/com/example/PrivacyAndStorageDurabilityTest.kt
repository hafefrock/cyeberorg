package com.example

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiAssistantRepository
import com.example.ai.AiPromptContext
import com.example.ai.AiPromptKind
import com.example.ai.AiPromptRequest
import com.example.ai.AiStreamUpdate
import com.example.ai.GeminiCallResult
import com.example.ai.GeminiContent
import com.example.ai.GeminiStreamEvent
import com.example.ai.GeminiTransport
import com.example.ai.KeyManager
import com.example.ai.SensitiveDataRedactor
import com.example.data.AndroidEncryptedKeyVault
import com.example.data.ApiKeyMetadata
import com.example.data.ApiKeyValidator
import com.example.data.AppLanguage
import com.example.data.AppSettings
import com.example.data.DataStorePromptHistoryRepository
import com.example.data.DataStoreSnippetRepository
import com.example.data.EncryptedVaultUnavailableException
import com.example.data.ExtraKeysLayout
import com.example.data.FallbackModelEntry
import com.example.data.KeyRoutingStrategy
import com.example.data.KeyVault
import com.example.data.LocaleHelper
import com.example.data.SettingsRepository
import com.example.data.SettingsStore
import com.example.data.SnippetStore
import com.example.data.PromptHistoryStore
import com.example.data.VaultStatus
import com.example.settings.AiSettingsScreen
import com.example.settings.ApiKeysScreen
import com.example.settings.SettingsSummaries
import com.example.settings.SettingsViewModel
import com.example.terminal.FakeTerminalBackend
import com.example.terminal.TerminalErrorContext
import com.example.ui.UiText
import com.example.ui.terminal.TerminalScreen
import com.example.ui.terminal.TerminalViewModel
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.HackerStudioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivacyAndStorageDurabilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class InMemoryVault : KeyVault {
        val entries = mutableMapOf<String, String>()
        private val _status = MutableStateFlow<VaultStatus>(VaultStatus.Ready)
        override val vaultStatus: StateFlow<VaultStatus> = _status.asStateFlow()

        override fun getKey(id: String): String? = entries[id]

        override fun putKey(id: String, rawKey: String) {
            if (_status.value !is VaultStatus.Ready) {
                throw EncryptedVaultUnavailableException(UiText.Dynamic("Vault unavailable"))
            }
            entries[id] = rawKey
        }

        override fun removeKey(id: String) {
            entries.remove(id)
        }

        override fun maskKey(rawKey: String): String {
            val clean = rawKey.trim()
            return if (clean.length <= 8) "••••" else "${clean.take(4)}••••${clean.takeLast(4)}"
        }

        override fun resetVault(): Boolean {
            entries.clear()
            _status.value = VaultStatus.Ready
            return true
        }
    }

    private class ConsentTrackingSettingsStore(
        initial: AppSettings = AppSettings(selectedModel = "gemini-3.8-flash", aiConsentAccepted = false)
    ) : SettingsStore {
        private val secrets = mutableMapOf<String, String>("k_1" to "AIzaSyTestKeyForPrivacyUnitTest1234567890")
        private val state = MutableStateFlow(
            initial.copy(
                apiKeys = initial.apiKeys.ifEmpty {
                    listOf(ApiKeyMetadata(id = "k_1", label = "Primary", maskedPreview = "AIza••••7890"))
                }
            )
        )
        override val settingsFlow: Flow<AppSettings> = state.asStateFlow()
        val vaultStatusMutable = MutableStateFlow<VaultStatus>(VaultStatus.Ready)
        override val vaultStatus: StateFlow<VaultStatus> = vaultStatusMutable.asStateFlow()

        var resetVaultCalls = 0
            private set

        override suspend fun setAppTheme(theme: AppThemePreset) {}
        override suspend fun setReduceEffects(enabled: Boolean) {}
        override suspend fun setAppLanguage(language: AppLanguage) {}
        override suspend fun setOnboardingCompleted(completed: Boolean) {}
        override suspend fun setFontSizeSp(sizeSp: Float) {}
        override suspend fun setExtraKeysLayout(layout: ExtraKeysLayout) {}
        override suspend fun setAiEnabled(enabled: Boolean) { state.update { it.copy(aiEnabled = enabled) } }
        override suspend fun setAiConsentAccepted(accepted: Boolean) {
            state.update { it.copy(aiConsentAccepted = accepted) }
        }
        override suspend fun setSelectedModel(modelId: String?) {}
        override suspend fun setFallbackModels(entries: List<FallbackModelEntry>) {}
        override suspend fun setRoutingStrategy(strategy: KeyRoutingStrategy) {}
        override suspend fun setCooldownSeconds(seconds: Int) {}
        override suspend fun addApiKey(label: String, rawKey: String): Result<ApiKeyMetadata> =
            Result.failure(UnsupportedOperationException())
        override suspend fun removeApiKey(id: String) {}
        override suspend fun setApiKeyEnabled(id: String, enabled: Boolean) {}
        override suspend fun updateApiKeyLabel(id: String, newLabel: String) {}
        override suspend fun resetCorruptedVault(): Result<Unit> {
            resetVaultCalls++
            secrets.clear()
            state.update { it.copy(apiKeys = emptyList()) }
            vaultStatusMutable.value = VaultStatus.Ready
            return Result.success(Unit)
        }
        override fun getSecretKey(id: String): String? = secrets[id]
    }

    @Test
    fun `SensitiveDataRedactor redacts API keys, tokens, passwords, Bearer headers, PEM keys, and sensitive env vars`() {
        val raw = """
            curl -H "Authorization: Bearer super_secret_bearer_token_9911" -H "x-goog-api-key: AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz012345" https://example.com
            export KEY="secret-value-1"
            export GEMINI_API_KEY=AIzaSyAnotherFakeGoogleApiKey1234567890
            GITHUB_TOKEN=ghp_1234567890abcdefghijklmnop
            APP_SECRET='top_secret_value'
            DB_PASSWORD=hunter2password
            password=my_inline_pass
            url=https://admin:s3cr3tPass@db.internal:5432/prod
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAv1fakekeymaterial
            xyz==
            -----END RSA PRIVATE KEY-----
            Regular command: ls -la /data/data/com.termux/files/home
        """.trimIndent()

        val redacted = SensitiveDataRedactor.redact(raw)

        assertFalse(redacted.contains("super_secret_bearer_token_9911"))
        assertFalse(redacted.contains("AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz012345"))
        assertFalse(redacted.contains("AIzaSyAnotherFakeGoogleApiKey1234567890"))
        assertFalse(redacted.contains("secret-value-1"))
        assertFalse(redacted.contains("ghp_1234567890abcdefghijklmnop"))
        assertFalse(redacted.contains("top_secret_value"))
        assertFalse(redacted.contains("hunter2password"))
        assertFalse(redacted.contains("my_inline_pass"))
        assertFalse(redacted.contains("s3cr3tPass"))
        assertFalse(redacted.contains("MIIEowIBAAKCAQEAv1fakekeymaterial"))
        assertTrue(redacted.contains("Authorization: Bearer [REDACTED]"))
        assertTrue(redacted.contains("x-goog-api-key: [REDACTED]"))
        assertTrue(redacted.contains("password=[REDACTED]"))
        assertTrue(redacted.contains("https://admin:[REDACTED]@db.internal:5432/prod"))
        assertTrue(redacted.contains("Regular command: ls -la /data/data/com.termux/files/home"))
        assertTrue(redacted.contains("[REDACTED]"))
        // Idempotent
        assertEquals(redacted, SensitiveDataRedactor.redact(redacted))
    }

    @Test
    fun `SensitiveDataRedactor ignores non-secret words like keyboard, monkey, token_count while redacting real secrets`() {
        val sample = """
            keyboard: us
            KEYBOARD=us
            monkey = 1
            MONKEY=true
            token_count = 5
            API_KEY=abc123xyz
            password: hunter2
            GITHUB_TOKEN=ghp_1234567890abcdefghijklmnop
        """.trimIndent()

        val redacted = SensitiveDataRedactor.redact(sample)

        assertTrue(redacted.contains("keyboard: us"))
        assertTrue(redacted.contains("KEYBOARD=us"))
        assertTrue(redacted.contains("monkey = 1"))
        assertTrue(redacted.contains("MONKEY=true"))
        assertTrue(redacted.contains("token_count = 5"))
        assertTrue(redacted.contains("API_KEY=[REDACTED]"))
        assertTrue(redacted.contains("password: [REDACTED]"))
        assertTrue(redacted.contains("GITHUB_TOKEN=[REDACTED]"))
        assertFalse(redacted.contains("abc123xyz"))
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("ghp_1234567890abcdefghijklmnop"))
    }

    @Test
    fun `SensitiveDataRedactor handles worst-case adversarial inputs linearly under 50ms`() {
        val worstCaseA = "a".repeat(64_000)
        val worstCaseH = "h".repeat(64_000)
        val repeatedAuth = "Authorization:\n".repeat(4_000)
        val repeatedAq = "AQ.".repeat(12_000)
        val longAq = "AQ." + "a".repeat(60_000)
        val unclosedPem = "-----BEGIN RSA PRIVATE KEY-----\n" + "MIIEowIBAAKCAQEAv1\n".repeat(3_000)

        // Warmup JIT on representative inputs so cold regex compilation is excluded
        repeat(15) {
            SensitiveDataRedactor.redact("warmup Authorization: Bearer tok123 AIzaSy12345678901234567890 AQ.Ab12345678901234567890")
            SensitiveDataRedactor.redact("AQ.".repeat(100))
            SensitiveDataRedactor.redact("AQ." + "a".repeat(350))
            SensitiveDataRedactor.redact("-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----")
        }

        val startNanos = System.nanoTime()
        val outA = SensitiveDataRedactor.redact(worstCaseA)
        val outH = SensitiveDataRedactor.redact(worstCaseH)
        val outAuth = SensitiveDataRedactor.redact(repeatedAuth)
        val outRepeatedAq = SensitiveDataRedactor.redact(repeatedAq)
        val outLongAq = SensitiveDataRedactor.redact(longAq)
        val outPem = SensitiveDataRedactor.redact(unclosedPem)
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L

        assertTrue("Expected < 50ms for all worst-case inputs, took ${elapsedMs}ms", elapsedMs < 50L)
        assertEquals(SensitiveDataRedactor.MAX_INPUT_CHARS, outA.length)
        assertEquals(SensitiveDataRedactor.MAX_INPUT_CHARS, outH.length)
        assertEquals(SensitiveDataRedactor.MAX_INPUT_CHARS, outAuth.length)
        assertFalse(outRepeatedAq.isEmpty())
        assertTrue(outLongAq.startsWith(SensitiveDataRedactor.REDACTED))
        assertEquals(SensitiveDataRedactor.REDACTED, outPem)
    }

    @Test
    fun `AiAssistantRepository caps context before redacting and runs prompt construction on Dispatchers Default`() = runTest {
        val store = ConsentTrackingSettingsStore(
            AppSettings(aiConsentAccepted = true, selectedModel = "gemini-3.8-flash")
        )
        var dispatchedToCompute = false
        val trackedDefault = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                dispatchedToCompute = true
                Dispatchers.Default.dispatch(context, block)
            }
        }
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
                emit(GeminiStreamEvent.Chunk("ok"))
                emit(GeminiStreamEvent.Completed("STOP"))
            }
        }
        val repo = AiAssistantRepository(
            keyManager = KeyManager(store),
            geminiApiClient = transport,
            settingsStore = store,
            defaultDispatcher = trackedDefault
        )

        val hugeRequest = AiPromptRequest(
            kind = AiPromptKind.CHAT,
            userPrompt = "x".repeat(50_000),
            context = AiPromptContext(
                cwd = "/".repeat(2_000),
                recentOutput = "line\n".repeat(20_000)
            )
        )
        repo.streamPrompt(hugeRequest).toList()

        assertTrue(dispatchedToCompute)
        val boundedPrompt = repo.buildPromptText(hugeRequest)
        assertTrue(boundedPrompt.length < 15_000)
    }

    @Test
    fun `AiAssistantRepository redacts secrets inside prompt and all context fields before sending`() = runTest {
        val store = ConsentTrackingSettingsStore(
            AppSettings(aiConsentAccepted = true, selectedModel = "gemini-3.8-flash")
        )
        val capturedPrompts = mutableListOf<String>()
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
                capturedPrompts += contents.joinToString("\n") { c -> c.parts.joinToString { it.text } }
                emit(GeminiStreamEvent.Chunk("Safe diagnosis"))
                emit(GeminiStreamEvent.Completed("STOP"))
            }
        }
        val repo = AiAssistantRepository(KeyManager(store), transport, store)

        val request = AiPromptRequest(
            kind = AiPromptKind.CHAT,
            userPrompt = "Debug Authorization: Bearer mySecretJwt123456",
            context = AiPromptContext(
                cwd = "/home/TOKEN=leak123",
                lastError = TerminalErrorContext(
                    sessionId = "s1",
                    command = "export API_SECRET=leak_secret && curl https://example.com",
                    exitCode = 1,
                    cwd = "/home",
                    errorOutput = "Rejected key AIzaSyFakeKeyThatMustNotLeave01234567"
                ),
                selectedText = "password=selected_pw",
                recentOutput = "JWT eyJhbGciOiJIUzI1NiIs.eyJzdWIiOiIxMjMifQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
            )
        )

        repo.streamPrompt(request).toList()

        assertEquals(1, capturedPrompts.size)
        val sent = capturedPrompts.single()
        assertFalse(sent.contains("mySecretJwt123456"))
        assertFalse(sent.contains("leak123"))
        assertFalse(sent.contains("leak_secret"))
        assertFalse(sent.contains("AIzaSyFakeKeyThatMustNotLeave01234567"))
        assertFalse(sent.contains("selected_pw"))
        assertFalse(sent.contains("SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"))
        assertTrue(sent.contains("[REDACTED]"))
    }

    @Test
    fun `consent gate blocks repository and TerminalViewModel until accepted, and revoking in Settings re-blocks`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = InMemoryVault()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val dsFile = File(context.filesDir, "datastore/test_consent_${System.nanoTime()}.preferences_pb")
        dsFile.parentFile?.mkdirs()
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { dsFile }
        )
        val repoSettings = SettingsRepository(context, vault, dataStore)
        repoSettings.setSelectedModel("gemini-3.8-flash")
        repoSettings.addApiKey("Main", "AIzaSyValidTestKey1234567890ABCDEFGHIJ")

        var transportCalls = 0
        val transport = object : GeminiTransport {
            override suspend fun generate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): GeminiCallResult<String> {
                transportCalls++
                return GeminiCallResult.Success("ok", "", "")
            }

            override fun streamGenerate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): Flow<GeminiStreamEvent> = flow {
                transportCalls++
                emit(GeminiStreamEvent.Chunk("Hello after consent"))
                emit(GeminiStreamEvent.Completed("STOP"))
            }
        }
        val keyManager = KeyManager(repoSettings)
        val aiRepo = AiAssistantRepository(keyManager, transport, repoSettings)

        try {
            // 1. Repository directly refuses without DataStore consent
            val blockedUpdates = aiRepo.streamPrompt(AiPromptRequest(AiPromptKind.CHAT, "hi")).toList()
            assertEquals(0, transportCalls)
            assertTrue(blockedUpdates.single() is AiStreamUpdate.Failed)
            assertFalse(repoSettings.settingsFlow.first().aiConsentAccepted)

            // 2. ViewModel gates sendAiMessage behind consent dialog
            val backend = FakeTerminalBackend()
            val snippetStore = object : SnippetStore {
                override val snippetsFlow: Flow<List<com.example.data.CommandSnippet>> = MutableStateFlow(emptyList())
                override suspend fun saveSnippet(title: String, command: String, tags: List<String>, existingId: String?) =
                    com.example.data.CommandSnippet(id = "s", title = title, command = command)
                override suspend fun deleteSnippet(id: String) {}
                override fun exportToJson(snippets: List<com.example.data.CommandSnippet>): String = "[]"
                override suspend fun importFromJson(rawJson: String): Result<Int> = Result.success(0)
            }
            val historyStore = object : PromptHistoryStore {
                override val historyFlow: Flow<List<com.example.data.PromptHistoryItem>> = MutableStateFlow(emptyList())
                override suspend fun recordPrompt(prompt: String) {}
                override suspend fun clearAll() {}
            }
            val vm = TerminalViewModel(backend, aiRepo, keyManager, repoSettings, snippetStore, historyStore)
            val collectJob = scope.launch { vm.uiState.collect {} }
            shadowOf(Looper.getMainLooper()).idle()

            vm.updateAiInput("check disk")
            vm.sendAiMessage()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(vm.uiState.value.isAiConsentDialogVisible)
            assertEquals("check disk", vm.uiState.value.aiInput)
            assertEquals(0, transportCalls)

            // 3. Dismissing sends nothing
            vm.dismissAiConsentDialog()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(vm.uiState.value.isAiConsentDialogVisible)
            assertEquals(0, transportCalls)

            // 4. Accepting persists consent in DataStore and dispatches queued prompt
            vm.sendAiMessage()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(vm.uiState.value.isAiConsentDialogVisible)

            vm.acceptAiConsentAndContinue()
            var waited = 0
            while (transportCalls < 1 && waited < 200) {
                Thread.sleep(5)
                shadowOf(Looper.getMainLooper()).idle()
                waited++
            }

            assertFalse(vm.uiState.value.isAiConsentDialogVisible)
            assertTrue(repoSettings.settingsFlow.first().aiConsentAccepted)
            assertEquals(1, transportCalls)

            // 5. Revoking consent in SettingsViewModel blocks subsequent requests and triggers dialog again
            val settingsVm = SettingsViewModel(repoSettings, keyManager)
            settingsVm.revokeAiConsent()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(repoSettings.settingsFlow.first().aiConsentAccepted)

            vm.updateAiInput("second prompt after revoke")
            vm.sendAiMessage()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(vm.uiState.value.isAiConsentDialogVisible)
            assertEquals(1, transportCalls)

            collectJob.cancel()
        } finally {
            scope.cancel()
            dsFile.delete()
        }
    }

    @Test
    fun `consent dialog and AiSettings revoke row work cleanly in Compose UI`() {
        val store = ConsentTrackingSettingsStore()
        val keyManager = KeyManager(store)
        val settingsVm = SettingsViewModel(store, keyManager)

        composeRule.setContent {
            val state by settingsVm.uiState.collectAsStateWithLifecycle()
            HackerStudioTheme {
                AiSettingsScreen(state = state, viewModel = settingsVm)
            }
        }

        // Initially consent is false -> button disabled
        composeRule.onNodeWithTag("revoke_ai_consent_button").assertIsNotEnabled()

        runTest { store.setAiConsentAccepted(true) }
        composeRule.waitForIdle()

        // Once accepted -> revoke button enabled and clicking revokes it
        composeRule.onNodeWithTag("revoke_ai_consent_button").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("revoke_ai_consent_button").assertIsNotEnabled()
    }

    @Test
    fun `addApiKey trims, validates empty, format, duplicates, and never persists invalid keys`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = InMemoryVault()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dsFile = File(context.filesDir, "datastore/test_keys_${System.nanoTime()}.preferences_pb")
        dsFile.parentFile?.mkdirs()
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { dsFile }
        )
        val repo = SettingsRepository(context, vault, dataStore)

        try {
            // Blank rejected
            assertTrue(repo.addApiKey("Empty", "   ").isFailure)
            assertTrue(vault.entries.isEmpty())
            assertTrue(repo.settingsFlow.first().apiKeys.isEmpty())

            // Wrong prefix rejected
            assertTrue(repo.addApiKey("BadPrefix", "sk-1234567890abcdefghijklmnopqrstuvwxyz").isFailure)
            assertTrue(vault.entries.isEmpty())

            // Too short rejected
            assertTrue(repo.addApiKey("Short", "AIzaShortKey").isFailure)
            assertTrue(vault.entries.isEmpty())

            // Embedded space rejected
            assertTrue(repo.addApiKey("Spaced", "AIzaSyValidKey WithSpace123456789012345").isFailure)
            assertTrue(vault.entries.isEmpty())

            // Valid with surrounding whitespace trimmed and saved
            val validKey = "AIzaSyValidGeminiKey_1234567890abcdefg"
            val added = repo.addApiKey("Primary", "  $validKey  ")
            assertTrue(added.isSuccess)
            val savedId = added.getOrThrow().id
            assertEquals(validKey, vault.entries[savedId])
            assertEquals(1, repo.settingsFlow.first().apiKeys.size)

            // Duplicate (even with leading/trailing whitespace) rejected and never stored
            val dupResult = repo.addApiKey("Duplicate", " $validKey ")
            assertTrue(dupResult.isFailure)
            assertEquals(1, vault.entries.size)
            assertEquals(1, repo.settingsFlow.first().apiKeys.size)
        } finally {
            scope.cancel()
            dsFile.delete()
        }
    }

    @Test
    fun `AndroidEncryptedKeyVault detects decryption corruption without crashing and resetVault recovers cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var failWithCorruption = true
        var resetCalled = false

        val cleanPrefs = context.getSharedPreferences("test_clean_vault_prefs", Context.MODE_PRIVATE)
        cleanPrefs.edit().clear().commit()

        val vault = AndroidEncryptedKeyVault(
            context = context,
            prefsProvider = { _, _ ->
                if (failWithCorruption) throw AEADBadTagException("Tag mismatch!")
                cleanPrefs
            },
            resetStorageAction = { _, _ ->
                resetCalled = true
                failWithCorruption = false
                cleanPrefs.edit().clear().commit()
            }
        )

        // Must never crash on init or read; reports VaultStatus.Corrupted
        assertTrue(vault.vaultStatus.value is VaultStatus.Corrupted)
        assertNull(vault.getKey("any"))
        assertTrue(vault.vaultStatus.value is VaultStatus.Corrupted)

        // putKey throws EncryptedVaultUnavailableException cleanly without crashing
        val putResult = runCatching { vault.putKey("k1", "AIzaSyValidGeminiKey_1234567890abcdefg") }
        assertTrue(putResult.exceptionOrNull() is EncryptedVaultUnavailableException)

        // Wire to SettingsRepository and verify resetCorruptedVault recovers cleanly
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dsFile = File(context.filesDir, "datastore/test_vault_reset_${System.nanoTime()}.preferences_pb")
        dsFile.parentFile?.mkdirs()
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { dsFile }
        )
        val repo = SettingsRepository(context, vault, dataStore)
        try {
            val resetResult = repo.resetCorruptedVault()
            assertTrue(resetResult.isSuccess)
            assertTrue(resetCalled)
            assertTrue(vault.vaultStatus.value is VaultStatus.Ready)
            assertTrue(repo.settingsFlow.first().apiKeys.isEmpty())

            // New key works after reset
            assertTrue(repo.addApiKey("Fresh", "AIzaSyFreshKeyAfterReset_1234567890abc").isSuccess)
        } finally {
            scope.cancel()
            dsFile.delete()
            cleanPrefs.edit().clear().commit()
        }
    }

    @Test
    fun `ApiKeysScreen shows corrupted vault banner and confirmation-gated Reset Vault action`() {
        val store = ConsentTrackingSettingsStore()
        store.vaultStatusMutable.value = VaultStatus.Corrupted(UiText.Dynamic("AEAD tag mismatch"))
        val keyManager = KeyManager(store)
        val vm = SettingsViewModel(store, keyManager)

        composeRule.setContent {
            val state by vm.uiState.collectAsStateWithLifecycle()
            HackerStudioTheme {
                ApiKeysScreen(state = state, viewModel = vm)
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vault_error_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("reset_vault_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("confirm_reset_vault_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_reset_vault_button").performClick()
        composeRule.waitForIdle()

        assertEquals(1, store.resetVaultCalls)
        assertEquals(VaultStatus.Ready, store.vaultStatus.value)
    }

    @Test
    fun `DataStore ReplaceFileCorruptionHandler recovers corrupted protobuf file to empty defaults without crashing`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pbFile = context.preferencesDataStoreFile("corrupt_test_${System.nanoTime()}")
        pbFile.parentFile?.mkdirs()
        // Write corrupt non-protobuf binary garbage
        pbFile.writeBytes(byteArrayOf(0x7F, 0x00, 0xFF.toByte(), 0x13, 0x37, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))

        val recoveredStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { pbFile }
        )
        val repo = SettingsRepository(context, InMemoryVault(), recoveredStore)

        try {
            val defaults = repo.settingsFlow.first()
            assertEquals(AppThemePreset.HACKER_PRO, defaults.appTheme)
            assertFalse(defaults.aiConsentAccepted)

            // Confirm subsequent writes succeed cleanly
            repo.setAiConsentAccepted(true)
            assertTrue(repo.settingsFlow.first().aiConsentAccepted)
        } finally {
            scope.cancel()
            pbFile.delete()
        }
    }

    @Test
    fun `localized EN and ID strings resolve cleanly for consent, validation, and corrupted vault`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val en = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.ENGLISH)
        val id = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.INDONESIAN)

        assertTrue(SettingsSummaries.buildKeysSummary(com.example.ai.KeyManagerSnapshot(), VaultStatus.Corrupted(UiText.Dynamic("err"))).asString(en).contains("Corrupted"))
        assertTrue(SettingsSummaries.buildKeysSummary(com.example.ai.KeyManagerSnapshot(), VaultStatus.Corrupted(UiText.Dynamic("err"))).asString(id).contains("rusak"))

        assertTrue(en.getString(R.string.ai_consent_dialog_body_free_tier).contains("free", ignoreCase = true))
        assertTrue(id.getString(R.string.ai_consent_dialog_body_free_tier).contains("gratis", ignoreCase = true))
        assertTrue(en.getString(R.string.vault_err_invalid_format).contains("AIza"))
        assertTrue(en.getString(R.string.vault_err_invalid_format).contains("AQ."))
        assertTrue(id.getString(R.string.vault_err_invalid_format).contains("AIza"))
        assertTrue(id.getString(R.string.vault_err_invalid_format).contains("AQ."))
    }
}
