package com.example.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.BuildConfig
import com.example.R
import com.example.ai.KeyHealthStatus
import com.example.ai.KeyManagerSnapshot
import com.example.data.AppSettings
import com.example.data.GeminiModels
import com.example.data.TerminalFontConfig
import com.example.data.VaultStatus
import com.example.ui.UiText

/**
 * Typed two-level Settings routes and complete, 1-to-1 inventory mapping of ALL existing
 * HackerStudio settings, toggles, choices, sliders, actions, and dialogs.
 *
 * Inventory & Group Mapping (zero missing, zero duplicate items):
 *
 * 1. [SettingsRoute.AiAssistant] ("AI Assistant" / "Asisten AI"):
 *    - AI Side Panel master switch (`ai_enabled_switch` -> `SettingsViewModel.setAiEnabled`)
 *    - AI Data Consent status & revoke consent action (`ai_consent_section`, `revoke_ai_consent_button` -> `SettingsViewModel.revokeAiConsent`)
 *    - Primary Gemini model single choice (`primary_model_<id>` + `primary_model_unset_notice` -> `SettingsViewModel.setSelectedModel`, shared with Onboarding Step 4)
 *    - Ordered fallback models list: move up (`fallback_up_<id>`), move down (`fallback_down_<id>`), toggle enabled (`fallback_toggle_<id>`), remove (`fallback_remove_<id>`), add chips (`add_fallback_<id>`), empty notice (`fallback_models_empty_notice`)
 *
 * 2. [SettingsRoute.ApiKeys] ("API Keys" / "Kunci API"):
 *    - Encrypted vault error/corruption banner + dismiss or Reset Vault action (`vault_error_banner`, `reset_vault_button` gated by `confirm_reset_vault_dialog` -> `SettingsViewModel.dismissVaultError` / `SettingsViewModel.resetCorruptedVault`)
 *    - Vault status & active key summary card + Reset Cooldowns button (`clear_cooldowns_button` -> `SettingsViewModel.clearCooldowns`)
 *    - Stored API keys list (`api_key_row_<id>`): per-key enable toggle (`toggle_key_<id>`), recheck button (`recheck_key_<id>`), delete key button (`delete_key_<id>` gated by confirmation dialog -> `SettingsViewModel.removeApiKey`)
 *    - Add Gemini API Key button (`add_api_key_button`) & `AddApiKeyDialog` (`api_key_label_input`, `api_key_secret_input`, `confirm_add_api_key` -> `SettingsViewModel.addApiKey`)
 *    - Collapsible Advanced section (collapsed by default): Key Routing Strategy (`routing_round_robin`, `routing_failover` -> `SettingsViewModel.setRoutingStrategy`) and 429/403 Retry Delay slider (`cooldown_slider` -> `SettingsViewModel.setCooldownSeconds`)
 *
 * 3. [SettingsRoute.Appearance] ("Appearance" / "Tampilan"):
 *    - Studio Theme single-choice preview cards (`theme_preview_<id>` -> `SettingsViewModel.setAppTheme`, shared `ThemeOptionPreviewCard` with Onboarding Step 2)
 *    - Reduce visual effects toggle (`reduce_effects_switch` -> `SettingsViewModel.setReduceEffects`)
 *    - Terminal font size slider + live prompt preview (`font_size_slider` -> `SettingsViewModel.setFontSizeSp`)
 *
 * 4. [SettingsRoute.Terminal] ("Terminal"):
 *    - Extra Keys Row Layout choice (`extra_layout_standard`, `extra_layout_developer`, `extra_layout_compact` -> `SettingsViewModel.setExtraKeysLayout`)
 *
 * 5. [SettingsRoute.Language] ("Language" / "Bahasa"):
 *    - App interface & Gemini AI response language choice (`lang_system`, `lang_id`, `lang_en` -> `SettingsViewModel.setAppLanguage`)
 *
 * 6. [SettingsRoute.DataPrivacy] ("Data & Privacy" / "Data & Privasi"):
 *    - Currently holds 0 Settings items (prompt history & snippet JSON export/import belong to AI panel & Snippets tab); omitted from Settings menu until populated.
 *
 * 7. [SettingsRoute.About] ("About" / "Tentang"):
 *    - App version (`BuildConfig.VERSION_NAME`, `BuildConfig.VERSION_CODE`), Package ID (`BuildConfig.APPLICATION_ID`), and library info
 *    - Danger Zone (`danger_zone_section`): Run First-Time Setup Wizard reset (`reopen_onboarding_button` gated by confirmation dialog -> `SettingsViewModel.reopenOnboarding` + `onOpenOnboarding`)
 */
sealed interface SettingsRoute {
    val key: String

    data object Home : SettingsRoute {
        override val key: String = "home"
    }

    data object AiAssistant : SettingsRoute {
        override val key: String = "ai_assistant"
    }

    data object ApiKeys : SettingsRoute {
        override val key: String = "api_keys"
    }

    data object Appearance : SettingsRoute {
        override val key: String = "appearance"
    }

    data object Terminal : SettingsRoute {
        override val key: String = "terminal"
    }

    data object Language : SettingsRoute {
        override val key: String = "language"
    }

    data object DataPrivacy : SettingsRoute {
        override val key: String = "data_privacy"
    }

    data object About : SettingsRoute {
        override val key: String = "about"
    }

    companion object {
        fun fromKey(key: String?): SettingsRoute = when (key) {
            AiAssistant.key -> AiAssistant
            ApiKeys.key -> ApiKeys
            Appearance.key -> Appearance
            Terminal.key -> Terminal
            Language.key -> Language
            DataPrivacy.key -> DataPrivacy
            About.key -> About
            else -> Home
        }

        val Saver: Saver<SettingsRoute, String> = Saver(
            save = { it.key },
            restore = { fromKey(it) }
        )
    }
}

enum class SettingsGroup(
    val route: SettingsRoute,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val testTagSuffix: String
) {
    AI_ASSISTANT(
        route = SettingsRoute.AiAssistant,
        titleRes = R.string.settings_group_ai,
        icon = Icons.Default.AutoAwesome,
        testTagSuffix = "ai_assistant"
    ),
    API_KEYS(
        route = SettingsRoute.ApiKeys,
        titleRes = R.string.settings_group_keys,
        icon = Icons.Default.Key,
        testTagSuffix = "api_keys"
    ),
    APPEARANCE(
        route = SettingsRoute.Appearance,
        titleRes = R.string.settings_group_appearance,
        icon = Icons.Default.Palette,
        testTagSuffix = "appearance"
    ),
    TERMINAL(
        route = SettingsRoute.Terminal,
        titleRes = R.string.settings_group_terminal,
        icon = Icons.Default.Terminal,
        testTagSuffix = "terminal"
    ),
    LANGUAGE(
        route = SettingsRoute.Language,
        titleRes = R.string.settings_group_language,
        icon = Icons.Default.Language,
        testTagSuffix = "language"
    ),
    DATA_PRIVACY(
        route = SettingsRoute.DataPrivacy,
        titleRes = R.string.settings_group_data,
        icon = Icons.Default.Security,
        testTagSuffix = "data_privacy"
    ),
    ABOUT(
        route = SettingsRoute.About,
        titleRes = R.string.settings_group_about,
        icon = Icons.Default.Info,
        testTagSuffix = "about"
    );

    companion object {
        fun fromRoute(route: SettingsRoute): SettingsGroup? =
            entries.firstOrNull { it.route == route }
    }
}

fun visibleSettingsGroups(hasDataPrivacyActions: Boolean = false): List<SettingsGroup> = buildList {
    add(SettingsGroup.AI_ASSISTANT)
    add(SettingsGroup.API_KEYS)
    add(SettingsGroup.APPEARANCE)
    add(SettingsGroup.TERMINAL)
    add(SettingsGroup.LANGUAGE)
    if (hasDataPrivacyActions) {
        add(SettingsGroup.DATA_PRIVACY)
    }
    add(SettingsGroup.ABOUT)
}

object SettingsSummaries {
    fun buildAiSummary(settings: AppSettings): UiText {
        if (!settings.aiEnabled) {
            return UiText.Resource(R.string.settings_summary_ai_off)
        }
        val normalized = GeminiModels.normalizeSelectedModel(settings.selectedModel)
        val modelUiText = GeminiModels.byId(normalized)?.displayName?.let { UiText.Dynamic(it) }
            ?: UiText.Resource(R.string.ai_model_unselected_short)
        val enabledFallbacks = settings.fallbackModels.count {
            it.enabled && GeminiModels.byId(it.modelId) != null
        }
        return if (enabledFallbacks > 0) {
            UiText.Resource(R.string.settings_summary_ai_with_fallback, modelUiText, enabledFallbacks)
        } else {
            modelUiText
        }
    }

    fun buildKeysSummary(
        snapshot: KeyManagerSnapshot,
        vaultStatus: VaultStatus = VaultStatus.Ready
    ): UiText {
        if (vaultStatus is VaultStatus.Corrupted) {
            return UiText.Resource(R.string.settings_summary_keys_vault_corrupted)
        }
        if (vaultStatus is VaultStatus.Unavailable) {
            return UiText.Resource(R.string.settings_summary_keys_vault_unavailable)
        }
        val total = snapshot.keys.size
        if (total == 0) {
            return UiText.Resource(R.string.settings_summary_keys_empty)
        }
        val cooldownCount = snapshot.keys.count {
            it.status == KeyHealthStatus.COOLDOWN || it.status == KeyHealthStatus.FORBIDDEN_DELAY
        }
        if (cooldownCount > 0) {
            return UiText.Resource(R.string.settings_summary_keys_cooldown, total, cooldownCount)
        }
        val errorCount = snapshot.keys.count {
            it.status == KeyHealthStatus.AUTH_ERROR || it.status == KeyHealthStatus.MODEL_UNAVAILABLE
        }
        if (errorCount > 0) {
            return UiText.Resource(R.string.settings_summary_keys_attention, total, errorCount)
        }
        return if (total == 1) {
            UiText.Resource(R.string.settings_summary_keys_single)
        } else {
            UiText.Resource(R.string.settings_summary_keys_many, total)
        }
    }

    fun buildAppearanceSummary(settings: AppSettings): UiText {
        val themeLabel = UiText.Resource(settings.appTheme.labelRes)
        return if (settings.reduceEffects) {
            UiText.Resource(R.string.settings_summary_appearance_reduced, themeLabel)
        } else {
            UiText.Resource(
                R.string.settings_summary_appearance_normal,
                themeLabel,
                TerminalFontConfig.formatFontSizeValue(settings.fontSizeSp)
            )
        }
    }

    fun buildTerminalSummary(settings: AppSettings): UiText =
        UiText.Resource(
            R.string.settings_summary_terminal,
            UiText.Resource(settings.extraKeysLayout.labelRes)
        )

    fun buildLanguageSummary(settings: AppSettings): UiText =
        UiText.Resource(settings.appLanguage.labelRes)

    fun buildAboutSummary(versionName: String = BuildConfig.VERSION_NAME): UiText =
        UiText.Resource(R.string.settings_summary_about, versionName)

    fun forGroup(
        group: SettingsGroup,
        settings: AppSettings,
        snapshot: KeyManagerSnapshot,
        vaultStatus: VaultStatus,
        versionName: String = BuildConfig.VERSION_NAME
    ): UiText = when (group) {
        SettingsGroup.AI_ASSISTANT -> buildAiSummary(settings)
        SettingsGroup.API_KEYS -> buildKeysSummary(snapshot, vaultStatus)
        SettingsGroup.APPEARANCE -> buildAppearanceSummary(settings)
        SettingsGroup.TERMINAL -> buildTerminalSummary(settings)
        SettingsGroup.LANGUAGE -> buildLanguageSummary(settings)
        SettingsGroup.DATA_PRIVACY -> UiText.Resource(R.string.settings_data_empty)
        SettingsGroup.ABOUT -> buildAboutSummary(versionName)
    }
}
