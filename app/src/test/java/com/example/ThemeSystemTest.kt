package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ai.KeyManager
import com.example.data.ApiKeyMetadata
import com.example.data.AppLanguage
import com.example.data.AppSettings
import com.example.data.ExtraKeysLayout
import com.example.data.FallbackModelEntry
import com.example.data.KeyRoutingStrategy
import com.example.data.LocaleHelper
import com.example.data.SettingsStore
import com.example.data.VaultStatus
import com.example.settings.SettingsScreen
import com.example.settings.SettingsViewModel
import com.example.ui.terminal.WorkspaceSection
import com.example.ui.terminal.WorkspaceSidebarDrawer
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.ChamferShape
import com.example.ui.theme.HackerStudioTheme
import com.example.ui.theme.NavIndicatorStyle
import com.example.ui.theme.RiskBadgeStyle
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.TerminalColorScheme
import com.example.ui.theme.TerminalCursorStyle
import com.example.ui.theme.ThemeOptionPreviewCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeSystemTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class InMemoryThemeSettingsStore(
        initial: AppSettings = AppSettings()
    ) : SettingsStore {
        private val state = MutableStateFlow(initial)
        override val settingsFlow: Flow<AppSettings> = state.asStateFlow()
        override val vaultStatus: StateFlow<VaultStatus> =
            MutableStateFlow<VaultStatus>(VaultStatus.Ready).asStateFlow()

        val current: AppSettings get() = state.value

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
        override suspend fun addApiKey(label: String, rawKey: String) =
            Result.failure<ApiKeyMetadata>(IllegalStateException("unused"))
        override suspend fun removeApiKey(id: String) {}
        override suspend fun setApiKeyEnabled(id: String, enabled: Boolean) {}
        override suspend fun updateApiKeyLabel(id: String, newLabel: String) {}
        override fun getSecretKey(id: String): String? = null
    }

    private fun channelLinear(c: Float): Double =
        if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Color): Double =
        0.2126 * channelLinear(color.red) +
            0.7152 * channelLinear(color.green) +
            0.0722 * channelLinear(color.blue)

    private fun contrastRatio(fg: Color, bg: Color): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun `only two AppThemePresets exist and legacy or unknown stored names migrate safely to HACKER_PRO`() {
        assertEquals(listOf(AppThemePreset.HACKER_PRO, AppThemePreset.CYBERPUNK), AppThemePreset.entries)
        assertEquals(listOf(TerminalColorScheme.HACKER_PRO, TerminalColorScheme.CYBERPUNK), TerminalColorScheme.entries)

        val fallbackCases = listOf(
            null,
            "",
            "   ",
            "obsidian",
            "OBSIDIAN",
            "midnight",
            "matrix",
            "light",
            "tokyo_night",
            "dracula",
            "unknown_legacy_theme",
            "hacker_pro",
            "HACKER_PRO"
        )
        fallbackCases.forEach { raw ->
            assertEquals("Expected $raw to map to HACKER_PRO", AppThemePreset.HACKER_PRO, AppThemePreset.fromStoredName(raw))
        }

        val cyberpunkCases = listOf("cyberpunk", "CYBERPUNK", " cyberpunk ", "cyberpunk_futuristik", "cyberpunk_futuristic")
        cyberpunkCases.forEach { raw ->
            assertEquals("Expected $raw to map to CYBERPUNK", AppThemePreset.CYBERPUNK, AppThemePreset.fromStoredName(raw))
        }

        // TerminalColorScheme automatically tracks AppThemePreset
        assertEquals(TerminalColorScheme.HACKER_PRO, AppSettings(appTheme = AppThemePreset.HACKER_PRO).terminalColorScheme)
        assertEquals(TerminalColorScheme.CYBERPUNK, AppSettings(appTheme = AppThemePreset.CYBERPUNK).terminalColorScheme)
    }

    @Test
    fun `both themes meet 4_5 to 1 WCAG contrast across baseCanvas, layer1, layer2 and interactive roles`() {
        AppThemePreset.entries.forEach { preset ->
            val t = preset.tokens
            val surfaces = listOf("baseCanvas" to t.baseCanvas, "layer1" to t.layer1, "layer2" to t.layer2)
            val foregrounds = listOf(
                "textPrimary" to t.textPrimary,
                "textSecondary" to t.textSecondary,
                "textMuted" to t.textMuted,
                "accent" to t.accent,
                "warning" to t.warning,
                "error" to t.error
            )
            surfaces.forEach { (bgName, bgColor) ->
                foregrounds.forEach { (fgName, fgColor) ->
                    val ratio = contrastRatio(fgColor, bgColor)
                    assertTrue(
                        "${preset.name}: $fgName on $bgName contrast $ratio < 4.5",
                        ratio >= 4.5
                    )
                }
            }
            assertTrue(
                "${preset.name}: onAccent on accent < 4.5",
                contrastRatio(t.onAccent, t.accent) >= 4.5
            )
            assertTrue(
                "${preset.name}: onSecondaryAccent on secondaryAccent < 4.5",
                contrastRatio(t.onSecondaryAccent, t.secondaryAccent) >= 4.5
            )
            assertTrue(
                "${preset.name}: primaryButtonContent on primaryButtonContainer < 4.5",
                contrastRatio(t.primaryButtonContent, t.primaryButtonContainer) >= 4.5
            )

            // Terminal foreground & prompt colors against terminal background
            val term = preset.terminalScheme
            assertTrue(
                "${preset.name}: terminal foreground on background < 4.5",
                contrastRatio(term.foreground, term.background) >= 4.5
            )
            assertTrue(
                "${preset.name}: terminal promptColor on background < 4.5",
                contrastRatio(term.promptColor, term.background) >= 4.5
            )
        }
    }

    @Test
    fun `themes differentiate holistic visual geometry, fonts, prompt glyphs, cursors, badges and nav indicators`() {
        val pro = AppThemePreset.HACKER_PRO.tokens
        val cyber = AppThemePreset.CYBERPUNK.tokens

        assertEquals("$", pro.promptGlyph)
        assertEquals("▸", cyber.promptGlyph)
        assertEquals(TerminalCursorStyle.BLOCK, pro.cursorStyle)
        assertEquals(TerminalCursorStyle.UNDERLINE_GLOW, cyber.cursorStyle)
        assertEquals(RiskBadgeStyle.BRACKET_MONO, pro.badgeStyle)
        assertEquals(RiskBadgeStyle.CHAMFER_ICON, cyber.badgeStyle)
        assertEquals(NavIndicatorStyle.TOP_LINE, pro.navIndicatorStyle)
        assertEquals(NavIndicatorStyle.BOTTOM_NEON_GLOW, cyber.navIndicatorStyle)
        assertTrue(pro.headingUppercase)
        assertFalse(cyber.headingUppercase)
        assertTrue(pro.density < cyber.density)
        assertTrue(pro.motionDurationMs < cyber.motionDurationMs)
        assertEquals(0.dp, pro.glowRadius)
        assertTrue(cyber.glowRadius > 0.dp)
        assertTrue(cyber.cardShape is ChamferShape)
        assertFalse(pro.cardShape is ChamferShape)
    }

    @Test
    fun `reduce effects disables glow and motion and respects explicit user override over system flag`() {
        val reducedCyber = AppThemePreset.CYBERPUNK.tokens.withReducedEffects(true)
        assertEquals(0.dp, reducedCyber.glowRadius)
        assertEquals(Color.Transparent, reducedCyber.glowColor)
        assertEquals(Color.Transparent, reducedCyber.cursorGlowColor)
        assertEquals(0, reducedCyber.motionDurationMs)

        // Default follows systemAnimationsDisabled when user override is null
        assertFalse(AppSettings(systemAnimationsDisabled = false, reduceEffectsOverride = null).reduceEffects)
        assertTrue(AppSettings(systemAnimationsDisabled = true, reduceEffectsOverride = null).reduceEffects)
        // Explicit user override wins
        assertFalse(AppSettings(systemAnimationsDisabled = true, reduceEffectsOverride = false).reduceEffects)
        assertTrue(AppSettings(systemAnimationsDisabled = false, reduceEffectsOverride = true).reduceEffects)

        var observedReduceEffects = false
        var observedGlow = 99.dp
        var observedMotion = 99
        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.CYBERPUNK, reduceEffects = true) {
                observedReduceEffects = StudioTheme.reduceEffects
                observedGlow = StudioTheme.tokens.glowRadius
                observedMotion = StudioTheme.tokens.motionDurationMs
            }
        }
        composeRule.waitForIdle()
        assertTrue(observedReduceEffects)
        assertEquals(0.dp, observedGlow)
        assertEquals(0, observedMotion)
    }

    @Test
    fun `ChamferShape clamps vertices within bounds across aspect ratios, tiny sizes and RTL`() {
        val shape = ChamferShape(cutTopEnd = 12.dp, cutBottomStart = 16.dp)
        val density = Density(2f, 1f)
        val sizes = listOf(
            Size(200f, 200f),
            Size(320f, 48f),
            Size(48f, 320f),
            Size(4f, 4f)
        )

        sizes.forEach { sz ->
            listOf(false, true).forEach { isRtl ->
                val vertices = ChamferShape.computeVertices(
                    size = sz,
                    topEndPx = with(density) { shape.cutTopEnd.toPx() },
                    bottomStartPx = with(density) { shape.cutBottomStart.toPx() },
                    isRtl = isRtl
                )
                assertEquals(6, vertices.size)
                vertices.forEach { pt ->
                    assertTrue("x=${pt.x} out of [0, ${sz.width}]", pt.x in 0f..sz.width)
                    assertTrue("y=${pt.y} out of [0, ${sz.height}]", pt.y in 0f..sz.height)
                }
                val outline = shape.createOutline(
                    sz,
                    if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    density
                )
                assertTrue(outline is Outline.Generic)
            }
        }

        val emptyOutline = shape.createOutline(Size.Zero, LayoutDirection.Ltr, density)
        assertTrue(emptyOutline is Outline.Rectangle)
    }

    @Test
    fun `English and Indonesian strings xml files have exact 1 to 1 key parity`() {
        fun locateRes(relative: String): File {
            val direct = File(relative)
            if (direct.exists()) return direct
            val fromRoot = File("app/$relative")
            assertTrue("Missing resource file $relative", fromRoot.exists())
            return fromRoot
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

        val enKeys = extractStringKeys(locateRes("src/main/res/values/strings.xml"))
        val idKeys = extractStringKeys(locateRes("src/main/res/values-in/strings.xml"))

        val missingInId = enKeys - idKeys
        val extraInId = idKeys - enKeys
        assertTrue("Missing in values-in/strings.xml: $missingInId", missingInId.isEmpty())
        assertTrue("Extra in values-in/strings.xml: $extraInId", extraInId.isEmpty())

        val base = ApplicationProvider.getApplicationContext<Context>()
        val enCtx = LocaleHelper.createLocalizedContext(base, AppLanguage.ENGLISH)
        val idCtx = LocaleHelper.createLocalizedContext(base, AppLanguage.INDONESIAN)
        assertEquals("Hacker Pro", enCtx.getString(R.string.theme_hacker_pro))
        assertEquals("Cyberpunk Futuristic", enCtx.getString(R.string.theme_cyberpunk))
        assertEquals("Cyberpunk Futuristik", idCtx.getString(R.string.theme_cyberpunk))
        assertEquals("Reduce effects", enCtx.getString(R.string.settings_reduce_effects_title))
        assertEquals("Kurangi efek", idCtx.getString(R.string.settings_reduce_effects_title))
    }

    @Test
    fun `ui ThemeOptionPreviewCard renders distinct Hacker Pro and Cyberpunk tokens with RadioButton semantics`() {
        var selectedPreset = AppThemePreset.HACKER_PRO
        composeRule.setContent {
            HackerStudioTheme(appTheme = selectedPreset) {
                Column {
                    AppThemePreset.entries.forEach { preset ->
                        ThemeOptionPreviewCard(
                            preset = preset,
                            selected = selectedPreset == preset,
                            reduceEffects = false,
                            onSelect = { selectedPreset = preset }
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("theme_preview_hacker_pro").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("theme_preview_cyberpunk").assertIsDisplayed()
        composeRule.onNodeWithText("$").assertIsDisplayed()
        composeRule.onNodeWithText("▸").assertIsDisplayed()
        composeRule.onNodeWithText("[LOW]").assertIsDisplayed()
        composeRule.onNodeWithText("LOW").assertIsDisplayed()
    }

    @Test
    fun `ui WorkspaceSidebarDrawer renders theme-specific navigation indicators`() {
        val currentTheme = MutableStateFlow(AppThemePreset.HACKER_PRO)
        composeRule.setContent {
            val preset by currentTheme.collectAsState()
            HackerStudioTheme(appTheme = preset) {
                WorkspaceSidebarDrawer(
                    visible = true,
                    selectedSection = WorkspaceSection.TERMINAL,
                    aiEnabled = true,
                    activeTheme = preset,
                    onSelectSection = {},
                    onOpenSettings = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_indicator_top_line").assertIsDisplayed()

        currentTheme.value = AppThemePreset.CYBERPUNK
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_indicator_bottom_neon_glow").assertIsDisplayed()
    }

    @Test
    fun `ui SettingsScreen reduces effects toggle updates store and keeps theme selection in sync`() {
        val store = InMemoryThemeSettingsStore(
            AppSettings(
                appTheme = AppThemePreset.CYBERPUNK,
                reduceEffectsOverride = false
            )
        )
        val viewModel = SettingsViewModel(
            settingsStore = store,
            keyManager = KeyManager(store)
        )

        composeRule.setContent {
            val liveSettings by store.settingsFlow.collectAsState(initial = store.current)
            HackerStudioTheme(
                appTheme = liveSettings.appTheme,
                reduceEffects = liveSettings.reduceEffects
            ) {
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onOpenOnboarding = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_group_appearance").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasTestTag("reduce_effects_switch"))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reduce_effects_switch").assertIsOff().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()

        assertTrue(store.current.reduceEffects)
        composeRule.onNodeWithTag("reduce_effects_switch").assertIsOn()
    }
}
