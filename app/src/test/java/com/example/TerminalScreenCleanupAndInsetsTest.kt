package com.example

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiAssistantRepository
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
import com.example.terminal.TerminalBackend
import com.example.terminal.TerminalErrorContext
import com.example.terminal.TerminalSessionHandle
import com.example.ui.terminal.TerminalConsoleView
import com.example.ui.terminal.TerminalScreen
import com.example.ui.terminal.TerminalViewModel
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.HackerStudioTheme
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
class TerminalScreenCleanupAndInsetsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeSettingsStore(
        initial: AppSettings = AppSettings(
            appTheme = AppThemePreset.HACKER_PRO,
            aiEnabled = true,
            selectedModel = "gemini-3.8-flash",
            aiConsentAccepted = true
        )
    ) : SettingsStore {
        private val state = MutableStateFlow(initial)
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
        override suspend fun addApiKey(label: String, rawKey: String): Result<ApiKeyMetadata> =
            Result.failure(IllegalStateException("unused"))
        override suspend fun removeApiKey(id: String) {}
        override suspend fun setApiKeyEnabled(id: String, enabled: Boolean) {}
        override suspend fun updateApiKeyLabel(id: String, newLabel: String) {}
        override fun getSecretKey(id: String): String? = null
    }

    private class SpyTerminalBackend(
        scope: CoroutineScope,
        private val delegate: FakeTerminalBackend = FakeTerminalBackend()
    ) : TerminalBackend {
        val resizeEvents = mutableListOf<Pair<Int, Int>>()

        private inner class SpyHandle(
            private val inner: TerminalSessionHandle
        ) : TerminalSessionHandle by inner {
            override fun resize(cols: Int, rows: Int) {
                resizeEvents.add(cols to rows)
                inner.resize(cols, rows)
            }
        }

        private val wrappedCache = linkedMapOf<String, SpyHandle>()

        override val sessions: StateFlow<List<TerminalSessionHandle>> =
            delegate.sessions.map { list ->
                list.map { handle -> wrappedCache.getOrPut(handle.id) { SpyHandle(handle) } }
            }.stateIn(scope, SharingStarted.Eagerly, emptyList())

        override val activeSessionId: StateFlow<String> = delegate.activeSessionId

        override fun createSession(title: String?): TerminalSessionHandle {
            val h = delegate.createSession(title)
            return wrappedCache.getOrPut(h.id) { SpyHandle(h) }
        }

        override fun closeSession(sessionId: String) = delegate.closeSession(sessionId)
        override fun switchSession(sessionId: String) = delegate.switchSession(sessionId)
        override fun stateFilePath(sessionId: String): String = delegate.stateFilePath(sessionId)
    }

    private fun createTestHarness(
        theme: AppThemePreset = AppThemePreset.HACKER_PRO,
        language: AppLanguage = AppLanguage.INDONESIAN
    ): Triple<TerminalViewModel, SpyTerminalBackend, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val backend = SpyTerminalBackend(scope)
        val settingsStore = FakeSettingsStore(
            AppSettings(
                appTheme = theme,
                appLanguage = language,
                aiEnabled = true,
                selectedModel = "gemini-3.8-flash",
                aiConsentAccepted = true
            )
        )
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
            ): Flow<GeminiStreamEvent> = flow {}
        }
        val aiRepo = AiAssistantRepository(keyManager, transport, settingsStore)
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
            aiRepository = aiRepo,
            keyManager = keyManager,
            settingsStore = settingsStore,
            snippetStore = snippetStore,
            promptHistoryStore = historyStore
        )
        return Triple(vm, backend, scope)
    }

    private fun locateSourceFile(relative: String): File {
        val direct = File(relative)
        if (direct.exists()) return direct
        val fromApp = File("app/$relative")
        assertTrue("Missing file $relative", fromApp.exists())
        return fromApp
    }

    private fun collectTextNodes(node: SemanticsNode, acc: MutableList<SemanticsNode> = mutableListOf()): List<SemanticsNode> {
        if (node.config.contains(SemanticsProperties.Text) || node.config.contains(SemanticsActions.GetTextLayoutResult)) {
            acc.add(node)
        }
        node.children.forEach { collectTextNodes(it, acc) }
        return acc
    }

    @Test
    fun `source audit verifies single safeDrawing inset and zero legacy inset modifiers or raw tokens`() {
        val terminalScreenText = locateSourceFile("src/main/java/com/example/ui/terminal/TerminalScreen.kt").readText()
        assertFalse(
            "TerminalScreen.kt must not contain imePadding",
            terminalScreenText.contains("imePadding")
        )
        assertFalse(
            "TerminalScreen.kt must not contain navigationBarsPadding",
            terminalScreenText.contains("navigationBarsPadding")
        )
        assertFalse(
            "TerminalScreen.kt must not apply innerPadding bottom calculation",
            Regex("""padding\s*\(\s*bottom\s*=\s*innerPadding|calculateBottomPadding""").containsMatchIn(terminalScreenText)
        )
        val safeDrawingMatches = Regex("""windowInsetsPadding\s*\(\s*WindowInsets\.safeDrawing\s*\)""")
            .findAll(terminalScreenText).count()
        assertEquals(
            "TerminalScreen.kt must apply windowInsetsPadding(WindowInsets.safeDrawing) exactly once",
            1,
            safeDrawingMatches
        )
        assertTrue(
            "Scaffold in TerminalScreen.kt must use WindowInsets(0, 0, 0, 0)",
            Regex("""contentWindowInsets\s*=\s*WindowInsets\s*\(\s*0\s*,\s*0\s*,\s*0\s*,\s*0\s*\)""")
                .containsMatchIn(terminalScreenText)
        )

        val sessionTabsText = locateSourceFile("src/main/java/com/example/ui/terminal/SessionTabsBar.kt").readText()
        assertFalse("SessionTabsBar.kt must not contain statusBarsPadding", sessionTabsText.contains("statusBarsPadding"))
        assertFalse("SessionTabsBar.kt must not contain navigationBarsPadding", sessionTabsText.contains("navigationBarsPadding"))

        val workspaceText = locateSourceFile("src/main/java/com/example/ui/terminal/WorkspaceComponents.kt").readText()
        assertFalse("WorkspaceComponents.kt must not contain statusBarsPadding", workspaceText.contains("statusBarsPadding"))
        assertFalse("WorkspaceComponents.kt must not contain navigationBarsPadding", workspaceText.contains("navigationBarsPadding"))

        val aiFullScreenText = locateSourceFile("src/main/java/com/example/ui/ai/AiFullScreen.kt").readText()
        assertFalse("AiFullScreen.kt must not contain imePadding", aiFullScreenText.contains("imePadding"))

        val consoleText = locateSourceFile("src/main/java/com/example/ui/terminal/TerminalConsoleView.kt").readText()
        assertFalse("TerminalConsoleView.kt must not use MaterialTheme.typography", consoleText.contains("MaterialTheme.typography"))
        assertFalse(
            "TerminalConsoleView.kt must not contain hardcoded .dp literals",
            Regex("""\d+(\.\d+)?\.dp""").containsMatchIn(consoleText)
        )
        assertFalse(
            "TerminalConsoleView.kt must not contain hardcoded .sp literals",
            Regex("""fontSize\s*=\s*\d+(\.\d+)?\.sp""").containsMatchIn(consoleText)
        )
    }

    @Test
    fun `IME closed shows WorkspaceStatusBar flush above nav bar and IME open hides it without gaps`() {
        val (vm, backend, scope) = createTestHarness()
        var composeHostView: View? = null
        try {
            composeRule.setContent {
                val state by vm.uiState.collectAsState()
                composeHostView = LocalView.current
                HackerStudioTheme(appTheme = state.settings.appTheme) {
                    TerminalScreen(viewModel = vm, onNavigateToSettings = {})
                }
            }
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()

            val hostView = composeHostView!!
            val density = composeRule.density.density
            val statusTopPx = (24 * density).toInt()
            val navBottomPx = (48 * density).toInt()
            val imeBottomPx = (280 * density).toInt()

            // Dispatch closed-IME system bars
            composeRule.runOnUiThread {
                val closedInsets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTopPx, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBottomPx))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                    .setVisible(WindowInsetsCompat.Type.ime(), false)
                    .build()
                ViewCompat.dispatchApplyWindowInsets(hostView, closedInsets)
            }
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()

            composeRule.onNodeWithTag("workspace_status_bar").assertIsDisplayed()
            val menuClosed = composeRule.onNodeWithTag("open_sidebar_menu_button").fetchSemanticsNode().boundsInRoot
            val extraKeysClosed = composeRule.onNodeWithTag("extra_keys_bar").fetchSemanticsNode().boundsInRoot
            val statusClosed = composeRule.onNodeWithTag("workspace_status_bar").fetchSemanticsNode().boundsInRoot
            val rootHeightPx = hostView.height.toFloat()

            // Single safeDrawing top inset (~24dp statusBar + small bar padding, strictly < 2x statusBar)
            assertTrue(
                "Top bar must respect single statusBar inset (menuTop=${menuClosed.top}, statusTopPx=$statusTopPx)",
                menuClosed.top >= statusTopPx.toFloat() && menuClosed.top < statusTopPx * 1.5f
            )
            assertEquals(rootHeightPx - navBottomPx, statusClosed.bottom, 2f)
            assertTrue(
                "ExtraKeysBar should sit flush above WorkspaceStatusBar (gap=${statusClosed.top - extraKeysClosed.bottom})",
                abs(statusClosed.top - extraKeysClosed.bottom) <= 2f
            )

            // Open IME: WorkspaceStatusBar must hide and ExtraKeysBar must sit flush above IME
            val resizesBeforeIme = backend.resizeEvents.size
            composeRule.runOnUiThread {
                val openInsets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTopPx, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBottomPx))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
                ViewCompat.dispatchApplyWindowInsets(hostView, openInsets)
            }
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()

            composeRule.onAllNodesWithTag("workspace_status_bar").assertCountEquals(0)
            val extraKeysOpen = composeRule.onNodeWithTag("extra_keys_bar").fetchSemanticsNode().boundsInRoot
            assertEquals(rootHeightPx - imeBottomPx, extraKeysOpen.bottom, 2f)

            val resizesDuringImeOpen = backend.resizeEvents.size - resizesBeforeIme
            assertTrue(
                "Opening IME should trigger at most 1 terminal resize, got $resizesDuringImeOpen",
                resizesDuringImeOpen <= 1
            )

            // Close IME again: WorkspaceStatusBar reappears flush above nav bar
            composeRule.runOnUiThread {
                val closedAgain = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTopPx, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBottomPx))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                    .setVisible(WindowInsetsCompat.Type.ime(), false)
                    .build()
                ViewCompat.dispatchApplyWindowInsets(hostView, closedAgain)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("workspace_status_bar").assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `compact terminal header is at most 32dp, shows cwd and grid size plus two icons only, and strips session title, exit pill, and explain button`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val idContext = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.INDONESIAN)
        val backend = FakeTerminalBackend()
        val session = backend.sessions.value.first()
        session.resize(39, 18)

        var keyboardFocused = 0
        var cleared = 0
        var preset by mutableStateOf(AppThemePreset.HACKER_PRO)

        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides idContext) {
                HackerStudioTheme(appTheme = preset) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TerminalConsoleView(
                            sessionTitle = "custom-tab-name",
                            cwd = FakeTerminalBackend.HOME_DIR,
                            lastExitCode = 2,
                            lastError = TerminalErrorContext(
                                sessionId = session.id,
                                command = "sh scripts/deploy.sh",
                                exitCode = 2,
                                errorOutput = "build failed",
                                cwd = FakeTerminalBackend.HOME_DIR
                            ),
                            screenBuffer = session.readScreen(),
                            screenVersion = session.screenVersion.value,
                            ctrlArmed = true,
                            altArmed = true,
                            fontSizeSp = 12f,
                            colorScheme = com.example.ui.theme.TerminalColorScheme.HACKER_PRO,
                            aiEnabled = true,
                            onResize = { _, _ -> },
                            onImeTextInput = {},
                            onImeBackspace = {},
                            onImeEnter = {},
                            onSendRawBytes = {},
                            onClearOutput = { cleared++ },
                            onExplainLastError = {},
                            onFixLastError = {},
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // Wait on fake focus / clear capture via TerminalStatusStrip callbacks:
        // Note: onFocusKeyboard calls haptic + onPaneFocused + focusRequester + keyboardController
        AppThemePreset.entries.forEach { theme ->
            composeRule.runOnUiThread { preset = theme }
            composeRule.waitForIdle()

            val headerNode = composeRule.onNodeWithTag("terminal_status_strip").fetchSemanticsNode()
            val headerHeightDp = with(composeRule.density) { headerNode.boundsInRoot.height.toDp() }
            assertTrue("Header height $headerHeightDp must be <= 32.dp", headerHeightDp <= 32.dp)

            composeRule.onNode(
                hasParent(hasTestTag("terminal_status_strip")) and hasText("~ 39×18")
            ).assertIsDisplayed()

            val keyboardNode = composeRule.onNodeWithTag("focus_keyboard_button").fetchSemanticsNode()
            val clearNode = composeRule.onNodeWithTag("clear_terminal_button").fetchSemanticsNode()
            with(composeRule.density) {
                assertTrue(keyboardNode.touchBoundsInRoot.width.toDp() >= 47.5.dp)
                assertTrue(keyboardNode.touchBoundsInRoot.height.toDp() >= 47.5.dp)
                assertTrue(clearNode.touchBoundsInRoot.width.toDp() >= 47.5.dp)
                assertTrue(clearNode.touchBoundsInRoot.height.toDp() >= 47.5.dp)
            }

            // Must NOT contain session title, exit code pill, Explain button, or CTRL/ALT badges in header
            composeRule.onAllNodes(
                hasParent(hasTestTag("terminal_status_strip")) and hasText("custom-tab-name", substring = true)
            ).assertCountEquals(0)
            composeRule.onAllNodes(
                hasParent(hasTestTag("terminal_status_strip")) and hasText("KELUAR", substring = true)
            ).assertCountEquals(0)
            composeRule.onAllNodes(
                hasParent(hasTestTag("terminal_status_strip")) and hasText("Jelaskan", substring = true)
            ).assertCountEquals(0)
            composeRule.onAllNodes(
                hasParent(hasTestTag("terminal_status_strip")) and hasText("CTRL", substring = true)
            ).assertCountEquals(0)
            composeRule.onAllNodesWithTag("inline_explain_error_button").assertCountEquals(0)
            composeRule.onAllNodesWithTag("terminal_status_prompt_glyph").assertCountEquals(0)
        }

        composeRule.onNodeWithTag("clear_terminal_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, cleared)
        assertEquals(0, keyboardFocused) // verified focus click doesn't trigger clear
    }

    @Test
    fun `non-zero exit renders single TopEnd error chip above prompt row and collapses buttons cleanly to contentDescribed icons on 320dp fontScale 1_3`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val idContext = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.INDONESIAN)
        val backend = FakeTerminalBackend()
        val session = backend.sessions.value.first()
        session.resize(36, 18)
        // Drive cursor toward lower rows like real shell output
        repeat(10) {
            session.write("echo line$it\r".encodeToByteArray())
        }
        session.write("sh scripts/deploy.sh\r".encodeToByteArray())
        val buffer = session.readScreen()
        assertEquals(1, session.lastExitCode.value)
        assertTrue("Cursor should sit near lower half (row=${buffer.cursorRow})", buffer.cursorRow >= 8)

        var widthDp by mutableStateOf(411.dp)
        var fontScale by mutableStateOf(1f)
        var preset by mutableStateOf(AppThemePreset.HACKER_PRO)
        var explainClicks = 0
        var fixClicks = 0

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides idContext,
                LocalDensity provides Density(baseDensity.density, fontScale)
            ) {
                HackerStudioTheme(appTheme = preset) {
                    Box(modifier = Modifier.width(widthDp).fillMaxSize()) {
                        TerminalConsoleView(
                            sessionTitle = "bash-1",
                            cwd = session.cwd.value,
                            lastExitCode = session.lastExitCode.value,
                            lastError = session.lastErrorContext.value,
                            screenBuffer = buffer,
                            screenVersion = session.screenVersion.value,
                            ctrlArmed = false,
                            altArmed = false,
                            fontSizeSp = 12f,
                            colorScheme = com.example.ui.theme.TerminalColorScheme.HACKER_PRO,
                            aiEnabled = true,
                            onResize = { _, _ -> },
                            onImeTextInput = {},
                            onImeBackspace = {},
                            onImeEnter = {},
                            onSendRawBytes = {},
                            onClearOutput = {},
                            onExplainLastError = { explainClicks++ },
                            onFixLastError = { fixClicks++ },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1) Normal phone width: exactly ONE set of Explain + Fix buttons, single-line text, TopEnd above prompt
        val explainLabel = idContext.getString(R.string.terminal_chip_explain)
        val fixLabel = idContext.getString(R.string.terminal_chip_fix_ai)
        composeRule.onAllNodesWithText(explainLabel).assertCountEquals(1)
        composeRule.onAllNodesWithText(fixLabel).assertCountEquals(1)
        composeRule.onAllNodesWithTag("terminal_chip_explain").assertCountEquals(1)
        composeRule.onAllNodesWithTag("terminal_chip_fix_ai").assertCountEquals(1)

        val gridNode = composeRule.onNodeWithTag("terminal_cell_grid").fetchSemanticsNode()
        val chipNode = composeRule.onNodeWithTag("terminal_fix_with_ai_chip", useUnmergedTree = true).fetchSemanticsNode()
        val gridBounds = gridNode.boundsInRoot
        val chipBounds = chipNode.boundsInRoot
        assertTrue("Chip must be near top of terminal grid", chipBounds.top < gridBounds.top + gridBounds.height * 0.25f)
        assertTrue("Chip must be aligned toward TopEnd", chipBounds.right >= gridBounds.right - gridBounds.width * 0.15f)
        val cursorApproxTop = gridBounds.top + (gridBounds.height / buffer.rows) * buffer.cursorRow
        assertTrue(
            "TopEnd chip (bottom=${chipBounds.bottom}) must not overlap lower active prompt row (top=$cursorApproxTop)",
            chipBounds.bottom < cursorApproxTop
        )

        // All visible text inside chip must remain 1 line (never split character-by-character)
        collectTextNodes(chipNode).forEach { txtNode ->
            val results = mutableListOf<TextLayoutResult>()
            val action = txtNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)
            if (action != null && action.action?.invoke(results) == true && results.isNotEmpty()) {
                assertEquals(
                    "Chip text '${txtNode.config.getOrNull(SemanticsProperties.Text)}' must stay 1 line",
                    1,
                    results.first().lineCount
                )
            }
        }

        // 2) Narrow 320dp + fontScale 1.3f in BOTH themes: fits cleanly, switches buttons to icons with contentDescription
        AppThemePreset.entries.forEach { theme ->
            composeRule.runOnUiThread {
                preset = theme
                widthDp = 320.dp
                fontScale = 1.3f
            }
            composeRule.waitForIdle()

            val narrowGrid = composeRule.onNodeWithTag("terminal_cell_grid").fetchSemanticsNode().boundsInRoot
            val narrowChip = composeRule.onNodeWithTag("terminal_fix_with_ai_chip", useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(
                "Error chip must stay inside 320dp grid (chip=${narrowChip.boundsInRoot}, grid=$narrowGrid)",
                narrowChip.boundsInRoot.left >= narrowGrid.left - 1f &&
                    narrowChip.boundsInRoot.right <= narrowGrid.right + 1f
            )

            // Icon fallback hides text labels so they cannot split letter-by-letter
            composeRule.onAllNodesWithText(explainLabel).assertCountEquals(0)
            composeRule.onAllNodesWithText(fixLabel).assertCountEquals(0)
            collectTextNodes(narrowChip).forEach { txtNode ->
                val results = mutableListOf<TextLayoutResult>()
                val action = txtNode.config.getOrNull(SemanticsActions.GetTextLayoutResult)
                if (action != null && action.action?.invoke(results) == true && results.isNotEmpty()) {
                    assertEquals(1, results.first().lineCount)
                }
            }

            val explainDesc = composeRule.onNodeWithTag("terminal_chip_explain").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val fixDesc = composeRule.onNodeWithTag("terminal_chip_fix_ai").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            assertTrue("Explain icon fallback must keep contentDescription", explainDesc.contains(explainLabel))
            assertTrue("Fix AI icon fallback must keep contentDescription", fixDesc.contains(fixLabel))
        }

        composeRule.onNodeWithTag("terminal_chip_explain").performClick()
        composeRule.onNodeWithTag("terminal_chip_fix_ai").performClick()
        composeRule.waitForIdle()
        assertEquals(1, explainClicks)
        assertEquals(1, fixClicks)

        composeRule.onNodeWithTag("terminal_chip_dismiss").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("terminal_fix_with_ai_chip", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `FakeTerminalBackend welcome text preserves every character cleanly at 36 and 39 columns and default font is 12sp`() {
        assertEquals(12f, AppSettings().fontSizeSp, 0.001f)

        listOf(36, 39).forEach { cols ->
            val backend = FakeTerminalBackend()
            val primary = backend.sessions.value.first()
            primary.resize(cols = cols, rows = 20)

            val recent = primary.readRecentText()
            val expectedLines = listOf(
                "HackerStudio PTY Shell v2.0",
                "[VT Cell Grid • OSC 7777 Ready]",
                "Type 'help' for commands, or run",
                "'sh scripts/deploy.sh' for AI fix."
            )
            expectedLines.forEach { expected ->
                assertTrue(
                    "Expected intact line '$expected' at $cols cols, got:\n$recent",
                    expected.length <= cols && recent.lines().any { it.trimEnd() == expected }
                )
            }

            val second = backend.createSession("bash-2")
            second.resize(cols = cols, rows = 20)
            val secondText = second.readRecentText()
            assertTrue(
                "Secondary session welcome must fit intact at $cols cols",
                secondText.lines().any { it.trimEnd() == "Session 'bash-2' (~)" }
            )
        }
    }
}
