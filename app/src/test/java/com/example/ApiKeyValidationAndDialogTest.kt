package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiAssistantRepository
import com.example.ai.AiPromptKind
import com.example.ai.AiPromptRequest
import com.example.ai.AiStreamUpdate
import com.example.ai.GeminiApiClient
import com.example.ai.GeminiCallResult
import com.example.ai.GeminiContent
import com.example.ai.GeminiStreamEvent
import com.example.ai.GeminiTransport
import com.example.ai.KeyHealthStatus
import com.example.ai.KeyManager
import com.example.ai.SensitiveDataRedactor
import com.example.data.ApiKeyMetadata
import com.example.data.ApiKeyValidator
import com.example.data.AppLanguage
import com.example.data.AppSettings
import com.example.data.KeyVault
import com.example.data.LocaleHelper
import com.example.data.SettingsRepository
import com.example.data.VaultStatus
import com.example.settings.ApiKeysScreen
import com.example.settings.SettingsViewModel
import com.example.ui.theme.CyberorgTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
class ApiKeyValidationAndDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class InMemoryVault : KeyVault {
        val entries = mutableMapOf<String, String>()
        private val _status = MutableStateFlow<VaultStatus>(VaultStatus.Ready)
        override val vaultStatus: StateFlow<VaultStatus> = _status.asStateFlow()

        override fun getKey(id: String): String? = entries[id]
        override fun putKey(id: String, rawKey: String) {
            entries[id] = rawKey
        }
        override fun removeKey(id: String) {
            entries.remove(id)
        }
        override fun maskKey(rawKey: String): String {
            val clean = rawKey.trim()
            return if (clean.length <= 8) "••••" else "${clean.take(4)}••••${clean.takeLast(4)}"
        }
    }

    private fun createTempRepo(
        scope: CoroutineScope,
        vault: InMemoryVault = InMemoryVault()
    ): Pair<SettingsRepository, File> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dsFile = File(context.filesDir, "datastore/test_aq_keys_${System.nanoTime()}.preferences_pb")
        dsFile.parentFile?.mkdirs()
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { dsFile }
        )
        return SettingsRepository(context, vault, dataStore) to dsFile
    }

    @Test
    fun `ApiKeyValidator accepts AQ and AIza keys, cleans prefixes and quotes, and rejects bad prefixes or chars`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val idContext = LocaleHelper.createLocalizedContext(context, AppLanguage.INDONESIAN)

        val validAq = "AQ.AbCdEfGhIjKlMnOpQrStUvWxYz_01-23.45"
        val validAiza = "AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz012345"
        val minLenAq = "AQ." + "a".repeat(17) // 20 chars total
        val maxLenAq = "AQ." + "Z".repeat(297) // 300 chars total

        assertEquals(ApiKeyValidator.ValidationResult.Valid(validAq), ApiKeyValidator.validate(validAq))
        assertEquals(ApiKeyValidator.ValidationResult.Valid(validAiza), ApiKeyValidator.validate(validAiza))
        assertEquals(ApiKeyValidator.ValidationResult.Valid(minLenAq), ApiKeyValidator.validate(minLenAq))
        assertEquals(ApiKeyValidator.ValidationResult.Valid(maxLenAq), ApiKeyValidator.validate(maxLenAq))

        // Cleaning spaces, newlines, quotes, and assignment prefixes
        val messy = "  export GEMINI_API_KEY = \"AQ.Ab123456\n7890_xyz-._12\" \r\n "
        val cleanedResult = ApiKeyValidator.validate(messy)
        assertEquals(ApiKeyValidator.ValidationResult.Valid("AQ.Ab1234567890_xyz-._12"), cleanedResult)

        val keyPrefixWrapped = "key='$validAiza'"
        assertEquals(ApiKeyValidator.ValidationResult.Valid(validAiza), ApiKeyValidator.validate(keyPrefixWrapped))

        // Other prefixes rejected and error message mentions both AQ. and AIza
        val wrongPrefix = ApiKeyValidator.validate("sk-1234567890abcdefghijklmnop")
        assertTrue(wrongPrefix is ApiKeyValidator.ValidationResult.Invalid)
        val wrongMsg = (wrongPrefix as ApiKeyValidator.ValidationResult.Invalid).uiReason.asString(idContext)
        assertTrue(wrongMsg.contains("AQ."))
        assertTrue(wrongMsg.contains("AIza"))

        // Too short (< 20) and too long (> 300) rejected
        assertTrue(ApiKeyValidator.validate("AQ." + "a".repeat(16)) is ApiKeyValidator.ValidationResult.Invalid)
        assertTrue(ApiKeyValidator.validate("AQ." + "a".repeat(298)) is ApiKeyValidator.ValidationResult.Invalid)

        // Invalid characters & internal spaces rejected
        assertTrue(ApiKeyValidator.validate("AQ.AbCdEfGh!jKlMnOpQrSt") is ApiKeyValidator.ValidationResult.Invalid)
        assertTrue(ApiKeyValidator.validate("AQ.AbCdEfGhIjKl MnOpQr") is ApiKeyValidator.ValidationResult.Invalid)
        assertTrue(ApiKeyValidator.validate("AQ.AbCdEfGhIjKl/MnOpQr") is ApiKeyValidator.ValidationResult.Invalid)
    }

    @Test
    fun `SensitiveDataRedactor redacts AQ keys in raw text, URLs, and env assignments`() {
        val aqKey = "AQ.AbCdEfGhIjKlMnOpQr_0123456789-xyz.99"
        val input = """
            curl "https://generativelanguage.googleapis.com/v1beta/models?key=$aqKey"
            token=$aqKey
            Failed with key $aqKey at line 4
            Regular text FAQ.not_an_api_key_at_all_1234567890
        """.trimIndent()

        val redacted = SensitiveDataRedactor.redact(input)

        assertFalse(redacted.contains(aqKey))
        assertTrue(redacted.contains("[REDACTED]"))
        assertTrue(redacted.contains("FAQ.not_an_api_key_at_all_1234567890"))
        assertEquals(redacted, SensitiveDataRedactor.redact(redacted))
    }

    @Test
    fun `AddApiKeyDialog stays open on invalid key, preserves input, hides key value from error, and supports Paste`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val vault = InMemoryVault()
        val (repo, dsFile) = createTempRepo(scope, vault)
        val keyManager = KeyManager(repo)
        val vm = SettingsViewModel(repo, keyManager)
        val context = ApplicationProvider.getApplicationContext<Context>()

        try {
            composeRule.setContent {
                val state by vm.uiState.collectAsStateWithLifecycle()
                CyberorgTheme {
                    ApiKeysScreen(state = state, viewModel = vm)
                }
            }
            composeRule.waitForIdle()

            // Open Add Key dialog
            composeRule.onNodeWithTag("add_api_key_button").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("add_api_key_dialog").assertIsDisplayed()

            // Verify password semantics & Done IME action on key field
            composeRule.onNodeWithTag("api_key_secret_input")
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))

            // Enter invalid key with disallowed character -> dialog stays open, input kept, error shown without key value
            val badKey = "bad_prefix_secret_value_12345"
            composeRule.onNodeWithTag("api_key_label_input").performTextInput("WorkKey")
            composeRule.onNodeWithTag("api_key_secret_input").performTextInput(badKey)
            composeRule.onNodeWithTag("confirm_add_api_key").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                shadowOf(Looper.getMainLooper()).idle()
                composeRule.onAllNodesWithTag("add_key_error_text", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithTag("add_api_key_dialog").assertIsDisplayed()
            composeRule.onNodeWithTag("add_key_error_text", useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
            assertFalse(
                composeRule.onNodeWithTag("add_key_error_text", useUnmergedTree = true)
                    .fetchSemanticsNode().config.toString().contains(badKey)
            )
            composeRule.onNodeWithTag("api_key_label_input").assertTextContains("WorkKey")
            assertTrue(vault.entries.isEmpty())

            // Use Paste ("Tempel") button with wrapped GEMINI_API_KEY="AQ...." clipboard content
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val validAq = "AQ.AbPastedCleanKey_1234567890abcdef"
            clipboard.setPrimaryClip(ClipData.newPlainText("api_key", "  GEMINI_API_KEY=\"$validAq\"\n "))

            composeRule.onNodeWithTag("paste_api_key_button").performScrollTo().assertIsDisplayed().performClick()
            composeRule.waitForIdle()

            // Submit via ImeAction.Done -> dialog closes after save succeeds, key becomes ACTIVE
            composeRule.onNodeWithTag("api_key_secret_input").performScrollTo().performImeAction()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                shadowOf(Looper.getMainLooper()).idle()
                composeRule.onAllNodesWithTag("add_api_key_dialog")
                    .fetchSemanticsNodes().isEmpty()
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("add_api_key_dialog").assertDoesNotExist()
            assertEquals(1, vault.entries.size)
            assertEquals(validAq, vault.entries.values.single())
            assertEquals(KeyHealthStatus.ACTIVE, keyManager.snapshot.value.keys.single().status)

            // Snackbar "Key saved" / "Kunci disimpan" with action "Test key" / "Uji kunci" is visible
            composeRule.onNodeWithText(context.getString(R.string.settings_key_saved_snackbar)).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.settings_action_test_key)).assertIsDisplayed()
        } finally {
            scope.cancel()
            dsFile.delete()
        }
    }

    @Test
    fun `Save activates first key only when none active, and recheck 401 or 403 shows Google rejection guidance`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val vault = InMemoryVault()
        val (repo, dsFile) = createTempRepo(scope, vault)
        val client = GeminiApiClient()
        var probeHttpCode = 401
        val keyManager = KeyManager(
            settingsStore = repo,
            keyProbe = { _, _ ->
                if (probeHttpCode == 401) {
                    client.mapHttpError(401, """{"error":{"code":401,"message":"Invalid key AQ.AbShouldBeRedacted1234567890","status":"UNAUTHENTICATED"}}""")
                } else {
                    client.mapHttpError(403, """{"error":{"code":403,"message":"Gemini API disabled","status":"PERMISSION_DENIED"}}""")
                }
            }
        )
        val vm = SettingsViewModel(repo, keyManager)
        val context = ApplicationProvider.getApplicationContext<Context>()

        try {
            composeRule.setContent {
                val state by vm.uiState.collectAsStateWithLifecycle()
                CyberorgTheme {
                    ApiKeysScreen(state = state, viewModel = vm)
                }
            }
            composeRule.waitForIdle()

            // Add first key -> becomes ACTIVE
            composeRule.onNodeWithTag("add_api_key_button").performClick()
            composeRule.onNodeWithTag("api_key_label_input").performTextInput("FirstKey")
            composeRule.onNodeWithTag("api_key_secret_input").performTextInput("AQ.AbFirstKey1234567890abcdefg")
            composeRule.onNodeWithTag("confirm_add_api_key").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                shadowOf(Looper.getMainLooper()).idle()
                keyManager.snapshot.value.activeKeyId != null &&
                    composeRule.onAllNodesWithText(context.getString(R.string.settings_action_test_key))
                        .fetchSemanticsNodes().isNotEmpty()
            }

            val firstId = keyManager.snapshot.value.activeKeyId!!
            assertEquals("FirstKey", keyManager.snapshot.value.activeKeyLabel)

            // Tap "Test key" on snackbar -> triggers recheckApiKey -> receives 401 -> displays rejection explanation
            composeRule.onNodeWithText(context.getString(R.string.settings_action_test_key)).performClick()
            val expectedMsg = context.getString(R.string.key_err_rejected_by_google)
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                shadowOf(Looper.getMainLooper()).idle()
                composeRule.onAllNodesWithTag("key_rejected_notice_$firstId")
                    .fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithText(expectedMsg).fetchSemanticsNodes().size >= 2
            }

            composeRule.onNodeWithTag("key_rejected_notice_$firstId").assertIsDisplayed().assertTextContains(expectedMsg)
            composeRule.onAllNodesWithText(expectedMsg).assertCountEquals(2)

            // Add second key while first is AUTH_ERROR -> second becomes ACTIVE
            composeRule.onNodeWithTag("add_api_key_button").performClick()
            composeRule.onNodeWithTag("api_key_label_input").performTextInput("SecondKey")
            composeRule.onNodeWithTag("api_key_secret_input").performTextInput("AQ.AbSecondKey1234567890abcdef")
            composeRule.onNodeWithTag("confirm_add_api_key").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                shadowOf(Looper.getMainLooper()).idle()
                keyManager.snapshot.value.keys.any { it.metadata.label == "SecondKey" } &&
                    composeRule.onAllNodesWithText(context.getString(R.string.settings_action_test_key))
                        .fetchSemanticsNodes().isNotEmpty()
            }

            val secondId = keyManager.snapshot.value.keys.first { it.metadata.label == "SecondKey" }.metadata.id
            assertEquals(secondId, keyManager.snapshot.value.activeKeyId)

            // Recheck second key with 403 -> shows rejection notice on key row and snackbar
            probeHttpCode = 403
            composeRule.onNodeWithText(context.getString(R.string.settings_action_test_key)).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                shadowOf(Looper.getMainLooper()).idle()
                composeRule.onAllNodesWithTag("key_rejected_notice_$secondId")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithTag("key_rejected_notice_$secondId").assertIsDisplayed().assertTextContains(expectedMsg)
        } finally {
            scope.cancel()
            dsFile.delete()
        }
    }

    @Test
    fun `AiAssistantRepository surfaces Google rejection explanation on 401 and 403 in Indonesian and English`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val (repoSettings, dsFile) = createTempRepo(scope)
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val idContext = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.INDONESIAN)
        val enContext = LocaleHelper.createLocalizedContext(baseContext, AppLanguage.ENGLISH)

        try {
            repoSettings.setAiConsentAccepted(true)
            repoSettings.setSelectedModel("gemini-3.8-flash")
            repoSettings.addApiKey("AQ-Main", "AQ.AbValidTestKey1234567890abcdef")
            val km = KeyManager(repoSettings)
            var nextError: GeminiCallResult<Nothing> = GeminiCallResult.AuthFailure("Unauthorized", 401)
            val transport = object : GeminiTransport {
                override suspend fun generate(
                    model: String,
                    apiKey: String,
                    systemInstruction: String,
                    contents: List<GeminiContent>
                ) = nextError

                override fun streamGenerate(
                    model: String,
                    apiKey: String,
                    systemInstruction: String,
                    contents: List<GeminiContent>
                ): Flow<GeminiStreamEvent> = flow {
                    emit(GeminiStreamEvent.Failure(nextError))
                }
            }
            val aiRepo = AiAssistantRepository(km, transport, repoSettings)

            val res401 = aiRepo.streamPrompt(AiPromptRequest(AiPromptKind.CHAT, "hi")).toList()
            val err401 = (res401.last() as AiStreamUpdate.Failed).errorUiText
            assertTrue(err401.asString(idContext).contains("Kunci ditolak oleh Google. Pastikan kunci aktif dan API Gemini diaktifkan."))
            assertTrue(err401.asString(enContext).contains("Key rejected by Google. Make sure the key is active and the Gemini API is enabled."))

            km.clearAllCooldownsAndErrors()
            nextError = GeminiCallResult.ForbiddenRetryable("Forbidden", 403)
            val res403 = aiRepo.streamPrompt(AiPromptRequest(AiPromptKind.CHAT, "hi")).toList()
            val err403 = (res403.last() as AiStreamUpdate.Failed).errorUiText
            assertTrue(err403.asString(idContext).contains("Kunci ditolak oleh Google. Pastikan kunci aktif dan API Gemini diaktifkan."))
            assertTrue(err403.asString(enContext).contains("Key rejected by Google. Make sure the key is active and the Gemini API is enabled."))
        } finally {
            scope.cancel()
            dsFile.delete()
        }
    }
}
