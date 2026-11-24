package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ai.KeyHealthStatus
import com.example.ai.KeyManager
import com.example.ai.KeyManagerSnapshot
import com.example.ai.ManagedKeyStatus
import com.example.data.ApiKeyMetadata
import com.example.data.AppLanguage
import com.example.data.AppSettings
import com.example.data.ExtraKeysLayout
import com.example.data.FallbackModelEntry
import com.example.data.KeyRoutingStrategy
import com.example.data.LocaleHelper
import com.example.data.SettingsStore
import com.example.data.VaultStatus
import com.example.settings.SettingsGroup
import com.example.settings.SettingsRoute
import com.example.settings.SettingsScreen
import com.example.settings.SettingsSummaries
import com.example.settings.SettingsViewModel
import com.example.settings.visibleSettingsGroups
import com.example.ui.UiText
import com.example.ui.onboarding.OnboardingScreen
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.HackerStudioTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xhdpi")
class SettingsArchitectureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeSettingsStore(
        initial: AppSettings = AppSettings()
    ) : SettingsStore {
        private val secrets = mutableMapOf<String, String>()
        private val state = MutableStateFlow(initial)
        override val settingsFlow: Flow<AppSettings> = state.asStateFlow()
        override val vaultStatus: StateFlow<VaultStatus> =
            MutableStateFlow<VaultStatus>(VaultStatus.Ready).asStateFlow()

        val current: AppSettings get() = state.value
        val removedKeyIds = mutableListOf<String>()

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
        override suspend fun setFontSizeSp(sizeSp: Float) {
            state.update { it.copy(fontSizeSp = sizeSp) }
        }
        override suspend fun setExtraKeysLayout(layout: ExtraKeysLayout) {
            state.update { it.copy(extraKeysLayout = layout) }
        }
        override suspend fun setAiEnabled(enabled: Boolean) {
            state.update { it.copy(aiEnabled = enabled) }
        }
        override suspend fun setSelectedModel(modelId: String?) {
            state.update { it.copy(selectedModel = modelId) }
        }
        override suspend fun setFallbackModels(entries: List<FallbackModelEntry>) {
            state.update { it.copy(fallbackModels = entries) }
        }
        override suspend fun setRoutingStrategy(strategy: KeyRoutingStrategy) {
            state.update { it.copy(routingStrategy = strategy) }
        }
        override suspend fun setCooldownSeconds(seconds: Int) {
            state.update { it.copy(cooldownSeconds = seconds) }
        }
        override suspend fun addApiKey(label: String, rawKey: String): Result<ApiKeyMetadata> {
            val id = "k_${state.value.apiKeys.size + 1}"
            secrets[id] = rawKey
            val meta = ApiKeyMetadata(id = id, label = label.ifBlank { "Key $id" }, maskedPreview = "AIza••••")
            state.update { it.copy(apiKeys = it.apiKeys + meta) }
            return Result.success(meta)
        }
        override suspend fun removeApiKey(id: String) {
            removedKeyIds.add(id)
            secrets.remove(id)
            state.update { cur -> cur.copy(apiKeys = cur.apiKeys.filterNot { it.id == id }) }
        }
        override suspend fun setApiKeyEnabled(id: String, enabled: Boolean) {
            state.update { cur ->
                cur.copy(apiKeys = cur.apiKeys.map { if (it.id == id) it.copy(enabled = enabled) else it })
            }
        }
        override suspend fun updateApiKeyLabel(id: String, newLabel: String) {}
        override fun getSecretKey(id: String): String? = secrets[id]
    }

    @Test
    fun `pure summary functions cover empty, single key, multiple keys, cooldown, AI, appearance, terminal and language`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val en = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.ENGLISH)
        val id = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.INDONESIAN)

        // AI summaries
        assertEquals("Disabled", SettingsSummaries.buildAiSummary(AppSettings(aiEnabled = false)).asString(en))
        assertEquals("Nonaktif", SettingsSummaries.buildAiSummary(AppSettings(aiEnabled = false)).asString(id))
        assertEquals("No model selected", SettingsSummaries.buildAiSummary(AppSettings(selectedModel = null)).asString(en))
        assertEquals("Belum dipilih", SettingsSummaries.buildAiSummary(AppSettings(selectedModel = null)).asString(id))
        assertEquals(
            "Gemini 3.8 Flash · 2 cadangan",
            SettingsSummaries.buildAiSummary(
                AppSettings(
                    selectedModel = "gemini-3.8-flash",
                    fallbackModels = listOf(
                        FallbackModelEntry("gemini-3.6-flash", enabled = true),
                        FallbackModelEntry("gemini-4-hydrogen", enabled = false),
                        FallbackModelEntry("gemini-3.5-flash-lite", enabled = true)
                    )
                )
            ).asString(id)
        )

        // Keys summaries
        assertEquals("No keys added", SettingsSummaries.buildKeysSummary(KeyManagerSnapshot()).asString(en))
        assertEquals("Belum ada kunci", SettingsSummaries.buildKeysSummary(KeyManagerSnapshot()).asString(id))

        val oneKey = KeyManagerSnapshot(
            keys = listOf(ManagedKeyStatus(ApiKeyMetadata("k1", "Primary", "AIza••"), KeyHealthStatus.ACTIVE))
        )
        assertEquals("1 key", SettingsSummaries.buildKeysSummary(oneKey).asString(en))
        assertEquals("1 kunci", SettingsSummaries.buildKeysSummary(oneKey).asString(id))

        val threeKeys = KeyManagerSnapshot(
            keys = listOf(
                ManagedKeyStatus(ApiKeyMetadata("k1", "K1", "AIza••"), KeyHealthStatus.ACTIVE),
                ManagedKeyStatus(ApiKeyMetadata("k2", "K2", "AIza••"), KeyHealthStatus.READY),
                ManagedKeyStatus(ApiKeyMetadata("k3", "K3", "AIza••"), KeyHealthStatus.READY)
            )
        )
        assertEquals("3 keys", SettingsSummaries.buildKeysSummary(threeKeys).asString(en))
        assertEquals("3 kunci", SettingsSummaries.buildKeysSummary(threeKeys).asString(id))

        val threeWithCooldown = KeyManagerSnapshot(
            keys = listOf(
                ManagedKeyStatus(ApiKeyMetadata("k1", "K1", "AIza••"), KeyHealthStatus.ACTIVE),
                ManagedKeyStatus(ApiKeyMetadata("k2", "K2", "AIza••"), KeyHealthStatus.COOLDOWN, cooldownRemainingSec = 30),
                ManagedKeyStatus(ApiKeyMetadata("k3", "K3", "AIza••"), KeyHealthStatus.READY)
            )
        )
        assertEquals("3 keys · 1 cooldown", SettingsSummaries.buildKeysSummary(threeWithCooldown).asString(en))
        assertEquals("3 kunci · 1 cooldown", SettingsSummaries.buildKeysSummary(threeWithCooldown).asString(id))

        assertEquals(
            "Encrypted vault unavailable",
            SettingsSummaries.buildKeysSummary(oneKey, VaultStatus.Unavailable(UiText.Dynamic("err"))).asString(en)
        )

        // Appearance, Terminal, Language, About
        assertEquals(
            "Cyberpunk Futuristik · Efek dikurangi",
            SettingsSummaries.buildAppearanceSummary(
                AppSettings(appTheme = AppThemePreset.CYBERPUNK, reduceEffectsOverride = true)
            ).asString(id)
        )
        assertEquals(
            "Hacker Pro · 13sp font",
            SettingsSummaries.buildAppearanceSummary(
                AppSettings(appTheme = AppThemePreset.HACKER_PRO, reduceEffectsOverride = false, fontSizeSp = 13f)
            ).asString(en)
        )
        assertEquals(
            "Extra keys: Developer Pro",
            SettingsSummaries.buildTerminalSummary(AppSettings(extraKeysLayout = ExtraKeysLayout.DEVELOPER)).asString(en)
        )
        assertEquals(
            "Bahasa Indonesia",
            SettingsSummaries.buildLanguageSummary(AppSettings(appLanguage = AppLanguage.INDONESIAN)).asString(id)
        )
        assertEquals("Version 1.0", SettingsSummaries.buildAboutSummary("1.0").asString(en))
    }

    @Test
    fun `SettingsRoute saver round-trips all routes and DataPrivacy is hidden when empty`() {
        val visible = visibleSettingsGroups()
        assertEquals(
            listOf(
                SettingsGroup.AI_ASSISTANT,
                SettingsGroup.API_KEYS,
                SettingsGroup.APPEARANCE,
                SettingsGroup.TERMINAL,
                SettingsGroup.LANGUAGE,
                SettingsGroup.ABOUT
            ),
            visible
        )
        assertFalse(visible.contains(SettingsGroup.DATA_PRIVACY))

        val allRoutes = listOf(
            SettingsRoute.Home,
            SettingsRoute.AiAssistant,
            SettingsRoute.ApiKeys,
            SettingsRoute.Appearance,
            SettingsRoute.Terminal,
            SettingsRoute.Language,
            SettingsRoute.DataPrivacy,
            SettingsRoute.About
        )
        val dummyScope = SaverScope { true }
        allRoutes.forEach { route ->
            val saved = with(SettingsRoute.Saver) { dummyScope.save(route) }
            val restored = SettingsRoute.Saver.restore(saved!!)
            assertEquals(route, restored)
        }
    }

    @Test
    fun `narrow screen navigates into sub-page and back returns to compact home menu`() {
        val store = FakeSettingsStore(AppSettings(selectedModel = "gemini-3.8-flash"))
        val viewModel = SettingsViewModel(store, KeyManager(store))

        composeRule.setContent {
            val live by store.settingsFlow.collectAsState(initial = store.current)
            HackerStudioTheme(appTheme = live.appTheme, reduceEffects = live.reduceEffects) {
                Box(modifier = Modifier.size(width = 400.dp, height = 800.dp)) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateBack = {},
                        onOpenOnboarding = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Home menu lists all 6 populated groups and hides empty DataPrivacy
        composeRule.onNodeWithTag("settings_home_list").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_group_ai_assistant").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_group_api_keys").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_group_appearance").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_group_terminal").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_group_language").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_group_about").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_group_data_privacy").assertDoesNotExist()

        // Open Terminal group -> verify sub-page opens -> Back returns to menu
        composeRule.onNodeWithTag("settings_group_terminal").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_terminal_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("extra_layout_developer").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ExtraKeysLayout.DEVELOPER, store.current.extraKeysLayout)

        composeRule.onNodeWithTag("settings_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_home_list").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp-xhdpi")
    fun `wide screen displays two-pane layout and updates detail pane directly`() {
        val store = FakeSettingsStore()
        val viewModel = SettingsViewModel(store, KeyManager(store))

        composeRule.setContent {
            val live by store.settingsFlow.collectAsState(initial = store.current)
            HackerStudioTheme(appTheme = live.appTheme, reduceEffects = live.reduceEffects) {
                Box(modifier = Modifier.size(width = 760.dp, height = 800.dp)) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateBack = {},
                        onOpenOnboarding = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_two_pane_layout").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_home_list").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_detail_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_ai_screen").assertIsDisplayed()

        composeRule.onNodeWithTag("settings_group_language").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_home_list").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_language_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("lang_id").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppLanguage.INDONESIAN, store.current.appLanguage)
    }

    @Test
    fun `ApiKeys Advanced is collapsed by default and destructive actions require confirmation`() = runTest {
        val store = FakeSettingsStore()
        val keyMeta = store.addApiKey("Primary", "secret-1").getOrThrow()
        val viewModel = SettingsViewModel(store, KeyManager(store))
        var onboardingOpened = false

        composeRule.setContent {
            val live by store.settingsFlow.collectAsState(initial = store.current)
            HackerStudioTheme(appTheme = live.appTheme, reduceEffects = live.reduceEffects) {
                Box(modifier = Modifier.size(width = 412.dp, height = 900.dp)) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateBack = {},
                        onOpenOnboarding = { onboardingOpened = true },
                        initialRoute = SettingsRoute.ApiKeys
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Advanced routing/cooldown are collapsed initially
        composeRule.onNodeWithTag("routing_failover").assertDoesNotExist()
        composeRule.onNodeWithTag("cooldown_slider").assertDoesNotExist()

        // Expand Advanced and modify routing strategy
        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasTestTag("api_keys_advanced_toggle"))
        composeRule.onNodeWithTag("api_keys_advanced_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasTestTag("routing_failover"))
        composeRule.onNodeWithTag("routing_failover").assertIsDisplayed().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(KeyRoutingStrategy.FAILOVER, store.current.routingStrategy)

        // Delete key -> Cancel keeps key intact
        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasTestTag("delete_key_${keyMeta.id}"))
        composeRule.onNodeWithTag("delete_key_${keyMeta.id}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("confirm_delete_key_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("cancel_delete_key_button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        assertTrue(store.current.apiKeys.any { it.id == keyMeta.id })
        assertTrue(store.removedKeyIds.isEmpty())

        // Delete key -> Confirm deletes key
        composeRule.onNodeWithTag("delete_key_${keyMeta.id}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("confirm_delete_key_button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        assertTrue(store.current.apiKeys.isEmpty())
        assertEquals(listOf(keyMeta.id), store.removedKeyIds)

        // Back to home -> About -> Reopen onboarding Danger Zone confirmation
        composeRule.onNodeWithTag("settings_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_group_about").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("danger_zone_section").assertIsDisplayed()
        composeRule.onNodeWithTag("reopen_onboarding_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("confirm_reopen_onboarding_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("cancel_reopen_onboarding_button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(onboardingOpened)

        composeRule.onNodeWithTag("reopen_onboarding_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("confirm_reopen_onboarding_button").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(onboardingOpened)
    }

    @Test
    fun `all remaining settings items in AiAssistant and Appearance save cleanly across sub-pages`() {
        val store = FakeSettingsStore(AppSettings(onboardingCompleted = true))
        val viewModel = SettingsViewModel(store, KeyManager(store))

        composeRule.setContent {
            val live by store.settingsFlow.collectAsState(initial = store.current)
            HackerStudioTheme(appTheme = live.appTheme, reduceEffects = live.reduceEffects) {
                Box(modifier = Modifier.size(width = 412.dp, height = 900.dp)) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateBack = {},
                        onOpenOnboarding = {},
                        initialRoute = SettingsRoute.AiAssistant
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // AI toggle, primary model, fallback model
        composeRule.onNodeWithTag("ai_enabled_switch").assertIsOn().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(store.current.aiEnabled)
        composeRule.onNodeWithTag("ai_enabled_switch").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(store.current.aiEnabled)

        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasTestTag("primary_model_gemini-4-hydrogen"))
        composeRule.onNodeWithTag("primary_model_gemini-4-hydrogen").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("gemini-4-hydrogen", store.current.selectedModel)

        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasTestTag("add_fallback_gemini-3.5-flash-lite"))
        composeRule.onNodeWithTag("add_fallback_gemini-3.5-flash-lite").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(FallbackModelEntry("gemini-3.5-flash-lite", enabled = true)), store.current.fallbackModels)

        // Back -> Appearance -> Theme & Reduce effects
        composeRule.onNodeWithTag("settings_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_group_appearance").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("theme_preview_cyberpunk").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppThemePreset.CYBERPUNK, store.current.appTheme)

        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasTestTag("reduce_effects_switch"))
        composeRule.onNodeWithTag("reduce_effects_switch").assertIsOff().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(store.current.reduceEffects)

        // Font size slider saves cleanly
        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasTestTag("font_size_slider"))
        composeRule.onNodeWithTag("font_size_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(16f) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(16f, store.current.fontSizeSp, 0.01f)
    }

    @Test
    fun `settings groups have unique routes, keys, titles and tags and cooldown slider saves`() {
        val groups = SettingsGroup.entries
        assertEquals(groups.size, groups.map { it.route }.distinct().size)
        assertEquals(groups.size, groups.map { it.route.key }.distinct().size)
        assertEquals(groups.size, groups.map { it.titleRes }.distinct().size)
        assertEquals(groups.size, groups.map { it.testTagSuffix }.distinct().size)

        val store = FakeSettingsStore()
        val viewModel = SettingsViewModel(store, KeyManager(store))
        composeRule.setContent {
            val live by store.settingsFlow.collectAsState(initial = store.current)
            HackerStudioTheme(appTheme = live.appTheme, reduceEffects = live.reduceEffects) {
                Box(modifier = Modifier.size(width = 412.dp, height = 900.dp)) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateBack = {},
                        onOpenOnboarding = {},
                        initialRoute = SettingsRoute.ApiKeys
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasTestTag("api_keys_advanced_toggle"))
        composeRule.onNodeWithTag("api_keys_advanced_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasTestTag("cooldown_slider"))
        composeRule.onNodeWithTag("cooldown_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(120, store.current.cooldownSeconds)
    }

    @Test
    fun `onboarding step 4 shares GeminiModelChoiceList and persists selected model`() {
        val store = FakeSettingsStore()
        val viewModel = SettingsViewModel(store, KeyManager(store))

        composeRule.setContent {
            val live by store.settingsFlow.collectAsState(initial = store.current)
            HackerStudioTheme(appTheme = live.appTheme, reduceEffects = live.reduceEffects) {
                OnboardingScreen(
                    viewModel = viewModel,
                    onCompleted = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Step 1 -> 2 -> 3 -> 4
        composeRule.onNodeWithTag("onboarding_primary_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_primary_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_skip_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("onboarding_model_gemini-3.8-flash").assertIsDisplayed().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("gemini-3.8-flash", store.current.selectedModel)
    }
}
