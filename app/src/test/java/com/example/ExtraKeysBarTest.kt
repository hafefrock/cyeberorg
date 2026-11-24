package com.example

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import com.example.data.PromptHistoryItem
import com.example.data.PromptHistoryStore
import com.example.data.SettingsStore
import com.example.data.SnippetStore
import com.example.data.VaultStatus
import com.example.terminal.ExtraKeyCode
import com.example.terminal.FakeTerminalBackend
import com.example.terminal.TerminalBackend
import com.example.terminal.TerminalSessionHandle
import com.example.ui.terminal.ExtraKeysBar
import com.example.ui.terminal.TerminalScreen
import com.example.ui.terminal.TerminalViewModel
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.CyberorgTheme
import com.example.ui.theme.StudioDimens
import com.example.ui.theme.chamferInset
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
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
class ExtraKeysBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun locateFile(relative: String): File {
        val direct = File(relative)
        if (direct.exists()) return direct
        val fromRoot = File("app/$relative")
        assertTrue("Missing file $relative", fromRoot.exists())
        return fromRoot
    }

    private fun extractStringEntries(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val name = node.attributes?.getNamedItem("name")?.nodeValue
            if (!name.isNullOrBlank()) {
                map[name] = node.textContent ?: ""
            }
        }
        return map
    }

    @Test
    fun `all ExtraKeysLayout variants render in two non-scrolling equal-weight rows preserving exact key order`() {
        var activeLayout by mutableStateOf(ExtraKeysLayout.STANDARD)

        composeRule.setContent {
            CyberorgTheme(appTheme = AppThemePreset.HACKER_PRO) {
                Box(modifier = Modifier.width(360.dp)) {
                    ExtraKeysBar(
                        layout = activeLayout,
                        ctrlArmed = false,
                        altArmed = false,
                        onKeyClick = {}
                    )
                }
            }
        }

        ExtraKeysLayout.entries.forEach { layout ->
            composeRule.runOnUiThread { activeLayout = layout }
            composeRule.waitForIdle()

            // No horizontalScroll semantics anywhere on bar or rows
            listOf("extra_keys_bar", "extra_keys_row_1", "extra_keys_row_2").forEach { tag ->
                val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
                assertFalse(
                    "$tag must not have horizontalScroll semantics",
                    node.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
                )
            }

            val keys = layout.keys
            val firstCount = (keys.size + 1) / 2
            val row1Keys = keys.take(firstCount)
            val row2Keys = keys.drop(firstCount)

            // Diff between rows is at most 1
            assertTrue(
                "Row count diff should be <= 1 for ${layout.name}: ${row1Keys.size} vs ${row2Keys.size}",
                abs(row1Keys.size - row2Keys.size) <= 1
            )
            assertEquals("Combined row keys must equal layout.keys", keys, row1Keys + row2Keys)

            val row1Bounds = row1Keys.map {
                composeRule.onNodeWithTag("extra_key_${it.name.lowercase()}").fetchSemanticsNode().boundsInRoot
            }
            val row2Bounds = row2Keys.map {
                composeRule.onNodeWithTag("extra_key_${it.name.lowercase()}").fetchSemanticsNode().boundsInRoot
            }

            // Strict left-to-right order in each row, and row 1 strictly above row 2
            assertTrue(
                "${layout.name} row 1 not left-to-right",
                row1Bounds.zipWithNext().all { (a, b) -> a.right < b.left }
            )
            assertTrue(
                "${layout.name} row 2 not left-to-right",
                row2Bounds.zipWithNext().all { (a, b) -> a.right < b.left }
            )
            assertTrue(
                "${layout.name} row 1 must be strictly above row 2",
                row1Bounds.maxOf { it.bottom } < row2Bounds.minOf { it.top }
            )

            // Equal weight per row + full width coverage (no trailing gap, row1 outer span == row2 outer span)
            val row1Widths = row1Bounds.map { it.width }
            val row2Widths = row2Bounds.map { it.width }
            assertTrue(
                "${layout.name} row 1 widths unequal: $row1Widths",
                (row1Widths.max() - row1Widths.min()) <= 2.0f
            )
            assertTrue(
                "${layout.name} row 2 widths unequal: $row2Widths",
                (row2Widths.max() - row2Widths.min()) <= 2.0f
            )
            assertEquals(
                "${layout.name} row 1 & row 2 must share exact left edge",
                row1Bounds.first().left,
                row2Bounds.first().left,
                1.5f
            )
            assertEquals(
                "${layout.name} row 1 & row 2 must share exact right edge without trailing gap",
                row1Bounds.last().right,
                row2Bounds.last().right,
                1.5f
            )
        }
    }

    @Test
    fun `sticky CTRL and ALT reflect active state and fire click callbacks across both themes`() {
        var preset by mutableStateOf(AppThemePreset.HACKER_PRO)
        var ctrlArmed by mutableStateOf(false)
        var altArmed by mutableStateOf(false)
        val clicks = mutableListOf<ExtraKeyCode>()

        composeRule.setContent {
            CyberorgTheme(appTheme = preset) {
                ExtraKeysBar(
                    layout = ExtraKeysLayout.STANDARD,
                    ctrlArmed = ctrlArmed,
                    altArmed = altArmed,
                    onKeyClick = { key ->
                        clicks.add(key)
                        when (key) {
                            ExtraKeyCode.CTRL -> ctrlArmed = !ctrlArmed
                            ExtraKeyCode.ALT -> altArmed = !altArmed
                            else -> Unit
                        }
                    }
                )
            }
        }
        composeRule.waitForIdle()

        listOf(AppThemePreset.HACKER_PRO, AppThemePreset.CYBERPUNK).forEach { theme ->
            composeRule.runOnUiThread {
                preset = theme
                ctrlArmed = false
                altArmed = false
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("extra_key_ctrl").performClick()
            composeRule.waitForIdle()
            assertTrue("$theme: CTRL should arm on first click", ctrlArmed)

            composeRule.onNodeWithTag("extra_key_alt").performClick()
            composeRule.waitForIdle()
            assertTrue("$theme: ALT should arm on click", altArmed)

            composeRule.onNodeWithTag("extra_key_ctrl").performClick()
            composeRule.waitForIdle()
            assertFalse("$theme: CTRL should disarm on second click", ctrlArmed)
        }
        assertTrue(clicks.containsAll(listOf(ExtraKeyCode.CTRL, ExtraKeyCode.ALT)))
    }

    @Test
    fun `densest layout at 320dp and fontScale 1_3f shows unclipped labels and meets cell width minimums`() {
        var widthDp by mutableStateOf(320.dp)
        var layout by mutableStateOf(ExtraKeysLayout.DEVELOPER)
        var preset by mutableStateOf(AppThemePreset.CYBERPUNK)

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 1.3f)
            ) {
                CyberorgTheme(appTheme = preset) {
                    Box(modifier = Modifier.width(widthDp)) {
                        ExtraKeysBar(
                            layout = layout,
                            ctrlArmed = true,
                            altArmed = false,
                            onKeyClick = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Densest layout (DEVELOPER, 10+9 keys) at 320dp + fontScale 1.3f: verify zero horizontal clipping
        val failures = mutableListOf<String>()
        listOf(AppThemePreset.CYBERPUNK, AppThemePreset.HACKER_PRO).forEach { theme ->
            composeRule.runOnUiThread {
                preset = theme
                widthDp = 320.dp
                layout = ExtraKeysLayout.DEVELOPER
            }
            composeRule.waitForIdle()

            ExtraKeysLayout.DEVELOPER.keys.forEach { key ->
                val textNode = composeRule.onNodeWithTag(
                    "extra_key_${key.name.lowercase()}",
                    useUnmergedTree = true
                )
                textNode.assertIsDisplayed()
                val textLayouts = mutableListOf<TextLayoutResult>()
                composeRule.onNodeWithText(key.label, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsActions.GetTextLayoutResult)
                    ?.action
                    ?.invoke(textLayouts)
                assertTrue("Expected TextLayoutResult for ${key.label}", textLayouts.isNotEmpty())
                val tlr = textLayouts.first()
                assertEquals("${key.label} ($theme) must stay single line", 1, tlr.lineCount)
                val cellBounds = composeRule.onNodeWithTag("extra_key_${key.name.lowercase()}").fetchSemanticsNode().boundsInRoot
                if (tlr.didOverflowWidth || tlr.hasVisualOverflow || tlr.getLineRight(0) > cellBounds.width) {
                    failures.add(
                        "${key.label}($theme): lineW=${tlr.getLineRight(0)}, size=${tlr.size}, maxW=${tlr.layoutInput.constraints.maxWidth}, cellW=${cellBounds.width}, overflow=${tlr.didOverflowWidth}"
                    )
                }
            }
        }
        assertTrue("320dp fontScale=1.3f overflows: $failures", failures.isEmpty())

        // Cell width checks at 360.dp (Cyberpunk has largest safeContentPadding chamfer)
        composeRule.runOnUiThread {
            preset = AppThemePreset.CYBERPUNK
            widthDp = 360.dp
            layout = ExtraKeysLayout.DEVELOPER
        }
        composeRule.waitForIdle()
        val devWidth360 = with(composeRule.density) {
            composeRule.onNodeWithTag("extra_key_ctrl").fetchSemanticsNode().boundsInRoot.width.toDp().value
        }
        assertTrue(
            "DEVELOPER (10 keys/row) cell width at 360.dp should be >= 32.dp, was ${devWidth360}dp",
            devWidth360 >= 32.0f
        )

        composeRule.runOnUiThread {
            layout = ExtraKeysLayout.STANDARD
        }
        composeRule.waitForIdle()
        val stdWidth360 = with(composeRule.density) {
            composeRule.onNodeWithTag("extra_key_ctrl").fetchSemanticsNode().boundsInRoot.width.toDp().value
        }
        assertTrue(
            "STANDARD (6 keys/row) cell width at 360.dp should be >= 44.dp, was ${stdWidth360}dp",
            stdWidth360 >= 44.0f
        )
    }

    @Test
    fun `two row ExtraKeysBar stays compact under 88dp and has zero touch overlap between adjacent keys and rows`() {
        val clicked = mutableListOf<ExtraKeyCode>()

        composeRule.setContent {
            CyberorgTheme(appTheme = AppThemePreset.CYBERPUNK) {
                Box(modifier = Modifier.width(360.dp)) {
                    ExtraKeysBar(
                        layout = ExtraKeysLayout.STANDARD,
                        ctrlArmed = false,
                        altArmed = false,
                        onKeyClick = { clicked.add(it) }
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val barBounds = composeRule.onNodeWithTag("extra_keys_bar").fetchSemanticsNode().boundsInRoot
        val totalHeightDp = with(density) { barBounds.height.toDp().value }
        assertTrue("ExtraKeysBar total height should be <= 88.dp, was ${totalHeightDp}dp", totalHeightDp <= 88.0f)

        val expectedRowDp = StudioDimens().extraKeyRowHeight.value
        val row1HeightDp = with(density) {
            composeRule.onNodeWithTag("extra_keys_row_1").fetchSemanticsNode().boundsInRoot.height.toDp().value
        }
        val row2HeightDp = with(density) {
            composeRule.onNodeWithTag("extra_keys_row_2").fetchSemanticsNode().boundsInRoot.height.toDp().value
        }
        assertEquals(expectedRowDp, row1HeightDp, 1.0f)
        assertEquals(expectedRowDp, row2HeightDp, 1.0f)

        // Verify zero overlap across all 12 cell bounds and edge-tap hits exact cell
        val allBounds = ExtraKeysLayout.STANDARD.keys.map { key ->
            key to composeRule.onNodeWithTag("extra_key_${key.name.lowercase()}").fetchSemanticsNode().boundsInRoot
        }
        for (i in allBounds.indices) {
            for (j in i + 1 until allBounds.size) {
                val (k1, b1) = allBounds[i]
                val (k2, b2) = allBounds[j]
                assertFalse(
                    "Touch/layout bounds overlap between $k1 ($b1) and $k2 ($b2)",
                    b1.overlaps(b2)
                )
            }
        }

        // Bottom edge of Row 1 key fires Row 1 key, top edge of Row 2 key fires Row 2 key directly below
        composeRule.onNodeWithTag("extra_key_esc").performTouchInput {
            click(Offset(centerX, bottom - 2f))
        }
        composeRule.onNodeWithTag("extra_key_arrow_left").performTouchInput {
            click(Offset(centerX, top + 2f))
        }
        composeRule.waitForIdle()
        assertEquals(listOf(ExtraKeyCode.ESC, ExtraKeyCode.ARROW_LEFT), clicked)
    }

    @Test
    fun `TerminalScreen sits directly above two-row ExtraKeysBar without gap and resizes cleanly on layout switch`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val settingsState = MutableStateFlow(
            AppSettings(
                appTheme = AppThemePreset.HACKER_PRO,
                extraKeysLayout = ExtraKeysLayout.STANDARD,
                aiEnabled = true,
                aiConsentAccepted = true
            )
        )
        val settingsStore = object : SettingsStore {
            override val settingsFlow: Flow<AppSettings> = settingsState.asStateFlow()
            override val vaultStatus: StateFlow<VaultStatus> =
                MutableStateFlow<VaultStatus>(VaultStatus.Ready).asStateFlow()
            override suspend fun setAppTheme(theme: AppThemePreset) {
                settingsState.update { it.copy(appTheme = theme) }
            }
            override suspend fun setAppLanguage(language: AppLanguage) = Unit
            override suspend fun setOnboardingCompleted(completed: Boolean) = Unit
            override suspend fun setFontSizeSp(sizeSp: Float) = Unit
            override suspend fun setExtraKeysLayout(layout: ExtraKeysLayout) {
                settingsState.update { it.copy(extraKeysLayout = layout) }
            }
            override suspend fun setSelectedModel(modelId: String?) = Unit
            override suspend fun setFallbackModels(entries: List<FallbackModelEntry>) = Unit
            override suspend fun setRoutingStrategy(strategy: KeyRoutingStrategy) = Unit
            override suspend fun setCooldownSeconds(seconds: Int) = Unit
            override suspend fun setAiEnabled(enabled: Boolean) = Unit
            override suspend fun setAiConsentAccepted(accepted: Boolean) = Unit
            override suspend fun setReduceEffects(enabled: Boolean) = Unit
            override suspend fun addApiKey(label: String, rawKey: String): Result<ApiKeyMetadata> =
                Result.failure(IllegalStateException("unused"))
            override suspend fun removeApiKey(id: String) = Unit
            override suspend fun setApiKeyEnabled(id: String, enabled: Boolean) = Unit
            override suspend fun updateApiKeyLabel(id: String, newLabel: String) = Unit
            override fun getSecretKey(id: String): String? = null
        }
        val snippetStore = object : SnippetStore {
            override val snippetsFlow: Flow<List<CommandSnippet>> = MutableStateFlow(emptyList())
            override suspend fun saveSnippet(
                title: String,
                command: String,
                tags: List<String>,
                existingId: String?
            ): CommandSnippet = CommandSnippet(id = existingId ?: "s1", title = title, command = command)
            override suspend fun deleteSnippet(id: String) = Unit
            override fun exportToJson(snippets: List<CommandSnippet>): String = "[]"
            override suspend fun importFromJson(rawJson: String): Result<Int> = Result.success(0)
        }
        val historyStore = object : PromptHistoryStore {
            override val historyFlow: Flow<List<PromptHistoryItem>> = MutableStateFlow(emptyList())
            override suspend fun recordPrompt(prompt: String) = Unit
            override suspend fun clearAll() = Unit
        }
        val keyManager = KeyManager(settingsStore)
        val fakeBackend = FakeTerminalBackend()
        val resizeCount = mutableListOf<Pair<Int, Int>>()
        val wrappedCache = linkedMapOf<String, TerminalSessionHandle>()
        val spyBackend = object : TerminalBackend {
            private fun wrap(inner: TerminalSessionHandle): TerminalSessionHandle =
                wrappedCache.getOrPut(inner.id) {
                    object : TerminalSessionHandle by inner {
                        override fun resize(cols: Int, rows: Int) {
                            resizeCount.add(cols to rows)
                            inner.resize(cols, rows)
                        }
                    }
                }
            override val sessions: StateFlow<List<TerminalSessionHandle>> =
                fakeBackend.sessions.map { list -> list.map(::wrap) }
                    .stateIn(scope, SharingStarted.Eagerly, emptyList())
            override val activeSessionId: StateFlow<String> = fakeBackend.activeSessionId
            override fun createSession(title: String?): TerminalSessionHandle = wrap(fakeBackend.createSession(title))
            override fun closeSession(sessionId: String) = fakeBackend.closeSession(sessionId)
            override fun switchSession(sessionId: String) = fakeBackend.switchSession(sessionId)
            override fun stateFilePath(sessionId: String): String = fakeBackend.stateFilePath(sessionId)
        }
        val transport = object : GeminiTransport {
            override suspend fun generate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): GeminiCallResult<String> = GeminiCallResult.Success("ok", model, "key-1")
            override fun streamGenerate(
                model: String,
                apiKey: String,
                systemInstruction: String,
                contents: List<GeminiContent>
            ): Flow<GeminiStreamEvent> = flow {
                emit(GeminiStreamEvent.Completed())
            }
        }
        val aiRepo = AiAssistantRepository(
            keyManager = keyManager,
            geminiApiClient = transport,
            settingsStore = settingsStore,
            defaultDispatcher = Dispatchers.Main.immediate
        )
        val viewModel = TerminalViewModel(
            terminalBackend = spyBackend,
            aiRepository = aiRepo,
            keyManager = keyManager,
            settingsStore = settingsStore,
            snippetStore = snippetStore,
            promptHistoryStore = historyStore
        )

        try {
            composeRule.setContent {
                CyberorgTheme(appTheme = AppThemePreset.HACKER_PRO) {
                    TerminalScreen(
                        viewModel = viewModel,
                        onNavigateToSettings = {}
                    )
                }
            }
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()

            val gridBounds = composeRule.onNodeWithTag("terminal_cell_grid").fetchSemanticsNode().boundsInRoot
            val extraKeysBounds = composeRule.onNodeWithTag("extra_keys_bar").fetchSemanticsNode().boundsInRoot
            val statusTextBounds = composeRule.onNodeWithText("EXIT 0", substring = true).fetchSemanticsNode().boundsInRoot
            val expectedConsoleBottomInsetPx = with(composeRule.density) { 10.dp.toPx() }

            assertTrue("Terminal grid must occupy remaining space (> 200px)", gridBounds.height > 200f)
            assertEquals(
                "TerminalConsoleView must sit flush above ExtraKeysBar with zero extra gap beyond its 10dp card padding",
                expectedConsoleBottomInsetPx,
                extraKeysBounds.top - gridBounds.bottom,
                2.0f
            )
            assertTrue(
                "WorkspaceStatusBar must sit directly below ExtraKeysBar (gap=${statusTextBounds.top - extraKeysBounds.bottom})",
                statusTextBounds.top >= extraKeysBounds.bottom &&
                    (statusTextBounds.top - extraKeysBounds.bottom) <= with(composeRule.density) { 8.dp.toPx() }
            )
            assertTrue("Terminal backend should have received initial resize matching grid", resizeCount.isNotEmpty())
            val resizeCountBeforeSwitch = resizeCount.size

            // Switching from STANDARD (12 keys) to DEVELOPER (19 keys) preserves identical 2-row height => no extra resize flicker
            settingsState.update { it.copy(extraKeysLayout = ExtraKeysLayout.DEVELOPER) }
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("extra_key_greater").assertIsDisplayed()
            assertEquals(
                "Switching extra key layout keeps constant 2-row height so terminal does not flicker-resize",
                resizeCountBeforeSwitch,
                resizeCount.size
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `ExtraKeysBar uses tokens only without horizontalScroll or theme branching and strings keep EN-ID parity`() {
        val file = locateFile("src/main/java/com/example/ui/terminal/ExtraKeysBar.kt")
        val content = file.readText()
        assertFalse("ExtraKeysBar.kt must not use horizontalScroll", content.contains("horizontalScroll"))
        assertFalse(
            "ExtraKeysBar.kt must not branch on theme presets",
            Regex("""\b(AppThemePreset|CYBERPUNK|HACKER_PRO)\b""").containsMatchIn(content)
        )
        assertFalse(
            "ExtraKeysBar.kt must not hardcode control dp literals",
            Regex("""\b(height|size|heightIn|widthIn|defaultMinSize)\s*\(\s*[^)]*\d+(\.\d+)?\.dp""").containsMatchIn(content)
        )

        val enStrings = extractStringEntries(locateFile("src/main/res/values/strings.xml"))
        val idStrings = extractStringEntries(locateFile("src/main/res/values-in/strings.xml"))
        assertEquals(enStrings.keys, idStrings.keys)

        // Safe padding token holds on Cyberpunk chamfer
        val inset = AppThemePreset.CYBERPUNK.tokens.smallShape.chamferInset()
        assertTrue("Cyberpunk smallShape should expose chamfer inset > 0", inset > 0.dp)
    }
}
