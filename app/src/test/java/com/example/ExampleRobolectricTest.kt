package com.example

import android.content.Context
import android.os.Looper
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
import com.example.data.KeyRoutingStrategy
import com.example.data.LocaleHelper
import com.example.data.PromptHistoryItem
import com.example.data.PromptHistoryStore
import com.example.data.SettingsStore
import com.example.data.SnippetStore
import com.example.data.VaultStatus
import com.example.terminal.FakeTerminalBackend
import com.example.ui.UiText
import com.example.ui.terminal.TerminalViewModel
import com.example.ui.theme.AppThemePreset
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `verify HackerStudio app name, com_termux package name, and bilingual localization`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("HackerStudio", baseContext.getString(R.string.app_name))
        assertEquals("com.termux", baseContext.packageName)

        val enContext = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.ENGLISH)
        val idContext = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.INDONESIAN)

        assertEquals("Workspace Settings", enContext.getString(R.string.settings_title))
        assertEquals("Pengaturan Ruang Kerja", idContext.getString(R.string.settings_title))
        assertEquals("New Shell Session", enContext.getString(R.string.empty_sessions_cta))
        assertEquals("Sesi Shell Baru", idContext.getString(R.string.empty_sessions_cta))
        assertEquals("Response truncated", enContext.getString(R.string.ai_response_truncated))
        assertEquals("Jawaban terpotong", idContext.getString(R.string.ai_response_truncated))
    }

    @Test
    fun `same TerminalBackend and sessions survive Activity recreation`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val initialActivity = controller.get()
        val initialBackend = initialActivity.terminalBackend
        val initialKeyManager = initialActivity.appContainer.keyManager
        val createdSession = initialBackend.createSession("persist-session")

        controller.recreate()

        val recreatedActivity = controller.get()
        assertSame(initialBackend, recreatedActivity.terminalBackend)
        assertSame(initialKeyManager, recreatedActivity.appContainer.keyManager)
        assertTrue(recreatedActivity.terminalBackend.sessions.value.any { it.id == createdSession.id })

        controller.pause().stop().destroy()
    }

    @Test
    fun `localized context preserves ActivityResultRegistryOwner and OnBackPressedDispatcherOwner`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val localized = LocaleHelper.createLocalizedContext(activity, AppLanguage.INDONESIAN)
        assertEquals("Pengaturan Ruang Kerja", localized.getString(R.string.settings_title))

        var cursor: Context? = localized
        var foundActivity: MainActivity? = null
        while (cursor is android.content.ContextWrapper) {
            if (cursor is MainActivity) {
                foundActivity = cursor
                break
            }
            cursor = cursor.baseContext
        }
        assertSame(activity, foundActivity)

        controller.pause().stop().destroy()
    }

    @Test
    fun `insertSuggestedCommand uses bracketed paste when 2004 is on, copies to clipboard when off for multiline, and inserts single-line as-is`() {
        val backend = FakeTerminalBackend()
        val session = backend.sessions.value.first()
        val settingsStore = object : SettingsStore {
            private val flow = MutableStateFlow(AppSettings())
            override val settingsFlow: Flow<AppSettings> = flow.asStateFlow()
            override val vaultStatus: StateFlow<VaultStatus> =
                MutableStateFlow<VaultStatus>(VaultStatus.Ready).asStateFlow()
            override suspend fun setAppTheme(theme: AppThemePreset) {}
            override suspend fun setReduceEffects(enabled: Boolean) {}
            override suspend fun setAppLanguage(language: AppLanguage) {}
            override suspend fun setOnboardingCompleted(completed: Boolean) {}
            override suspend fun setFontSizeSp(sizeSp: Float) {}
            override suspend fun setExtraKeysLayout(layout: ExtraKeysLayout) {}
            override suspend fun setAiEnabled(enabled: Boolean) {}
            override suspend fun setSelectedModel(modelId: String?) {}
            override suspend fun setFallbackModels(entries: List<com.example.data.FallbackModelEntry>) {}
            override suspend fun setRoutingStrategy(strategy: KeyRoutingStrategy) {}
            override suspend fun setCooldownSeconds(seconds: Int) {}
            override suspend fun addApiKey(label: String, rawKey: String) =
                Result.failure<ApiKeyMetadata>(IllegalStateException("unused"))
            override suspend fun removeApiKey(id: String) {}
            override suspend fun setApiKeyEnabled(id: String, enabled: Boolean) {}
            override suspend fun updateApiKeyLabel(id: String, newLabel: String) {}
            override fun getSecretKey(id: String): String? = null
        }
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
            override suspend fun saveSnippet(
                title: String,
                command: String,
                tags: List<String>,
                existingId: String?
            ) = CommandSnippet(id = existingId ?: "test-snippet", title = title, command = command)
            override suspend fun deleteSnippet(id: String) {}
            override fun exportToJson(snippets: List<CommandSnippet>): String = "[]"
            override suspend fun importFromJson(rawJson: String): Result<Int> = Result.success(0)
        }
        val historyStore = object : PromptHistoryStore {
            override val historyFlow: Flow<List<PromptHistoryItem>> = MutableStateFlow(emptyList())
            override suspend fun recordPrompt(prompt: String) {}
            override suspend fun clearAll() {}
        }
        val viewModel = TerminalViewModel(
            terminalBackend = backend,
            aiRepository = aiRepo,
            keyManager = keyManager,
            settingsStore = settingsStore,
            snippetStore = snippetStore,
            promptHistoryStore = historyStore
        )

        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val job = collectorScope.launch { viewModel.uiState.collect {} }
        try {
            shadowOf(Looper.getMainLooper()).idle()

            // 1. Mode ON + multiline -> inserted via bracketed paste without " && "
            assertTrue(session.bracketedPasteEnabled.value)
            var copiedText: String? = null
            viewModel.insertSuggestedCommand("echo first\necho second\n") { copiedText = it }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(null, copiedText)
            assertEquals(UiText.Resource(R.string.status_inserted_draft), viewModel.uiState.value.statusBannerMessage)
            val gridText = session.readRecentText()
            assertTrue(gridText.contains("echo first"))
            assertTrue(gridText.contains("echo second"))
            assertFalse(gridText.contains(" && "))

            // Clear draft line buffer via Ctrl+U and disable DECSET 2004
            session.write(byteArrayOf(0x15))
            session.write("printf \"\\033[?2004l\"\r".encodeToByteArray())
            assertFalse(session.bracketedPasteEnabled.value)

            // 2. Mode OFF + multiline -> NOT inserted; copied to clipboard + explanatory banner shown
            val versionBefore = session.screenVersion.value
            viewModel.insertSuggestedCommand("echo line1\necho line2") { copiedText = it }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("echo line1\necho line2", copiedText)
            assertEquals(versionBefore, session.screenVersion.value)
            assertEquals(
                UiText.Resource(R.string.status_multiline_paste_copied),
                viewModel.uiState.value.statusBannerMessage
            )

            // 3. Mode OFF + single-line -> inserted as-is
            copiedText = null
            viewModel.insertSuggestedCommand("pwd") { copiedText = it }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(null, copiedText)
            assertTrue(session.readRecentText().contains("pwd"))
            assertEquals(UiText.Resource(R.string.status_inserted_draft), viewModel.uiState.value.statusBannerMessage)
        } finally {
            job.cancel()
            collectorScope.cancel()
        }
    }
}
