package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.example.data.SnippetJsonCodec
import com.example.data.SnippetStore
import com.example.data.VaultStatus
import com.example.terminal.FakeTerminalBackend
import com.example.terminal.TerminalPasteSanitizer
import com.example.ui.terminal.SnippetsScreen
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
import kotlinx.coroutines.flow.flow
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InputSecurityAndImportTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class MemorySettingsStore : SettingsStore {
        private val state = MutableStateFlow(AppSettings(selectedModel = "gemini-3.8-flash"))
        override val settingsFlow: Flow<AppSettings> = state.asStateFlow()
        override val vaultStatus: StateFlow<VaultStatus> = MutableStateFlow<VaultStatus>(VaultStatus.Ready).asStateFlow()
        override suspend fun setAppTheme(theme: AppThemePreset) {}
        override suspend fun setReduceEffects(enabled: Boolean) {}
        override suspend fun setAppLanguage(language: AppLanguage) {}
        override suspend fun setOnboardingCompleted(completed: Boolean) {}
        override suspend fun setFontSizeSp(sizeSp: Float) {}
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

    private fun buildVm(): Triple<TerminalViewModel, FakeTerminalBackend, CoroutineScope> {
        val backend = FakeTerminalBackend()
        val settings = MemorySettingsStore()
        val keyManager = KeyManager(settings)
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
            ): Flow<GeminiStreamEvent> = flow { emit(GeminiStreamEvent.Completed("STOP")) }
        }
        val repo = AiAssistantRepository(keyManager, transport, settings)
        val snippets = object : SnippetStore {
            override val snippetsFlow: Flow<List<CommandSnippet>> = MutableStateFlow(emptyList())
            override suspend fun saveSnippet(title: String, command: String, tags: List<String>, existingId: String?) =
                CommandSnippet(id = "1", title = title, command = command)
            override suspend fun deleteSnippet(id: String) {}
            override fun exportToJson(snippets: List<CommandSnippet>): String = "[]"
            override suspend fun importFromJson(rawJson: String): Result<Int> =
                SnippetJsonCodec.parseImportJson(rawJson).map { it.size }
        }
        val history = object : PromptHistoryStore {
            override val historyFlow: Flow<List<PromptHistoryItem>> = MutableStateFlow(emptyList())
            override suspend fun recordPrompt(prompt: String) {}
            override suspend fun clearAll() {}
        }
        val vm = TerminalViewModel(backend, repo, keyManager, settings, snippets, history)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        return Triple(vm, backend, scope)
    }

    @Test
    fun `unconfirmed snippet with control chars or newlines is blocked and confirmed snippet is sanitized without executing`() {
        val (vm, backend, scope) = buildVm()
        val job = scope.launch { vm.uiState.collect {} }
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val session = backend.sessions.value.first()
            val versionBefore = session.screenVersion.value

            // Unconfirmed multiline/control snippet MUST NOT write to terminal
            vm.insertSnippetCommand("echo one\necho two", userConfirmed = false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(versionBefore, session.screenVersion.value)
            assertEquals(
                context.getString(R.string.status_snippet_confirm_required),
                vm.uiState.value.statusBannerMessage?.asString(context)
            )

            vm.insertSnippetCommand("echo hi\u0003ls", userConfirmed = false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(versionBefore, session.screenVersion.value)

            // Confirmed insertion strips dangerous ESC/C0/201~ markers and writes as draft (does not auto-run 'id')
            vm.insertSnippetCommand("echo draft\u001B[201~\u0003\nid", userConfirmed = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(session.screenVersion.value > versionBefore)
            val recent = session.readRecentText()
            assertTrue(recent.contains("echo draft"))
            assertTrue(recent.contains("id"))
            assertFalse(recent.contains("uid=10137")) // 'id' did NOT auto-run
            assertFalse(recent.contains("^C")) // raw ETX did NOT trigger interrupt

            // Over 64 KB paste surfaces truncation banner
            vm.insertSuggestedCommand("x".repeat(TerminalPasteSanitizer.MAX_PASTE_BYTES + 256))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                context.getString(R.string.status_paste_truncated),
                vm.uiState.value.statusBannerMessage?.asString(context)
            )
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `SnippetsScreen prompts confirmation before inserting snippet with newlines or control characters`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snippets = listOf(
            CommandSnippet(
                id = "multi_1",
                title = "Multi-line Safe",
                command = "echo step1\necho step2"
            )
        )
        var insertedCommand: String? = null

        composeRule.setContent {
            HackerStudioTheme(appTheme = AppThemePreset.HACKER_PRO) {
                SnippetsScreen(
                    snippets = snippets,
                    onInsertSnippet = { insertedCommand = it },
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

        composeRule.onNodeWithTag("snippet_insert_multi_1").performClick()
        composeRule.waitForIdle()

        // Must require confirmation before calling onInsertSnippet
        assertEquals(null, insertedCommand)
        composeRule.onNodeWithTag("high_risk_insert_dialog").assertIsDisplayed()

        composeRule.onNodeWithTag("confirm_high_risk_insert").performClick()
        composeRule.waitForIdle()
        assertEquals("echo step1\necho step2", insertedCommand)
    }

    @Test
    fun `bilingual strings exist in English and Indonesian for all new security keys`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val en = LocaleHelper.createLocalizedContext(base, AppLanguage.ENGLISH)
        val id = LocaleHelper.createLocalizedContext(base, AppLanguage.INDONESIAN)

        val keys = listOf(
            R.string.status_paste_truncated,
            R.string.status_snippet_confirm_required,
            R.string.risk_reason_find_delete,
            R.string.risk_reason_root_perm,
            R.string.risk_reason_too_long,
            R.string.risk_reason_multiline_or_control,
            R.string.snippets_import_err_file_size
        )
        keys.forEach { res ->
            val enStr = en.getString(res)
            val idStr = id.getString(res)
            assertTrue(enStr.isNotBlank())
            assertTrue(idStr.isNotBlank())
            assertTrue("Expected distinct localized string for $res", enStr != idStr)
        }
    }
}
