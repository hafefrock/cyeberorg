package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiAssistantRepository
import com.example.ai.AiChatMessage
import com.example.ai.AiMessageRole
import com.example.ai.CommandRiskClassifier
import com.example.ai.CommandSuggestion
import com.example.ai.GeminiCallResult
import com.example.ai.GeminiContent
import com.example.ai.GeminiStreamEvent
import com.example.ai.GeminiTransport
import com.example.ai.KeyManager
import com.example.data.ApiKeyMetadata
import com.example.data.AppLanguage
import com.example.data.AppSettings
import com.example.data.CommandSnippet
import com.example.data.ExtraKeysLayout
import com.example.data.FallbackModelEntry
import com.example.data.KeyRoutingStrategy
import com.example.data.LocaleHelper
import com.example.data.PromptHistoryItem
import com.example.data.PromptHistoryStore
import com.example.data.SettingsStore
import com.example.data.SnippetStore
import com.example.data.VaultStatus
import com.example.terminal.FakeTerminalBackend
import com.example.ui.UiText
import com.example.ui.ai.AiCommandCard
import com.example.ui.ai.AiQuickActionsBar
import com.example.ui.ai.computeCompactMessages
import com.example.ui.terminal.SessionTabsBar
import com.example.ui.terminal.SplitMode
import com.example.ui.terminal.TerminalViewModel
import com.example.ui.terminal.WorkspaceSection
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.HackerStudioTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiUiAndStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class InMemorySettingsStore(
        initialModel: String? = "gemini-3.8-flash"
    ) : SettingsStore {
        private val secrets = mutableMapOf<String, String>()
        private val state = MutableStateFlow(
            AppSettings(
                aiConsentAccepted = true,
                selectedModel = initialModel
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
        override suspend fun setAiEnabled(enabled: Boolean) {
            state.update { it.copy(aiEnabled = enabled) }
        }
        override suspend fun setSelectedModel(modelId: String?) {
            state.update { it.copy(selectedModel = modelId) }
        }
        override suspend fun setFallbackModels(entries: List<FallbackModelEntry>) {}
        override suspend fun setRoutingStrategy(strategy: KeyRoutingStrategy) {}
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

    private fun createViewModel(
        replyGate: CompletableDeferred<String>? = null
    ): Triple<TerminalViewModel, InMemorySettingsStore, CoroutineScope> {
        val backend = FakeTerminalBackend()
        val settingsStore = InMemorySettingsStore(initialModel = "gemini-3.8-flash")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch { settingsStore.addApiKey("Primary", "secret-key-1") }
        val keyManager = KeyManager(settingsStore)

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
                val reply = replyGate?.await() ?: "Here is your answer.\n```bash\nls -la\n```"
                emit(GeminiStreamEvent.Chunk(reply))
                emit(GeminiStreamEvent.Completed("STOP"))
            }
        }
        val repo = AiAssistantRepository(
            keyManager = keyManager,
            geminiApiClient = transport,
            settingsStore = settingsStore,
            defaultDispatcher = Dispatchers.Main.immediate
        )
        val snippetStore = object : SnippetStore {
            override val snippetsFlow: Flow<List<CommandSnippet>> = MutableStateFlow(emptyList())
            override suspend fun saveSnippet(title: String, command: String, tags: List<String>, existingId: String?) =
                CommandSnippet(id = existingId ?: "s1", title = title, command = command)
            override suspend fun deleteSnippet(id: String) {}
            override fun exportToJson(snippets: List<CommandSnippet>): String = "[]"
            override suspend fun importFromJson(rawJson: String): Result<Int> = Result.success(0)
        }
        val historyStore = object : PromptHistoryStore {
            override val historyFlow: Flow<List<PromptHistoryItem>> = MutableStateFlow(emptyList())
            override suspend fun recordPrompt(prompt: String) {}
            override suspend fun clearAll() {}
        }
        val vm = TerminalViewModel(
            terminalBackend = backend,
            aiRepository = repo,
            keyManager = keyManager,
            settingsStore = settingsStore,
            snippetStore = snippetStore,
            promptHistoryStore = historyStore
        )
        return Triple(vm, settingsStore, scope)
    }

    @Test
    fun `switching Terminal to AI and back leaves isAiPanelOpen false, and tab switches always close sheet`() {
        val (vm, _, scope) = createViewModel()
        val job = scope.launch { vm.uiState.collect {} }
        try {
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(WorkspaceSection.TERMINAL, vm.uiState.value.activeSection)
            assertFalse(vm.uiState.value.isAiPanelOpen)

            // 1. Terminal -> AI -> Terminal MUST leave isAiPanelOpen = false
            vm.selectWorkspaceSection(WorkspaceSection.AI)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WorkspaceSection.AI, vm.uiState.value.activeSection)
            assertFalse(vm.uiState.value.isAiPanelOpen)

            vm.selectWorkspaceSection(WorkspaceSection.TERMINAL)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WorkspaceSection.TERMINAL, vm.uiState.value.activeSection)
            assertFalse(vm.uiState.value.isAiPanelOpen)

            // 2. Opening sheet then switching to Snippets -> Terminal MUST leave isAiPanelOpen = false
            vm.toggleAiPanel(forceOpen = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(vm.uiState.value.isAiPanelOpen)

            vm.selectWorkspaceSection(WorkspaceSection.SNIPPETS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WorkspaceSection.SNIPPETS, vm.uiState.value.activeSection)
            assertFalse(vm.uiState.value.isAiPanelOpen)

            vm.selectWorkspaceSection(WorkspaceSection.TERMINAL)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WorkspaceSection.TERMINAL, vm.uiState.value.activeSection)
            assertFalse(vm.uiState.value.isAiPanelOpen)

            // 3. TERM_AND_AI split mode does NOT activate bottom sheet isAiPanelOpen
            vm.setSplitMode(SplitMode.TERM_AND_AI)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SplitMode.TERM_AND_AI, vm.uiState.value.splitMode)
            assertFalse(vm.uiState.value.isAiPanelOpen)
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `AI tab and Terminal bottom sheet share exact same conversation state and track unread reply`() {
        val gate = CompletableDeferred<String>()
        val (vm, _, scope) = createViewModel(replyGate = gate)
        val job = scope.launch { vm.uiState.collect {} }
        try {
            shadowOf(Looper.getMainLooper()).idle()

            // Compose and send from AI tab
            vm.selectWorkspaceSection(WorkspaceSection.AI)
            vm.updateAiInput("show hidden files")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("show hidden files", vm.uiState.value.aiInput)

            vm.sendAiMessage()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(vm.uiState.value.isAiLoading)
            assertEquals("", vm.uiState.value.aiInput)

            // User switches back to TERMINAL (sheet closed) BEFORE reply arrives
            vm.selectWorkspaceSection(WorkspaceSection.TERMINAL)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(vm.uiState.value.isAiPanelOpen)
            assertFalse(vm.uiState.value.hasUnreadAiReply)

            // Now stream completes while neither sheet nor AI tab is open -> unread dot lights up
            gate.complete("Use ls -la\n```bash\nls -la\n```")
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(vm.uiState.value.isAiLoading)
            assertTrue(vm.uiState.value.hasUnreadAiReply)

            val sharedMsgsOnTerminal = vm.uiState.value.aiMessages
            assertTrue(sharedMsgsOnTerminal.any { it.role == AiMessageRole.USER })
            assertTrue(sharedMsgsOnTerminal.any { it.suggestions.any { s -> s.command == "ls -la" } })

            // Opening sheet clears unread dot AND preserves the exact same messages
            vm.toggleAiPanel(forceOpen = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(vm.uiState.value.isAiPanelOpen)
            assertFalse(vm.uiState.value.hasUnreadAiReply)
            assertEquals(sharedMsgsOnTerminal, vm.uiState.value.aiMessages)

            // Toggling context in sheet persists when switching to full AI tab
            vm.toggleContextCwd()
            shadowOf(Looper.getMainLooper()).idle()
            val cwdEnabledInSheet = vm.uiState.value.aiContextConfig.includeCwd
            assertFalse(cwdEnabledInSheet)

            vm.openFullAiTab()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(WorkspaceSection.AI, vm.uiState.value.activeSection)
            assertFalse(vm.uiState.value.isAiPanelOpen)
            assertEquals(sharedMsgsOnTerminal, vm.uiState.value.aiMessages)
            assertFalse(vm.uiState.value.aiContextConfig.includeCwd)
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `computeCompactMessages keeps only last user prompt and last reply`() {
        val m0 = AiChatMessage(id = "0", role = AiMessageRole.ASSISTANT, text = UiText.Dynamic("welcome"))
        val u1 = AiChatMessage(id = "1", role = AiMessageRole.USER, text = UiText.Dynamic("q1"))
        val a1 = AiChatMessage(id = "2", role = AiMessageRole.ASSISTANT, text = UiText.Dynamic("a1"))
        val u2 = AiChatMessage(id = "3", role = AiMessageRole.USER, text = UiText.Dynamic("q2"))
        val a2 = AiChatMessage(id = "4", role = AiMessageRole.ASSISTANT, text = UiText.Dynamic("a2"))

        assertEquals(listOf(m0), computeCompactMessages(listOf(m0)))
        assertEquals(listOf(u2, a2), computeCompactMessages(listOf(m0, u1, a1, u2, a2)))
        assertEquals(listOf(u2), computeCompactMessages(listOf(m0, u1, a1, u2)))
    }

    @Test
    fun `both AppThemePresets maintain at least 4_5 to 1 WCAG contrast across surfaces`() {
        fun channelLinear(c: Float): Double =
            if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

        fun luminance(color: Color): Double =
            0.2126 * channelLinear(color.red) +
                0.7152 * channelLinear(color.green) +
                0.0722 * channelLinear(color.blue)

        fun contrastRatio(fg: Color, bg: Color): Double {
            val l1 = luminance(fg)
            val l2 = luminance(bg)
            val lighter = maxOf(l1, l2)
            val darker = minOf(l1, l2)
            return (lighter + 0.05) / (darker + 0.05)
        }

        AppThemePreset.entries.forEach { preset ->
            val t = preset.tokens
            val surfaces = listOf(t.baseCanvas, t.layer1, t.layer2, t.layer3)
            surfaces.forEach { bg ->
                assertTrue(
                    "${preset.name} textPrimary contrast on surface too low: ${contrastRatio(t.textPrimary, bg)}",
                    contrastRatio(t.textPrimary, bg) >= 4.5
                )
                assertTrue(
                    "${preset.name} textSecondary contrast on surface too low: ${contrastRatio(t.textSecondary, bg)}",
                    contrastRatio(t.textSecondary, bg) >= 4.5
                )
                assertTrue(
                    "${preset.name} textMuted contrast on surface too low: ${contrastRatio(t.textMuted, bg)}",
                    contrastRatio(t.textMuted, bg) >= 4.5
                )
            }
            listOf(t.baseCanvas, t.layer1, t.layer2).forEach { bg ->
                assertTrue(
                    "${preset.name} accent contrast on surface too low: ${contrastRatio(t.accent, bg)}",
                    contrastRatio(t.accent, bg) >= 4.5
                )
            }
            assertTrue(
                "${preset.name} onAccent vs accent contrast too low: ${contrastRatio(t.onAccent, t.accent)}",
                contrastRatio(t.onAccent, t.accent) >= 4.5
            )
        }
    }

    @Test
    fun `bilingual strings exist in English and Indonesian for new AI UI controls`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val en = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.ENGLISH)
        val id = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.INDONESIAN)

        assertEquals("Save to Scriptbook", en.getString(R.string.ai_btn_save))
        assertEquals("Simpan ke Buku Skrip", id.getString(R.string.ai_btn_save))
        assertEquals("View full conversation", en.getString(R.string.ai_view_full_conversation))
        assertEquals("Lihat percakapan penuh", id.getString(R.string.ai_view_full_conversation))
        assertEquals("New AI reply available", en.getString(R.string.ai_star_state_unread))
        assertEquals("Ada balasan AI baru", id.getString(R.string.ai_star_state_unread))
    }

    @Test
    fun `ui quick action Explain error is disabled when activeLastError is null and SessionTabsBar shows unread dot`() {
        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.HACKER_PRO) {
                AiQuickActionsBar(
                    hasLastError = false,
                    isLoading = false,
                    hasModelSelected = true,
                    onExplainLastError = {},
                    onSuggestCommand = {},
                    onWriteScript = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_explain_error_chip").assertIsNotEnabled()
        composeRule.onNodeWithTag("ai_suggest_command_chip").assertIsEnabled()
        composeRule.onNodeWithTag("ai_write_script_chip").assertIsEnabled()
    }

    @Test
    fun `ui SessionTabsBar star button displays unread dot and updates stateDescription`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unreadDesc = context.getString(R.string.cd_ai_star_unread)

        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.HACKER_PRO) {
                SessionTabsBar(
                    sessions = emptyList(),
                    activeSessionId = "",
                    aiEnabled = true,
                    isAiPanelOpen = false,
                    hasUnreadAiReply = true,
                    showSplitButton = false,
                    splitMode = SplitMode.SINGLE,
                    onSelectSession = {},
                    onAddSession = {},
                    onCloseSession = {},
                    onRenameSession = { _, _ -> },
                    onToggleAiPanel = {},
                    onToggleSplitMode = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_star_unread_dot", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(unreadDesc).assertIsDisplayed()
    }

    @Test
    fun `ui AiCommandCard shows neutral LOW badge and Save to Snippet action button`() {
        val command = "ls -la"
        val suggestion = CommandSuggestion(
            id = "cmd_1",
            title = UiText.Dynamic("List files"),
            command = command,
            language = "bash",
            risk = CommandRiskClassifier.classify(command)
        )
        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.CYBERPUNK) {
                AiCommandCard(
                    suggestion = suggestion,
                    onInsert = {},
                    onCopy = {},
                    onSaveSnippet = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("risk_badge_low").assertIsDisplayed()
        composeRule.onNodeWithTag("insert_command_button").assertIsEnabled()
        composeRule.onNodeWithTag("copy_command_button").assertIsEnabled()
        composeRule.onNodeWithTag("save_command_button").assertIsEnabled()
        composeRule.onNodeWithText("ls -la").assertIsDisplayed()
    }
}
