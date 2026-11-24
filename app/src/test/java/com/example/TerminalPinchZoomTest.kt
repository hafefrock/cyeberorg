package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.example.data.KeyVault
import com.example.data.PromptHistoryItem
import com.example.data.PromptHistoryStore
import com.example.data.SettingsRepository
import com.example.data.SettingsStore
import com.example.data.SnippetStore
import com.example.data.TerminalFontConfig
import com.example.data.VaultStatus
import com.example.settings.AppearanceSettingsScreen
import com.example.settings.SettingsViewModel
import com.example.terminal.FakeTerminalBackend
import com.example.ui.terminal.TerminalConsoleView
import com.example.ui.terminal.TerminalScreenSnapshot
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xhdpi")
class TerminalPinchZoomTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeVault : KeyVault {
        override val vaultStatus: StateFlow<VaultStatus> =
            MutableStateFlow<VaultStatus>(VaultStatus.Ready).asStateFlow()
        override fun putKey(id: String, rawKey: String) {}
        override fun getKey(id: String): String? = null
        override fun removeKey(id: String) {}
        override fun maskKey(rawKey: String): String = "AIza••••"
        override fun resetVault(): Boolean = true
    }

    private class SharedFakeSettingsStore(
        initial: AppSettings = AppSettings()
    ) : SettingsStore {
        private val state = MutableStateFlow(initial)
        override val settingsFlow: Flow<AppSettings> = state.asStateFlow()
        override val vaultStatus: StateFlow<VaultStatus> =
            MutableStateFlow<VaultStatus>(VaultStatus.Ready).asStateFlow()

        val snapshot: AppSettings get() = state.value
        val persistedFontSizes = mutableListOf<Float>()

        override suspend fun setAppTheme(theme: AppThemePreset) {}
        override suspend fun setReduceEffects(enabled: Boolean) {}
        override suspend fun setAppLanguage(language: AppLanguage) {}
        override suspend fun setOnboardingCompleted(completed: Boolean) {}
        override suspend fun setFontSizeSp(sizeSp: Float) {
            val normalized = TerminalFontConfig.normalizeFontSizeSp(sizeSp)
            persistedFontSizes.add(normalized)
            state.update { it.copy(fontSizeSp = normalized) }
        }
        override suspend fun setExtraKeysLayout(layout: ExtraKeysLayout) {}
        override suspend fun setAiEnabled(enabled: Boolean) {}
        override suspend fun setSelectedModel(modelId: String?) {}
        override suspend fun setFallbackModels(entries: List<FallbackModelEntry>) {}
        override suspend fun setRoutingStrategy(strategy: KeyRoutingStrategy) {}
        override suspend fun setCooldownSeconds(seconds: Int) {}
        override suspend fun addApiKey(label: String, rawKey: String): Result<ApiKeyMetadata> =
            Result.failure(UnsupportedOperationException())
        override suspend fun removeApiKey(id: String) {}
        override suspend fun setApiKeyEnabled(id: String, enabled: Boolean) {}
        override suspend fun updateApiKeyLabel(id: String, newLabel: String) {}
        override fun getSecretKey(id: String): String? = null
    }

    private fun locateRepoFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../app/$relativePath"),
            File("../$relativePath")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate $relativePath from ${File("").absolutePath}")
    }

    @Test
    fun `pure pinch scale helper snaps to 0_5 sp, clamps 8-28 sp, and ignores micro jitter`() {
        // Rounding to 0.5 sp steps
        assertEquals(12.5f, TerminalFontConfig.calculatePinchFontSizeSp(12f, 1.05f), 0.001f)
        assertEquals(18.0f, TerminalFontConfig.calculatePinchFontSizeSp(12f, 1.50f), 0.001f)
        assertEquals(9.5f, TerminalFontConfig.calculatePinchFontSizeSp(12f, 0.80f), 0.001f)

        // Clamping to [8, 28] sp
        assertEquals(8.0f, TerminalFontConfig.calculatePinchFontSizeSp(10f, 0.40f), 0.001f)
        assertEquals(28.0f, TerminalFontConfig.calculatePinchFontSizeSp(20f, 2.00f), 0.001f)

        // Deadband: tiny scales do not move the font size
        assertEquals(12.0f, TerminalFontConfig.calculatePinchFontSizeSp(12f, 1.005f), 0.001f)
        assertEquals(12.0f, TerminalFontConfig.calculatePinchFontSizeSp(12f, 0.990f), 0.001f)
        assertEquals(14.5f, TerminalFontConfig.calculatePinchFontSizeSp(14.5f, 1.015f), 0.001f)
        assertEquals(12.0f, TerminalFontConfig.calculatePinchFontSizeSp(12f, Float.NaN), 0.001f)
        assertEquals(12.0f, TerminalFontConfig.calculatePinchFontSizeSp(12f, -1f), 0.001f)

        // Formatting
        assertEquals("12", TerminalFontConfig.formatFontSizeValue(12f))
        assertEquals("12.5", TerminalFontConfig.formatFontSizeValue(12.5f))
    }

    @Test
    fun `pinch previews live without saving mid-gesture, persists once on release, hides HUD after 800ms, and double-tap resets to 12sp`() {
        val backend = FakeTerminalBackend()
        val session = backend.sessions.value.first()
        var fontSizeState by mutableStateOf(12f)
        val committedSizes = mutableListOf<Float>()
        val resizeEvents = mutableListOf<Pair<Int, Int>>()

        val screenFlow = MutableStateFlow(
            TerminalScreenSnapshot(
                sessionId = session.id,
                version = session.screenVersion.value,
                buffer = session.readScreen()
            )
        )

        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.HACKER_PRO) {
                Box(modifier = Modifier.size(width = 400.dp, height = 560.dp)) {
                    TerminalConsoleView(
                        sessionTitle = "bash",
                        cwd = FakeTerminalBackend.HOME_DIR,
                        lastExitCode = 0,
                        lastError = null,
                        screenFlow = screenFlow,
                        ctrlArmed = false,
                        altArmed = false,
                        fontSizeSp = fontSizeState,
                        colorScheme = AppThemePreset.HACKER_PRO.terminalScheme,
                        aiEnabled = true,
                        onResize = { c, r ->
                            resizeEvents.add(c to r)
                            session.resize(c, r)
                        },
                        onImeTextInput = {},
                        onImeBackspace = {},
                        onImeEnter = {},
                        onSendRawBytes = {},
                        onClearOutput = {},
                        onExplainLastError = {},
                        onFontSizeChange = { newSize ->
                            committedSizes.add(newSize)
                            fontSizeState = newSize
                        }
                    )
                }
            }
        }
        composeRule.waitForIdle()
        resizeEvents.clear()

        // 1. Pinch out (2x span) WITHOUT lifting yet -> HUD appears with liveRegion, nothing committed yet
        composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
            val c = center
            down(0, Offset(c.x - 40f, c.y))
            down(1, Offset(c.x + 40f, c.y))
            advanceEventTime(16L)
            moveTo(0, Offset(c.x - 60f, c.y))
            moveTo(1, Offset(c.x + 60f, c.y))
            advanceEventTime(16L)
            moveTo(0, Offset(c.x - 80f, c.y))
            moveTo(1, Offset(c.x + 80f, c.y))
        }
        composeRule.waitForIdle()

        assertTrue("Font size must NOT be persisted mid-pinch", committedSizes.isEmpty())
        composeRule.onNodeWithTag("terminal_zoom_indicator")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        composeRule.onNodeWithText("24 sp").assertIsDisplayed()

        // 2. Lift fingers -> saves ONCE and flushes resize
        composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
            up(0)
            up(1)
        }
        composeRule.waitForIdle()

        assertEquals(listOf(24.0f), committedSizes)
        assertEquals(24.0f, fontSizeState, 0.001f)
        assertTrue("Final resize should be sent after pinch release", resizeEvents.isNotEmpty())

        // 3. After ~800 ms HUD hides automatically
        composeRule.mainClock.advanceTimeBy(950L)
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_zoom_indicator").assertDoesNotExist()

        // 4. Double-tap resets back to default 12 sp
        composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
            doubleClick(center)
        }
        composeRule.waitForIdle()

        assertEquals(listOf(24.0f, 12.0f), committedSizes)
        assertEquals(12.0f, fontSizeState, 0.001f)
        composeRule.onNodeWithText("12 sp").assertIsDisplayed()
    }

    @Test
    fun `pinch does not scroll or select text, while 1-finger swipe still scrolls and long-press still selects`() {
        val backend = FakeTerminalBackend()
        val session = backend.sessions.value.first()
        session.resize(48, 12)
        // Generate > 12 lines so scrollback is populated and selectable words exist
        repeat(18) { idx ->
            session.write("echo line_$idx\r".encodeToByteArray())
        }
        val screenFlow = MutableStateFlow(
            TerminalScreenSnapshot(
                sessionId = session.id,
                version = session.screenVersion.value,
                buffer = session.readScreen()
            )
        )
        var fontSizeState by mutableStateOf(12f)

        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.HACKER_PRO) {
                Box(modifier = Modifier.size(width = 400.dp, height = 500.dp)) {
                    TerminalConsoleView(
                        sessionTitle = "bash",
                        cwd = FakeTerminalBackend.HOME_DIR,
                        lastExitCode = 0,
                        lastError = null,
                        screenFlow = screenFlow,
                        ctrlArmed = false,
                        altArmed = false,
                        fontSizeSp = fontSizeState,
                        colorScheme = AppThemePreset.HACKER_PRO.terminalScheme,
                        aiEnabled = true,
                        onResize = { _, _ -> },
                        onImeTextInput = {},
                        onImeBackspace = {},
                        onImeEnter = {},
                        onSendRawBytes = {},
                        onClearOutput = {},
                        onExplainLastError = {},
                        onFontSizeChange = { fontSizeState = it }
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. Two-finger pinch with vertical offset must NOT scroll and must NOT open selection toolbar
        composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
            val c = center
            down(0, Offset(c.x - 40f, c.y - 20f))
            down(1, Offset(c.x + 40f, c.y - 20f))
            advanceEventTime(16L)
            moveTo(0, Offset(c.x - 70f, c.y + 60f))
            moveTo(1, Offset(c.x + 70f, c.y + 60f))
            advanceEventTime(16L)
            up(0)
            up(1)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("terminal_selection_toolbar").assertDoesNotExist()
        // Scrollback jump pill only appears if scrollbackOffset > 0
        composeRule.onNodeWithText("Jump to live", substring = true).assertDoesNotExist()
        assertTrue("Pinch should change font size > 12", fontSizeState > 12f)

        // Reset HUD before further gestures
        composeRule.mainClock.advanceTimeBy(950L)
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()

        // 2. Single-finger vertical swipe still scrolls scrollback
        composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
            swipeDown(startY = centerY - 80f, endY = centerY + 120f)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Jump to live", substring = true).assertIsDisplayed()

        // 3. Long-press + drag still triggers grid text selection toolbar
        composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
            val start = Offset(left + 160f, top + 140f)
            down(start)
            advanceEventTime(700L)
            moveTo(Offset(left + 280f, top + 140f))
            advanceEventTime(16L)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_selection_toolbar").assertIsDisplayed()

        // Tap dismisses selection cleanly
        composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
            click(center)
        }
        composeRule.mainClock.advanceTimeBy(400L)
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_selection_toolbar").assertDoesNotExist()
    }

    @Test
    fun `resize preserves active cursor row visibility when zoomed-in viewport shrinks rows`() {
        val backend = FakeTerminalBackend()
        val session = backend.sessions.value.first()
        session.resize(cols = 60, rows = 24)
        repeat(22) { idx ->
            session.write("echo line_$idx\r".encodeToByteArray())
        }
        session.write("unique_cursor_draft".encodeToByteArray())
        val screenBefore = session.readScreen()
        assertTrue("Expected cursor on lower half (>= 12), was ${screenBefore.cursorRow}", screenBefore.cursorRow >= 12)

        // Zooming in reduces available cols (60 -> 42) and rows (24 -> 10)
        session.resize(cols = 42, rows = 10)
        val screenAfter = session.readScreen()
        assertTrue(screenAfter.cursorRow in 0 until 10)
        val cursorRowText = screenAfter.visibleRows[screenAfter.cursorRow].map { it.char }.joinToString("")
        assertTrue(
            "Cursor row after shrink-resize should still contain draft/prompt, got: '$cursorRowText'",
            cursorRowText.contains("unique_cursor_draft")
        )
    }

    @Test
    fun `SettingsRepository preserves old 10-22 values, supports 8-28 range, and clamps out-of-range values`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dsFile = File(context.filesDir, "datastore/test_font_zoom_${System.nanoTime()}.preferences_pb")
        dsFile.parentFile?.mkdirs()
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { dsFile }
        )
        val repo = SettingsRepository(context, FakeVault(), dataStore)
        val fontKey = floatPreferencesKey("font_size_sp")

        try {
            // Legacy stored values inside 10..22 remain intact
            for (legacy in listOf(10f, 12f, 16f, 22f)) {
                dataStore.edit { it[fontKey] = legacy }
                assertEquals(legacy, repo.settingsFlow.first().fontSizeSp, 0.001f)
            }

            // Expanded range 8..28 including 0.5 sp steps
            repo.setFontSizeSp(8.5f)
            assertEquals(8.5f, repo.settingsFlow.first().fontSizeSp, 0.001f)
            repo.setFontSizeSp(27.5f)
            assertEquals(27.5f, repo.settingsFlow.first().fontSizeSp, 0.001f)

            // Out-of-bounds values clamped safely on both read and write
            dataStore.edit { it[fontKey] = 4f }
            assertEquals(8.0f, repo.settingsFlow.first().fontSizeSp, 0.001f)
            dataStore.edit { it[fontKey] = 42f }
            assertEquals(28.0f, repo.settingsFlow.first().fontSizeSp, 0.001f)

            repo.setFontSizeSp(2f)
            assertEquals(8.0f, repo.settingsFlow.first().fontSizeSp, 0.001f)
            repo.setFontSizeSp(99f)
            assertEquals(28.0f, repo.settingsFlow.first().fontSizeSp, 0.001f)
        } finally {
            scope.cancel()
            dsFile.delete()
        }
    }

    @Test
    fun `AppearanceSettings slider covers 8-28 sp and stays synchronized with pinch zoom via shared SettingsStore`() {
        val sharedStore = SharedFakeSettingsStore(AppSettings(fontSizeSp = 12f))
        val keyManager = KeyManager(sharedStore)
        val settingsViewModel = SettingsViewModel(sharedStore, keyManager)

        val backend = FakeTerminalBackend()
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
        val aiRepo = AiAssistantRepository(keyManager, transport, sharedStore)
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
        val terminalViewModel = TerminalViewModel(
            terminalBackend = backend,
            aiRepository = aiRepo,
            keyManager = keyManager,
            settingsStore = sharedStore,
            snippetStore = snippetStore,
            promptHistoryStore = historyStore
        )

        var showSettings by mutableStateOf(true)
        composeRule.setContent {
            val settingsUi by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val termUi by terminalViewModel.uiState.collectAsStateWithLifecycle()
            HackerStudioTheme(appTheme = AppThemePreset.HACKER_PRO) {
                Box(modifier = Modifier.size(width = 411.dp, height = 800.dp)) {
                    if (showSettings) {
                        AppearanceSettingsScreen(
                            state = settingsUi,
                            viewModel = settingsViewModel
                        )
                    } else {
                        TerminalConsoleView(
                            sessionTitle = "bash",
                            cwd = FakeTerminalBackend.HOME_DIR,
                            lastExitCode = 0,
                            lastError = null,
                            screenFlow = terminalViewModel.activeScreen,
                            ctrlArmed = false,
                            altArmed = false,
                            fontSizeSp = termUi.settings.fontSizeSp,
                            colorScheme = termUi.settings.terminalColorScheme,
                            aiEnabled = true,
                            onResize = terminalViewModel::resizeActiveSession,
                            onImeTextInput = {},
                            onImeBackspace = {},
                            onImeEnter = {},
                            onSendRawBytes = {},
                            onClearOutput = {},
                            onExplainLastError = {},
                            onFontSizeChange = terminalViewModel::setFontSizeSp
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Slider accepts new bounds (26.5 sp -> 28 sp -> 8.5 sp)
        composeRule.onNodeWithTag("font_size_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(26.5f) }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        assertEquals(26.5f, sharedStore.snapshot.fontSizeSp, 0.001f)
        composeRule.onNodeWithText("26.5sp", substring = true).assertIsDisplayed()

        // Switch to terminal -> pinch down 0.5x (26.5 * 0.5 = 13.25 -> 13.5 sp)
        composeRule.runOnUiThread { showSettings = false }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
            val c = center
            down(0, Offset(c.x - 100f, c.y))
            down(1, Offset(c.x + 100f, c.y))
            advanceEventTime(16L)
            moveTo(0, Offset(c.x - 50f, c.y))
            moveTo(1, Offset(c.x + 50f, c.y))
            advanceEventTime(16L)
            up(0)
            up(1)
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        assertEquals(13.5f, sharedStore.snapshot.fontSizeSp, 0.001f)

        // Return to Settings -> slider label immediately shows 13.5sp
        composeRule.runOnUiThread { showSettings = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("13.5sp", substring = true).assertIsDisplayed()
    }

    @Test
    fun `modified UI files have no raw dp literals and EN-ID string resources keep 100pct parity`() {
        val files = listOf(
            locateRepoFile("src/main/java/com/example/ui/terminal/TerminalConsoleView.kt"),
            locateRepoFile("src/main/java/com/example/settings/AppearanceSettingsScreen.kt"),
            locateRepoFile("src/main/java/com/example/ui/terminal/TerminalScreen.kt")
        )
        val rawDpRegex = Regex("""\d+(\.\d+)?\.dp""")
        files.forEach { file ->
            assertFalse("Raw .dp literal found in ${file.name}", rawDpRegex.containsMatchIn(file.readText()))
        }

        val hardcodedSpRegex = Regex("""fontSize\s*=\s*\d+(\.\d+)?\.sp""")
        assertFalse(
            "Hardcoded .sp fontSize found in TerminalConsoleView.kt",
            hardcodedSpRegex.containsMatchIn(files.first().readText())
        )

        fun loadKeys(file: File): Set<String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.documentElement.childNodes
            val keys = linkedSetOf<String>()
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                if (n.nodeType == org.w3c.dom.Node.ELEMENT_NODE && n.attributes != null) {
                    val nameAttr = n.attributes.getNamedItem("name")
                    if (nameAttr != null) keys.add(nameAttr.nodeValue)
                }
            }
            return keys
        }

        val enKeys = loadKeys(locateRepoFile("src/main/res/values/strings.xml"))
        val idKeys = loadKeys(locateRepoFile("src/main/res/values-in/strings.xml"))
        assertEquals(enKeys, idKeys)
        assertTrue(enKeys.contains("terminal_font_zoom_indicator"))
        assertTrue(enKeys.contains("settings_font_size"))
    }
}
