package com.example

import android.app.NotificationManager
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
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
import com.example.data.LongCommandNotificationConfig
import com.example.data.PromptHistoryItem
import com.example.data.PromptHistoryStore
import com.example.data.SettingsRepository
import com.example.data.SettingsStore
import com.example.data.SnippetStore
import com.example.data.TerminalFontConfig
import com.example.data.VaultStatus
import com.example.settings.SettingsViewModel
import com.example.settings.TerminalSettingsScreen
import com.example.terminal.FakeTerminalBackend
import com.example.terminal.LongCommandNotificationStatus
import com.example.terminal.LongCommandNotifier
import com.example.terminal.NotificationPermissionState
import com.example.terminal.TerminalCell
import com.example.terminal.TerminalCommandEvent
import com.example.terminal.TerminalFontRegistry
import com.example.terminal.TerminalGridPoint
import com.example.terminal.TerminalGridSelection
import com.example.terminal.TerminalOutputExporter
import com.example.terminal.TerminalScreenBuffer
import com.example.terminal.TerminalTokenDetector
import com.example.terminal.TerminalTokenKind
import com.example.ui.terminal.TerminalConsoleView
import com.example.ui.terminal.TerminalScreen
import com.example.ui.terminal.TerminalViewModel
import com.example.ui.terminal.WorkspaceSection
import com.example.ui.terminal.resolveTerminalWindowFlags
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.CyberorgTheme
import com.example.ui.theme.TerminalColorScheme
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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class TerminalEnhancementsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class FakeVault : KeyVault {
        override val vaultStatus: StateFlow<VaultStatus> =
            MutableStateFlow<VaultStatus>(VaultStatus.Ready).asStateFlow()
        override fun putKey(id: String, rawKey: String) {}
        override fun getKey(id: String): String? = null
        override fun removeKey(id: String) {}
        override fun maskKey(rawKey: String): String = "AIza••••"
        override fun resetVault(): Boolean = true
    }

    private class FakeSettingsStore(
        initial: AppSettings = AppSettings()
    ) : SettingsStore {
        private val state = MutableStateFlow(initial)
        override val settingsFlow: Flow<AppSettings> = state.asStateFlow()
        override val vaultStatus: StateFlow<VaultStatus> =
            MutableStateFlow<VaultStatus>(VaultStatus.Ready).asStateFlow()

        val snapshot: AppSettings get() = state.value

        override suspend fun setAppTheme(theme: AppThemePreset) = state.update { it.copy(appTheme = theme) }
        override suspend fun setReduceEffects(enabled: Boolean) = state.update { it.copy(reduceEffectsOverride = enabled) }
        override suspend fun setAppLanguage(language: AppLanguage) = state.update { it.copy(appLanguage = language) }
        override suspend fun setOnboardingCompleted(completed: Boolean) = state.update { it.copy(onboardingCompleted = completed) }
        override suspend fun setFontSizeSp(sizeSp: Float) =
            state.update { it.copy(fontSizeSp = TerminalFontConfig.normalizeFontSizeSp(sizeSp)) }
        override suspend fun setExtraKeysLayout(layout: ExtraKeysLayout) = state.update { it.copy(extraKeysLayout = layout) }
        override suspend fun setFullscreenTerminal(enabled: Boolean) = state.update { it.copy(fullscreenTerminal = enabled) }
        override suspend fun setKeepScreenOn(enabled: Boolean) = state.update { it.copy(keepScreenOn = enabled) }
        override suspend fun setNotifyLongCommands(enabled: Boolean) = state.update { it.copy(notifyLongCommands = enabled) }
        override suspend fun setLongCommandThresholdSeconds(seconds: Int) =
            state.update { it.copy(longCommandThresholdSeconds = LongCommandNotificationConfig.normalizeThresholdSeconds(seconds)) }
        override suspend fun setTerminalFontId(fontId: String) =
            state.update { it.copy(terminalFontId = TerminalFontRegistry.fromId(fontId).id) }
        override suspend fun setAiEnabled(enabled: Boolean) = state.update { it.copy(aiEnabled = enabled) }
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

    private fun buildBufferFromLines(lines: List<String>, cols: Int = 60, rows: Int = 10): TerminalScreenBuffer {
        val visible = List(rows) { r ->
            val text = lines.getOrElse(r) { "" }
            List(cols) { c ->
                val ch = text.getOrNull(c) ?: ' '
                TerminalCell(char = ch)
            }
        }
        return TerminalScreenBuffer(
            cols = cols,
            rows = rows,
            cursorRow = 0,
            cursorCol = 0,
            visibleRows = visible,
            scrollbackRows = emptyList()
        )
    }

    // --- Feature 3 Tests: Token detection & Tappable sheet ---

    @Test
    fun `detectTokens matches all supported URL schemes, IPv4, paths, and adjacent tokens linearly`() {
        val line = "https://example.org/a http://localhost:8080 ftp://10.0.0.2/f ssh://u@h git://host/r.git 192.168.1.42 /etc/hosts ~/notes.md ./run.sh ../cfg"
        val tokens = TerminalTokenDetector.detectTokens(line)

        val urls = tokens.filter { it.kind == TerminalTokenKind.URL }.map { it.text }
        assertEquals(
            listOf(
                "https://example.org/a",
                "http://localhost:8080",
                "ftp://10.0.0.2/f",
                "ssh://u@h",
                "git://host/r.git"
            ),
            urls
        )

        val ips = tokens.filter { it.kind == TerminalTokenKind.IPV4 }.map { it.text }
        assertEquals(listOf("192.168.1.42"), ips)

        val paths = tokens.filter { it.kind == TerminalTokenKind.FILE_PATH }.map { it.text }
        assertEquals(listOf("/etc/hosts", "~/notes.md", "./run.sh", "../cfg"), paths)

        // Reject invalid IPv4 & bare / or //comments
        val noisy = "256.1.1.1 01.2.3.4 / //comment 127.0.0.1,/var/log/syslog"
        val noisyTokens = TerminalTokenDetector.detectTokens(noisy)
        assertEquals(listOf("127.0.0.1", "/var/log/syslog"), noisyTokens.map { it.text })
    }

    @Test
    fun `detectTokens enforces length bounds and worst-case adversarial 2500 chars completes well under 50ms`() {
        val longToken = "https://example.com/" + "a".repeat(700)
        val parsedLong = TerminalTokenDetector.detectTokens(longToken)
        assertEquals(1, parsedLong.size)
        assertEquals(TerminalTokenDetector.MAX_TOKEN_LENGTH, parsedLong.first().text.length)

        val worstCase = buildString {
            repeat(500) { append("h:/012.345/") }
        }
        val startNs = System.nanoTime()
        val result = TerminalTokenDetector.detectTokens(worstCase)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        assertTrue("Worst-case detectTokens took ${elapsedMs}ms (must be < 50ms)", elapsedMs < 50.0)
        assertNotNull(result)
    }

    @Test
    fun `tapping URL shows action sheet requiring confirmation before open, tapping path inserts draft, tapping outside does not open sheet`() {
        val insertedDrafts = mutableListOf<String>()
        val copiedTexts = mutableListOf<String>()
        val lineUrl = "Visit https://termux.dev/docs now"
        val linePath = "Open ~/notes.md please"
        val buf = buildBufferFromLines(listOf(lineUrl, linePath, ""), cols = 48, rows = 8)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val density = composeRule.density
        val fontSizePx = with(density) { 12f * fontScale * density.density }
        val metrics = TerminalFontRegistry.computeCellMetrics(
            textPaint = paint,
            fontSizePx = fontSizePx,
            typeface = TerminalFontRegistry.resolveTypeface(TerminalFontRegistry.DEFAULT_FONT_ID, context)
        )

        composeRule.setContent {
            CyberorgTheme(appTheme = AppThemePreset.HACKER_PRO) {
                Box(modifier = Modifier.size(380.dp, 280.dp)) {
                    TerminalConsoleView(
                        sessionTitle = "bash-1",
                        cwd = FakeTerminalBackend.HOME_DIR,
                        lastExitCode = 0,
                        lastError = null,
                        screenBuffer = buf,
                        screenVersion = 1L,
                        ctrlArmed = false,
                        altArmed = false,
                        fontSizeSp = 12f,
                        colorScheme = TerminalColorScheme.HACKER_PRO,
                        aiEnabled = false,
                        onResize = { _, _ -> },
                        onImeTextInput = {},
                        onImeBackspace = {},
                        onImeEnter = {},
                        onSendRawBytes = {},
                        onClearOutput = {},
                        onExplainLastError = {},
                        onCopySelection = { copiedTexts.add(it) },
                        onInsertToken = { insertedDrafts.add(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Tap outside token on row 2 (empty line) -> must NOT open token sheet
        composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
            click(Offset(metrics.cellWidthPx * 2f, metrics.cellHeightPx * 2.5f))
        }
        composeRule.mainClock.advanceTimeBy(600L)
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("terminal_token_action_sheet").assertCountEquals(0)

        // Tap inside URL token on row 0 ("Visit https://...")
        composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
            click(Offset(metrics.cellWidthPx * 10.5f, metrics.cellHeightPx * 0.5f))
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_token_action_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("token_sheet_address_text").assertIsDisplayed()
        composeRule.onAllNodesWithTag("token_open_url_confirm_box").assertCountEquals(0)

        // Click Open -> shows confirmation box first rather than auto-opening
        composeRule.onNodeWithTag("token_action_open_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("token_open_url_confirm_box").assertIsDisplayed()
        composeRule.onNodeWithTag("token_sheet_dismiss_button").performClick()
        composeRule.waitForIdle()

        // Tap inside FILE_PATH token on row 1 ("Open ~/notes.md please") -> Insert into terminal
        composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
            click(Offset(metrics.cellWidthPx * 8.5f, metrics.cellHeightPx * 1.5f))
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_token_action_sheet").assertIsDisplayed()
        composeRule.onAllNodesWithTag("token_action_open_button").assertCountEquals(0)
        composeRule.onNodeWithTag("token_action_insert_button").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("~/notes.md"), insertedDrafts)
    }

    // --- Feature 5 Tests: Fullscreen & Keep Screen On ---

    @Test
    fun `fullscreen and keepScreenOn default false, migrate safely from legacy DataStore, and resolve window flags cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dsFile = File(context.filesDir, "enhancements_legacy_migration_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { dsFile }
        )
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("terminal_font_id")] = "legacy_nonexistent_font"
        }

        val repo = SettingsRepository(context = context, keyVault = FakeVault(), customDataStore = dataStore)
        val migrated = repo.settingsFlow.first()
        assertFalse(migrated.fullscreenTerminal)
        assertFalse(migrated.keepScreenOn)
        assertFalse(migrated.notifyLongCommands)
        assertEquals(LongCommandNotificationConfig.DEFAULT_THRESHOLD_SECONDS, migrated.longCommandThresholdSeconds)
        assertEquals(TerminalFontRegistry.DEFAULT_FONT_ID, migrated.terminalFontId)

        repo.setFullscreenTerminal(true)
        repo.setKeepScreenOn(true)
        repo.setNotifyLongCommands(true)
        repo.setLongCommandThresholdSeconds(999)
        repo.setTerminalFontId(TerminalFontRegistry.SYSTEM_MONOSPACE_ID)

        val updated = repo.settingsFlow.first()
        assertTrue(updated.fullscreenTerminal)
        assertTrue(updated.keepScreenOn)
        assertTrue(updated.notifyLongCommands)
        assertEquals(LongCommandNotificationConfig.MAX_THRESHOLD_SECONDS, updated.longCommandThresholdSeconds)
        assertEquals(TerminalFontRegistry.SYSTEM_MONOSPACE_ID, updated.terminalFontId)

        // Pure resolver check
        val activeFlags = resolveTerminalWindowFlags(
            isTerminalScreenActive = true,
            isAppForeground = true,
            fullscreenEnabled = true,
            keepScreenOnEnabled = true
        )
        assertTrue(activeFlags.applyFullscreen)
        assertTrue(activeFlags.applyKeepScreenOn)

        val switchedAway = resolveTerminalWindowFlags(
            isTerminalScreenActive = false,
            isAppForeground = true,
            fullscreenEnabled = true,
            keepScreenOnEnabled = true
        )
        assertFalse(switchedAway.applyFullscreen)
        assertFalse(switchedAway.applyKeepScreenOn)

        val backgrounded = resolveTerminalWindowFlags(
            isTerminalScreenActive = true,
            isAppForeground = false,
            fullscreenEnabled = true,
            keepScreenOnEnabled = true
        )
        assertFalse(backgrounded.applyFullscreen)
        assertFalse(backgrounded.applyKeepScreenOn)
    }

    @Test
    fun `switching workspace away from Terminal restores keepScreenOn window flag`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val settingsStore = FakeSettingsStore(
            AppSettings(fullscreenTerminal = true, keepScreenOn = true, aiEnabled = false)
        )
        val backend = FakeTerminalBackend()
        val keyManager = KeyManager(settingsStore) { _, _ -> GeminiCallResult.AuthFailure("stub", 401) }
        val emptyRepo = object : SnippetStore {
            override val snippetsFlow: Flow<List<CommandSnippet>> = MutableStateFlow(emptyList())
            override suspend fun saveSnippet(title: String, command: String, tags: List<String>, existingId: String?) =
                CommandSnippet(id = existingId ?: "s1", title = title, command = command)
            override suspend fun deleteSnippet(id: String) {}
            override fun exportToJson(snippets: List<CommandSnippet>): String = "[]"
            override suspend fun importFromJson(rawJson: String): Result<Int> = Result.success(0)
        }
        val emptyHistory = object : PromptHistoryStore {
            override val historyFlow: Flow<List<PromptHistoryItem>> = MutableStateFlow(emptyList())
            override suspend fun recordPrompt(prompt: String) {}
            override suspend fun clearAll() {}
        }
        val noopTransport = object : GeminiTransport {
            override suspend fun generate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ) = GeminiCallResult.AuthFailure("unused", 401)

            override fun streamGenerate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ) = flow<GeminiStreamEvent> {}
        }
        val vm = TerminalViewModel(
            terminalBackend = backend,
            aiRepository = AiAssistantRepository(keyManager, noopTransport, settingsStore),
            keyManager = keyManager,
            settingsStore = settingsStore,
            snippetStore = emptyRepo,
            promptHistoryStore = emptyHistory
        )

        try {
            composeRule.setContent {
                val state by vm.uiState.collectAsState()
                CyberorgTheme(appTheme = state.settings.appTheme) {
                    TerminalScreen(viewModel = vm, onNavigateToSettings = {})
                }
            }
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()

            val windowFlagsOnTerminal = composeRule.activity.window.attributes.flags
            assertTrue(
                "FLAG_KEEP_SCREEN_ON should be set on active Terminal screen",
                (windowFlagsOnTerminal and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
            )

            composeRule.runOnUiThread {
                vm.selectWorkspaceSection(WorkspaceSection.FILES)
            }
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()

            val windowFlagsOnFiles = composeRule.activity.window.attributes.flags
            assertEquals(
                "FLAG_KEEP_SCREEN_ON must clear when switching to non-Terminal section",
                0,
                windowFlagsOnFiles and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        } finally {
            scope.cancel()
        }
    }

    // --- Feature 11 Tests: Long Command Notifications ---

    @Test
    fun `shouldNotify, command preview formatting, channel importance, and FakeTerminalBackend duration tracking`() = runBlocking {
        assertFalse(LongCommandNotifier.shouldNotify(20_000L, 15_000L, isForeground = true, enabled = true))
        assertFalse(LongCommandNotifier.shouldNotify(20_000L, 15_000L, isForeground = false, enabled = false))
        assertFalse(LongCommandNotifier.shouldNotify(15_000L, 15_000L, isForeground = false, enabled = true))
        assertTrue(LongCommandNotifier.shouldNotify(15_001L, 15_000L, isForeground = false, enabled = true))
        assertFalse(LongCommandNotifier.shouldNotify(0L, 15_000L, isForeground = false, enabled = true))

        val longCmd = "  echo   hello\nworld   " + "x".repeat(100)
        val preview = LongCommandNotifier.formatCommandPreview(longCmd)
        assertTrue(preview.length <= LongCommandNotifier.MAX_COMMAND_PREVIEW_CHARS)
        assertFalse(preview.contains('\n'))

        assertEquals(
            NotificationPermissionState.NOT_REQUIRED,
            LongCommandNotifier.resolvePermissionState(Build.VERSION_CODES.S, hasPostNotificationsPermission = false)
        )
        assertEquals(
            NotificationPermissionState.NEEDS_PERMISSION,
            LongCommandNotifier.resolvePermissionState(Build.VERSION_CODES.TIRAMISU, hasPostNotificationsPermission = false)
        )
        assertEquals(
            NotificationPermissionState.GRANTED,
            LongCommandNotifier.resolvePermissionState(Build.VERSION_CODES.TIRAMISU, hasPostNotificationsPermission = true)
        )
        assertEquals(
            LongCommandNotificationStatus.NEEDS_PERMISSION,
            LongCommandNotifier.resolveSettingStatus(enabled = true, permissionState = NotificationPermissionState.NEEDS_PERMISSION)
        )

        // Ensure channel created with low importance
        val context = ApplicationProvider.getApplicationContext<Context>()
        LongCommandNotifier.ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(LongCommandNotifier.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)

        // Verify FakeTerminalBackend emits Started and Finished with clock delta
        var fakeTime = 1_000L
        val backend = FakeTerminalBackend(clockMillis = {
            fakeTime += 18_000L
            fakeTime
        })
        val session = backend.sessions.value.first()
        val captured = mutableListOf<TerminalCommandEvent>()
        val collectJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            session.commandEvents.take(2).toList(captured)
        }
        session.write("pwd\r".encodeToByteArray())
        kotlinx.coroutines.withTimeout(5_000L) {
            collectJob.join()
        }
        assertEquals(2, captured.size)
        val finished = captured[1] as TerminalCommandEvent.Finished
        assertEquals("pwd", finished.command)
        assertEquals(0, finished.exitCode)
        assertEquals(18_000L, finished.durationMs)
    }

    // --- Feature 12 Tests: Share Output & Cache Cleanup ---

    @Test
    fun `export output extracts selection or visible screen, caps bitmap at most 4096px, and cleans cache files`() {
        val buf = buildBufferFromLines(
            listOf("user@termux:~$ pwd   ", "/data/data/com.termux/files/home   ", "done"),
            cols = 40,
            rows = 6
        )
        assertEquals(
            "user@termux:~$ pwd\n/data/data/com.termux/files/home\ndone",
            TerminalOutputExporter.buildExportText(buf, selection = null)
        )
        val sel = TerminalGridSelection(
            anchor = TerminalGridPoint(1, 0),
            focus = TerminalGridPoint(1, 9)
        )
        assertEquals("/data/data", TerminalOutputExporter.buildExportText(buf, selection = sel))

        val hugeBuf = buildBufferFromLines(listOf("wide line"), cols = 240, rows = 120)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bmp = TerminalOutputExporter.renderTerminalBufferToBitmap(
            buffer = hugeBuf,
            colorScheme = TerminalColorScheme.CYBERPUNK,
            typeface = Typeface.MONOSPACE,
            fontSizePx = 32f
        )
        assertTrue(bmp.width <= TerminalOutputExporter.MAX_EXPORT_IMAGE_DIMENSION_PX)
        assertTrue(bmp.height <= TerminalOutputExporter.MAX_EXPORT_IMAGE_DIMENSION_PX)

        // Cache write & cleanup test
        val cacheRoot = File(context.cacheDir, "export_cleanup_test_${System.nanoTime()}").apply { mkdirs() }
        val firstFile = TerminalOutputExporter.writeBitmapToCachePng(cacheRoot, bmp, nowMillis = 1000L)
        assertTrue(firstFile.exists())
        val secondFile = TerminalOutputExporter.writeBitmapToCachePng(cacheRoot, bmp, nowMillis = 2000L)
        assertFalse("Writing a new PNG should clean up stale export files", firstFile.exists())
        assertTrue(secondFile.exists())

        val cleaned = TerminalOutputExporter.cleanupExportCache(
            cacheDir = cacheRoot,
            nowMillis = 999_999L,
            maxAgeMs = 1000L
        )
        assertEquals(1, cleaned)
        assertFalse(secondFile.exists())
        bmp.recycle()
        cacheRoot.deleteRecursively()
    }

    @Test
    fun `share output sheet shows non-blocking sensitive data warning and both Text & PNG buttons`() {
        val buf = buildBufferFromLines(listOf("echo ok"), cols = 30, rows = 6)
        composeRule.setContent {
            CyberorgTheme(appTheme = AppThemePreset.HACKER_PRO) {
                Box(modifier = Modifier.size(360.dp, 240.dp)) {
                    TerminalConsoleView(
                        sessionTitle = "bash-1",
                        cwd = FakeTerminalBackend.HOME_DIR,
                        lastExitCode = 0,
                        lastError = null,
                        screenBuffer = buf,
                        screenVersion = 1L,
                        ctrlArmed = false,
                        altArmed = false,
                        fontSizeSp = 12f,
                        colorScheme = TerminalColorScheme.HACKER_PRO,
                        aiEnabled = false,
                        onResize = { _, _ -> },
                        onImeTextInput = {},
                        onImeBackspace = {},
                        onImeEnter = {},
                        onSendRawBytes = {},
                        onClearOutput = {},
                        onExplainLastError = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("share_output_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("terminal_share_output_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("share_output_sensitive_warning").assertIsDisplayed()
        composeRule.onNodeWithTag("share_output_text_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("share_output_image_btn").assertIsDisplayed()
    }

    // --- Feature 13 Tests: Terminal Font Choice & Settings UI ---

    @Test
    fun `font registry defaults to jetbrains_mono, falls back safely on unknown id, and updates SettingsViewModel`() {
        assertEquals(TerminalFontRegistry.DEFAULT_FONT_ID, TerminalFontRegistry.fromId(null).id)
        assertEquals(TerminalFontRegistry.DEFAULT_FONT_ID, TerminalFontRegistry.fromId("unknown-font").id)
        assertEquals(TerminalFontRegistry.SYSTEM_MONOSPACE_ID, TerminalFontRegistry.fromId("system_monospace").id)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val mSmall = TerminalFontRegistry.computeCellMetrics(paint, 14f, Typeface.MONOSPACE)
        val mLarge = TerminalFontRegistry.computeCellMetrics(paint, 28f, Typeface.MONOSPACE)
        assertTrue(mLarge.cellHeightPx > mSmall.cellHeightPx)
        assertNotEquals(mSmall, mLarge)

        val store = FakeSettingsStore()
        val keyManager = KeyManager(store) { _, _ -> GeminiCallResult.AuthFailure("unused", 401) }
        val settingsVm = SettingsViewModel(store, keyManager)
        val context = ApplicationProvider.getApplicationContext<Context>()

        composeRule.setContent {
            val state by settingsVm.uiState.collectAsState()
            val existingRegistryOwner = androidx.activity.compose.LocalActivityResultRegistryOwner.current
            CompositionLocalProvider(
                LocalContext provides context,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides existingRegistryOwner!!
            ) {
                CyberorgTheme(appTheme = state.settings.appTheme) {
                    TerminalSettingsScreen(state = state, viewModel = settingsVm)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("terminal_font_preview_card").assertIsDisplayed()
        composeRule.onNodeWithTag("terminal_font_system_monospace").performClick()
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TerminalFontRegistry.SYSTEM_MONOSPACE_ID, store.snapshot.terminalFontId)
    }

    // --- Resource Parity & Code Hygiene ---

    @Test
    fun `strings xml has exact EN and ID key parity and no hardcoded control dp or sp literals in new UI files`() {
        val enFile = locateRepoFile("src/main/res/values/strings.xml")
        val idFile = locateRepoFile("src/main/res/values-in/strings.xml")

        fun extractKeys(file: File): Set<String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            val keys = LinkedHashSet<String>(nodes.length)
            for (i in 0 until nodes.length) {
                val name = nodes.item(i).attributes.getNamedItem("name")?.nodeValue
                if (!name.isNullOrBlank()) keys.add(name)
            }
            return keys
        }

        val enKeys = extractKeys(enFile)
        val idKeys = extractKeys(idFile)
        assertEquals("Missing keys in values-in/strings.xml", emptySet<String>(), enKeys - idKeys)
        assertEquals("Extra keys in values-in/strings.xml", emptySet<String>(), idKeys - enKeys)

        val uiFiles = listOf(
            locateRepoFile("src/main/java/com/example/ui/terminal/TerminalWindowEffects.kt"),
            locateRepoFile("src/main/java/com/example/ui/terminal/TerminalActionSheets.kt"),
            locateRepoFile("src/main/java/com/example/settings/TerminalSettingsScreen.kt"),
            locateRepoFile("src/main/java/com/example/ui/terminal/TerminalConsoleView.kt")
        )
        val hardcodedSp = Regex("""fontSize\s*=\s*\d+(\.\d+)?\.sp""")
        val controlDp = Regex("""\b(height|size|heightIn|widthIn|defaultMinSize)\s*\(\s*[^)]*\d+(\.\d+)?\.dp""")
        uiFiles.forEach { file ->
            val text = file.readText()
            assertNull("Hardcoded fontSize found in ${file.name}", hardcodedSp.find(text))
            assertNull("Hardcoded control dp found in ${file.name}", controlDp.find(text))
        }
    }
}
