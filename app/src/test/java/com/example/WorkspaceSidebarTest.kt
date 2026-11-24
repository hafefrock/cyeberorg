package com.example

import android.content.Context
import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiAssistantRepository
import com.example.ai.GeminiCallResult
import com.example.ai.GeminiContent
import com.example.ai.GeminiStreamEvent
import com.example.ai.GeminiTransport
import com.example.ai.KeyManager
import com.example.ai.KeyManagerSnapshot
import com.example.ai.KeyHealthStatus
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
import com.example.settings.SettingsScreen
import com.example.settings.SettingsViewModel
import com.example.terminal.FakeTerminalBackend
import com.example.terminal.TerminalBackend
import com.example.terminal.TerminalSessionHandle
import com.example.ui.terminal.SessionTabItem
import com.example.ui.terminal.SessionTabsBar
import com.example.ui.terminal.SplitMode
import com.example.ui.terminal.TerminalScreen
import com.example.ui.terminal.TerminalViewModel
import com.example.ui.terminal.WorkspaceSection
import com.example.ui.terminal.WorkspaceSidebarDrawer
import com.example.ui.terminal.WorkspaceStatusBar
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.HackerStudioTheme
import javax.xml.parsers.DocumentBuilderFactory
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xhdpi")
class WorkspaceSidebarTest {

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
        var resizeCount = 0
            private set

        private inner class SpyHandle(
            private val inner: TerminalSessionHandle
        ) : TerminalSessionHandle by inner {
            override fun resize(cols: Int, rows: Int) {
                resizeCount++
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
        initialTheme: AppThemePreset = AppThemePreset.HACKER_PRO,
        aiEnabled: Boolean = true
    ): Triple<TerminalViewModel, SpyTerminalBackend, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val backend = SpyTerminalBackend(scope)
        val settingsStore = FakeSettingsStore(
            AppSettings(
                appTheme = initialTheme,
                aiEnabled = aiEnabled,
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

    @Test
    fun `narrow screen omits bottom bar, opens and closes sidebar without triggering terminal resize in BOTH themes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (vm, backend, scope) = createTestHarness(initialTheme = AppThemePreset.HACKER_PRO)
        try {
            composeRule.setContent {
                val state by vm.uiState.collectAsState()
                HackerStudioTheme(appTheme = state.settings.appTheme) {
                    TerminalScreen(viewModel = vm, onNavigateToSettings = {})
                }
            }
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()

            AppThemePreset.entries.forEach { preset ->
                vm.setAppTheme(preset)
                shadowOf(Looper.getMainLooper()).idle()
                composeRule.waitForIdle()

                // No bottom bar, no command palette search icon, no key pill on top bar in narrow screen
                composeRule.onAllNodesWithTag("workspace_bottom_bar").assertCountEquals(0)
                composeRule.onAllNodesWithTag("bottom_nav_terminal").assertCountEquals(0)
                composeRule.onAllNodesWithTag("workspace_navigation_rail").assertCountEquals(0)
                composeRule.onAllNodesWithTag("open_command_palette_button").assertCountEquals(0)
                composeRule.onAllNodesWithTag("active_key_pill").assertCountEquals(0)
                composeRule.onNodeWithTag("terminal_cell_grid").assertIsDisplayed()
                composeRule.onNodeWithTag("open_sidebar_menu_button").assertIsDisplayed()
                composeRule.onNodeWithTag("add_session_button").assertIsDisplayed()
                composeRule.onNodeWithTag("toggle_ai_panel_button").assertIsDisplayed()

                val menuNodeClosed = composeRule.onNodeWithTag("open_sidebar_menu_button").fetchSemanticsNode()
                assertEquals(
                    context.getString(R.string.sidebar_state_closed),
                    menuNodeClosed.config[SemanticsProperties.StateDescription]
                )

                val density = composeRule.density
                assertTrue(with(density) { menuNodeClosed.touchBoundsInRoot.width.toDp().value } >= 47.9f)
                assertTrue(with(density) { menuNodeClosed.touchBoundsInRoot.height.toDp().value } >= 47.9f)

                val gridBoundsBefore = composeRule.onNodeWithTag("terminal_cell_grid").fetchSemanticsNode().boundsInRoot
                val extraKeysBounds = composeRule.onNodeWithTag("extra_key_esc").fetchSemanticsNode().boundsInRoot
                val gapDp = with(density) { (extraKeysBounds.top - gridBoundsBefore.bottom).toDp().value }
                assertTrue("${preset.name} unexpected bottom gap below terminal: ${gapDp}dp", gapDp <= 24.0f)

                val resizeBaseline = backend.resizeCount

                // Open sidebar via menu button
                composeRule.onNodeWithTag("open_sidebar_menu_button").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("workspace_sidebar").assertIsDisplayed()
                composeRule.onNodeWithTag("workspace_sidebar_scrim").assertIsDisplayed()
                assertEquals(resizeBaseline, backend.resizeCount)

                val menuNodeOpen = composeRule.onNodeWithTag("open_sidebar_menu_button").fetchSemanticsNode()
                assertEquals(
                    context.getString(R.string.sidebar_state_open),
                    menuNodeOpen.config[SemanticsProperties.StateDescription]
                )

                // Close sidebar via scrim tap on the exposed right side of the screen
                composeRule.onNodeWithTag("workspace_sidebar_scrim").performTouchInput {
                    click(Offset(right - 20f, centerY))
                }
                composeRule.waitForIdle()
                composeRule.onAllNodesWithTag("workspace_sidebar").assertCountEquals(0)
                assertEquals(resizeBaseline, backend.resizeCount)

                // Vertical scroll and long-press inside terminal do NOT falsely trigger edge sidebar
                composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
                    swipeUp(startY = bottom - 40f, endY = top + 40f)
                }
                composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
                    longClick(Offset(8f, centerY))
                }
                composeRule.waitForIdle()
                composeRule.onAllNodesWithTag("workspace_sidebar").assertCountEquals(0)

                // Open via left-edge swipe gesture (< 20dp from left) and close via swipe left
                composeRule.onNodeWithTag("terminal_cell_grid").performTouchInput {
                    swipeRight(startX = 4f, endX = 160f)
                }
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("workspace_sidebar").assertIsDisplayed()
                assertEquals(resizeBaseline, backend.resizeCount)

                composeRule.onNodeWithTag("workspace_sidebar").performTouchInput {
                    swipeLeft(startX = right - 20f, endX = 0f)
                }
                composeRule.waitForIdle()
                composeRule.onAllNodesWithTag("workspace_sidebar").assertCountEquals(0)
                assertEquals(resizeBaseline, backend.resizeCount)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `selecting sidebar items switches sections, closes sidebar, and Settings triggers callback`() {
        val (vm, _, scope) = createTestHarness(initialTheme = AppThemePreset.HACKER_PRO, aiEnabled = true)
        var settingsOpened = 0
        try {
            composeRule.setContent {
                val state by vm.uiState.collectAsState()
                HackerStudioTheme(appTheme = state.settings.appTheme) {
                    TerminalScreen(
                        viewModel = vm,
                        onNavigateToSettings = { settingsOpened++ }
                    )
                }
            }
            composeRule.waitForIdle()

            AppThemePreset.entries.forEachIndexed { idx, preset ->
                vm.setAppTheme(preset)
                vm.selectWorkspaceSection(WorkspaceSection.TERMINAL)
                shadowOf(Looper.getMainLooper()).idle()
                composeRule.waitForIdle()

                // Open sidebar and verify all 5 items in exact order
                composeRule.onNodeWithTag("open_sidebar_menu_button").performClick()
                composeRule.waitForIdle()
                val tags = listOf(
                    "sidebar_terminal",
                    "sidebar_files",
                    "sidebar_ai",
                    "sidebar_snippets",
                    "sidebar_settings"
                )
                val tops = tags.map { tag ->
                    composeRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
                }
                assertTrue("${preset.name} sidebar items must be top-to-bottom: $tops", tops.zipWithNext().all { (a, b) -> a < b })
                composeRule.onNodeWithTag("sidebar_terminal").assertIsSelected()

                // Select Scriptbook -> switches section & closes sidebar
                composeRule.onNodeWithTag("sidebar_snippets").performClick()
                composeRule.waitForIdle()
                assertEquals(WorkspaceSection.SNIPPETS, vm.uiState.value.activeSection)
                assertFalse(vm.uiState.value.isSidebarOpen)
                composeRule.onAllNodesWithTag("workspace_sidebar").assertCountEquals(0)

                // Re-open sidebar and tap Settings -> closes sidebar & calls onNavigateToSettings
                composeRule.onNodeWithTag("open_sidebar_menu_button").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("sidebar_settings").performClick()
                composeRule.waitForIdle()
                assertEquals(idx + 1, settingsOpened)
                assertFalse(vm.uiState.value.isSidebarOpen)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Back press closes sidebar first, then AI sheet, then returns to Terminal`() {
        val (vm, _, scope) = createTestHarness(initialTheme = AppThemePreset.CYBERPUNK)
        var backDispatcher: OnBackPressedDispatcher? = null
        try {
            composeRule.setContent {
                val state by vm.uiState.collectAsState()
                backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                HackerStudioTheme(appTheme = state.settings.appTheme) {
                    TerminalScreen(viewModel = vm, onNavigateToSettings = {})
                }
            }
            composeRule.waitForIdle()

            // Phase 1: Sidebar open on Terminal -> Back #1 closes sidebar
            vm.openSidebar()
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            assertTrue(vm.uiState.value.isSidebarOpen)

            composeRule.runOnUiThread { backDispatcher?.onBackPressed() }
            composeRule.waitForIdle()
            assertFalse(vm.uiState.value.isSidebarOpen)
            assertEquals(WorkspaceSection.TERMINAL, vm.uiState.value.activeSection)

            // Phase 2: AI bottom sheet open -> Back #2 closes AI sheet
            vm.toggleAiPanel(forceOpen = true)
            composeRule.waitForIdle()
            assertTrue(vm.uiState.value.isAiPanelOpen)
            composeRule.runOnUiThread { backDispatcher?.onBackPressed() }
            composeRule.waitForIdle()
            assertFalse(vm.uiState.value.isAiPanelOpen)

            // Phase 3: Non-Terminal tab with sidebar open -> Back #3 closes sidebar, Back #4 returns to Terminal
            vm.selectWorkspaceSection(WorkspaceSection.FILES)
            vm.openSidebar()
            composeRule.waitForIdle()
            assertTrue(vm.uiState.value.isSidebarOpen)
            assertEquals(WorkspaceSection.FILES, vm.uiState.value.activeSection)

            composeRule.runOnUiThread { backDispatcher?.onBackPressed() }
            composeRule.waitForIdle()
            assertFalse(vm.uiState.value.isSidebarOpen)
            assertEquals(WorkspaceSection.FILES, vm.uiState.value.activeSection)

            composeRule.runOnUiThread { backDispatcher?.onBackPressed() }
            composeRule.waitForIdle()
            assertEquals(WorkspaceSection.TERMINAL, vm.uiState.value.activeSection)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sidebar and AI bottom sheet are mutually exclusive and sidebar state survives ViewModel retention on rotation`() {
        val (vm, _, scope) = createTestHarness(initialTheme = AppThemePreset.HACKER_PRO)
        val collectorJob = scope.launch { vm.uiState.collect {} }
        try {
            val store = ViewModelStore()
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = vm as T
            }
            val retainedBefore = ViewModelProvider(store, factory)[TerminalViewModel::class.java]

            retainedBefore.toggleAiPanel(forceOpen = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(retainedBefore.uiState.value.isAiPanelOpen)
            assertFalse(retainedBefore.uiState.value.isSidebarOpen)

            // Opening sidebar closes AI sheet
            retainedBefore.openSidebar()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(retainedBefore.uiState.value.isSidebarOpen)
            assertFalse(retainedBefore.uiState.value.isAiPanelOpen)

            // Opening AI sheet closes sidebar
            retainedBefore.toggleAiPanel(forceOpen = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(retainedBefore.uiState.value.isAiPanelOpen)
            assertFalse(retainedBefore.uiState.value.isSidebarOpen)

            // Re-open sidebar and simulate post-rotation UI lookup from retained ViewModelStore
            retainedBefore.openSidebar()
            shadowOf(Looper.getMainLooper()).idle()
            val retainedAfterRotation = ViewModelProvider(store, factory)[TerminalViewModel::class.java]
            assertTrue(retainedAfterRotation.uiState.value.isSidebarOpen)

            composeRule.setContent {
                val state by retainedAfterRotation.uiState.collectAsState()
                HackerStudioTheme(appTheme = state.settings.appTheme) {
                    TerminalScreen(viewModel = retainedAfterRotation, onNavigateToSettings = {})
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("workspace_sidebar").assertIsDisplayed()
            store.clear()
        } finally {
            collectorJob.cancel()
            scope.cancel()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w800dp-h600dp-xhdpi")
    fun `wide screen at least 600dp uses permanent NavigationRail and hides menu button in BOTH themes`() {
        val (vm, _, scope) = createTestHarness(initialTheme = AppThemePreset.HACKER_PRO)
        try {
            composeRule.setContent {
                val state by vm.uiState.collectAsState()
                HackerStudioTheme(appTheme = state.settings.appTheme) {
                    TerminalScreen(viewModel = vm, onNavigateToSettings = {})
                }
            }
            composeRule.waitForIdle()

            AppThemePreset.entries.forEach { preset ->
                vm.setAppTheme(preset)
                shadowOf(Looper.getMainLooper()).idle()
                composeRule.waitForIdle()

                composeRule.onNodeWithTag("workspace_navigation_rail").assertIsDisplayed()
                composeRule.onNodeWithTag("rail_settings").assertIsDisplayed()
                composeRule.onNodeWithTag("toggle_split_mode_button").assertIsDisplayed()
                composeRule.onAllNodesWithTag("open_command_palette_button").assertCountEquals(0)
                composeRule.onAllNodesWithTag("active_key_pill").assertCountEquals(0)
                composeRule.onAllNodesWithTag("open_sidebar_menu_button").assertCountEquals(0)
                composeRule.onAllNodesWithTag("workspace_sidebar").assertCountEquals(0)
                composeRule.onAllNodesWithTag("workspace_bottom_bar").assertCountEquals(0)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `SessionTabsBar at 320dp and fontScale 1_3 omits search and pill, keeps tabs unclipped with 48dp touch targets`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val idCtx = LocaleHelper.createLocalizedContext(base, AppLanguage.INDONESIAN)
        val density = composeRule.density
        var currentPreset by mutableStateOf(AppThemePreset.HACKER_PRO)
        var showDrawer by mutableStateOf(false)

        composeRule.setContent {
            val baseDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity, fontScale = 1.3f)
            ) {
                HackerStudioTheme(appTheme = currentPreset) {
                    Box(modifier = Modifier.width(320.dp)) {
                        SessionTabsBar(
                            sessions = listOf(
                                SessionTabItem(id = "s1", title = "bash-long-session-name", lastExitCode = 0, isActive = true)
                            ),
                            activeSessionId = "s1",
                            aiEnabled = true,
                            isAiPanelOpen = false,
                            showSplitButton = false,
                            splitMode = SplitMode.SINGLE,
                            showMenuButton = true,
                            isSidebarOpen = showDrawer,
                            onOpenSidebar = { showDrawer = true },
                            onSelectSession = {},
                            onAddSession = {},
                            onCloseSession = {},
                            onRenameSession = { _, _ -> },
                            onToggleAiPanel = {},
                            onToggleSplitMode = {}
                        )
                        WorkspaceSidebarDrawer(
                            visible = showDrawer,
                            selectedSection = WorkspaceSection.TERMINAL,
                            aiEnabled = true,
                            activeTheme = currentPreset,
                            onSelectSection = {},
                            onOpenSettings = {},
                            onDismiss = { showDrawer = false }
                        )
                    }
                }
            }
        }

        AppThemePreset.entries.forEach { preset ->
            composeRule.runOnUiThread {
                currentPreset = preset
                showDrawer = false
            }
            composeRule.waitForIdle()

            // No search icon or key pill in SessionTabsBar in either theme
            composeRule.onAllNodesWithTag("open_command_palette_button").assertCountEquals(0)
            composeRule.onAllNodesWithTag("active_key_pill").assertCountEquals(0)

            // Tab title gets expanded room and stays cleanly to the left of AI star button without overlap
            val tabNode = composeRule.onNodeWithTag("session_tab_s1").assertIsDisplayed().fetchSemanticsNode()
            val addNode = composeRule.onNodeWithTag("add_session_button").assertIsDisplayed().fetchSemanticsNode()
            val starNode = composeRule.onNodeWithTag("toggle_ai_panel_button").assertIsDisplayed().fetchSemanticsNode()
            val menuNode = composeRule.onNodeWithTag("open_sidebar_menu_button").assertIsDisplayed().fetchSemanticsNode()
            assertTrue("${preset.name} menu must be left of tab", menuNode.boundsInRoot.right <= tabNode.boundsInRoot.left + 1f)
            assertTrue("${preset.name} add button must not overlap AI star", addNode.boundsInRoot.right <= starNode.boundsInRoot.left + 1f)
            assertTrue(with(density) { menuNode.touchBoundsInRoot.width.toDp().value } >= 47.9f)
            assertTrue(with(density) { menuNode.touchBoundsInRoot.height.toDp().value } >= 47.9f)
            assertTrue(with(density) { tabNode.touchBoundsInRoot.height.toDp().value } >= 47.9f)
            assertTrue(with(density) { addNode.touchBoundsInRoot.height.toDp().value } >= 47.9f)
            assertTrue(with(density) { starNode.touchBoundsInRoot.height.toDp().value } >= 47.9f)

            composeRule.runOnUiThread { showDrawer = true }
            composeRule.waitForIdle()
            val sidebarBounds = composeRule.onNodeWithTag("workspace_sidebar").fetchSemanticsNode().boundsInRoot
            listOf(
                R.string.nav_terminal,
                R.string.nav_files,
                R.string.nav_ai,
                R.string.nav_snippets,
                R.string.nav_settings
            ).forEach { strRes ->
                val text = base.getString(strRes)
                val labelNode = composeRule.onNodeWithText(text, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                assertTrue("${preset.name} label '$text' clipped on right", labelNode.boundsInRoot.right <= sidebarBounds.right)
            }

            listOf(
                "sidebar_terminal",
                "sidebar_files",
                "sidebar_ai",
                "sidebar_snippets",
                "sidebar_settings"
            ).forEach { tag ->
                val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
                val heightDp = with(density) { node.boundsInRoot.height.toDp().value }
                assertTrue("$tag visual height should be ~40.dp, was ${heightDp}dp", heightDp in 39.5f..40.5f)
                assertTrue("$tag touch height must be >= 48.dp", with(density) { node.touchBoundsInRoot.height.toDp().value } >= 47.9f)
            }

            assertEquals("Buku Skrip", idCtx.getString(R.string.nav_snippets))
        }
    }

    @Test
    fun `WorkspaceStatusBar continues displaying active key status and Settings still exposes Theme and Language`() {
        val activeMeta = ApiKeyMetadata(id = "k1", label = "ProdKey-1", maskedPreview = "AIza••••1234")
        val snapshot = KeyManagerSnapshot(
            keys = listOf(ManagedKeyStatus(metadata = activeMeta, status = KeyHealthStatus.ACTIVE)),
            activeKeyId = "k1",
            activeKeyLabel = "ProdKey-1",
            usableKeyCount = 1
        )
        val settingsStore = FakeSettingsStore()
        val settingsVm = SettingsViewModel(
            settingsStore = settingsStore,
            keyManager = KeyManager(settingsStore)
        )

        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.CYBERPUNK) {
                Column(modifier = Modifier.fillMaxSize()) {
                    WorkspaceStatusBar(
                        cwd = "/home/hacker/project",
                        lastExitCode = 0,
                        keySnapshot = snapshot,
                        selectedModel = "gemini-3.8-flash"
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        SettingsScreen(
                            viewModel = settingsVm,
                            onNavigateBack = {},
                            onOpenOnboarding = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Status bar key label remains intact
        composeRule.onNodeWithText("ProdKey-1").assertIsDisplayed()

        // Theme & Language replacement groups exist in Settings
        composeRule.onNodeWithTag("settings_group_appearance").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_group_language").assertIsDisplayed()
    }

    @Test
    fun `removed CommandPalette, bottom bar, and active key pill no longer exist in main sources and EN-ID strings match`() {
        fun locate(rel: String): File {
            val direct = File(rel)
            if (direct.exists()) return direct
            return File("app/$rel")
        }
        val mainDir = locate("src/main")
        val ktFiles = mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val forbiddenKt = listOf(
            "CommandPalette",
            "PaletteAction",
            "isCommandPaletteOpen",
            "openCommandPalette",
            "closeCommandPalette",
            "ActiveKeyStatusPill",
            "open_command_palette_button",
            "active_key_pill",
            "WorkspaceBottomBar",
            "bottom_nav_",
            "workspace_bottom_bar"
        )
        ktFiles.forEach { file ->
            val text = file.readText()
            forbiddenKt.forEach { token ->
                assertFalse("${file.name} must not contain $token", text.contains(token))
            }
        }

        fun extractStringKeys(file: File): Set<String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            val keys = linkedSetOf<String>()
            for (i in 0 until nodes.length) {
                val name = nodes.item(i).attributes?.getNamedItem("name")?.nodeValue
                if (!name.isNullOrBlank()) keys.add(name)
            }
            return keys
        }

        val enKeys = extractStringKeys(locate("src/main/res/values/strings.xml"))
        val idKeys = extractStringKeys(locate("src/main/res/values-in/strings.xml"))
        assertEquals("EN and ID string keys must have exact 1-to-1 parity", enKeys, idKeys)
        assertTrue("No palette_* strings should remain", enKeys.none { it.startsWith("palette_") })
        assertFalse("cd_open_command_palette must be removed", enKeys.contains("cd_open_command_palette"))
    }
}
