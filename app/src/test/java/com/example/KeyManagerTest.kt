package com.example

import com.example.ai.AiAssistantRepository
import com.example.ai.GeminiApiClient
import com.example.ai.GeminiCallResult
import com.example.ai.KeyHealthStatus
import com.example.ai.KeyManager
import com.example.data.ApiKeyMetadata
import com.example.data.AppLanguage
import com.example.data.AppSettings
import com.example.data.EncryptedVaultUnavailableException
import com.example.data.ExtraKeysLayout
import com.example.data.KeyRoutingStrategy
import com.example.data.SettingsStore
import com.example.data.VaultStatus
import com.example.ui.UiText
import com.example.ui.theme.AppThemePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyManagerTest {

    private class FakeSettingsStore(
        vaultAvailable: Boolean = true
    ) : SettingsStore {
        private val secrets = mutableMapOf<String, String>()
        private val state = MutableStateFlow(AppSettings(cooldownSeconds = 60))
        override val settingsFlow: Flow<AppSettings> = state.asStateFlow()

        private val _vaultStatus = MutableStateFlow<VaultStatus>(
            if (vaultAvailable) VaultStatus.Ready
            else VaultStatus.Unavailable(UiText.Dynamic("Keystore locked"))
        )
        override val vaultStatus: StateFlow<VaultStatus> = _vaultStatus.asStateFlow()

        override suspend fun setAppTheme(theme: AppThemePreset) {
            state.update { it.copy(appTheme = theme) }
        }
        override suspend fun setReduceEffects(enabled: Boolean) {
            state.update { it.copy(reduceEffectsOverride = enabled) }
        }
        override suspend fun setAppLanguage(language: AppLanguage) {
            state.update { it.copy(appLanguage = language) }
        }
        override suspend fun setOnboardingCompleted(completed: Boolean) {
            state.update { it.copy(onboardingCompleted = completed) }
        }
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
        override suspend fun setCooldownSeconds(seconds: Int) {
            state.update { it.copy(cooldownSeconds = seconds) }
        }
        override suspend fun addApiKey(label: String, rawKey: String): Result<ApiKeyMetadata> {
            if (_vaultStatus.value is VaultStatus.Unavailable) {
                return Result.failure(EncryptedVaultUnavailableException(UiText.Dynamic("Keystore locked")))
            }
            val id = "k_${state.value.apiKeys.size + 1}"
            secrets[id] = rawKey
            val meta = ApiKeyMetadata(id = id, label = label, maskedPreview = "AIza••••")
            state.update { it.copy(apiKeys = it.apiKeys + meta) }
            return Result.success(meta)
        }
        override suspend fun removeApiKey(id: String) {}
        override suspend fun setApiKeyEnabled(id: String, enabled: Boolean) {
            state.update { cur ->
                cur.copy(apiKeys = cur.apiKeys.map { if (it.id == id) it.copy(enabled = enabled) else it })
            }
        }
        override suspend fun updateApiKeyLabel(id: String, newLabel: String) {}
        override fun getSecretKey(id: String): String? = secrets[id]
    }

    @Test
    fun `round robin rotates enabled keys and fails over on 429 cooldown`() = runTest {
        var now = 1_000_000L
        val store = FakeSettingsStore()
        store.addApiKey("Primary", "secret-key-1")
        store.addApiKey("Backup", "secret-key-2")
        store.setRoutingStrategy(KeyRoutingStrategy.ROUND_ROBIN)

        val manager = KeyManager(settingsStore = store, clockMillis = { now })

        val usedKeys = mutableListOf<String>()
        val result = manager.executeWithFailover { apiKey, meta ->
            usedKeys.add(meta.label)
            if (apiKey == "secret-key-1") {
                GeminiCallResult.QuotaExceeded("Rate limit exceeded", 429)
            } else {
                GeminiCallResult.Success("ok", meta.id, meta.label)
            }
        }

        assertTrue(result is GeminiCallResult.Success)
        assertEquals(listOf("Primary", "Backup"), usedKeys)

        val snapshot = manager.snapshot.value
        val primaryStatus = snapshot.keys.first { it.metadata.label == "Primary" }
        assertEquals(KeyHealthStatus.COOLDOWN, primaryStatus.status)
        assertEquals("Backup", snapshot.activeKeyLabel)

        now += 61_000L
        val recoveredKeys = mutableListOf<String>()
        val secondAttempt = manager.executeWithFailover { _, meta ->
            recoveredKeys.add(meta.label)
            GeminiCallResult.Success("ok2", meta.id, meta.label)
        }
        assertTrue(secondAttempt is GeminiCallResult.Success)
        assertTrue(recoveredKeys.isNotEmpty())
    }

    @Test
    fun `http 403 is retryable after delay and recheck restores key immediately`() = runTest {
        val now = 2_000_000L
        val store = FakeSettingsStore()
        val meta1 = store.addApiKey("Key-A", "secret-a").getOrThrow()
        store.addApiKey("Key-B", "secret-b")
        store.setRoutingStrategy(KeyRoutingStrategy.FAILOVER)

        var probeShouldPass = false
        val manager = KeyManager(
            settingsStore = store,
            clockMillis = { now },
            keyProbe = { _, _ ->
                if (probeShouldPass) GeminiCallResult.Success(Unit, "", "")
                else GeminiCallResult.ForbiddenRetryable("Still delayed", 403)
            }
        )

        val res = manager.executeWithFailover { apiKey, meta ->
            if (apiKey == "secret-a") {
                GeminiCallResult.ForbiddenRetryable("Temporary permission delay", 403)
            } else {
                GeminiCallResult.Success("ok-b", meta.id, meta.label)
            }
        }

        assertTrue(res is GeminiCallResult.Success)
        val keyA = manager.snapshot.value.keys.first { it.metadata.id == meta1.id }
        assertEquals(KeyHealthStatus.FORBIDDEN_DELAY, keyA.status)

        manager.recheckKey(meta1.id)
        assertEquals(
            KeyHealthStatus.FORBIDDEN_DELAY,
            manager.snapshot.value.keys.first { it.metadata.id == meta1.id }.status
        )

        probeShouldPass = true
        manager.recheckKey(meta1.id)
        assertEquals(
            KeyHealthStatus.ACTIVE,
            manager.snapshot.value.keys.first { it.metadata.id == meta1.id }.status
        )
    }

    @Test
    fun `recheckKey on NetworkOrServerError preserves previous status and sets transient retry note`() = runTest {
        val now = 3_000_000L
        val store = FakeSettingsStore()
        val meta1 = store.addApiKey("Key-Net", "secret-net").getOrThrow()

        var probeMode = "429"
        val manager = KeyManager(
            settingsStore = store,
            clockMillis = { now },
            keyProbe = { _, _ ->
                when (probeMode) {
                    "network" -> GeminiCallResult.NetworkOrServerError("Socket timeout")
                    "ok" -> GeminiCallResult.Success(Unit, "", "")
                    else -> GeminiCallResult.QuotaExceeded("Quota hit", 429)
                }
            }
        )

        manager.executeWithFailover { _, _ ->
            GeminiCallResult.QuotaExceeded("Quota hit", 429)
        }
        assertEquals(
            KeyHealthStatus.COOLDOWN,
            manager.snapshot.value.keys.first { it.metadata.id == meta1.id }.status
        )

        probeMode = "network"
        manager.recheckKey(meta1.id)
        val afterNetErr = manager.snapshot.value.keys.first { it.metadata.id == meta1.id }
        assertEquals(KeyHealthStatus.COOLDOWN, afterNetErr.status)
        assertEquals(UiText.Resource(R.string.key_note_network_retry), afterNetErr.lastStatusNote)

        manager.clearAllCooldownsAndErrors()
        assertEquals(
            KeyHealthStatus.READY,
            manager.snapshot.value.keys.first { it.metadata.id == meta1.id }.status
        )
        manager.recheckKey(meta1.id)
        val readyAfterNetErr = manager.snapshot.value.keys.first { it.metadata.id == meta1.id }
        assertEquals(KeyHealthStatus.READY, readyAfterNetErr.status)
        assertEquals(UiText.Resource(R.string.key_note_network_retry), readyAfterNetErr.lastStatusNote)
    }

    @Test
    fun `settings store refuses to store key when encrypted vault is unavailable`() = runTest {
        val brokenStore = FakeSettingsStore(vaultAvailable = false)
        val result = brokenStore.addApiKey("Blocked", "AIzaSecret")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EncryptedVaultUnavailableException)
    }

    @Test
    fun `ai system instruction follows active app language directive`() {
        val store = FakeSettingsStore()
        val manager = KeyManager(settingsStore = store)
        val repo = AiAssistantRepository(
            keyManager = manager,
            geminiApiClient = GeminiApiClient(),
            settingsStore = store
        )

        val idInstruction = repo.buildSystemInstruction(AppLanguage.INDONESIAN)
        assertTrue(idInstruction.contains("Bahasa Indonesia"))

        val enInstruction = repo.buildSystemInstruction(AppLanguage.ENGLISH)
        assertTrue(enInstruction.contains("English"))
    }
}
