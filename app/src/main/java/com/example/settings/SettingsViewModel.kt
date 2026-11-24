package com.example.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.ai.KeyHealthStatus
import com.example.ai.KeyManager
import com.example.ai.KeyManagerSnapshot
import com.example.ai.SensitiveDataRedactor
import com.example.data.ApiKeyMetadata
import com.example.data.ApiKeyValidator
import com.example.data.AppLanguage
import com.example.data.AppSettings
import com.example.data.EncryptedVaultUnavailableException
import com.example.data.ExtraKeysLayout
import com.example.data.KeyRoutingStrategy
import com.example.data.SettingsStore
import com.example.data.TerminalFontConfig
import com.example.data.VaultStatus
import com.example.ui.UiText
import com.example.ui.theme.AppThemePreset
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ApiKeyActionEvent {
    data class RecheckAuthOrForbidden(val keyId: String) : ApiKeyActionEvent
    data class RecheckSuccess(val keyId: String) : ApiKeyActionEvent
    data class RecheckNetworkRetry(val keyId: String) : ApiKeyActionEvent
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val keySnapshot: KeyManagerSnapshot = KeyManagerSnapshot(),
    val vaultStatus: VaultStatus = VaultStatus.Ready,
    val vaultErrorMessage: UiText? = null
)

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val keyManager: KeyManager
) : ViewModel() {

    private val addKeyError = MutableStateFlow<UiText?>(null)
    private val _keyActionEvents = MutableSharedFlow<ApiKeyActionEvent>(extraBufferCapacity = 8)
    val keyActionEvents: SharedFlow<ApiKeyActionEvent> = _keyActionEvents.asSharedFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsStore.settingsFlow,
        keyManager.snapshot,
        settingsStore.vaultStatus,
        addKeyError
    ) { settings, keySnap, vaultStatus, lastErr ->
        SettingsUiState(
            settings = settings,
            keySnapshot = keySnap,
            vaultStatus = vaultStatus,
            vaultErrorMessage = lastErr
                ?: (vaultStatus as? VaultStatus.Corrupted)?.reason
                ?: (vaultStatus as? VaultStatus.Unavailable)?.reason
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    fun setAppTheme(theme: AppThemePreset) {
        viewModelScope.launch { settingsStore.setAppTheme(theme) }
    }

    fun setReduceEffects(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setReduceEffects(enabled) }
    }

    fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsStore.setAppLanguage(language) }
    }

    fun completeOnboarding() {
        viewModelScope.launch { settingsStore.setOnboardingCompleted(true) }
    }

    fun reopenOnboarding() {
        viewModelScope.launch { settingsStore.setOnboardingCompleted(false) }
    }

    fun setFontSizeSp(sizeSp: Float) {
        val normalized = TerminalFontConfig.normalizeFontSizeSp(sizeSp)
        viewModelScope.launch { settingsStore.setFontSizeSp(normalized) }
    }

    fun setExtraKeysLayout(layout: ExtraKeysLayout) {
        viewModelScope.launch { settingsStore.setExtraKeysLayout(layout) }
    }

    fun setFullscreenTerminal(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setFullscreenTerminal(enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setKeepScreenOn(enabled) }
    }

    fun setNotifyLongCommands(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNotifyLongCommands(enabled) }
    }

    fun setLongCommandThresholdSeconds(seconds: Int) {
        viewModelScope.launch { settingsStore.setLongCommandThresholdSeconds(seconds) }
    }

    fun setTerminalFontId(fontId: String) {
        viewModelScope.launch { settingsStore.setTerminalFontId(fontId) }
    }

    fun setAiEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAiEnabled(enabled) }
    }

    fun revokeAiConsent() {
        viewModelScope.launch { settingsStore.setAiConsentAccepted(false) }
    }

    fun setSelectedModel(modelId: String?) {
        viewModelScope.launch { settingsStore.setSelectedModel(modelId) }
    }

    fun addFallbackModel(modelId: String) {
        viewModelScope.launch {
            val current = uiState.value.settings.fallbackModels
            if (current.any { it.modelId == modelId }) return@launch
            settingsStore.setFallbackModels(current + com.example.data.FallbackModelEntry(modelId, enabled = true))
        }
    }

    fun removeFallbackModel(modelId: String) {
        viewModelScope.launch {
            val updated = uiState.value.settings.fallbackModels.filterNot { it.modelId == modelId }
            settingsStore.setFallbackModels(updated)
        }
    }

    fun setFallbackModelEnabled(modelId: String, enabled: Boolean) {
        viewModelScope.launch {
            val updated = uiState.value.settings.fallbackModels.map {
                if (it.modelId == modelId) it.copy(enabled = enabled) else it
            }
            settingsStore.setFallbackModels(updated)
        }
    }

    fun moveFallbackModel(modelId: String, delta: Int) {
        viewModelScope.launch {
            val list = uiState.value.settings.fallbackModels.toMutableList()
            val idx = list.indexOfFirst { it.modelId == modelId }
            if (idx < 0) return@launch
            val targetIdx = (idx + delta).coerceIn(0, list.lastIndex)
            if (targetIdx == idx) return@launch
            val item = list.removeAt(idx)
            list.add(targetIdx, item)
            settingsStore.setFallbackModels(list)
        }
    }

    fun setRoutingStrategy(strategy: KeyRoutingStrategy) {
        viewModelScope.launch { settingsStore.setRoutingStrategy(strategy) }
    }

    fun setCooldownSeconds(seconds: Int) {
        viewModelScope.launch { settingsStore.setCooldownSeconds(seconds) }
    }

    suspend fun addApiKeyAndAwait(label: String, rawKey: String): Result<ApiKeyMetadata> {
        val cleanedKey = ApiKeyValidator.cleanInput(rawKey)
        val result = settingsStore.addApiKey(label = label, rawKey = cleanedKey)
        val savedMeta = result.getOrNull()
        if (savedMeta != null) {
            keyManager.activateKeyIfNone(savedMeta.id)
            addKeyError.value = null
            return Result.success(savedMeta)
        }
        val ex = result.exceptionOrNull()
        val safeReason = sanitizeKeyError(ex = ex, rawKey = rawKey, cleanedKey = cleanedKey)
        return Result.failure(EncryptedVaultUnavailableException(safeReason, ex))
    }

    fun addApiKey(label: String, rawKey: String) {
        viewModelScope.launch {
            val result = addApiKeyAndAwait(label = label, rawKey = rawKey)
            val ex = result.exceptionOrNull()
            addKeyError.value = (ex as? EncryptedVaultUnavailableException)?.uiReason
        }
    }

    private fun sanitizeKeyError(ex: Throwable?, rawKey: String, cleanedKey: String): UiText {
        if (ex is EncryptedVaultUnavailableException) {
            val reason = ex.uiReason
            return if (reason is UiText.Dynamic) {
                UiText.Dynamic(scrubSecretFromMessage(reason.value, rawKey, cleanedKey))
            } else {
                reason
            }
        }
        val msg = ex?.message?.takeIf { it.isNotBlank() }
        return if (msg != null) {
            UiText.Dynamic(scrubSecretFromMessage(msg, rawKey, cleanedKey))
        } else {
            UiText.Resource(R.string.vault_err_invalid_format)
        }
    }

    private fun scrubSecretFromMessage(message: String, rawKey: String, cleanedKey: String): String {
        var safe = SensitiveDataRedactor.redact(message)
        val candidates = listOf(cleanedKey, rawKey.trim()).filter { it.length >= 4 }.distinct()
        for (secret in candidates) {
            if (safe.contains(secret)) {
                safe = safe.replace(secret, SensitiveDataRedactor.REDACTED)
            }
        }
        return safe
    }

    fun resetCorruptedVault() {
        viewModelScope.launch {
            val result = settingsStore.resetCorruptedVault()
            val ex = result.exceptionOrNull()
            addKeyError.value = when (ex) {
                is EncryptedVaultUnavailableException -> ex.uiReason
                null -> null
                else -> ex.message?.let { UiText.Dynamic(SensitiveDataRedactor.redact(it)) }
            }
        }
    }

    fun dismissVaultError() {
        addKeyError.value = null
    }

    fun removeApiKey(id: String) {
        viewModelScope.launch { settingsStore.removeApiKey(id) }
    }

    fun setApiKeyEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { settingsStore.setApiKeyEnabled(id, enabled) }
    }

    fun recheckApiKey(id: String) {
        viewModelScope.launch {
            keyManager.recheckKey(id)
            val updated = keyManager.snapshot.value.keys.firstOrNull { it.metadata.id == id }
            when (updated?.status) {
                KeyHealthStatus.AUTH_ERROR,
                KeyHealthStatus.FORBIDDEN_DELAY -> {
                    _keyActionEvents.tryEmit(ApiKeyActionEvent.RecheckAuthOrForbidden(id))
                }
                KeyHealthStatus.ACTIVE,
                KeyHealthStatus.READY -> {
                    if (updated.lastStatusNote == UiText.Resource(R.string.key_note_network_retry)) {
                        _keyActionEvents.tryEmit(ApiKeyActionEvent.RecheckNetworkRetry(id))
                    } else {
                        _keyActionEvents.tryEmit(ApiKeyActionEvent.RecheckSuccess(id))
                    }
                }
                else -> Unit
            }
        }
    }

    fun clearCooldowns() {
        viewModelScope.launch { keyManager.clearAllCooldownsAndErrors() }
    }

    class Factory(
        private val settingsStore: SettingsStore,
        private val keyManager: KeyManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(settingsStore, keyManager) as T
        }
    }
}
