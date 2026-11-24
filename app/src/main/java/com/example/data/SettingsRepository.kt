package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.R
import com.example.ui.UiText
import com.example.ui.theme.AppThemePreset
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.hackerStudioDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "hackerstudio_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

interface SettingsStore {
    val settingsFlow: Flow<AppSettings>
    val vaultStatus: StateFlow<VaultStatus>
    suspend fun setAppTheme(theme: AppThemePreset)
    suspend fun setReduceEffects(enabled: Boolean)
    suspend fun setAppLanguage(language: AppLanguage)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setFontSizeSp(sizeSp: Float)
    suspend fun setExtraKeysLayout(layout: ExtraKeysLayout)
    suspend fun setFullscreenTerminal(enabled: Boolean) {}
    suspend fun setKeepScreenOn(enabled: Boolean) {}
    suspend fun setNotifyLongCommands(enabled: Boolean) {}
    suspend fun setLongCommandThresholdSeconds(seconds: Int) {}
    suspend fun setTerminalFontId(fontId: String) {}
    suspend fun setAiEnabled(enabled: Boolean)
    suspend fun setAiConsentAccepted(accepted: Boolean) {}
    suspend fun setSelectedModel(modelId: String?)
    suspend fun setFallbackModels(entries: List<FallbackModelEntry>)
    suspend fun setRoutingStrategy(strategy: KeyRoutingStrategy)
    suspend fun setCooldownSeconds(seconds: Int)
    suspend fun addApiKey(label: String, rawKey: String): Result<ApiKeyMetadata>
    suspend fun removeApiKey(id: String)
    suspend fun setApiKeyEnabled(id: String, enabled: Boolean)
    suspend fun updateApiKeyLabel(id: String, newLabel: String)
    suspend fun resetCorruptedVault(): Result<Unit> = Result.success(Unit)
    fun getSecretKey(id: String): String?
}

class SettingsRepository @JvmOverloads constructor(
    context: Context,
    private val keyVault: KeyVault,
    customDataStore: DataStore<Preferences>? = null
) : SettingsStore {

    private val appContext = context.applicationContext
    private val dataStore = customDataStore ?: appContext.hackerStudioDataStore

    override val vaultStatus: StateFlow<VaultStatus> = keyVault.vaultStatus

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val keyListAdapter = moshi.adapter<List<ApiKeyMetadata>>(
        Types.newParameterizedType(List::class.java, ApiKeyMetadata::class.java)
    )

    private val fallbackListAdapter = moshi.adapter<List<FallbackModelEntry>>(
        Types.newParameterizedType(List::class.java, FallbackModelEntry::class.java)
    )

    override val settingsFlow: Flow<AppSettings> = dataStore.data.map { prefs ->
        val rawKeysJson = prefs[KEY_API_KEYS_JSON]
        val parsedKeys = if (rawKeysJson.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { keyListAdapter.fromJson(rawKeysJson) }.getOrNull().orEmpty()
        }

        val parsedFallback = decodeFallbackList(prefs[KEY_FALLBACK_MODELS_JSON])

        AppSettings(
            appTheme = AppThemePreset.fromStoredName(prefs[KEY_APP_THEME]),
            appLanguage = AppLanguage.fromId(prefs[KEY_APP_LANGUAGE]),
            onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: false,
            fontSizeSp = TerminalFontConfig.normalizeFontSizeSp(
                prefs[KEY_FONT_SIZE_SP] ?: TerminalFontConfig.DEFAULT_FONT_SIZE_SP
            ),
            extraKeysLayout = ExtraKeysLayout.fromId(prefs[KEY_EXTRA_KEYS_LAYOUT]),
            aiEnabled = prefs[KEY_AI_ENABLED] ?: true,
            aiConsentAccepted = prefs[KEY_AI_CONSENT_ACCEPTED] ?: false,
            selectedModel = GeminiModels.normalizeSelectedModel(prefs[KEY_SELECTED_MODEL]),
            fallbackModels = parsedFallback,
            routingStrategy = KeyRoutingStrategy.fromId(prefs[KEY_ROUTING_STRATEGY]),
            cooldownSeconds = (prefs[KEY_COOLDOWN_SECONDS] ?: 60).coerceIn(10, 600),
            apiKeys = parsedKeys,
            systemAnimationsDisabled = SystemEffectsHelper.areSystemAnimationsDisabled(appContext),
            reduceEffectsOverride = prefs[KEY_REDUCE_EFFECTS],
            fullscreenTerminal = prefs[KEY_FULLSCREEN_TERMINAL] ?: false,
            keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: false,
            notifyLongCommands = prefs[KEY_NOTIFY_LONG_COMMANDS] ?: false,
            longCommandThresholdSeconds = LongCommandNotificationConfig.normalizeThresholdSeconds(
                prefs[KEY_LONG_COMMAND_THRESHOLD_SECONDS]
                    ?: LongCommandNotificationConfig.DEFAULT_THRESHOLD_SECONDS
            ),
            terminalFontId = com.example.terminal.TerminalFontRegistry.fromId(
                prefs[KEY_TERMINAL_FONT_ID]
            ).id
        )
    }

    override suspend fun setAppTheme(theme: AppThemePreset) {
        dataStore.edit { it[KEY_APP_THEME] = theme.id }
    }

    override suspend fun setReduceEffects(enabled: Boolean) {
        dataStore.edit { it[KEY_REDUCE_EFFECTS] = enabled }
    }

    override suspend fun setAppLanguage(language: AppLanguage) {
        dataStore.edit { it[KEY_APP_LANGUAGE] = language.id }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setFontSizeSp(sizeSp: Float) {
        dataStore.edit { it[KEY_FONT_SIZE_SP] = TerminalFontConfig.normalizeFontSizeSp(sizeSp) }
    }

    override suspend fun setExtraKeysLayout(layout: ExtraKeysLayout) {
        dataStore.edit { it[KEY_EXTRA_KEYS_LAYOUT] = layout.id }
    }

    override suspend fun setFullscreenTerminal(enabled: Boolean) {
        dataStore.edit { it[KEY_FULLSCREEN_TERMINAL] = enabled }
    }

    override suspend fun setKeepScreenOn(enabled: Boolean) {
        dataStore.edit { it[KEY_KEEP_SCREEN_ON] = enabled }
    }

    override suspend fun setNotifyLongCommands(enabled: Boolean) {
        dataStore.edit { it[KEY_NOTIFY_LONG_COMMANDS] = enabled }
    }

    override suspend fun setLongCommandThresholdSeconds(seconds: Int) {
        dataStore.edit {
            it[KEY_LONG_COMMAND_THRESHOLD_SECONDS] =
                LongCommandNotificationConfig.normalizeThresholdSeconds(seconds)
        }
    }

    override suspend fun setTerminalFontId(fontId: String) {
        val resolved = com.example.terminal.TerminalFontRegistry.fromId(fontId).id
        dataStore.edit { it[KEY_TERMINAL_FONT_ID] = resolved }
    }

    override suspend fun setAiEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AI_ENABLED] = enabled }
    }

    override suspend fun setAiConsentAccepted(accepted: Boolean) {
        dataStore.edit { it[KEY_AI_CONSENT_ACCEPTED] = accepted }
    }

    override suspend fun setSelectedModel(modelId: String?) {
        val normalized = GeminiModels.normalizeSelectedModel(modelId)
        dataStore.edit { prefs ->
            if (normalized == null) {
                prefs.remove(KEY_SELECTED_MODEL)
            } else {
                prefs[KEY_SELECTED_MODEL] = normalized
            }
        }
    }

    override suspend fun setFallbackModels(entries: List<FallbackModelEntry>) {
        val cleaned = normalizeFallbackEntries(entries)
        dataStore.edit { prefs ->
            prefs[KEY_FALLBACK_MODELS_JSON] = fallbackListAdapter.toJson(cleaned)
        }
    }

    override suspend fun setRoutingStrategy(strategy: KeyRoutingStrategy) {
        dataStore.edit { it[KEY_ROUTING_STRATEGY] = strategy.id }
    }

    override suspend fun setCooldownSeconds(seconds: Int) {
        dataStore.edit { it[KEY_COOLDOWN_SECONDS] = seconds.coerceIn(10, 600) }
    }

    override suspend fun addApiKey(label: String, rawKey: String): Result<ApiKeyMetadata> {
        val initialVaultStatus = keyVault.vaultStatus.value
        if (initialVaultStatus is VaultStatus.Corrupted) {
            return Result.failure(EncryptedVaultUnavailableException(initialVaultStatus.reason))
        }
        if (initialVaultStatus is VaultStatus.Unavailable) {
            return Result.failure(EncryptedVaultUnavailableException(initialVaultStatus.reason))
        }

        val currentPrefs = dataStore.data.first()
        val currentMetaList = decodeList(currentPrefs[KEY_API_KEYS_JSON])
        val existingSecrets = currentMetaList.mapNotNull { keyVault.getKey(it.id) }

        when (val postReadStatus = keyVault.vaultStatus.value) {
            is VaultStatus.Corrupted ->
                return Result.failure(EncryptedVaultUnavailableException(postReadStatus.reason))
            is VaultStatus.Unavailable ->
                return Result.failure(EncryptedVaultUnavailableException(postReadStatus.reason))
            VaultStatus.Ready -> Unit
        }

        val validation = ApiKeyValidator.validate(rawKey = rawKey, existingSecrets = existingSecrets)
        val cleanKey = when (validation) {
            is ApiKeyValidator.ValidationResult.Invalid ->
                return Result.failure(EncryptedVaultUnavailableException(validation.uiReason))
            is ApiKeyValidator.ValidationResult.Valid -> validation.cleanedKey
        }

        val id = "key_${UUID.randomUUID().toString().take(8)}"
        try {
            keyVault.putKey(id, cleanKey)
        } catch (e: EncryptedVaultUnavailableException) {
            return Result.failure(e)
        }

        val meta = ApiKeyMetadata(
            id = id,
            label = label.trim().ifEmpty { "Key-${id.takeLast(4)}" },
            maskedPreview = keyVault.maskKey(cleanKey),
            enabled = true,
            addedAtMillis = System.currentTimeMillis()
        )

        dataStore.edit { prefs ->
            val current = decodeList(prefs[KEY_API_KEYS_JSON])
            prefs[KEY_API_KEYS_JSON] = keyListAdapter.toJson(current + meta)
        }
        return Result.success(meta)
    }

    override suspend fun removeApiKey(id: String) {
        keyVault.removeKey(id)
        dataStore.edit { prefs ->
            val current = decodeList(prefs[KEY_API_KEYS_JSON])
            val updated = current.filterNot { it.id == id }
            prefs[KEY_API_KEYS_JSON] = keyListAdapter.toJson(updated)
        }
    }

    override suspend fun setApiKeyEnabled(id: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = decodeList(prefs[KEY_API_KEYS_JSON])
            val updated = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
            prefs[KEY_API_KEYS_JSON] = keyListAdapter.toJson(updated)
        }
    }

    override suspend fun updateApiKeyLabel(id: String, newLabel: String) {
        val clean = newLabel.trim()
        if (clean.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decodeList(prefs[KEY_API_KEYS_JSON])
            val updated = current.map { if (it.id == id) it.copy(label = clean) else it }
            prefs[KEY_API_KEYS_JSON] = keyListAdapter.toJson(updated)
        }
    }

    override suspend fun resetCorruptedVault(): Result<Unit> {
        val recovered = keyVault.resetVault()
        if (!recovered) {
            val reason = when (val st = keyVault.vaultStatus.value) {
                is VaultStatus.Corrupted -> st.reason
                is VaultStatus.Unavailable -> st.reason
                VaultStatus.Ready -> UiText.Resource(R.string.vault_err_unavailable, "Reset failed")
            }
            return Result.failure(EncryptedVaultUnavailableException(reason))
        }
        dataStore.edit { prefs ->
            prefs[KEY_API_KEYS_JSON] = "[]"
        }
        return Result.success(Unit)
    }

    override fun getSecretKey(id: String): String? = keyVault.getKey(id)

    private fun decodeList(json: String?): List<ApiKeyMetadata> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { keyListAdapter.fromJson(json) }.getOrNull().orEmpty()
    }

    private fun decodeFallbackList(json: String?): List<FallbackModelEntry> {
        if (json.isNullOrBlank()) return emptyList()
        val parsed = runCatching { fallbackListAdapter.fromJson(json) }.getOrNull().orEmpty()
        return normalizeFallbackEntries(parsed)
    }

    private fun normalizeFallbackEntries(entries: List<FallbackModelEntry>): List<FallbackModelEntry> {
        val seen = LinkedHashSet<String>()
        return entries.filter { entry ->
            GeminiModels.byId(entry.modelId) != null && seen.add(entry.modelId)
        }
    }

    companion object {
        private val KEY_APP_THEME = stringPreferencesKey("app_theme")
        private val KEY_REDUCE_EFFECTS = booleanPreferencesKey("reduce_effects")
        private val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_FONT_SIZE_SP = floatPreferencesKey("font_size_sp")
        private val KEY_EXTRA_KEYS_LAYOUT = stringPreferencesKey("extra_keys_layout")
        private val KEY_AI_ENABLED = booleanPreferencesKey("ai_enabled")
        private val KEY_AI_CONSENT_ACCEPTED = booleanPreferencesKey("ai_consent_accepted")
        private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model")
        private val KEY_FALLBACK_MODELS_JSON = stringPreferencesKey("fallback_models_json")
        private val KEY_ROUTING_STRATEGY = stringPreferencesKey("routing_strategy")
        private val KEY_COOLDOWN_SECONDS = intPreferencesKey("cooldown_seconds")
        private val KEY_API_KEYS_JSON = stringPreferencesKey("api_keys_json")
        private val KEY_FULLSCREEN_TERMINAL = booleanPreferencesKey("fullscreen_terminal")
        private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val KEY_NOTIFY_LONG_COMMANDS = booleanPreferencesKey("notify_long_commands")
        private val KEY_LONG_COMMAND_THRESHOLD_SECONDS = intPreferencesKey("long_command_threshold_seconds")
        private val KEY_TERMINAL_FONT_ID = stringPreferencesKey("terminal_font_id")
    }
}
