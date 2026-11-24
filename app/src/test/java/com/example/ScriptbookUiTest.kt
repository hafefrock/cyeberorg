package com.example

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppLanguage
import com.example.data.CommandSnippet
import com.example.data.ExtraKeysLayout
import com.example.data.LocaleHelper
import com.example.terminal.ExtraKeyCode
import com.example.ui.terminal.ExtraKeysBar
import com.example.ui.terminal.SessionTabItem
import com.example.ui.terminal.SessionTabsBar
import com.example.ui.terminal.SnippetsScreen
import com.example.ui.terminal.SplitMode
import com.example.ui.terminal.WorkspaceNavigationRail
import com.example.ui.terminal.WorkspaceSection
import com.example.ui.terminal.WorkspaceSidebarDrawer
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.HackerStudioTheme
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.chamferInset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xhdpi")
class ScriptbookUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun locateRes(relative: String): File {
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
    fun `strings xml files have 1 to 1 parity and zero remaining Cuplikan or Snippet user strings`() {
        val enEntries = extractStringEntries(locateRes("src/main/res/values/strings.xml"))
        val idEntries = extractStringEntries(locateRes("src/main/res/values-in/strings.xml"))

        assertEquals(emptySet<String>(), enEntries.keys - idEntries.keys)
        assertEquals(emptySet<String>(), idEntries.keys - enEntries.keys)

        val forbidden = Regex("cuplikan|snippet", RegexOption.IGNORE_CASE)
        enEntries.forEach { (key, value) ->
            assertFalse("EN string '$key' still mentions snippet/cuplikan: $value", forbidden.containsMatchIn(value))
        }
        idEntries.forEach { (key, value) ->
            assertFalse("ID string '$key' still mentions snippet/cuplikan: $value", forbidden.containsMatchIn(value))
        }

        val base = ApplicationProvider.getApplicationContext<Context>()
        val enCtx = LocaleHelper.createLocalizedContext(base, AppLanguage.ENGLISH)
        val idCtx = LocaleHelper.createLocalizedContext(base, AppLanguage.INDONESIAN)
        assertEquals("Scriptbook", enCtx.getString(R.string.nav_snippets))
        assertEquals("Buku Skrip", idCtx.getString(R.string.nav_snippets))
    }

    @Test
    fun `compact typography theme scale enforces exact downsized tokens and strict 11sp floor`() {
        val observed = mutableListOf<Float>()
        var currentPreset by mutableStateOf(AppThemePreset.HACKER_PRO)
        composeRule.setContent {
            HackerStudioTheme(appTheme = currentPreset) {
                val compact = StudioTheme.compactTypography
                val m3 = MaterialTheme.typography
                observed.clear()
                observed.addAll(
                    listOf(
                        compact.screenTitle.fontSize.value,
                        compact.cardTitle.fontSize.value,
                        compact.body.fontSize.value,
                        compact.tabOrChip.fontSize.value,
                        compact.navLabel.fontSize.value,
                        compact.monoPreview.fontSize.value,
                        m3.displaySmall.fontSize.value,
                        m3.headlineMedium.fontSize.value,
                        m3.titleLarge.fontSize.value,
                        m3.titleMedium.fontSize.value,
                        m3.titleSmall.fontSize.value,
                        m3.bodyLarge.fontSize.value,
                        m3.bodyMedium.fontSize.value,
                        m3.bodySmall.fontSize.value,
                        m3.labelLarge.fontSize.value,
                        m3.labelMedium.fontSize.value,
                        m3.labelSmall.fontSize.value
                    )
                )
                assertEquals(16.sp, compact.screenTitle.fontSize)
                assertEquals(14.sp, compact.cardTitle.fontSize)
                assertEquals(12.sp, compact.body.fontSize)
                assertEquals(11.sp, compact.tabOrChip.fontSize)
                assertEquals(11.sp, compact.navLabel.fontSize)
                assertEquals(11.sp, compact.monoPreview.fontSize)
            }
        }
        AppThemePreset.entries.forEach { preset ->
            composeRule.runOnUiThread { currentPreset = preset }
            composeRule.waitForIdle()
            assertTrue("Expected all typography >= 11.sp for ${preset.name}: $observed", observed.all { it >= 11f })
        }

        // Source code audit: modified UI / AI / Settings / Preview components must not hardcode .sp fontSizes
        // (AppearanceSettingsScreen's user-configurable terminal fontSizeSp slider preview is explicitly excluded)
        val filesToAudit = listOf(
            locateRes("src/main/java/com/example/ui/terminal/SnippetsScreen.kt"),
            locateRes("src/main/java/com/example/ui/terminal/WorkspaceComponents.kt"),
            locateRes("src/main/java/com/example/ui/terminal/SessionTabsBar.kt"),
            locateRes("src/main/java/com/example/ui/terminal/ExtraKeysBar.kt"),
            locateRes("src/main/java/com/example/ui/terminal/TerminalConsoleView.kt"),
            locateRes("src/main/java/com/example/ui/ai/CommandSuggestionCard.kt"),
            locateRes("src/main/java/com/example/ui/ai/AiComponents.kt"),
            locateRes("src/main/java/com/example/ui/ai/AiBottomSheet.kt"),
            locateRes("src/main/java/com/example/ui/ai/AiFullScreen.kt"),
            locateRes("src/main/java/com/example/ui/theme/ThemePreviewCard.kt"),
            locateRes("src/main/java/com/example/settings/SettingsComponents.kt"),
            locateRes("src/main/java/com/example/settings/SettingsScreen.kt"),
            locateRes("src/main/java/com/example/settings/SettingsHomeScreen.kt"),
            locateRes("src/main/java/com/example/settings/AiSettingsScreen.kt"),
            locateRes("src/main/java/com/example/settings/ApiKeysScreen.kt"),
            locateRes("src/main/java/com/example/settings/ApiKeyComponents.kt"),
            locateRes("src/main/java/com/example/settings/TerminalSettingsScreen.kt"),
            locateRes("src/main/java/com/example/settings/LanguageSettingsScreen.kt"),
            locateRes("src/main/java/com/example/settings/DataPrivacySettingsScreen.kt"),
            locateRes("src/main/java/com/example/settings/AboutScreen.kt")
        )
        val hardcodedSpRegex = Regex("""fontSize\s*=\s*\d+(\.\d+)?\.sp""")
        filesToAudit.forEach { file ->
            val content = file.readText()
            assertFalse("Hardcoded fontSize found in ${file.name}", hardcodedSpRegex.containsMatchIn(content))
        }
    }

    @Test
    fun `empty library state shows create first script CTA while empty search shows distinct message`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var snippetsState by mutableStateOf<List<CommandSnippet>>(emptyList())
        var presetState by mutableStateOf(AppThemePreset.HACKER_PRO)
        composeRule.setContent {
            HackerStudioTheme(appTheme = presetState) {
                SnippetsScreen(
                    snippets = snippetsState,
                    onInsertSnippet = {},
                    onCopySnippet = {},
                    onSaveSnippet = { _, _, _, _ -> },
                    onDeleteSnippet = {},
                    onBuildExportJson = { "[]" },
                    onExportSuccess = {},
                    onImportJson = {},
                    onIoError = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("snippets_empty_library").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.snippets_empty_library_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("snippets_create_first_button").assertIsDisplayed()

        // Now test populated library with non-matching search query -> distinct empty search state
        composeRule.runOnUiThread {
            presetState = AppThemePreset.CYBERPUNK
            snippetsState = listOf(
                CommandSnippet(id = "s1", title = "Disk usage", command = "df -h", tags = listOf("disk"))
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("snippets_search_toggle").performClick()
        composeRule.onNodeWithTag("snippets_search_input").performTextInput("zzzz_nomatch")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("snippets_empty_search").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.snippets_empty_search_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("snippets_clear_filters_button").assertIsDisplayed()
    }

    @Test
    fun `tag filter filters scripts and card limits visible tags to 3 plus overflow badge`() {
        val snippets = listOf(
            CommandSnippet(
                id = "s1",
                title = "Inspect Network",
                command = "ss -tulnp",
                tags = listOf("net", "ports", "tcp", "udp", "audit")
            ),
            CommandSnippet(
                id = "s2",
                title = "Git Status",
                command = "git status -sb",
                tags = listOf("git")
            )
        )

        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.HACKER_PRO) {
                SnippetsScreen(
                    snippets = snippets,
                    onInsertSnippet = {},
                    onCopySnippet = {},
                    onSaveSnippet = { _, _, _, _ -> },
                    onDeleteSnippet = {},
                    onBuildExportJson = { "[]" },
                    onExportSuccess = {},
                    onImportJson = {},
                    onIoError = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Card s1 has 5 tags -> max 3 shown on card + "+2" overflow pill
        composeRule.onNodeWithTag("snippet_tag_overflow_s1").assertIsDisplayed()
        composeRule.onNodeWithText("+2").assertIsDisplayed()

        // Select #git tag filter -> only s2 visible
        composeRule.onNodeWithTag("tag_filter_git").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("snippet_card_s2").assertIsDisplayed()
        composeRule.onAllNodesWithText("Inspect Network").assertCountEquals(0)

        // Tap All -> both visible again
        composeRule.onNodeWithTag("tag_filter_all").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("snippet_card_s1").assertIsDisplayed()
        composeRule.onNodeWithTag("snippet_card_s2").assertIsDisplayed()
    }

    @Test
    fun `card overflow menu offers Copy Edit and confirmation-gated Delete`() {
        var copiedCmd: String? = null
        var deletedId: String? = null
        var savedTitle: String? = null
        val snippets = listOf(
            CommandSnippet(
                id = "s1",
                title = "Check Disk",
                command = "df -h",
                tags = listOf("disk")
            )
        )

        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.CYBERPUNK) {
                SnippetsScreen(
                    snippets = snippets,
                    onInsertSnippet = {},
                    onCopySnippet = { copiedCmd = it },
                    onSaveSnippet = { title, _, _, _ -> savedTitle = title },
                    onDeleteSnippet = { deletedId = it },
                    onBuildExportJson = { "[]" },
                    onExportSuccess = {},
                    onImportJson = {},
                    onIoError = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Copy via overflow menu
        composeRule.onNodeWithTag("snippet_more_s1").performClick()
        composeRule.onNodeWithTag("snippet_menu_copy_s1").performClick()
        assertEquals("df -h", copiedCmd)

        // Edit via overflow menu
        composeRule.onNodeWithTag("snippet_more_s1").performClick()
        composeRule.onNodeWithTag("snippet_menu_edit_s1").performClick()
        composeRule.onNodeWithTag("snippet_editor_save_button").performClick()
        assertEquals("Check Disk", savedTitle)

        // Delete via overflow menu MUST show confirmation dialog first
        composeRule.onNodeWithTag("snippet_more_s1").performClick()
        composeRule.onNodeWithTag("snippet_menu_delete_s1").performClick()
        assertEquals(null, deletedId)
        composeRule.onNodeWithTag("snippet_delete_confirm_dialog").assertIsDisplayed()

        composeRule.onNodeWithTag("confirm_delete_snippet").performClick()
        composeRule.waitForIdle()
        assertEquals("s1", deletedId)
    }

    @Test
    fun `tapping card opens detail bottom sheet and Insert preserves multiline confirmation`() {
        var insertedCmd: String? = null
        val snippets = listOf(
            CommandSnippet(
                id = "multi_1",
                title = "Deploy Check",
                command = "git fetch origin\ngit status -sb\necho done",
                tags = listOf("git", "deploy", "ci", "prod")
            )
        )

        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.HACKER_PRO) {
                SnippetsScreen(
                    snippets = snippets,
                    onInsertSnippet = { insertedCmd = it },
                    onCopySnippet = {},
                    onSaveSnippet = { _, _, _, _ -> },
                    onDeleteSnippet = {},
                    onBuildExportJson = { "[]" },
                    onExportSuccess = {},
                    onImportJson = {},
                    onIoError = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Tapping card body opens detail sheet
        composeRule.onNodeWithTag("snippet_card_multi_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("script_detail_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("detail_insert_button").assertIsDisplayed()
        composeRule.onNodeWithTag("detail_copy_button").assertIsDisplayed()
        composeRule.onNodeWithTag("detail_edit_button").assertIsDisplayed()
        composeRule.onNodeWithTag("detail_delete_button").assertIsDisplayed()

        // Insert from detail sheet still gates multiline script behind HighRiskCommandConfirmDialog
        composeRule.onNodeWithTag("detail_insert_button").performClick()
        composeRule.waitForIdle()
        assertEquals(null, insertedCmd)
        composeRule.onNodeWithTag("high_risk_insert_dialog").assertIsDisplayed()

        composeRule.onNodeWithTag("confirm_high_risk_insert").performClick()
        composeRule.waitForIdle()
        assertEquals("git fetch origin\ngit status -sb\necho done", insertedCmd)
    }

    @Test
    fun `sidebar and rail render Scriptbook and Buku Skrip cleanly at 320dp and fontScale 1_3`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val idCtx = LocaleHelper.createLocalizedContext(base, AppLanguage.INDONESIAN)
        val expectedIdLabel = idCtx.getString(R.string.nav_snippets)
        assertEquals("Buku Skrip", expectedIdLabel)

        var useRail by mutableStateOf(false)
        composeRule.setContent {
            val baseDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity, fontScale = 1.3f)
            ) {
                if (!useRail) {
                    HackerStudioTheme(appTheme = AppThemePreset.HACKER_PRO) {
                        Box(modifier = Modifier.width(320.dp)) {
                            WorkspaceSidebarDrawer(
                                visible = true,
                                selectedSection = WorkspaceSection.SNIPPETS,
                                aiEnabled = true,
                                activeTheme = AppThemePreset.HACKER_PRO,
                                onSelectSection = {},
                                onOpenSettings = {},
                                onDismiss = {}
                            )
                        }
                    }
                } else {
                    HackerStudioTheme(appTheme = AppThemePreset.CYBERPUNK) {
                        WorkspaceNavigationRail(
                            selectedSection = WorkspaceSection.SNIPPETS,
                            aiEnabled = true,
                            onSelectSection = {},
                            onOpenSettings = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sidebar_snippets").assertIsDisplayed()
        composeRule.onNodeWithText(base.getString(R.string.nav_snippets), useUnmergedTree = true)
            .assertIsDisplayed()

        composeRule.runOnUiThread { useRail = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rail_snippets").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_indicator_bottom_neon_glow").assertIsDisplayed()
    }

    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast48Dp(tagDesc: String) {
        val node = fetchSemanticsNode()
        val density = composeRule.density
        val touchRect = node.touchBoundsInRoot
        val widthDp = with(density) { touchRect.width.toDp().value }
        val heightDp = with(density) { touchRect.height.toDp().value }
        assertTrue(
            "Expected $tagDesc touch width >= 48.dp, was ${widthDp}dp",
            widthDp >= 47.9f
        )
        assertTrue(
            "Expected $tagDesc touch height >= 48.dp, was ${heightDp}dp",
            heightDp >= 47.9f
        )
    }

    @Test
    fun `StudioDimens exposes exact downsized tokens and WorkspaceSidebarDrawer keeps 40dp items with 48dp touch in BOTH themes`() {
        var currentPreset by mutableStateOf(AppThemePreset.HACKER_PRO)
        composeRule.setContent {
            val baseDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity, fontScale = 1.3f)
            ) {
                HackerStudioTheme(appTheme = currentPreset) {
                    val d = StudioTheme.dimens
                    assertEquals(32.dp, d.controlHeight)
                    assertEquals(28.dp, d.controlHeightSmall)
                    assertEquals(24.dp, d.chipHeight)
                    assertEquals(16.dp, d.iconSize)
                    assertEquals(14.dp, d.iconSizeSmall)
                    assertEquals(18.dp, d.navIconSize)
                    assertEquals(18.dp, d.tabIconVisualSize)
                    assertEquals(28.dp, d.tabHeight)
                    assertEquals(260.dp, d.sidebarWidth)
                    assertEquals(40.dp, d.sidebarItemHeight)
                    assertEquals(20.dp, d.sidebarGestureZone)
                    assertEquals(56.dp, d.railWidth)
                    assertEquals(40.dp, d.fabSize)
                    assertEquals(10.dp, d.cardPaddingH)
                    assertEquals(8.dp, d.cardPaddingV)
                    assertEquals(6.dp, d.listSpacing)
                    assertEquals(48.dp, d.minTouchTarget)

                    Box(modifier = Modifier.width(320.dp)) {
                        WorkspaceSidebarDrawer(
                            visible = true,
                            selectedSection = WorkspaceSection.SNIPPETS,
                            aiEnabled = true,
                            activeTheme = currentPreset,
                            onSelectSection = {},
                            onOpenSettings = {},
                            onDismiss = {}
                        )
                    }
                }
            }
        }

        val density = composeRule.density
        val base = ApplicationProvider.getApplicationContext<Context>()
        AppThemePreset.entries.forEach { preset ->
            composeRule.runOnUiThread { currentPreset = preset }
            composeRule.waitForIdle()

            val sidebarNode = composeRule.onNodeWithTag("workspace_sidebar").fetchSemanticsNode()
            val sidebarBounds = sidebarNode.boundsInRoot
            val sidebarWidthDp = with(density) { sidebarBounds.width.toDp().value }
            assertTrue("${preset.name} sidebar width at 320dp must be <= 256dp (80%), was ${sidebarWidthDp}dp", sidebarWidthDp <= 256.5f)

            val snippetsTabNode = composeRule.onNodeWithTag("sidebar_snippets").fetchSemanticsNode()
            val itemHeightDp = with(density) { snippetsTabNode.boundsInRoot.height.toDp().value }
            assertTrue("${preset.name} sidebar item visual height must be ~40.dp, was ${itemHeightDp}dp", itemHeightDp in 39.5f..40.5f)
            composeRule.onNodeWithTag("sidebar_snippets").assertTouchTargetAtLeast48Dp("sidebar_snippets")

            val labelNode = composeRule.onNodeWithText(base.getString(R.string.nav_snippets), useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
            assertTrue("${preset.name} label right clipped", labelNode.boundsInRoot.right <= sidebarBounds.right)
        }
    }

    @Test
    fun `Scriptbook dual-theme layout places Insert on right, clears chamfer, removes FAB, and avoids badge truncation`() {
        var insertedCmd: String? = null
        val snippets = listOf(
            CommandSnippet(
                id = "s_high",
                title = "Wipe Logs Long Title Name For Layout Verification",
                command = "rm -rf /tmp/old_cache/*",
                tags = listOf("cleanup", "audit", "system")
            )
        )
        var currentPreset by mutableStateOf(AppThemePreset.HACKER_PRO)
        var chamferCutDp = 0.dp

        composeRule.setContent {
            HackerStudioTheme(appTheme = currentPreset) {
                chamferCutDp = StudioTheme.tokens.cardShape.chamferInset()
                SnippetsScreen(
                    snippets = snippets,
                    onInsertSnippet = { insertedCmd = it },
                    onCopySnippet = {},
                    onSaveSnippet = { _, _, _, _ -> },
                    onDeleteSnippet = {},
                    onBuildExportJson = { "[]" },
                    onExportSuccess = {},
                    onImportJson = {},
                    onIoError = {}
                )
            }
        }

        val density = composeRule.density
        AppThemePreset.entries.forEach { preset ->
            composeRule.runOnUiThread { currentPreset = preset }
            composeRule.waitForIdle()

            // No floating FAB in Scriptbook; "+" lives in top app bar next to search
            composeRule.onAllNodesWithTag("snippets_fab").assertCountEquals(0)
            composeRule.onNodeWithTag("snippets_new_button").assertIsDisplayed()
            composeRule.onNodeWithTag("snippets_search_toggle").assertIsDisplayed()

            val newBtnBounds = composeRule.onNodeWithTag("snippets_new_button").fetchSemanticsNode().boundsInRoot
            val searchBtnBounds = composeRule.onNodeWithTag("snippets_search_toggle").fetchSemanticsNode().boundsInRoot
            assertTrue("${preset.name}: new (+) button should appear on top bar next to search", newBtnBounds.right <= searchBtnBounds.left + 1f)

            // Tag bar leftmost pill starts cleanly inside padding
            val tagBarBounds = composeRule.onNodeWithTag("scriptbook_tag_bar").fetchSemanticsNode().boundsInRoot
            val allPillBounds = composeRule.onNodeWithTag("tag_filter_all").fetchSemanticsNode().boundsInRoot
            assertTrue("${preset.name}: leftmost tag pill should clear tag bar left edge", allPillBounds.left > tagBarBounds.left)

            // Card geometry: title clears chamfer, Insert on the right of title, <= 36dp height, no overlap with more button
            val cardBounds = composeRule.onNodeWithTag("snippet_card_s_high").fetchSemanticsNode().boundsInRoot
            val titleBounds = composeRule.onNodeWithTag("snippet_title_s_high", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val insertNode = composeRule.onNodeWithTag("snippet_insert_s_high").fetchSemanticsNode()
            val insertBounds = insertNode.boundsInRoot
            val moreBounds = composeRule.onNodeWithTag("snippet_more_s_high").fetchSemanticsNode().boundsInRoot
            val badgeNode = composeRule.onNodeWithTag("risk_badge_high", useUnmergedTree = true).fetchSemanticsNode()
            val badgeBounds = badgeNode.boundsInRoot

            val chamferPx = with(density) { chamferCutDp.toPx() }
            assertTrue(
                "${preset.name}: title left (${titleBounds.left}) must clear card left + chamfer (${cardBounds.left + chamferPx})",
                titleBounds.left >= cardBounds.left + chamferPx - 0.5f
            )

            val insertHeightDp = with(density) { insertBounds.height.toDp().value }
            assertTrue("${preset.name}: Insert height must be <= 36.dp, was ${insertHeightDp}dp", insertHeightDp <= 36.0f)
            assertTrue(
                "${preset.name}: Insert button must sit to the right of title column",
                insertBounds.left >= titleBounds.right - 1f
            )
            assertFalse("${preset.name}: more button must not overlap Insert", moreBounds.overlaps(insertBounds))
            assertFalse("${preset.name}: more button must not overlap title", moreBounds.overlaps(titleBounds))

            // Badge must sit on the title row, single line, not truncated
            assertTrue("${preset.name}: badge must not clip past insert button", badgeBounds.right <= insertBounds.left + 1f)
            val badgeHeightDp = with(density) { badgeBounds.height.toDp().value }
            assertTrue("${preset.name}: badge must remain single-line compact height <= 28.dp, was ${badgeHeightDp}dp", badgeHeightDp <= 28.0f)

            // Touch targets >= 48dp across Insert, Overflow, Tags, New, Search
            composeRule.onNodeWithTag("snippet_insert_s_high").assertTouchTargetAtLeast48Dp("snippet_insert_s_high")
            composeRule.onNodeWithTag("snippet_more_s_high").assertTouchTargetAtLeast48Dp("snippet_more_s_high")
            composeRule.onNodeWithTag("tag_filter_all").assertTouchTargetAtLeast48Dp("tag_filter_all")
            composeRule.onNodeWithTag("tag_filter_cleanup").assertTouchTargetAtLeast48Dp("tag_filter_cleanup")
            composeRule.onNodeWithTag("snippets_new_button").assertTouchTargetAtLeast48Dp("snippets_new_button")
            composeRule.onNodeWithTag("snippets_search_toggle").assertTouchTargetAtLeast48Dp("snippets_search_toggle")
        }

        // Expanded margin tap still fires Insert (high-risk opens confirm dialog cleanly without changing logic)
        composeRule.onNodeWithTag("snippet_insert_s_high").performTouchInput {
            click(Offset(centerX, -6f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("high_risk_insert_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_high_risk_insert").performClick()
        composeRule.waitForIdle()
        assertEquals("rm -rf /tmp/old_cache/*", insertedCmd)
    }

    @Test
    fun `narrow width below 360dp renders compact icon-only Insert with accessible label`() {
        var insertedCmd: String? = null
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snippets = listOf(
            CommandSnippet(
                id = "s_narrow",
                title = "List Ports",
                command = "ss -tulnp",
                tags = listOf("net", "audit")
            )
        )

        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.CYBERPUNK) {
                Box(modifier = Modifier.width(320.dp)) {
                    SnippetsScreen(
                        snippets = snippets,
                        onInsertSnippet = { insertedCmd = it },
                        onCopySnippet = {},
                        onSaveSnippet = { _, _, _, _ -> },
                        onDeleteSnippet = {},
                        onBuildExportJson = { "[]" },
                        onExportSuccess = {},
                        onImportJson = {},
                        onIoError = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val insertNode = composeRule.onNodeWithTag("snippet_insert_s_narrow").fetchSemanticsNode()
        val insertWidthDp = with(density) { insertNode.boundsInRoot.width.toDp().value }
        assertTrue("Narrow (< 360dp) Insert button should be icon-only <= 34.dp wide, was ${insertWidthDp}dp", insertWidthDp <= 34.0f)
        assertEquals(
            listOf(context.getString(R.string.snippets_insert)),
            insertNode.config[SemanticsProperties.ContentDescription]
        )
        composeRule.onNodeWithTag("snippet_insert_s_narrow").assertTouchTargetAtLeast48Dp("snippet_insert_s_narrow")
        composeRule.onNodeWithTag("snippet_insert_s_narrow").performClick()
        composeRule.waitForIdle()
        assertEquals("ss -tulnp", insertedCmd)
    }

    @Test
    fun `SessionTabsBar compact tabs and 18dp close or rename buttons maintain at least 48dp touch bounds`() {
        var closedId: String? = null
        val tabs = listOf(
            SessionTabItem(id = "s1", title = "main", lastExitCode = 0, isActive = true)
        )

        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.HACKER_PRO) {
                SessionTabsBar(
                    sessions = tabs,
                    activeSessionId = "s1",
                    aiEnabled = true,
                    isAiPanelOpen = false,
                    showSplitButton = true,
                    splitMode = SplitMode.SINGLE,
                    onSelectSession = {},
                    onAddSession = {},
                    onCloseSession = { closedId = it },
                    onRenameSession = { _, _ -> },
                    onToggleAiPanel = {},
                    onToggleSplitMode = {}
                )
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val tabNode = composeRule.onNodeWithTag("session_tab_s1").fetchSemanticsNode()
        val tabHeightDp = with(density) { tabNode.boundsInRoot.height.toDp().value }
        assertTrue("Session tab height should be <= 30.dp, was ${tabHeightDp}dp", tabHeightDp <= 30.0f)

        val closeNode = composeRule.onNodeWithTag("close_session_s1").fetchSemanticsNode()
        val closeVisualDp = with(density) { closeNode.boundsInRoot.height.toDp().value }
        assertTrue("Close icon visual size should be <= 20.dp, was ${closeVisualDp}dp", closeVisualDp <= 20.0f)

        composeRule.onNodeWithTag("close_session_s1").assertTouchTargetAtLeast48Dp("close_session_s1")
        composeRule.onNodeWithTag("rename_session_button").assertTouchTargetAtLeast48Dp("rename_session_button")
        composeRule.onNodeWithTag("add_session_button").assertTouchTargetAtLeast48Dp("add_session_button")
        composeRule.onNodeWithTag("toggle_ai_panel_button").assertTouchTargetAtLeast48Dp("toggle_ai_panel_button")

        // Real pointer tap in expanded vertical margin above 18.dp close button closes the session!
        composeRule.onNodeWithTag("close_session_s1").performTouchInput {
            click(Offset(centerX, -10f))
        }
        composeRule.waitForIdle()
        assertEquals("s1", closedId)
    }

    @Test
    fun `ExtraKeysBar splits keys across two non-scrolling equal-weight rows without touch overlap`() {
        val pressedKeys = mutableListOf<ExtraKeyCode>()

        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.CYBERPUNK) {
                ExtraKeysBar(
                    layout = ExtraKeysLayout.STANDARD,
                    ctrlArmed = true,
                    altArmed = false,
                    onKeyClick = { pressedKeys.add(it) }
                )
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        val keys = ExtraKeysLayout.STANDARD.keys
        val firstRowCount = (keys.size + 1) / 2
        val row1Keys = keys.take(firstRowCount)
        val row2Keys = keys.drop(firstRowCount)

        val row1Bounds = row1Keys.map { key ->
            val tag = "extra_key_${key.name.lowercase()}"
            val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            val heightDp = with(density) { node.boundsInRoot.height.toDp().value }
            assertEquals("$tag row height should match 38.dp token", 38.0f, heightDp, 1.5f)
            node.boundsInRoot
        }
        val row2Bounds = row2Keys.map { key ->
            val tag = "extra_key_${key.name.lowercase()}"
            val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            val heightDp = with(density) { node.boundsInRoot.height.toDp().value }
            assertEquals("$tag row height should match 38.dp token", 38.0f, heightDp, 1.5f)
            node.boundsInRoot
        }

        assertTrue(
            "Row 1 horizontal order mismatch",
            row1Bounds.zipWithNext().all { (a, b) -> a.right < b.left }
        )
        assertTrue(
            "Row 2 horizontal order mismatch",
            row2Bounds.zipWithNext().all { (a, b) -> a.right < b.left }
        )
        assertTrue(
            "Row 1 must sit strictly above Row 2 without vertical overlap",
            row1Bounds.maxOf { it.bottom } < row2Bounds.minOf { it.top }
        )

        composeRule.onNodeWithTag("extra_key_ctrl").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(ExtraKeyCode.CTRL), pressedKeys)
    }

    @Test
    fun `WorkspaceComponents removes Material 3 NavigationBar and all UI components avoid hardcoded control dp literals`() {
        val workspaceFile = locateRes("src/main/java/com/example/ui/terminal/WorkspaceComponents.kt")
        val workspaceText = workspaceFile.readText()
        assertFalse(
            "WorkspaceComponents.kt must not import or use Material 3 NavigationBar",
            Regex("""\bNavigationBar(Defaults|Item|ItemDefaults)?\b""").containsMatchIn(workspaceText)
        )
        assertFalse(
            "WorkspaceComponents.kt must not import or use Material 3 NavigationRail",
            Regex("""\bNavigationRail(Item|ItemDefaults)?\b""").containsMatchIn(workspaceText)
        )

        val files = listOf(
            locateRes("src/main/java/com/example/ui/terminal/ExtraKeysBar.kt"),
            locateRes("src/main/java/com/example/ui/terminal/SessionTabsBar.kt"),
            locateRes("src/main/java/com/example/ui/terminal/SnippetsScreen.kt"),
            locateRes("src/main/java/com/example/ui/terminal/WorkspaceComponents.kt"),
            locateRes("src/main/java/com/example/ui/terminal/TerminalScreen.kt"),
            locateRes("src/main/java/com/example/ui/terminal/TerminalConsoleView.kt"),
            locateRes("src/main/java/com/example/ui/ai/CommandSuggestionCard.kt"),
            locateRes("src/main/java/com/example/ui/ai/AiComponents.kt"),
            locateRes("src/main/java/com/example/ui/ai/AiBottomSheet.kt"),
            locateRes("src/main/java/com/example/ui/ai/AiFullScreen.kt"),
            locateRes("src/main/java/com/example/ui/theme/ThemePreviewCard.kt"),
            locateRes("src/main/java/com/example/settings/SettingsComponents.kt"),
            locateRes("src/main/java/com/example/settings/SettingsScreen.kt"),
            locateRes("src/main/java/com/example/settings/SettingsHomeScreen.kt"),
            locateRes("src/main/java/com/example/settings/AppearanceSettingsScreen.kt"),
            locateRes("src/main/java/com/example/settings/AiSettingsScreen.kt"),
            locateRes("src/main/java/com/example/settings/ApiKeysScreen.kt"),
            locateRes("src/main/java/com/example/settings/ApiKeyComponents.kt"),
            locateRes("src/main/java/com/example/settings/TerminalSettingsScreen.kt"),
            locateRes("src/main/java/com/example/settings/LanguageSettingsScreen.kt"),
            locateRes("src/main/java/com/example/settings/DataPrivacySettingsScreen.kt"),
            locateRes("src/main/java/com/example/settings/AboutScreen.kt")
        )
        val controlDpRegex = Regex("""\b(height|size|heightIn|widthIn|defaultMinSize)\s*\(\s*[^)]*\d+(\.\d+)?\.dp""")
        files.forEach { file ->
            val match = controlDpRegex.find(file.readText())
            assertEquals("Found hardcoded control dp in ${file.name}: ${match?.value}", null, match)
        }
    }
}
