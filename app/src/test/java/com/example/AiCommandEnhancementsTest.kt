package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiAssistantRepository
import com.example.ai.AiChatMessage
import com.example.ai.AiMessageRole
import com.example.ai.AiPromptKind
import com.example.ai.CommandRiskLevel
import com.example.ai.CommandSuggestion
import com.example.ai.GeminiCallResult
import com.example.ai.GeminiContent
import com.example.ai.GeminiStreamEvent
import com.example.ai.GeminiTransport
import com.example.ai.KeyHealthStatus
import com.example.ai.KeyManager
import com.example.ai.KeyManagerSnapshot
import com.example.ai.ManagedKeyStatus
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
import com.example.terminal.TerminalErrorContext
import com.example.ui.UiText
import com.example.ui.ai.AiBottomSheetOverlay
import com.example.ui.ai.AiCommandCard
import com.example.ui.ai.AiPromptInput
import com.example.ui.ai.AiQuickActionsBar
import com.example.ui.ai.CommandExplanationBottomSheet
import com.example.ui.terminal.AiContextConfig
import com.example.ui.terminal.CommandExplanationState
import com.example.ui.terminal.SnippetsScreen
import com.example.ui.terminal.TerminalConsoleView
import com.example.ui.terminal.TerminalScreen
import com.example.ui.terminal.TerminalScreenSnapshot
import com.example.ui.terminal.TerminalViewModel
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.CyberorgTheme
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xhdpi")
class AiCommandEnhancementsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class InMemorySettingsStore(
        initialConsentAccepted: Boolean = true,
        initialModel: String? = "gemini-3.8-flash"
    ) : SettingsStore {
        private val secrets = mutableMapOf<String, String>()
        private val state = MutableStateFlow(
            AppSettings(
                aiConsentAccepted = initialConsentAccepted,
                selectedModel = initialModel
            )
        )
        override val settingsFlow: Flow<AppSettings> = state.asStateFlow()
        override val vaultStatus: StateFlow<VaultStatus> =
            MutableStateFlow<VaultStatus>(VaultStatus.Ready).asStateFlow()

        override suspend fun setAppTheme(theme: com.example.ui.theme.AppThemePreset) {}
        override suspend fun setReduceEffects(enabled: Boolean) {}
        override suspend fun setAppLanguage(language: AppLanguage) {}
        override suspend fun setOnboardingCompleted(completed: Boolean) {}
        override suspend fun setFontSizeSp(sizeSp: Float) {}
        override suspend fun setExtraKeysLayout(layout: ExtraKeysLayout) {}
        override suspend fun setAiEnabled(enabled: Boolean) {
            state.update { it.copy(aiEnabled = enabled) }
        }
        override suspend fun setAiConsentAccepted(accepted: Boolean) {
            state.update { it.copy(aiConsentAccepted = accepted) }
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

    private class RecordingGeminiTransport(
        private val replyProvider: (promptText: String) -> String
    ) : GeminiTransport {
        val capturedPrompts = mutableListOf<String>()

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
            val userText = contents.lastOrNull()?.parts?.joinToString("\n") { it.text }.orEmpty()
            capturedPrompts += userText
            emit(GeminiStreamEvent.Chunk(replyProvider(userText)))
            emit(GeminiStreamEvent.Completed("STOP"))
        }
    }

    private fun createHarness(
        consentAccepted: Boolean = true,
        replyProvider: (String) -> String = {
            "Find files larger than 100 MB safely.\n```bash\nfind . -type f -size +100M\n```"
        }
    ): Harness {
        val backend = FakeTerminalBackend()
        val settingsStore = InMemorySettingsStore(initialConsentAccepted = consentAccepted)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch { settingsStore.addApiKey("Primary", "secret-key-1") }
        val keyManager = KeyManager(settingsStore)
        val transport = RecordingGeminiTransport(replyProvider)
        val repo = AiAssistantRepository(
            keyManager = keyManager,
            geminiApiClient = transport,
            settingsStore = settingsStore,
            defaultDispatcher = Dispatchers.Main.immediate
        )
        val snippetStore = object : SnippetStore {
            override val snippetsFlow: Flow<List<CommandSnippet>> = MutableStateFlow(emptyList())
            override suspend fun saveSnippet(
                title: String,
                command: String,
                tags: List<String>,
                existingId: String?
            ) = CommandSnippet(id = existingId ?: "s1", title = title, command = command)
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
        return Harness(vm, backend, transport, settingsStore, repo, scope)
    }

    private data class Harness(
        val vm: TerminalViewModel,
        val backend: FakeTerminalBackend,
        val transport: RecordingGeminiTransport,
        val settingsStore: InMemorySettingsStore,
        val repo: AiAssistantRepository,
        val scope: CoroutineScope
    )

    @Test
    fun `parseResponse handles closed blocks, no blocks, unclosed fences, and per-kind caps`() {
        val h = createHarness()
        try {
            // 1. Valid fenced block
            val (text1, cards1) = h.repo.parseResponse(
                "Lists large files.\n```bash\nfind . -size +100M\n```",
                maxSuggestions = 2
            )
            assertEquals("Lists large files.\n\n\u21B3", (text1 as UiText.Dynamic).value)
            assertEquals(1, cards1.size)
            assertEquals("find . -size +100M", cards1.first().command)

            // 2. Plain text with NO code block -> 0 cards, text preserved
            val plain = "There is no shell command needed for that explanation."
            val (text2, cards2) = h.repo.parseResponse(plain, maxSuggestions = 2)
            assertEquals(plain, (text2 as UiText.Dynamic).value)
            assertTrue(cards2.isEmpty())

            // 3. Unclosed fence -> 0 cards, full text preserved safely
            val unclosed = "Here is partial output:\n```bash\nls -la"
            val (text3, cards3) = h.repo.parseResponse(unclosed, maxSuggestions = 2)
            assertEquals(unclosed, (text3 as UiText.Dynamic).value)
            assertTrue(cards3.isEmpty())

            // 4. Multiple fenced blocks capped at maxSuggestions = 2 and 1, with zero cards for 0
            val multi = """
                First cause check:
                ```bash
                df -h
                ```
                Second option:
                ```bash
                du -sh *
                ```
                Third option:
                ```bash
                rm -rf /tmp/old-cache
                ```
            """.trimIndent()
            val (_, capped2) = h.repo.parseResponse(multi, maxSuggestions = 2)
            assertEquals(2, capped2.size)
            assertEquals("df -h", capped2[0].command)
            assertEquals("du -sh *", capped2[1].command)

            val (rootCauseText, capped1) = h.repo.parseResponse(multi, maxSuggestions = 1)
            assertEquals(1, capped1.size)
            assertEquals("df -h", capped1[0].command)
            assertTrue((rootCauseText as UiText.Dynamic).value.contains("du -sh *"))

            val (_, capped0) = h.repo.parseResponse(multi, maxSuggestions = 0)
            assertTrue(capped0.isEmpty())

            // 5. Comment-only fenced block yields 0 runnable command cards
            val commentOnly = "Note:\n```bash\n# just a comment\n```"
            val (_, commentCards) = h.repo.parseResponse(commentOnly, maxSuggestions = 2)
            assertTrue(commentCards.isEmpty())
        } finally {
            h.scope.cancel()
        }
    }

    @Test
    fun `natural language command includes redacted cwd and shell, respects recent output toggle, and uses risk classifier`() {
        val h = createHarness(
            consentAccepted = true,
            replyProvider = {
                "Removes build artifacts recursively and checks disk space.\n```bash\nrm -rf /tmp/*\n```\n```bash\ndf -h\n```"
            }
        )
        val job = h.scope.launch { h.vm.uiState.collect {} }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val handle = h.backend.sessions.value.first()
            handle.write("echo MY_LINE APIzaSecretAbCdEf123456789012345678901\n".encodeToByteArray())
            shadowOf(Looper.getMainLooper()).idle()

            // Turn off recent output context toggle and include secret in user prompt
            h.vm.toggleContextRecentOutput()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(h.vm.uiState.value.aiContextConfig.includeRecentOutput)

            h.vm.requestNaturalLanguageCommand("clean files using AIzaSyTestSecretKeyValue123456789012345")
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(1, h.transport.capturedPrompts.size)
            val sentPrompt = h.transport.capturedPrompts.first()
            assertTrue(sentPrompt.contains("Current working directory:"))
            assertTrue(sentPrompt.contains("Shell:"))
            assertFalse("Raw API key leaked", sentPrompt.contains("AIzaSyTestSecretKeyValue123456789012345"))
            assertTrue(sentPrompt.contains("[REDACTED]"))
            assertFalse("Terminal output leaked while toggle off", sentPrompt.contains("Recent terminal output:"))

            val assistantMsg = h.vm.uiState.value.aiMessages.last { it.role == AiMessageRole.ASSISTANT }
            assertEquals(2, assistantMsg.suggestions.size)
            assertEquals(CommandRiskLevel.HIGH, assistantMsg.suggestions[0].risk.level)
            assertEquals(CommandRiskLevel.LOW, assistantMsg.suggestions[1].risk.level)

            // Inserting a suggestion strips trailing newline (draft only) and never auto-executes
            val linesBeforeInsert = h.backend.sessions.value.first().readRecentText(50).lines().size
            h.vm.insertSuggestedCommand("df -h\n") {}
            shadowOf(Looper.getMainLooper()).idle()
            val afterDraftText = h.backend.sessions.value.first().readRecentText(50)
            assertTrue(afterDraftText.contains("df -h"))
            assertEquals(linesBeforeInsert, afterDraftText.lines().size)
            assertEquals(
                UiText.Resource(R.string.status_inserted_draft),
                h.vm.uiState.value.statusBannerMessage
            )
        } finally {
            job.cancel()
            h.scope.cancel()
        }
    }

    @Test
    fun `consent gate blocks all four new features until accepted, then resumes cleanly`() {
        val h = createHarness(
            consentAccepted = false,
            replyProvider = { "Explanation bullet\n- Parses files" }
        )
        val job = h.scope.launch { h.vm.uiState.collect {} }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val handle = h.backend.sessions.value.first()
            handle.write("sample log line\n".encodeToByteArray())
            shadowOf(Looper.getMainLooper()).idle()

            // Write command blocked before consent
            h.vm.requestNaturalLanguageCommand("find large files")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(h.vm.uiState.value.isAiConsentDialogVisible)
            assertEquals(0, h.transport.capturedPrompts.size)
            h.vm.dismissAiConsentDialog()
            shadowOf(Looper.getMainLooper()).idle()

            // Summarize blocked before consent
            h.vm.summarizeTerminalOutput("sample selection output")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(h.vm.uiState.value.isAiConsentDialogVisible)
            assertEquals(0, h.transport.capturedPrompts.size)
            h.vm.dismissAiConsentDialog()
            shadowOf(Looper.getMainLooper()).idle()

            // Find root cause blocked before consent
            h.vm.findRootCauseInOutput("FATAL error trace")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(h.vm.uiState.value.isAiConsentDialogVisible)
            assertEquals(0, h.transport.capturedPrompts.size)
            h.vm.dismissAiConsentDialog()
            shadowOf(Looper.getMainLooper()).idle()

            // Explain command blocked before consent, then executes after acceptance
            h.vm.explainCommand("ls -la /tmp")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(h.vm.uiState.value.isAiConsentDialogVisible)
            assertEquals(0, h.transport.capturedPrompts.size)

            h.vm.acceptAiConsentAndContinue()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(h.vm.uiState.value.isAiConsentDialogVisible)
            assertEquals(1, h.transport.capturedPrompts.size)
            assertTrue(h.vm.uiState.value.commandExplanation.visible)
        } finally {
            job.cancel()
            h.scope.cancel()
        }
    }

    @Test
    fun `explainCommand uses sheet state without appending chat messages and redacts command`() {
        val h = createHarness(
            consentAccepted = true,
            replyProvider = { "- curl sends HTTP request\n- Authorization header omitted" }
        )
        val job = h.scope.launch { h.vm.uiState.collect {} }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val initialMsgCount = h.vm.uiState.value.aiMessages.size

            h.vm.explainCommand("curl -H 'Authorization: Bearer secret_token_123456' https://example.com")
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(initialMsgCount, h.vm.uiState.value.aiMessages.size)
            assertEquals(1, h.transport.capturedPrompts.size)
            assertFalse(h.transport.capturedPrompts.first().contains("secret_token_123456"))
            assertTrue(h.transport.capturedPrompts.first().contains("[REDACTED]"))

            val explain = h.vm.uiState.value.commandExplanation
            assertTrue(explain.visible)
            assertFalse(explain.isLoading)
            assertFalse(explain.command.contains("secret_token_123456"))
            assertTrue((explain.explanationText as UiText.Dynamic).value.contains("curl sends HTTP request"))

            h.vm.dismissCommandExplanation()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(h.vm.uiState.value.commandExplanation.visible)
        } finally {
            job.cancel()
            h.scope.cancel()
        }
    }

    @Test
    fun `summarize and root cause skip empty output without API call and cap root cause cards at one`() {
        val h = createHarness(
            consentAccepted = true,
            replyProvider = {
                """
                Likely cause:
                1. Port 8080 already in use.
                ```bash
                lsof -i :8080
                ```
                ```bash
                kill -9 9999
                ```
                """.trimIndent()
            }
        )
        val job = h.scope.launch { h.vm.uiState.collect {} }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            // Close active session so terminal has empty output -> 0 API calls + banner message
            val handle = h.backend.sessions.value.first()
            h.vm.closeSession(handle.id)
            shadowOf(Looper.getMainLooper()).idle()

            // Empty output -> 0 API calls + banner message
            h.vm.summarizeTerminalOutput()
            h.vm.findRootCauseInOutput("   \n  ")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, h.transport.capturedPrompts.size)
            assertNotNull(h.vm.uiState.value.statusBannerMessage)
            h.vm.clearStatusBanner()

            // With selection -> uses selection & redacts secrets
            h.vm.findRootCauseInOutput("Error: bind EADDRINUSE Bearer my_top_secret_tok_987654")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, h.transport.capturedPrompts.size)
            val sent = h.transport.capturedPrompts.first()
            assertTrue(sent.contains("EADDRINUSE"))
            assertFalse(sent.contains("my_top_secret_tok_987654"))
            assertTrue(sent.contains("[REDACTED]"))

            val assistantMsg = h.vm.uiState.value.aiMessages.last { it.role == AiMessageRole.ASSISTANT }
            assertEquals("Root cause must cap suggestions at 1", 1, assistantMsg.suggestions.size)
            assertEquals("lsof -i :8080", assistantMsg.suggestions.first().command)
        } finally {
            job.cancel()
            h.scope.cancel()
        }
    }

    @Test
    fun `ui components render new chips, explain buttons with 48dp touch targets, and explanation sheet`() {
        val localizedContext = LocaleHelper.createLocalizedContext(
            ApplicationProvider.getApplicationContext<Context>(),
            AppLanguage.INDONESIAN
        )
        var explainClickedCmd: String? = null
        var dismissedSheet = false
        var writeChipClicked = false
        val activeStep = androidx.compose.runtime.mutableIntStateOf(0)
        val showExplainSheet = androidx.compose.runtime.mutableStateOf(false)

        val suggestion = CommandSuggestion(
            id = "cmd1",
            title = UiText.Dynamic("List files"),
            command = "ls -lah"
        )
        val snippet = CommandSnippet(
            id = "snip_1",
            title = "Disk usage",
            command = "du -sh *"
        )

        composeRule.setContent {
            val existingRegistryOwner = androidx.activity.compose.LocalActivityResultRegistryOwner.current
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides existingRegistryOwner!!
            ) {
                CyberorgTheme {
                    if (activeStep.intValue == 0) {
                        androidx.compose.foundation.layout.Column {
                            AiPromptInput(
                                input = "",
                                isLoading = false,
                                hasModelSelected = true,
                                onInputChange = {},
                                onSend = {},
                                onStopGeneration = {},
                                isWriteCommandMode = true
                            )
                            AiQuickActionsBar(
                                hasLastError = true,
                                isLoading = false,
                                hasModelSelected = true,
                                isWriteCommandMode = true,
                                onExplainLastError = {},
                                onSuggestCommand = {},
                                onWriteScript = {},
                                onWriteCommand = { writeChipClicked = true },
                                onSummarizeOutput = {},
                                onFindRootCause = {}
                            )
                            AiCommandCard(
                                suggestion = suggestion,
                                onInsert = {},
                                onCopy = {},
                                onSaveSnippet = {},
                                onExplain = {
                                    explainClickedCmd = it
                                    showExplainSheet.value = true
                                }
                            )
                        }
                        CommandExplanationBottomSheet(
                            state = CommandExplanationState(
                                visible = showExplainSheet.value,
                                command = "ls -lah",
                                explanationText = UiText.Dynamic("- ls: daftar isi direktori"),
                                isLoading = false
                            ),
                            onDismiss = {
                                dismissedSheet = true
                                showExplainSheet.value = false
                            },
                            onRetry = {}
                        )
                    } else {
                        SnippetsScreen(
                            snippets = listOf(snippet),
                            onInsertSnippet = {},
                            onCopySnippet = {},
                            onExplainSnippet = { explainClickedCmd = it },
                            onSaveSnippet = { _, _, _, _ -> },
                            onDeleteSnippet = {},
                            onBuildExportJson = { "[]" },
                            onExportSuccess = {},
                            onImportJson = { Result.success(0) },
                            onIoError = {}
                        )
                    }
                }
            }
        }

        // Step 0: single Write command chip, card explain button (48dp touch target), and explanation sheet
        composeRule.onAllNodesWithText("Tulis perintah").assertCountEquals(1)
        composeRule.onNodeWithTag("ai_write_command_chip").performClick()
        assertTrue(writeChipClicked)

        composeRule.onNodeWithTag("ai_write_command_chip").assertExists()
        composeRule.onNodeWithTag("ai_summarize_output_chip").assertExists()
        composeRule.onNodeWithTag("ai_root_cause_chip").assertExists()

        val cardExplain = composeRule.onNodeWithTag("explain_command_button")
        cardExplain.assertExists()
        with(composeRule.density) {
            val touchHeightDp = cardExplain.fetchSemanticsNode().touchBoundsInRoot.height.toDp()
            assertTrue("Expected >= 48.dp touch target, got $touchHeightDp", touchHeightDp >= 47.5f.dp)
        }
        cardExplain.performClick()
        assertEquals("ls -lah", explainClickedCmd)

        // Explanation sheet displays command, bullet text, and closes on click
        composeRule.onNodeWithText("- ls: daftar isi direktori").assertIsDisplayed()
        composeRule.onNodeWithTag("close_command_explanation_button").performClick()
        assertTrue(dismissedSheet)

        // Step 1: SnippetsScreen explain button with >= 48dp touch target
        composeRule.runOnUiThread { activeStep.intValue = 1 }
        composeRule.waitForIdle()
        val snippetExplain = composeRule.onNodeWithTag("snippet_explain_snip_1")
        snippetExplain.assertExists()
        with(composeRule.density) {
            val touchHeightDp = snippetExplain.fetchSemanticsNode().touchBoundsInRoot.height.toDp()
            assertTrue("Expected >= 48.dp touch target, got $touchHeightDp", touchHeightDp >= 47.5f.dp)
        }
        snippetExplain.performClick()
        assertEquals("du -sh *", explainClickedCmd)
    }

    @Test
    fun `error chip opens AI panel, clears terminal focus, hides keyboard, and dismiss does not reopen keyboard`() {
        val localizedContext = LocaleHelper.createLocalizedContext(
            ApplicationProvider.getApplicationContext<Context>(),
            AppLanguage.INDONESIAN
        )
        var showCalls = 0
        var hideCalls = 0
        val fakeKeyboard = object : SoftwareKeyboardController {
            override fun show() {
                showCalls++
            }

            override fun hide() {
                hideCalls++
            }
        }
        val sheetVisible = androidx.compose.runtime.mutableStateOf(false)
        val backend = FakeTerminalBackend()
        val session = backend.sessions.value.first()
        val screenState = MutableStateFlow(
            TerminalScreenSnapshot(version = 1L, buffer = session.readScreen())
        )
        val errCtx = TerminalErrorContext(
            sessionId = "s1",
            command = "badcmd",
            exitCode = 127,
            errorOutput = "badcmd: command not found",
            cwd = FakeTerminalBackend.HOME_DIR
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalSoftwareKeyboardController provides fakeKeyboard
            ) {
                CyberorgTheme {
                    Box(modifier = Modifier.width(360.dp).height(760.dp)) {
                        TerminalConsoleView(
                            sessionTitle = "main",
                            cwd = FakeTerminalBackend.HOME_DIR,
                            lastExitCode = 127,
                            lastError = errCtx,
                            screenFlow = screenState,
                            ctrlArmed = false,
                            altArmed = false,
                            fontSizeSp = 12f,
                            colorScheme = AppThemePreset.HACKER_PRO.terminalScheme,
                            aiEnabled = true,
                            isFocusedPane = true,
                            isAiPanelOpen = sheetVisible.value,
                            onResize = { _, _ -> },
                            onImeTextInput = {},
                            onImeBackspace = {},
                            onImeEnter = {},
                            onSendRawBytes = {},
                            onClearOutput = {},
                            onExplainLastError = { sheetVisible.value = true },
                            onFixLastError = { sheetVisible.value = true }
                        )
                        AiBottomSheetOverlay(
                            visible = sheetVisible.value,
                            messages = emptyList(),
                            input = "",
                            isLoading = false,
                            contextConfig = AiContextConfig(),
                            activeCwd = FakeTerminalBackend.HOME_DIR,
                            activeLastError = errCtx,
                            selectedModel = "gemini-3.8-flash",
                            keySnapshot = KeyManagerSnapshot(
                                activeKeyId = "k1",
                                activeKeyLabel = "Primary",
                                keys = emptyList()
                            ),
                            onSelectModel = {},
                            onInputChange = {},
                            onSend = {},
                            onStopGeneration = {},
                            onRetryMessage = {},
                            onExplainLastError = {},
                            onSuggestCommand = {},
                            onWriteScript = {},
                            onToggleContextCwd = {},
                            onToggleContextLastError = {},
                            onToggleContextSelectedText = {},
                            onClearSelectedTextContext = {},
                            onToggleContextRecentOutput = {},
                            onInsertCommand = {},
                            onCopyCommand = {},
                            onSaveSnippet = { _, _, _ -> },
                            onOpenSettings = {},
                            onViewFullConversation = {},
                            onDismiss = { sheetVisible.value = false },
                            fullScreenHeight = 760.dp
                        )
                    }
                }
            }
        }

        // Focus terminal first and open keyboard explicitly
        composeRule.onNodeWithTag("focus_keyboard_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_ime_input").assertIsFocused()
        assertEquals(1, showCalls)

        val hidesBeforeErrorTap = hideCalls
        // Open AI sheet via error chip -> releases terminal focus and hides keyboard
        composeRule.onNodeWithTag("terminal_chip_explain").performClick()
        composeRule.waitForIdle()
        assertTrue(sheetVisible.value)
        composeRule.onNodeWithTag("terminal_ime_input").assertIsNotFocused()
        assertTrue(hideCalls > hidesBeforeErrorTap)
        assertEquals(1, showCalls)

        // Dismiss sheet via scrim -> does NOT re-focus terminal or show keyboard
        composeRule.onNodeWithTag("ai_bottom_sheet_scrim").performClick()
        composeRule.waitForIdle()
        assertFalse(sheetVisible.value)
        composeRule.onNodeWithTag("terminal_ime_input").assertIsNotFocused()
        assertEquals(1, showCalls)

        // First tap on terminal canvas after dismiss immediately focuses terminal and opens keyboard
        composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput { click(center) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_ime_input").assertIsFocused()
        assertEquals(2, showCalls)
    }

    @Test
    fun `ai sheet at 360dp width renders single-line header, last error card, single Tulis perintah chip, and zero overlap in both themes`() {
        val localizedContext = LocaleHelper.createLocalizedContext(
            ApplicationProvider.getApplicationContext<Context>(),
            AppLanguage.INDONESIAN
        )
        val messages = listOf(
            AiChatMessage(role = AiMessageRole.USER, text = UiText.Dynamic("Ringkas log build")),
            AiChatMessage(role = AiMessageRole.ASSISTANT, text = UiText.Dynamic("Semua tugas berhasil dikompilasi."))
        )
        var activeTheme by mutableStateOf(AppThemePreset.HACKER_PRO)
        var closeCount = 0
        var fullConvCount = 0
        var explainErrCount = 0
        var fixErrCount = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext) {
                CyberorgTheme(appTheme = activeTheme) {
                    // Simulate container shrunk to 460dp (by IME) while full screen height is 780dp
                    Box(modifier = Modifier.width(360.dp).height(460.dp)) {
                        AiBottomSheetOverlay(
                            visible = true,
                            messages = messages,
                            input = "",
                            isLoading = false,
                            isWriteCommandMode = true,
                            contextConfig = AiContextConfig(),
                            activeCwd = FakeTerminalBackend.HOME_DIR,
                            activeLastError = TerminalErrorContext("s1", "ls", 1, "ls: missing file", FakeTerminalBackend.HOME_DIR),
                            selectedModel = "gemini-3.8-flash",
                            keySnapshot = KeyManagerSnapshot(
                                activeKeyId = "k1",
                                activeKeyLabel = "Primary",
                                usableKeyCount = 1,
                                keys = listOf(
                                    ManagedKeyStatus(
                                        metadata = ApiKeyMetadata("k1", "Primary", "AIza••••"),
                                        status = KeyHealthStatus.ACTIVE
                                    )
                                )
                            ),
                            onSelectModel = {},
                            onInputChange = {},
                            onSend = {},
                            onStopGeneration = {},
                            onRetryMessage = {},
                            onExplainLastError = { explainErrCount++ },
                            onFixLastError = { fixErrCount++ },
                            onSuggestCommand = {},
                            onWriteScript = {},
                            onToggleContextCwd = {},
                            onToggleContextLastError = {},
                            onToggleContextSelectedText = {},
                            onClearSelectedTextContext = {},
                            onToggleContextRecentOutput = {},
                            onInsertCommand = {},
                            onCopyCommand = {},
                            onSaveSnippet = { _, _, _ -> },
                            onOpenSettings = {},
                            onViewFullConversation = { fullConvCount++ },
                            onDismiss = { closeCount++ },
                            fullScreenHeight = 780.dp
                        )
                    }
                }
            }
        }

        val containerRightPx = with(composeRule.density) { 360.dp.toPx() }
        AppThemePreset.entries.forEachIndexed { idx, theme ->
            composeRule.runOnUiThread { activeTheme = theme }
            composeRule.waitForIdle()

            // Legacy header chips/buttons must NOT exist
            composeRule.onNodeWithTag("ai_select_model_button").assertDoesNotExist()
            composeRule.onNodeWithTag("ai_key_status_chip").assertDoesNotExist()
            composeRule.onNodeWithTag("ai_select_key_button").assertDoesNotExist()

            // Conversation state must NOT show empty state suggestion card
            composeRule.onNodeWithTag("ai_empty_state").assertDoesNotExist()

            // Only ONE "Tulis perintah" chip in the entire sheet
            composeRule.onAllNodesWithText("Tulis perintah").assertCountEquals(1)

            // Single-line header: dot + model name on left; expand + full-view + close icons on right
            composeRule.onNodeWithTag("ai_key_status_dot", useUnmergedTree = true).assertIsDisplayed()
            val handleBounds = composeRule.onNodeWithTag("ai_bottom_sheet_handle").fetchSemanticsNode().boundsInRoot
            val modelNameNode = composeRule.onNodeWithTag("ai_header_model_name", useUnmergedTree = true).fetchSemanticsNode()
            val expandBtnNode = composeRule.onNodeWithTag("ai_sheet_expand_button").fetchSemanticsNode()
            val fullConvBtnNode = composeRule.onNodeWithTag("ai_view_full_conversation_button").fetchSemanticsNode()
            val closeBtnNode = composeRule.onNodeWithTag("ai_sheet_close_button").fetchSemanticsNode()

            val layoutResults = mutableListOf<TextLayoutResult>()
            modelNameNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layoutResults)
            assertTrue("Expected text layout result on model name", layoutResults.isNotEmpty())
            val lr = layoutResults.first()
            assertFalse(
                "${theme.name} header model name clipped/overflowed at 360dp (size=${lr.size}, maxWidth=${lr.layoutInput.constraints.maxWidth})",
                lr.hasVisualOverflow
            )

            assertTrue("Model name overlaps expand button", modelNameNode.boundsInRoot.right <= expandBtnNode.boundsInRoot.left + 1f)
            assertTrue("Expand button overlaps full view icon", expandBtnNode.boundsInRoot.right <= fullConvBtnNode.boundsInRoot.left + 1f)
            assertTrue("Full view icon overlaps close icon", fullConvBtnNode.boundsInRoot.right <= closeBtnNode.boundsInRoot.left + 1f)
            assertTrue("Close icon clipped 360dp", closeBtnNode.boundsInRoot.right <= containerRightPx + 1f)

            listOf(expandBtnNode, fullConvBtnNode, closeBtnNode).forEach { node ->
                with(composeRule.density) {
                    assertTrue(
                        "Header button touch width >= 48dp, got ${node.touchBoundsInRoot.width.toDp()}",
                        node.touchBoundsInRoot.width.toDp() >= 47.5f.dp
                    )
                    assertTrue(
                        "Header button touch height >= 48dp, got ${node.touchBoundsInRoot.height.toDp()}",
                        node.touchBoundsInRoot.height.toDp() >= 47.5f.dp
                    )
                }
            }

            // All chips remain cleanly within 360dp bounds without horizontal overflow
            listOf(
                "ai_write_command_chip",
                "ai_summarize_output_chip",
                "ai_root_cause_chip",
                "ai_explain_error_chip",
                "ai_suggest_command_chip",
                "ai_write_script_chip"
            ).forEach { tag ->
                val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue("Chip $tag overflowed width 360dp: right=${bounds.right} > $containerRightPx", bounds.right <= containerRightPx + 1f)
                assertTrue("Chip $tag width must be positive", bounds.width > 0f)
            }

            // Collapsed height computed from fullScreenHeight (780 * 0.45 = 351dp), NOT post-IME 460 * 0.45 = 207dp
            val sheetBounds = composeRule.onNodeWithTag("ai_bottom_sheet").fetchSemanticsNode().boundsInRoot
            with(composeRule.density) {
                assertTrue(
                    "Sheet collapsed height should use fullScreenHeight (~351dp), got ${sheetBounds.height.toDp()}",
                    sheetBounds.height.toDp() >= 340.dp
                )
            }

            // Strict non-overlapping order: handle -> header -> message list -> chips -> input
            val header = composeRule.onNodeWithTag("ai_sheet_header").fetchSemanticsNode().boundsInRoot
            val msgList = composeRule.onNodeWithTag("ai_sheet_message_list").fetchSemanticsNode().boundsInRoot
            val chips = composeRule.onNodeWithTag("ai_quick_actions_bar").fetchSemanticsNode().boundsInRoot
            val inputContainer = composeRule.onNodeWithTag("ai_sheet_prompt_input_container").fetchSemanticsNode().boundsInRoot
            val sendBtn = composeRule.onNodeWithTag("send_ai_prompt_button").fetchSemanticsNode().boundsInRoot

            assertTrue("Handle overlaps header", handleBounds.bottom <= header.top + 1f)
            assertTrue("Header overlaps message list", header.bottom <= msgList.top + 1f)
            assertTrue("Message list collapsed unexpectedly", with(composeRule.density) { msgList.height.toDp() >= 48.dp })
            assertTrue("Message list overlaps chips", msgList.bottom <= chips.top + 1f)
            assertTrue("Chips overlap input", chips.bottom <= inputContainer.top + 1f)
            assertTrue("Input clipped past sheet bottom", inputContainer.bottom <= sheetBounds.bottom + 1f)
            assertTrue("Send button overflowed 360dp", sendBtn.right <= containerRightPx + 1f)

            composeRule.onNodeWithTag("ai_view_full_conversation_button").performClick()
            composeRule.waitForIdle()
            assertEquals(idx + 1, fullConvCount)

            composeRule.onNodeWithTag("ai_sheet_close_button").performClick()
            composeRule.waitForIdle()
            assertEquals(idx + 1, closeCount)
        }

        // Last error card displays summary, Explain & Fix actions, and disappears when dismissed
        composeRule.onNodeWithTag("ai_last_error_card").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_last_error_summary", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("ai_error_card_explain_button").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_error_card_fix_button").assertIsDisplayed()

        // Clicking model name in header opens the existing quick settings / model picker sheet
        composeRule.onNodeWithTag("ai_header_model_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_settings_model_gemini-3.8-flash").assertIsDisplayed()
        composeRule.onNodeWithTag("quick_settings_model_gemini-3.8-flash").performClick()
    }

    @Test
    fun `ai sheet no-key state at 360dp shows centered add key card, disables input and chips, and keeps single-line header in both themes`() {
        val localizedContext = LocaleHelper.createLocalizedContext(
            ApplicationProvider.getApplicationContext<Context>(),
            AppLanguage.INDONESIAN
        )
        var activeTheme by mutableStateOf(AppThemePreset.HACKER_PRO)
        var openedKeysSettings = 0
        var openedFullView = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext) {
                CyberorgTheme(appTheme = activeTheme) {
                    Box(modifier = Modifier.width(360.dp).height(760.dp)) {
                        AiBottomSheetOverlay(
                            visible = true,
                            messages = emptyList(),
                            input = "",
                            isLoading = false,
                            isWriteCommandMode = false,
                            contextConfig = AiContextConfig(),
                            activeCwd = FakeTerminalBackend.HOME_DIR,
                            activeLastError = TerminalErrorContext("s1", "ls", 1, "err", FakeTerminalBackend.HOME_DIR),
                            selectedModel = "gemini-3.8-flash",
                            keySnapshot = KeyManagerSnapshot(
                                activeKeyId = null,
                                activeKeyLabel = null,
                                keys = emptyList()
                            ),
                            onSelectModel = {},
                            onInputChange = {},
                            onSend = {},
                            onStopGeneration = {},
                            onRetryMessage = {},
                            onExplainLastError = {},
                            onFixLastError = {},
                            onSuggestCommand = {},
                            onWriteScript = {},
                            onToggleContextCwd = {},
                            onToggleContextLastError = {},
                            onToggleContextSelectedText = {},
                            onClearSelectedTextContext = {},
                            onToggleContextRecentOutput = {},
                            onInsertCommand = {},
                            onCopyCommand = {},
                            onSaveSnippet = { _, _, _ -> },
                            onOpenSettings = {},
                            onOpenApiKeysSettings = { openedKeysSettings++ },
                            onViewFullConversation = { openedFullView++ },
                            onDismiss = {},
                            fullScreenHeight = 760.dp
                        )
                    }
                }
            }
        }

        AppThemePreset.entries.forEachIndexed { idx, theme ->
            composeRule.runOnUiThread { activeTheme = theme }
            composeRule.waitForIdle()

            // Header remains compact and single-line with red/dim dot + expand/full/close icons
            composeRule.onNodeWithTag("ai_select_key_button").assertDoesNotExist()
            composeRule.onNodeWithTag("ai_key_status_dot", useUnmergedTree = true).assertIsDisplayed()
            val containerRightPx = with(composeRule.density) { 360.dp.toPx() }
            val modelNameNode = composeRule.onNodeWithTag("ai_header_model_name", useUnmergedTree = true).fetchSemanticsNode()
            val expandBtnBounds = composeRule.onNodeWithTag("ai_sheet_expand_button").fetchSemanticsNode().boundsInRoot
            val fullConvBtnBounds = composeRule.onNodeWithTag("ai_view_full_conversation_button").fetchSemanticsNode().boundsInRoot
            val closeBtnBounds = composeRule.onNodeWithTag("ai_sheet_close_button").fetchSemanticsNode().boundsInRoot

            val layoutResults = mutableListOf<TextLayoutResult>()
            modelNameNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layoutResults)
            val lr = layoutResults.first()
            assertFalse(
                "${theme.name} header model name overflowed at 360dp (size=${lr.size}, maxWidth=${lr.layoutInput.constraints.maxWidth})",
                lr.hasVisualOverflow
            )
            assertTrue("${theme.name} model overlaps expand button", modelNameNode.boundsInRoot.right <= expandBtnBounds.left + 1f)
            assertTrue("${theme.name} expand overlaps full view", expandBtnBounds.right <= fullConvBtnBounds.left + 1f)
            assertTrue("${theme.name} full view overlaps close", fullConvBtnBounds.right <= closeBtnBounds.left + 1f)
            assertTrue("${theme.name} close icon clipped", closeBtnBounds.right <= containerRightPx + 1f)

            // Centered no-key card with "Belum ada kunci API" + "Tambah kunci" button opening Settings > API Keys
            composeRule.onNodeWithTag("ai_no_key_card").assertIsDisplayed()
            composeRule.onNodeWithText("Belum ada kunci API", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("ai_add_key_button").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            assertEquals(idx + 1, openedKeysSettings)

            // Empty suggestions and error card must NOT be shown when no key is active
            composeRule.onNodeWithTag("ai_empty_state").assertDoesNotExist()
            composeRule.onNodeWithTag("ai_last_error_card").assertDoesNotExist()

            // Quick action chips and prompt input are disabled when no active key exists
            composeRule.onNodeWithTag("ai_write_command_chip").assertIsNotEnabled()
            composeRule.onNodeWithTag("ai_summarize_output_chip").assertIsNotEnabled()
            composeRule.onNodeWithTag("ai_explain_error_chip").assertIsNotEnabled()
            composeRule.onNodeWithTag("ai_prompt_input").assertIsNotEnabled()
            composeRule.onNodeWithTag("send_ai_prompt_button").assertIsNotEnabled()

            composeRule.onNodeWithTag("ai_view_full_conversation_button").performClick()
            composeRule.waitForIdle()
            assertEquals(idx + 1, openedFullView)

            // Send button matches input row height and placeholder stays inside 360dp
            val sendBtn = composeRule.onNodeWithTag("send_ai_prompt_button").fetchSemanticsNode().boundsInRoot
            val placeholderBounds = composeRule.onNodeWithTag("ai_prompt_placeholder", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("${theme.name} send button must satisfy min touch target", with(composeRule.density) { sendBtn.height.toDp() >= 47.dp })
            assertTrue("${theme.name} placeholder overlaps send button", placeholderBounds.right <= sendBtn.left + 1f)
        }
    }

    @Test
    fun `ai sheet empty state shows conditional error suggestion, invokes chip actions, and hides error card on dismiss or analyze`() {
        val localizedContext = LocaleHelper.createLocalizedContext(
            ApplicationProvider.getApplicationContext<Context>(),
            AppLanguage.INDONESIAN
        )
        var lastError by mutableStateOf<TerminalErrorContext?>(null)
        var explainCalls = 0
        var fixCalls = 0
        var writeCmdCalls = 0
        var summarizeCalls = 0

        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext) {
                CyberorgTheme {
                    Box(modifier = Modifier.width(360.dp).height(760.dp)) {
                        AiBottomSheetOverlay(
                            visible = true,
                            messages = emptyList(),
                            input = "",
                            isLoading = false,
                            contextConfig = AiContextConfig(),
                            activeCwd = FakeTerminalBackend.HOME_DIR,
                            activeLastError = lastError,
                            selectedModel = "gemini-3.8-flash",
                            keySnapshot = KeyManagerSnapshot(
                                activeKeyId = "k1",
                                activeKeyLabel = "Primary",
                                usableKeyCount = 1,
                                keys = listOf(
                                    ManagedKeyStatus(
                                        metadata = ApiKeyMetadata("k1", "Primary", "AIza••••"),
                                        status = KeyHealthStatus.ACTIVE
                                    )
                                )
                            ),
                            onSelectModel = {},
                            onInputChange = {},
                            onSend = {},
                            onStopGeneration = {},
                            onRetryMessage = {},
                            onExplainLastError = { explainCalls++ },
                            onFixLastError = { fixCalls++ },
                            onSuggestCommand = {},
                            onWriteScript = {},
                            onWriteCommand = { writeCmdCalls++ },
                            onSummarizeOutput = { summarizeCalls++ },
                            onToggleContextCwd = {},
                            onToggleContextLastError = {},
                            onToggleContextSelectedText = {},
                            onClearSelectedTextContext = {},
                            onToggleContextRecentOutput = {},
                            onInsertCommand = {},
                            onCopyCommand = {},
                            onSaveSnippet = { _, _, _ -> },
                            onOpenSettings = {},
                            onViewFullConversation = {},
                            onDismiss = {},
                            fullScreenHeight = 760.dp
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Without error: "Jelaskan galat terakhir" is hidden; Write command & Summarize output trigger chip actions
        composeRule.onNodeWithTag("ai_empty_state").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_empty_suggestion_explain_error").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_last_error_card").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_empty_suggestion_write_command").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("ai_empty_suggestion_summarize").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, writeCmdCalls)
        assertEquals(1, summarizeCalls)

        // With error #1: "Jelaskan galat terakhir" appears and error card renders; dismissing hides error card
        composeRule.runOnUiThread {
            lastError = TerminalErrorContext("s1", "badcmd", 127, "badcmd: command not found", FakeTerminalBackend.HOME_DIR)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_last_error_card").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_empty_suggestion_explain_error").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_error_card_dismiss_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_last_error_card").assertDoesNotExist()

        // With new error #2: error card reappears and clicking "Perbaiki" runs fix action and hides card
        composeRule.runOnUiThread {
            lastError = TerminalErrorContext("s1", "make build", 2, "Compilation failed", FakeTerminalBackend.HOME_DIR)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_last_error_card").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_error_card_fix_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, fixCalls)
        composeRule.onNodeWithTag("ai_last_error_card").assertDoesNotExist()

        // With new error #3: clicking "Jelaskan galat terakhir" runs explain action and hides error card
        composeRule.runOnUiThread {
            lastError = TerminalErrorContext("s1", "pytest", 1, "AssertionError", FakeTerminalBackend.HOME_DIR)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_last_error_card").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_empty_suggestion_explain_error").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, explainCalls)
        composeRule.onNodeWithTag("ai_last_error_card").assertDoesNotExist()
    }

    @Test
    fun `TerminalScreen hides ExtraKeysBar when AI bottom sheet is open and restores it on close without pushing terminal`() {
        val h = createHarness()
        val scopeJob = h.scope.launch { h.vm.uiState.collect {} }
        try {
            composeRule.setContent {
                CyberorgTheme {
                    Box(modifier = Modifier.width(360.dp).height(760.dp)) {
                        TerminalScreen(viewModel = h.vm, onNavigateToSettings = {})
                    }
                }
            }
            composeRule.waitForIdle()

            // Before opening AI sheet: ExtraKeysBar is visible and Terminal grid is displayed
            composeRule.onNodeWithTag("extra_keys_bar").assertIsDisplayed()
            val gridBefore = composeRule.onNodeWithTag("terminal_cell_grid").fetchSemanticsNode().boundsInRoot
            assertTrue(gridBefore.top >= 0f)

            // Open AI sheet
            composeRule.runOnUiThread { h.vm.toggleAiPanel(forceOpen = true) }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("ai_bottom_sheet").assertIsDisplayed()
            composeRule.onNodeWithTag("extra_keys_bar").assertDoesNotExist()
            val gridDuringSheet = composeRule.onNodeWithTag("terminal_cell_grid").fetchSemanticsNode().boundsInRoot
            assertEquals("Terminal top must not shift when AI sheet opens", gridBefore.top, gridDuringSheet.top, 2f)
            assertTrue("Terminal height must not shrink or be pushed off screen", gridDuringSheet.height >= gridBefore.height - 2f)

            // Close AI sheet via header close button -> ExtraKeysBar is restored
            composeRule.onNodeWithTag("ai_sheet_close_button").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("ai_bottom_sheet").assertDoesNotExist()
            composeRule.onNodeWithTag("extra_keys_bar").assertIsDisplayed()
        } finally {
            scopeJob.cancel()
            h.scope.cancel()
        }
    }

    @Test
    fun `values and values-in strings xml keep identical keys for new AI strings`() {
        fun locate(rel: String): File {
            val f1 = File(rel)
            return if (f1.exists()) f1 else File("app/$rel")
        }
        fun keys(file: File): Set<String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            val set = linkedSetOf<String>()
            for (i in 0 until nodes.length) {
                nodes.item(i).attributes?.getNamedItem("name")?.nodeValue?.let { set += it }
            }
            return set
        }
        val enKeys = keys(locate("src/main/res/values/strings.xml"))
        val idKeys = keys(locate("src/main/res/values-in/strings.xml"))
        assertEquals(enKeys, idKeys)
        val required = listOf(
            "ai_quick_write_command",
            "ai_write_command_placeholder",
            "ai_btn_explain",
            "ai_btn_fix",
            "ai_explain_sheet_title",
            "ai_explain_sheet_close",
            "ai_quick_summarize_output",
            "ai_quick_root_cause",
            "terminal_sel_summarize",
            "terminal_sel_root_cause",
            "ai_empty_output_notice",
            "ai_key_status_active",
            "ai_select_key",
            "cd_ai_select_model",
            "cd_ai_select_key",
            "cd_ai_key_status_active",
            "ai_empty_title",
            "ai_empty_suggestion_1",
            "ai_empty_suggestion_2",
            "ai_empty_suggestion_3",
            "cd_ai_expand_sheet",
            "cd_ai_collapse_sheet",
            "ai_no_api_key_title",
            "ai_add_key_cta",
            "ai_empty_suggest_explain_error",
            "ai_error_card_summary"
        )
        required.forEach { key ->
            assertTrue("Missing EN key: $key", key in enKeys)
            assertTrue("Missing ID key: $key", key in idKeys)
        }
    }
}
