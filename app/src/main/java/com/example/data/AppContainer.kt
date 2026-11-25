package com.example.data

import android.content.Context
import com.example.ai.AiAssistantRepository
import com.example.ai.GeminiApiClient
import com.example.ai.GeminiCallResult
import com.example.ai.GeminiContent
import com.example.ai.GeminiPart
import com.example.ai.KeyManager
import com.example.terminal.FakeTerminalBackend
import com.example.terminal.LongCommandNotifier
import com.example.terminal.TerminalBackend
import com.example.terminal.TerminalCommandEvent
import com.example.terminal.TerminalOutputExporter
import com.example.termux.TermuxPaths
import com.example.termux.TermuxService
import com.example.termux.TermuxSessionBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isAppForeground = MutableStateFlow(true)
    val isAppForeground: StateFlow<Boolean> = _isAppForeground.asStateFlow()

    private val _latestSettings = MutableStateFlow(AppSettings())

    private val keyVault: KeyVault = AndroidEncryptedKeyVault(appContext)

    val settingsRepository: SettingsRepository = SettingsRepository(
        context = appContext,
        keyVault = keyVault
    )

    // Real Termux shell once the base system is installed (Settings > Terminal > Linux environment;
    // restart the app after installing). Otherwise the built-in simulated terminal.
    val terminalBackend: TerminalBackend =
        if (TermuxPaths(appContext.filesDir).isInstalled()) {
            TermuxSessionBackend(appContext)
        } else {
            FakeTerminalBackend()
        }

    val snippetRepository: SnippetStore = DataStoreSnippetRepository(appContext)

    val promptHistoryRepository: PromptHistoryStore = DataStorePromptHistoryRepository(appContext)

    private val geminiApiClient: GeminiApiClient = GeminiApiClient()

    val keyManager: KeyManager = KeyManager(
        settingsStore = settingsRepository,
        keyProbe = { apiKey, model ->
            when (
                val res = geminiApiClient.generate(
                    model = model,
                    apiKey = apiKey,
                    systemInstruction = "Respond with OK.",
                    contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart("ping"))))
                )
            ) {
                is GeminiCallResult.Success -> GeminiCallResult.Success(Unit, "", "")
                is GeminiCallResult.QuotaExceeded -> res
                is GeminiCallResult.ForbiddenRetryable -> res
                is GeminiCallResult.ModelUnavailable -> res
                is GeminiCallResult.AuthFailure -> res
                is GeminiCallResult.NetworkOrServerError -> res
            }
        }
    )

    val aiAssistantRepository: AiAssistantRepository = AiAssistantRepository(
        keyManager = keyManager,
        geminiApiClient = geminiApiClient,
        settingsStore = settingsRepository
    )

    /** Closes every terminal session (used by the foreground service's Exit action). */
    fun closeAllTerminalSessions() {
        terminalBackend.sessions.value.map { it.id }.forEach { terminalBackend.closeSession(it) }
    }

    init {
        TerminalOutputExporter.cleanupExportCache(appContext.cacheDir)

        // Keep real shell sessions alive in the background with a foreground service.
        if (terminalBackend is TermuxSessionBackend) {
            appScope.launch {
                terminalBackend.sessions.collect { list ->
                    if (list.isNotEmpty()) {
                        runCatching { TermuxService.start(appContext) }
                    }
                }
            }
        }

        appScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _latestSettings.value = settings
            }
        }

        appScope.launch {
            terminalBackend.sessions
                .flatMapLatest { handles ->
                    handles.map { it.commandEvents }.merge()
                }
                .collect { event ->
                    if (event is TerminalCommandEvent.Finished) {
                        val settings = _latestSettings.value
                        val thresholdMs = settings.longCommandThresholdSeconds * 1000L
                        if (LongCommandNotifier.shouldNotify(
                                durationMs = event.durationMs,
                                thresholdMs = thresholdMs,
                                isForeground = _isAppForeground.value,
                                enabled = settings.notifyLongCommands
                            )
                        ) {
                            LongCommandNotifier.notifyCommandCompleted(
                                context = appContext,
                                sessionId = event.sessionId,
                                sessionTitle = event.sessionTitle,
                                command = event.command,
                                exitCode = event.exitCode
                            )
                        }
                    }
                }
        }
    }

    fun setAppForeground(foreground: Boolean) {
        _isAppForeground.value = foreground
    }
}
