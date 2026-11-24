package com.example

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiAssistantRepository
import com.example.ai.AiPromptContext
import com.example.ai.AiPromptKind
import com.example.ai.AiPromptRequest
import com.example.ai.AiStreamUpdate
import com.example.ai.GeminiApiClient
import com.example.ai.GeminiCallResult
import com.example.ai.GeminiContent
import com.example.ai.GeminiGenerateRequest
import com.example.ai.GeminiPart
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
import com.example.data.SnippetJsonCodec
import com.example.data.SnippetStore
import com.example.data.VaultStatus
import com.example.terminal.FakeTerminalBackend
import com.example.terminal.TerminalCell
import com.example.terminal.TerminalGridPoint
import com.example.terminal.TerminalGridSelection
import com.example.ui.terminal.ExtraKeysBar
import com.example.ui.terminal.SessionTabsBar
import com.example.ui.terminal.TerminalScreen
import com.example.ui.terminal.TerminalScreenUiState
import com.example.ui.terminal.TerminalViewModel
import com.example.ui.terminal.WorkspaceStatusBar
import com.example.ui.terminal.groupTerminalRowRuns
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.CyberorgTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xhdpi")
class TerminalPerformanceOptimizationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeSettingsStore(
        initial: AppSettings = AppSettings(
            appTheme = AppThemePreset.HACKER_PRO,
            appLanguage = AppLanguage.INDONESIAN,
            onboardingCompleted = true,
            selectedModel = "gemini-3.8-flash",
            aiConsentAccepted = true
        )
    ) : SettingsStore {
        private val state = MutableStateFlow(initial)
        private val secrets = linkedMapOf<String, String>()
        override val settingsFlow: Flow<AppSettings> = state.asStateFlow()
        override val vaultStatus: StateFlow<VaultStatus> =
            MutableStateFlow<VaultStatus>(VaultStatus.Ready).asStateFlow()

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
        override suspend fun setAiConsentAccepted(accepted: Boolean) {
            state.update { it.copy(aiConsentAccepted = accepted) }
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
            val id = "key-${secrets.size + 1}"
            secrets[id] = rawKey
            val meta = ApiKeyMetadata(
                id = id,
                label = label,
                maskedPreview = "AIza...${rawKey.takeLast(4)}",
                enabled = true
            )
            state.update { it.copy(apiKeys = it.apiKeys + meta) }
            return Result.success(meta)
        }
        override suspend fun removeApiKey(id: String) {
            secrets.remove(id)
            state.update { it.copy(apiKeys = it.apiKeys.filterNot { k -> k.id == id }) }
        }
        override suspend fun setApiKeyEnabled(id: String, enabled: Boolean) {
            state.update {
                it.copy(apiKeys = it.apiKeys.map { k -> if (k.id == id) k.copy(enabled = enabled) else k })
            }
        }
        override suspend fun updateApiKeyLabel(id: String, newLabel: String) {
            state.update {
                it.copy(apiKeys = it.apiKeys.map { k -> if (k.id == id) k.copy(label = newLabel) else k })
            }
        }
        override fun getSecretKey(id: String): String? = secrets[id]
    }

    private class FakeSnippetStore : SnippetStore {
        override val snippetsFlow: Flow<List<CommandSnippet>> = MutableStateFlow(emptyList())
        override suspend fun saveSnippet(
            title: String,
            command: String,
            tags: List<String>,
            existingId: String?
        ): CommandSnippet = CommandSnippet(id = existingId ?: "s1", title = title, command = command, tags = tags)
        override suspend fun deleteSnippet(id: String) {}
        override fun exportToJson(snippets: List<CommandSnippet>): String = "[]"
        override suspend fun importFromJson(rawJson: String): Result<Int> = Result.success(0)
    }

    private class FakePromptHistoryStore : PromptHistoryStore {
        override val historyFlow: Flow<List<PromptHistoryItem>> = MutableStateFlow(emptyList())
        override suspend fun recordPrompt(prompt: String) {}
        override suspend fun clearAll() {}
    }

    private class FakeGeminiTransport : GeminiTransport {
        override suspend fun generate(
            model: String,
            apiKey: String,
            systemInstruction: String,
            contents: List<GeminiContent>
        ): GeminiCallResult<String> = GeminiCallResult.Success("ok", "k1", "Primary", model)

        override fun streamGenerate(
            model: String,
            apiKey: String,
            systemInstruction: String,
            contents: List<GeminiContent>
        ): Flow<GeminiStreamEvent> = flow {
            emit(GeminiStreamEvent.Chunk("echo "))
            emit(GeminiStreamEvent.Chunk("ok"))
            emit(GeminiStreamEvent.Completed("STOP"))
        }
    }

    @Test
    fun `run grouping merges consecutive same-style cells and splits on fg bg or bold changes`() {
        val defFg = TerminalCell.DEFAULT_FG
        val defBg = TerminalCell.DEFAULT_BG
        val cells = listOf(
            // Run 1: "u0@" (bold green on default bg)
            TerminalCell('u', fgColorCode = 32, bgColorCode = defBg, bold = true),
            TerminalCell('0', fgColorCode = 32, bgColorCode = defBg, bold = true),
            TerminalCell('@', fgColorCode = 32, bgColorCode = defBg, bold = true),
            // Run 2: "t" (non-bold green on default bg -> bold changed)
            TerminalCell('t', fgColorCode = 32, bgColorCode = defBg, bold = false),
            // Run 3: ":~" (non-bold cyan on default bg -> fg changed)
            TerminalCell(':', fgColorCode = 36, bgColorCode = defBg, bold = false),
            TerminalCell('~', fgColorCode = 36, bgColorCode = defBg, bold = false),
            // Run 4: "$ " (default fg on blue bg -> fg & bg changed)
            TerminalCell('$', fgColorCode = defFg, bgColorCode = 44, bold = false),
            TerminalCell(' ', fgColorCode = defFg, bgColorCode = 44, bold = false),
            // Run 5: "ls -la" (default fg on default bg -> bg changed)
            TerminalCell('l', fgColorCode = defFg, bgColorCode = defBg, bold = false),
            TerminalCell('s', fgColorCode = defFg, bgColorCode = defBg, bold = false),
            TerminalCell(' ', fgColorCode = defFg, bgColorCode = defBg, bold = false),
            TerminalCell('-', fgColorCode = defFg, bgColorCode = defBg, bold = false),
            TerminalCell('l', fgColorCode = defFg, bgColorCode = defBg, bold = false),
            TerminalCell('a', fgColorCode = defFg, bgColorCode = defBg, bold = false)
        )

        val runs = groupTerminalRowRuns(cells)
        assertEquals(5, runs.size)

        assertEquals(0, runs[0].startCol)
        assertEquals(3, runs[0].length)
        assertEquals("u0@", runs[0].text)
        assertTrue(runs[0].bold)
        assertEquals(32, runs[0].fgColorCode)

        assertEquals(3, runs[1].startCol)
        assertEquals(1, runs[1].length)
        assertEquals("t", runs[1].text)
        assertEquals(false, runs[1].bold)

        assertEquals(4, runs[2].startCol)
        assertEquals(2, runs[2].length)
        assertEquals(":~", runs[2].text)
        assertEquals(36, runs[2].fgColorCode)

        assertEquals(6, runs[3].startCol)
        assertEquals(2, runs[3].length)
        assertEquals("$ ", runs[3].text)
        assertEquals(44, runs[3].bgColorCode)

        assertEquals(8, runs[4].startCol)
        assertEquals(6, runs[4].length)
        assertEquals("ls -la", runs[4].text)
        assertEquals(defBg, runs[4].bgColorCode)

        val expectedText = cells.joinToString("") { it.char.toString() }
        val actualText = runs.joinToString("") { it.text }
        assertEquals(expectedText, actualText)
    }

    @Test
    fun `readScreen twice without edits shares identical row references and typing only copies dirty row`() {
        val tempDir = kotlin.io.path.createTempDirectory("cow_test").toFile().apply { deleteOnExit() }
        val backend = FakeTerminalBackend(stateDirectory = tempDir)
        val session = backend.sessions.value.first()
        session.resize(cols = 40, rows = 12)

        // Populate scrollback (>12 lines)
        repeat(20) { idx ->
            session.write("echo line_$idx\r".encodeToByteArray())
        }

        val first = session.readScreen()
        val second = session.readScreen()
        assertSame("No-op readScreen should return cached buffer", first, second)
        assertSame(first.scrollbackRows, second.scrollbackRows)
        first.visibleRows.indices.forEach { r ->
            assertSame("Unchanged visible row $r must share reference", first.visibleRows[r], second.visibleRows[r])
        }
        first.scrollbackRows.indices.forEach { r ->
            assertSame("Unchanged scrollback row $r must share reference", first.scrollbackRows[r], second.scrollbackRows[r])
        }

        // Now type one character on the prompt line
        val dirtyPromptRow = first.cursorRow
        session.write("x".encodeToByteArray())
        val afterType = session.readScreen()

        assertSame(
            "Typing on prompt row must not copy scrollback list",
            first.scrollbackRows,
            afterType.scrollbackRows
        )
        for (r in 0 until first.rows) {
            if (r == dirtyPromptRow) {
                assertNotSame("Typed row must produce a fresh snapshot", first.visibleRows[r], afterType.visibleRows[r])
            } else {
                assertSame("Untouched row $r must keep original reference", first.visibleRows[r], afterType.visibleRows[r])
            }
        }

        // Verify indexed access & selection extraction match combined list behavior
        assertEquals(afterType.scrollbackRows.size + afterType.visibleRows.size, afterType.totalRowCount)
        val combined = afterType.scrollbackRows + afterType.visibleRows
        for (absIdx in 0 until afterType.totalRowCount) {
            assertSame(combined[absIdx], afterType.getRow(absIdx))
        }
        val sel = TerminalGridSelection(
            anchor = TerminalGridPoint(afterType.scrollbackRows.size, 0),
            focus = TerminalGridPoint(afterType.scrollbackRows.size + dirtyPromptRow, afterType.cols - 1)
        )
        assertEquals(sel.extractText(combined), sel.extractText(afterType))
        assertTrue(session.readRecentText(10).isNotBlank())
    }

    @Test
    fun `typing increments screenVersion without recomposing TerminalScreen or outer chrome`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val idContext = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.INDONESIAN)
        val settingsStore = FakeSettingsStore()
        val keyManager = KeyManager(settingsStore)
        val snippetStore = FakeSnippetStore()
        val historyStore = FakePromptHistoryStore()
        val tempDir = kotlin.io.path.createTempDirectory("recomp_test").toFile().apply { deleteOnExit() }
        val backend = FakeTerminalBackend(stateDirectory = tempDir)
        val vm = TerminalViewModel(
            terminalBackend = backend,
            aiRepository = AiAssistantRepository(keyManager, FakeGeminiTransport(), settingsStore),
            keyManager = keyManager,
            settingsStore = settingsStore,
            snippetStore = snippetStore,
            promptHistoryStore = historyStore
        )

        var outerUiStateRecompositions = 0
        var tabsBarRecompositions = 0
        var statusBarRecompositions = 0
        var extraKeysRecompositions = 0
        var lastObservedScreenVersion = -1L

        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides idContext) {
                CyberorgTheme(appTheme = AppThemePreset.HACKER_PRO) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        OuterChromeProbe(
                            viewModel = vm,
                            onOuterUiRecompose = { outerUiStateRecompositions++ },
                            onTabsRecompose = { tabsBarRecompositions++ },
                            onStatusRecompose = { statusBarRecompositions++ },
                            onExtraKeysRecompose = { extraKeysRecompositions++ }
                        )
                        ActiveScreenProbe(
                            viewModel = vm,
                            onVersionObserved = { lastObservedScreenVersion = it }
                        )
                        TerminalScreen(
                            viewModel = vm,
                            onNavigateToSettings = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_cell_grid").assertExists()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(32))
        composeRule.waitForIdle()

        val baseVersion = backend.sessions.value.first().screenVersion.value
        assertEquals(baseVersion, lastObservedScreenVersion)
        val baselineOuter = outerUiStateRecompositions
        val baselineTabs = tabsBarRecompositions
        val baselineStatus = statusBarRecompositions
        val baselineExtra = extraKeysRecompositions

        // Type characters via IME — only screenVersion & activeScreen change, NOT uiState or outer chrome
        vm.onImeTextInput("a")
        vm.onImeTextInput("b")
        vm.onImeTextInput("c")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(32))
        composeRule.waitForIdle()

        val finalVersion = backend.sessions.value.first().screenVersion.value
        assertTrue("screenVersion must advance after typing", finalVersion > baseVersion)
        assertEquals("activeScreen must receive final settled version", finalVersion, lastObservedScreenVersion)

        assertEquals(
            "uiState collector outside terminal must have 0 recompositions on typing",
            baselineOuter,
            outerUiStateRecompositions
        )
        assertEquals(
            "SessionTabsBar must have 0 recompositions on typing",
            baselineTabs,
            tabsBarRecompositions
        )
        assertEquals(
            "WorkspaceStatusBar must have 0 recompositions on typing",
            baselineStatus,
            statusBarRecompositions
        )
        assertEquals(
            "ExtraKeysBar must have 0 recompositions on typing",
            baselineExtra,
            extraKeysRecompositions
        )
    }

    @Composable
    private fun ActiveScreenProbe(
        viewModel: TerminalViewModel,
        onVersionObserved: (Long) -> Unit
    ) {
        val screenSnapshot by viewModel.activeScreen.collectAsStateWithLifecycle()
        onVersionObserved(screenSnapshot.version)
    }

    @Composable
    private fun OuterChromeProbe(
        viewModel: TerminalViewModel,
        onOuterUiRecompose: () -> Unit,
        onTabsRecompose: () -> Unit,
        onStatusRecompose: () -> Unit,
        onExtraKeysRecompose: () -> Unit
    ) {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        SideEffect { onOuterUiRecompose() }
        ProbeTabsSlot(uiState = uiState, onRecompose = onTabsRecompose)
        ProbeStatusSlot(uiState = uiState, onRecompose = onStatusRecompose)
        ProbeExtraKeysSlot(uiState = uiState, onRecompose = onExtraKeysRecompose)
    }

    @Composable
    private fun ProbeTabsSlot(
        uiState: TerminalScreenUiState,
        onRecompose: () -> Unit
    ) {
        SideEffect { onRecompose() }
        SessionTabsBar(
            sessions = uiState.sessionTabs,
            activeSessionId = uiState.activeSessionId,
            aiEnabled = uiState.settings.aiEnabled,
            isAiPanelOpen = uiState.isAiPanelOpen,
            hasUnreadAiReply = uiState.hasUnreadAiReply,
            showSplitButton = false,
            splitMode = uiState.splitMode,
            showMenuButton = false,
            isSidebarOpen = false,
            onOpenSidebar = {},
            onSelectSession = {},
            onAddSession = {},
            onCloseSession = {},
            onRenameSession = { _, _ -> },
            onToggleAiPanel = {},
            onToggleSplitMode = {}
        )
    }

    @Composable
    private fun ProbeStatusSlot(
        uiState: TerminalScreenUiState,
        onRecompose: () -> Unit
    ) {
        SideEffect { onRecompose() }
        WorkspaceStatusBar(
            cwd = uiState.activeCwd,
            lastExitCode = uiState.activeLastExitCode,
            keySnapshot = uiState.keySnapshot,
            selectedModel = uiState.settings.selectedModel
        )
    }

    @Composable
    private fun ProbeExtraKeysSlot(
        uiState: TerminalScreenUiState,
        onRecompose: () -> Unit
    ) {
        SideEffect { onRecompose() }
        ExtraKeysBar(
            layout = uiState.settings.extraKeysLayout,
            ctrlArmed = uiState.ctrlArmed,
            altArmed = uiState.altArmed,
            onKeyClick = {}
        )
    }

    @Test
    fun `moshi codecs and mock AI streaming serialize and stream cleanly`() = runBlocking {
        val client = GeminiApiClient()
        val encoded = client.encodeRequestJson(
            GeminiGenerateRequest(
                contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = "ls"))))
            )
        )
        assertTrue(encoded.contains("\"text\":\"ls\""))

        val snippets = listOf(
            CommandSnippet(id = "s1", title = "List", command = "ls -la", tags = listOf("fs"))
        )
        val exported = SnippetJsonCodec.exportToJson(snippets)
        val parsed = SnippetJsonCodec.parseImportJson(exported).getOrThrow()
        assertEquals(1, parsed.size)
        assertEquals("ls -la", parsed.first().command)

        val settingsStore = FakeSettingsStore()
        settingsStore.addApiKey("Primary", "AIzaSyD-TestKeyForFastBuild1234567890")
        val keyManager = KeyManager(settingsStore)
        val repo = AiAssistantRepository(keyManager, FakeGeminiTransport(), settingsStore)
        val events = repo.streamPrompt(
            AiPromptRequest(
                kind = AiPromptKind.CHAT,
                userPrompt = "say ok",
                context = AiPromptContext(cwd = "~")
            )
        ).toList()
        assertTrue(events.any { it is AiStreamUpdate.Delta })
        assertTrue(events.any { it is AiStreamUpdate.Completed })
    }
}
