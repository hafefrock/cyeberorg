package com.example.ui.terminal

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.ai.AiAssistantRepository
import com.example.ai.AiChatMessage
import com.example.ai.AiMessageRole
import com.example.ai.AiPromptContext
import com.example.ai.AiPromptKind
import com.example.ai.AiPromptRequest
import com.example.ai.AiStreamUpdate
import com.example.ai.KeyManager
import com.example.ai.KeyManagerSnapshot
import com.example.ai.SensitiveDataRedactor
import com.example.data.AppLanguage
import com.example.data.AppSettings
import com.example.data.CommandSnippet
import com.example.data.GeminiModels
import com.example.data.PromptHistoryItem
import com.example.data.PromptHistoryStore
import com.example.data.SettingsStore
import com.example.data.SnippetImportException
import com.example.data.SnippetStore
import com.example.data.TerminalFontConfig
import com.example.terminal.ExtraKeyCode
import com.example.terminal.TerminalBackend
import com.example.terminal.TerminalErrorContext
import com.example.terminal.TerminalInputEncoder
import com.example.terminal.TerminalPasteSanitizer
import com.example.terminal.TerminalScreenBuffer
import com.example.terminal.TerminalSessionHandle
import com.example.ui.UiText
import com.example.ui.theme.AppThemePreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WorkspaceSection {
    TERMINAL,
    FILES,
    AI,
    SNIPPETS,
    SETTINGS
}

enum class SplitMode {
    SINGLE,
    TERM_AND_AI,
    DUAL_TERMINAL
}

@Immutable
data class SessionTabItem(
    val id: String,
    val title: String,
    val lastExitCode: Int?,
    val isActive: Boolean = false
)

@Immutable
data class TerminalScreenSnapshot(
    val sessionId: String = "",
    val version: Long = 0L,
    val buffer: TerminalScreenBuffer? = null
)

@Immutable
data class SessionProjection(
    val id: String = "",
    val title: String = "",
    val cwd: String = "",
    val lastExitCode: Int? = 0,
    val lastError: TerminalErrorContext? = null
)

@Immutable
data class AiContextConfig(
    val includeCwd: Boolean = true,
    val includeLastError: Boolean = true,
    val includeSelectedText: Boolean = true,
    val includeRecentOutput: Boolean = true,
    val recentLineCount: Int = 20,
    val selectedText: String = ""
) {
    fun activeCount(hasLastError: Boolean): Int {
        var count = 0
        if (includeCwd) count++
        if (includeLastError && hasLastError) count++
        if (includeSelectedText && selectedText.isNotBlank()) count++
        if (includeRecentOutput) count++
        return count
    }
}

@Immutable
data class CommandExplanationState(
    val visible: Boolean = false,
    val command: String = "",
    val rawCommand: String = "",
    val explanationText: UiText = UiText.Dynamic(""),
    val isLoading: Boolean = false,
    val errorUiText: UiText? = null,
    val isTruncated: Boolean = false,
    val keyLabel: String? = null,
    val modelUsed: String? = null
)

@Immutable
data class TerminalScreenUiState(
    val activeSection: WorkspaceSection = WorkspaceSection.TERMINAL,
    val splitMode: SplitMode = SplitMode.SINGLE,
    val isSidebarOpen: Boolean = false,
    val sessionTabs: List<SessionTabItem> = emptyList(),
    val activeSessionId: String = "",
    val activeCwd: String = "",
    val activeLastExitCode: Int? = 0,
    val activeLastError: TerminalErrorContext? = null,
    val secondarySession: SessionProjection? = null,
    val ctrlArmed: Boolean = false,
    val altArmed: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val keySnapshot: KeyManagerSnapshot = KeyManagerSnapshot(),
    val isAiPanelOpen: Boolean = false,
    val isAiConsentDialogVisible: Boolean = false,
    val hasUnreadAiReply: Boolean = false,
    val aiMessages: List<AiChatMessage> = emptyList(),
    val aiInput: String = "",
    val isWriteCommandMode: Boolean = false,
    val isAiLoading: Boolean = false,
    val aiContextConfig: AiContextConfig = AiContextConfig(),
    val commandExplanation: CommandExplanationState = CommandExplanationState(),
    val snippets: List<CommandSnippet> = emptyList(),
    val promptHistory: List<PromptHistoryItem> = emptyList(),
    val statusBannerMessage: UiText? = null
)

/**
 * TerminalViewModel depends ONLY on [TerminalBackend] and [TerminalSessionHandle],
 * never on any concrete emulator implementation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModel(
    private val terminalBackend: TerminalBackend,
    private val aiRepository: AiAssistantRepository,
    private val keyManager: KeyManager,
    private val settingsStore: SettingsStore,
    private val snippetStore: SnippetStore,
    private val promptHistoryStore: PromptHistoryStore
) : ViewModel() {

    private data class ModifierState(
        val ctrlArmed: Boolean = false,
        val altArmed: Boolean = false
    )

    private data class WorkspaceState(
        val section: WorkspaceSection = WorkspaceSection.TERMINAL,
        val splitMode: SplitMode = SplitMode.SINGLE,
        val isSidebarOpen: Boolean = false,
        val secondarySessionId: String? = null
    )

    private data class AiLocalState(
        val isAiPanelOpen: Boolean = false,
        val isAiConsentDialogVisible: Boolean = false,
        val hasUnreadAiReply: Boolean = false,
        val messages: List<AiChatMessage> = listOf(
            AiChatMessage(
                role = AiMessageRole.ASSISTANT,
                text = UiText.Resource(R.string.ai_welcome_message)
            )
        ),
        val input: String = "",
        val isWriteCommandMode: Boolean = false,
        val isLoading: Boolean = false,
        val activeStreamingMessageId: String? = null,
        val activeStreamingRawText: String = "",
        val contextConfig: AiContextConfig = AiContextConfig(),
        val explanationState: CommandExplanationState = CommandExplanationState(),
        val statusBanner: UiText? = null
    )

    private sealed interface PendingAiAction {
        data class ErrorAnalysis(val kind: AiPromptKind) : PendingAiAction
        data class ExplainSelection(val selectedText: String) : PendingAiAction
        data class SuggestCommand(val defaultGoal: String, val goalOverride: String?) : PendingAiAction
        data class WriteScript(val defaultPurpose: String, val purposeOverride: String?) : PendingAiAction
        data class WriteCommand(val goalOverride: String?) : PendingAiAction
        data class ExplainCommand(val command: String) : PendingAiAction
        data class OutputAnalysis(val kind: AiPromptKind, val selectedOverride: String?) : PendingAiAction
        data object SendMessage : PendingAiAction
        data class RetryMessage(val messageId: String) : PendingAiAction
    }

    private data class DataLibrariesBundle(
        val snippets: List<CommandSnippet> = emptyList(),
        val promptHistory: List<PromptHistoryItem> = emptyList()
    )

    private var activeAiJob: Job? = null
    private var activeExplainJob: Job? = null
    private var pendingAiConsentAction: PendingAiAction? = null

    private val modifierState = MutableStateFlow(ModifierState())
    private val workspaceState = MutableStateFlow(WorkspaceState())
    private val aiLocalState = MutableStateFlow(AiLocalState())

    private val activeHandleFlow = combine(
        terminalBackend.sessions,
        terminalBackend.activeSessionId
    ) { handles, activeId ->
        handles.firstOrNull { it.id == activeId } ?: handles.firstOrNull()
    }

    private val secondaryHandleFlow = combine(
        terminalBackend.sessions,
        terminalBackend.activeSessionId,
        workspaceState
    ) { handles, activeId, ws ->
        val explicit = handles.firstOrNull { it.id == ws.secondarySessionId && it.id != activeId }
        explicit ?: handles.firstOrNull { it.id != activeId }
    }

    private val tabItemsFlow = combine(
        terminalBackend.sessions,
        terminalBackend.activeSessionId
    ) { handles, activeId ->
        handles to activeId
    }.flatMapLatest { (handles, activeId) ->
        if (handles.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                handles.map { handle ->
                    combine(handle.title, handle.lastExitCode) { title, exitCode ->
                        SessionTabItem(
                            id = handle.id,
                            title = title,
                            lastExitCode = exitCode,
                            isActive = handle.id == activeId
                        )
                    }
                }
            ) { array -> array.toList() }
        }
    }

    private fun screenFlowFor(
        handleFlow: Flow<TerminalSessionHandle?>,
        frameIntervalMs: Long = SCREEN_FRAME_INTERVAL_MS
    ): Flow<TerminalScreenSnapshot> {
        return handleFlow.flatMapLatest { handle ->
            if (handle == null) {
                flowOf(TerminalScreenSnapshot())
            } else {
                val versionStream: Flow<Long> = handle.screenVersion
                versionStream
                    .conflate()
                    .transform { version ->
                        emit(
                            TerminalScreenSnapshot(
                                sessionId = handle.id,
                                version = version,
                                buffer = handle.readScreen()
                            )
                        )
                        if (frameIntervalMs > 0L) {
                            delay(frameIntervalMs)
                        }
                    }
            }
        }
    }

    val activeScreen: StateFlow<TerminalScreenSnapshot> = screenFlowFor(activeHandleFlow)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TerminalScreenSnapshot()
        )

    val secondaryScreen: StateFlow<TerminalScreenSnapshot> = screenFlowFor(secondaryHandleFlow)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TerminalScreenSnapshot()
        )

    private val activeProjectionFlow = projectSessionFlow(activeHandleFlow)
    private val secondaryProjectionFlow = projectSessionFlow(secondaryHandleFlow)

    private fun projectSessionFlow(
        handleFlow: Flow<TerminalSessionHandle?>
    ): Flow<SessionProjection?> {
        return handleFlow.flatMapLatest { handle ->
            if (handle == null) {
                flowOf(null)
            } else {
                combine(
                    handle.title,
                    handle.cwd,
                    handle.lastExitCode,
                    handle.lastErrorContext
                ) { title, cwd, exitCode, lastError ->
                    SessionProjection(
                        id = handle.id,
                        title = title,
                        cwd = cwd,
                        lastExitCode = exitCode,
                        lastError = lastError
                    )
                }
            }
        }
    }

    private data class CombinedTerminalBundle(
        val tabs: List<SessionTabItem>,
        val activeProj: SessionProjection?,
        val secondaryProj: SessionProjection?,
        val mods: ModifierState,
        val ws: WorkspaceState
    )

    private val coreTerminalFlow = combine(
        tabItemsFlow,
        activeProjectionFlow,
        secondaryProjectionFlow,
        modifierState,
        workspaceState
    ) { tabs, activeProj, secondaryProj, mods, ws ->
        CombinedTerminalBundle(tabs, activeProj, secondaryProj, mods, ws)
    }

    private val librariesFlow = combine(
        snippetStore.snippetsFlow,
        promptHistoryStore.historyFlow
    ) { snippets, history ->
        DataLibrariesBundle(snippets = snippets, promptHistory = history)
    }

    val uiState: StateFlow<TerminalScreenUiState> = combine(
        coreTerminalFlow,
        settingsStore.settingsFlow,
        keyManager.snapshot,
        aiLocalState,
        librariesFlow
    ) { bundle, settings, keySnap, aiState, libs ->
        val activeProj = bundle.activeProj ?: SessionProjection()
        val effectiveAiOpen = aiState.isAiPanelOpen &&
            settings.aiEnabled &&
            bundle.ws.splitMode != SplitMode.TERM_AND_AI
        val effectiveUnread = aiState.hasUnreadAiReply &&
            settings.aiEnabled &&
            !effectiveAiOpen &&
            bundle.ws.section != WorkspaceSection.AI &&
            bundle.ws.splitMode != SplitMode.TERM_AND_AI
        TerminalScreenUiState(
            activeSection = bundle.ws.section,
            splitMode = bundle.ws.splitMode,
            isSidebarOpen = bundle.ws.isSidebarOpen,
            sessionTabs = bundle.tabs,
            activeSessionId = activeProj.id,
            activeCwd = activeProj.cwd,
            activeLastExitCode = activeProj.lastExitCode,
            activeLastError = activeProj.lastError,
            secondarySession = bundle.secondaryProj,
            ctrlArmed = bundle.mods.ctrlArmed,
            altArmed = bundle.mods.altArmed,
            settings = settings,
            keySnapshot = keySnap,
            isAiPanelOpen = effectiveAiOpen,
            isAiConsentDialogVisible = aiState.isAiConsentDialogVisible,
            hasUnreadAiReply = effectiveUnread,
            aiMessages = aiState.messages,
            aiInput = aiState.input,
            isWriteCommandMode = aiState.isWriteCommandMode,
            isAiLoading = aiState.isLoading,
            aiContextConfig = aiState.contextConfig,
            commandExplanation = aiState.explanationState,
            snippets = libs.snippets,
            promptHistory = libs.promptHistory,
            statusBannerMessage = aiState.statusBanner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TerminalScreenUiState()
    )

    private fun currentHandle(): TerminalSessionHandle? {
        val activeId = terminalBackend.activeSessionId.value
        val handles = terminalBackend.sessions.value
        return handles.firstOrNull { it.id == activeId } ?: handles.firstOrNull()
    }

    private fun handleById(sessionId: String): TerminalSessionHandle? {
        return terminalBackend.sessions.value.firstOrNull { it.id == sessionId }
    }

    // --- Workspace Navigation & Split Modes ---

    fun selectWorkspaceSection(section: WorkspaceSection) {
        workspaceState.update { it.copy(section = section, isSidebarOpen = false) }
        aiLocalState.update { current ->
            current.copy(
                isAiPanelOpen = false,
                hasUnreadAiReply = if (section == WorkspaceSection.AI) false else current.hasUnreadAiReply
            )
        }
    }

    fun toggleSidebar(forceOpen: Boolean? = null) {
        val targetOpen = forceOpen ?: !workspaceState.value.isSidebarOpen
        workspaceState.update { it.copy(isSidebarOpen = targetOpen) }
        if (targetOpen) {
            aiLocalState.update { it.copy(isAiPanelOpen = false) }
        }
    }

    fun openSidebar() = toggleSidebar(forceOpen = true)

    fun closeSidebar() = toggleSidebar(forceOpen = false)

    fun toggleSplitMode() {
        var nextMode = SplitMode.SINGLE
        workspaceState.update { current ->
            val next = when (current.splitMode) {
                SplitMode.SINGLE -> if (uiState.value.settings.aiEnabled) SplitMode.TERM_AND_AI else SplitMode.DUAL_TERMINAL
                SplitMode.TERM_AND_AI -> SplitMode.DUAL_TERMINAL
                SplitMode.DUAL_TERMINAL -> SplitMode.SINGLE
            }
            nextMode = next
            if (next == SplitMode.DUAL_TERMINAL && terminalBackend.sessions.value.size < 2) {
                val created = terminalBackend.createSession()
                current.copy(splitMode = next, secondarySessionId = created.id)
            } else {
                current.copy(splitMode = next)
            }
        }
        aiLocalState.update { current ->
            current.copy(
                isAiPanelOpen = false,
                hasUnreadAiReply = if (nextMode == SplitMode.TERM_AND_AI) false else current.hasUnreadAiReply
            )
        }
    }

    fun setSplitMode(mode: SplitMode) {
        workspaceState.update { current ->
            if (mode == SplitMode.DUAL_TERMINAL && terminalBackend.sessions.value.size < 2) {
                val created = terminalBackend.createSession()
                current.copy(splitMode = mode, secondarySessionId = created.id)
            } else {
                current.copy(splitMode = mode)
            }
        }
        aiLocalState.update { current ->
            current.copy(
                isAiPanelOpen = false,
                hasUnreadAiReply = if (mode == SplitMode.TERM_AND_AI) false else current.hasUnreadAiReply
            )
        }
    }

    fun setAppTheme(theme: AppThemePreset) {
        viewModelScope.launch { settingsStore.setAppTheme(theme) }
    }

    fun setReduceEffects(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setReduceEffects(enabled) }
    }

    fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsStore.setAppLanguage(language) }
    }

    fun setFontSizeSp(sizeSp: Float) {
        val normalized = TerminalFontConfig.normalizeFontSizeSp(sizeSp)
        viewModelScope.launch { settingsStore.setFontSizeSp(normalized) }
    }

    fun selectAiModel(modelId: String) {
        viewModelScope.launch { settingsStore.setSelectedModel(modelId) }
    }

    private fun ensureAiReadyForPrompt(
        pendingAction: PendingAiAction,
        bypassConsentCheck: Boolean = false
    ): Boolean {
        val settings = uiState.value.settings
        if (!settings.aiEnabled) {
            aiLocalState.update { it.copy(statusBanner = UiText.Resource(R.string.status_ai_disabled_banner)) }
            return false
        }
        if (GeminiModels.normalizeSelectedModel(settings.selectedModel) == null) {
            val shouldOpenSheet = workspaceState.value.section == WorkspaceSection.TERMINAL &&
                workspaceState.value.splitMode != SplitMode.TERM_AND_AI
            if (shouldOpenSheet) {
                workspaceState.update { it.copy(isSidebarOpen = false) }
            }
            aiLocalState.update {
                it.copy(
                    isAiPanelOpen = if (shouldOpenSheet) true else it.isAiPanelOpen,
                    hasUnreadAiReply = if (shouldOpenSheet) false else it.hasUnreadAiReply,
                    statusBanner = UiText.Resource(R.string.ai_err_select_model_first)
                )
            }
            return false
        }
        if (!bypassConsentCheck && !settings.aiConsentAccepted) {
            pendingAiConsentAction = pendingAction
            aiLocalState.update { it.copy(isAiConsentDialogVisible = true) }
            return false
        }
        return true
    }

    fun acceptAiConsentAndContinue() {
        val pending = pendingAiConsentAction
        pendingAiConsentAction = null
        viewModelScope.launch {
            settingsStore.setAiConsentAccepted(true)
            aiLocalState.update { it.copy(isAiConsentDialogVisible = false) }
            when (pending) {
                is PendingAiAction.ErrorAnalysis -> launchErrorAnalysis(pending.kind, bypassConsentCheck = true)
                is PendingAiAction.ExplainSelection -> explainSelectedTextInternal(pending.selectedText, bypassConsentCheck = true)
                is PendingAiAction.SuggestCommand -> requestCommandSuggestionInternal(
                    defaultGoal = pending.defaultGoal,
                    goalOverride = pending.goalOverride,
                    bypassConsentCheck = true
                )
                is PendingAiAction.WriteScript -> requestShellScriptInternal(
                    defaultPurpose = pending.defaultPurpose,
                    purposeOverride = pending.purposeOverride,
                    bypassConsentCheck = true
                )
                is PendingAiAction.WriteCommand -> requestNaturalLanguageCommandInternal(
                    goalOverride = pending.goalOverride,
                    bypassConsentCheck = true
                )
                is PendingAiAction.ExplainCommand -> explainCommandInternal(
                    command = pending.command,
                    bypassConsentCheck = true
                )
                is PendingAiAction.OutputAnalysis -> analyzeOutputInternal(
                    kind = pending.kind,
                    selectedOverride = pending.selectedOverride,
                    bypassConsentCheck = true
                )
                is PendingAiAction.SendMessage -> sendAiMessageInternal(bypassConsentCheck = true)
                is PendingAiAction.RetryMessage -> retryAiMessageInternal(pending.messageId, bypassConsentCheck = true)
                null -> Unit
            }
        }
    }

    fun dismissAiConsentDialog() {
        pendingAiConsentAction = null
        aiLocalState.update { it.copy(isAiConsentDialogVisible = false) }
    }

    // --- Session Lifecycle ---

    fun createSession() {
        terminalBackend.createSession()
        workspaceState.update { it.copy(section = WorkspaceSection.TERMINAL, isSidebarOpen = false) }
        aiLocalState.update { it.copy(isAiPanelOpen = false) }
    }

    fun closeSession(sessionId: String) {
        terminalBackend.closeSession(sessionId)
    }

    fun switchSession(sessionId: String) {
        terminalBackend.switchSession(sessionId)
    }

    fun renameSession(sessionId: String, newTitle: String) {
        terminalBackend.sessions.value.firstOrNull { it.id == sessionId }?.rename(newTitle)
    }

    fun resizeActiveSession(cols: Int, rows: Int) {
        currentHandle()?.resize(cols, rows)
    }

    fun clearActiveSession() {
        currentHandle()?.clearScreen()
    }

    fun resizeSession(sessionId: String, cols: Int, rows: Int) {
        handleById(sessionId)?.resize(cols, rows)
    }

    fun clearSession(sessionId: String) {
        handleById(sessionId)?.clearScreen()
    }

    fun writeRawBytesToSession(sessionId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        handleById(sessionId)?.write(bytes)
    }

    fun onImeTextInputForSession(sessionId: String, text: String) {
        if (text.isEmpty()) return
        val handle = handleById(sessionId) ?: return
        val mods = modifierState.value
        val bytes = TerminalInputEncoder.encodeInput(
            text = text,
            ctrlArmed = mods.ctrlArmed,
            altArmed = mods.altArmed
        )
        modifierState.value = ModifierState()
        handle.write(bytes)
    }

    fun onImeBackspaceForSession(sessionId: String) {
        modifierState.value = ModifierState()
        handleById(sessionId)?.write(byteArrayOf(0x7F))
    }

    fun onImeEnterForSession(sessionId: String) {
        modifierState.value = ModifierState()
        handleById(sessionId)?.write(byteArrayOf(0x0D))
    }

    // --- Byte-oriented PTY Input ---

    fun writeRawBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        currentHandle()?.write(bytes)
    }

    fun onImeTextInput(text: String) {
        if (text.isEmpty()) return
        val handle = currentHandle() ?: return
        val mods = modifierState.value
        val bytes = TerminalInputEncoder.encodeInput(
            text = text,
            ctrlArmed = mods.ctrlArmed,
            altArmed = mods.altArmed
        )
        modifierState.value = ModifierState()
        handle.write(bytes)
    }

    fun onImeBackspace() {
        modifierState.value = ModifierState()
        currentHandle()?.write(byteArrayOf(0x7F))
    }

    fun onImeEnter() {
        modifierState.value = ModifierState()
        currentHandle()?.write(byteArrayOf(0x0D))
    }

    fun sendExtraKey(key: ExtraKeyCode) {
        val handle = currentHandle() ?: return
        when (key) {
            ExtraKeyCode.CTRL -> {
                modifierState.update { it.copy(ctrlArmed = !it.ctrlArmed, altArmed = false) }
            }
            ExtraKeyCode.ALT -> {
                modifierState.update { it.copy(altArmed = !it.altArmed, ctrlArmed = false) }
            }
            ExtraKeyCode.ESC -> {
                modifierState.value = ModifierState()
                key.toByteSequence()?.let { handle.write(it) }
            }
            else -> {
                modifierState.value = ModifierState()
                key.toByteSequence()?.let { handle.write(it) }
            }
        }
    }

    /**
     * Inserts a suggested command into the PTY buffer WITHOUT executing (no trailing newline).
     * Multi-line text requires bracketed paste (DECSET 2004); if disabled, copies to clipboard
     * and surfaces an explanatory banner instead of inserting.
     */
    fun insertSuggestedCommand(
        command: String,
        onCopyFallback: (String) -> Unit = {}
    ) {
        performSafePasteInsert(command, onCopyFallback)
    }

    /**
     * Inserts a snippet into the PTY buffer WITHOUT executing.
     * Snippets containing control characters or newlines require explicit user confirmation
     * ([userConfirmed] == true); otherwise insertion is blocked with a confirmation prompt banner.
     */
    fun insertSnippetCommand(
        command: String,
        userConfirmed: Boolean = false,
        onCopyFallback: (String) -> Unit = {}
    ) {
        if (!userConfirmed && TerminalPasteSanitizer.containsControlOrNewline(command)) {
            aiLocalState.update {
                it.copy(statusBanner = UiText.Resource(R.string.status_snippet_confirm_required))
            }
            return
        }
        performSafePasteInsert(command, onCopyFallback)
    }

    private fun performSafePasteInsert(
        command: String,
        onCopyFallback: (String) -> Unit
    ) {
        val sanitized = TerminalPasteSanitizer.sanitize(command, TerminalPasteSanitizer.MAX_PASTE_BYTES)
        if (sanitized.text.isEmpty()) return

        val handle = currentHandle() ?: terminalBackend.createSession()
        val outcome = handle.pasteTextWithOutcome(sanitized.text)
        if (outcome.copiedFallbackRequired) {
            onCopyFallback(sanitized.text)
            aiLocalState.update {
                it.copy(statusBanner = UiText.Resource(R.string.status_multiline_paste_copied))
            }
            return
        }
        if (!outcome.inserted) return

        val wasTruncated = sanitized.wasTruncated || outcome.truncated
        workspaceState.update { it.copy(section = WorkspaceSection.TERMINAL, isSidebarOpen = false) }
        aiLocalState.update {
            it.copy(
                isAiPanelOpen = false,
                statusBanner = UiText.Resource(
                    if (wasTruncated) R.string.status_paste_truncated else R.string.status_inserted_draft
                )
            )
        }
    }

    fun showCopiedFeedback() {
        aiLocalState.update {
            it.copy(statusBanner = UiText.Resource(R.string.status_copied_clipboard))
        }
    }

    fun showMessageBanner(@androidx.annotation.StringRes resId: Int) {
        aiLocalState.update {
            it.copy(statusBanner = UiText.Resource(resId))
        }
    }

    fun clearStatusBanner() {
        aiLocalState.update { it.copy(statusBanner = null) }
    }

    // --- Context Chips Toggles ---

    fun toggleContextCwd() {
        aiLocalState.update {
            val cfg = it.contextConfig
            it.copy(contextConfig = cfg.copy(includeCwd = !cfg.includeCwd))
        }
    }

    fun toggleContextLastError() {
        aiLocalState.update {
            val cfg = it.contextConfig
            it.copy(contextConfig = cfg.copy(includeLastError = !cfg.includeLastError))
        }
    }

    fun toggleContextSelectedText() {
        aiLocalState.update {
            val cfg = it.contextConfig
            it.copy(contextConfig = cfg.copy(includeSelectedText = !cfg.includeSelectedText))
        }
    }

    fun clearSelectedTextContext() {
        aiLocalState.update {
            it.copy(
                contextConfig = it.contextConfig.copy(
                    selectedText = "",
                    includeSelectedText = true
                )
            )
        }
    }

    fun toggleContextRecentOutput() {
        aiLocalState.update {
            val cfg = it.contextConfig
            it.copy(contextConfig = cfg.copy(includeRecentOutput = !cfg.includeRecentOutput))
        }
    }

    private fun buildFilteredContext(
        handle: TerminalSessionHandle?,
        forceIncludeError: TerminalErrorContext? = null,
        forceIncludeSelected: String? = null
    ): AiPromptContext {
        val cfg = aiLocalState.value.contextConfig
        val cwd = if (cfg.includeCwd) (handle?.cwd?.value ?: "~") else null
        val err = forceIncludeError ?: if (cfg.includeLastError) handle?.lastErrorContext?.value else null
        val selected = forceIncludeSelected ?: if (cfg.includeSelectedText && cfg.selectedText.isNotBlank()) {
            cfg.selectedText
        } else {
            null
        }
        val recent = if (cfg.includeRecentOutput && handle != null) {
            handle.readRecentText(maxLines = cfg.recentLineCount).takeIf { it.isNotBlank() }
        } else {
            null
        }
        return AiPromptContext(
            cwd = cwd,
            lastError = err,
            selectedText = selected,
            recentOutput = recent
        )
    }

    // --- AI Bottom Sheet & Streaming ---

    fun toggleAiPanel(forceOpen: Boolean? = null) {
        if (!uiState.value.settings.aiEnabled) {
            aiLocalState.update {
                it.copy(statusBanner = UiText.Resource(R.string.status_ai_disabled_banner))
            }
            return
        }
        if (workspaceState.value.splitMode == SplitMode.TERM_AND_AI) {
            aiLocalState.update { it.copy(isAiPanelOpen = false, hasUnreadAiReply = false) }
            return
        }
        val targetOpen = forceOpen ?: !aiLocalState.value.isAiPanelOpen
        if (targetOpen) {
            workspaceState.update { it.copy(section = WorkspaceSection.TERMINAL, isSidebarOpen = false) }
        }
        aiLocalState.update { current ->
            current.copy(
                isAiPanelOpen = targetOpen,
                hasUnreadAiReply = if (targetOpen) false else current.hasUnreadAiReply
            )
        }
    }

    fun openFullAiTab() {
        selectWorkspaceSection(WorkspaceSection.AI)
    }

    fun updateAiInput(text: String) {
        aiLocalState.update { it.copy(input = text) }
    }

    fun clearAiChat() {
        stopAiGeneration()
        aiLocalState.update {
            it.copy(
                hasUnreadAiReply = false,
                messages = listOf(
                    AiChatMessage(
                        role = AiMessageRole.ASSISTANT,
                        text = UiText.Resource(R.string.ai_chat_cleared)
                    )
                )
            )
        }
    }

    fun explainLastError() {
        launchErrorAnalysis(kind = AiPromptKind.EXPLAIN_ERROR)
    }

    fun fixLastError() {
        launchErrorAnalysis(kind = AiPromptKind.FIX_ERROR)
    }

    private fun openSheetIfTerminalNonSplit() {
        if (workspaceState.value.splitMode != SplitMode.TERM_AND_AI) {
            workspaceState.update { it.copy(section = WorkspaceSection.TERMINAL, isSidebarOpen = false) }
            aiLocalState.update { it.copy(isAiPanelOpen = true, hasUnreadAiReply = false) }
        }
    }

    private fun launchErrorAnalysis(kind: AiPromptKind, bypassConsentCheck: Boolean = false) {
        if (!ensureAiReadyForPrompt(PendingAiAction.ErrorAnalysis(kind), bypassConsentCheck)) return
        val handle = currentHandle() ?: return
        val errorCtx = handle.lastErrorContext.value
        if (errorCtx == null) {
            aiLocalState.update { current ->
                current.copy(
                    messages = current.messages + AiChatMessage(
                        role = AiMessageRole.ASSISTANT,
                        text = UiText.Resource(R.string.ai_no_error_recorded, handle.title.value)
                    )
                )
            }
            return
        }

        openSheetIfTerminalNonSplit()
        aiLocalState.update {
            it.copy(contextConfig = it.contextConfig.copy(includeLastError = true))
        }
        val labelRes = if (kind == AiPromptKind.FIX_ERROR) R.string.ai_user_fix_error else R.string.ai_user_explain_error
        val userMsg = AiChatMessage(
            role = AiMessageRole.USER,
            text = UiText.Resource(labelRes, errorCtx.exitCode, errorCtx.command)
        )
        val request = AiPromptRequest(
            kind = kind,
            userPrompt = errorCtx.command,
            context = buildFilteredContext(handle, forceIncludeError = errorCtx)
        )
        startStreamingPrompt(userMessage = userMsg, request = request, historyText = null)
    }

    fun askAiWithSelection(selectedText: String) {
        val clean = selectedText.trim()
        if (clean.isEmpty()) return
        if (!uiState.value.settings.aiEnabled) {
            aiLocalState.update { it.copy(statusBanner = UiText.Resource(R.string.status_ai_disabled_banner)) }
            return
        }
        openSheetIfTerminalNonSplit()
        aiLocalState.update {
            it.copy(
                contextConfig = it.contextConfig.copy(
                    selectedText = clean,
                    includeSelectedText = true
                )
            )
        }
    }

    fun explainSelectedText(selectedText: String) {
        explainSelectedTextInternal(selectedText, bypassConsentCheck = false)
    }

    private fun explainSelectedTextInternal(selectedText: String, bypassConsentCheck: Boolean) {
        val clean = selectedText.trim()
        if (clean.isEmpty()) return
        aiLocalState.update {
            it.copy(
                contextConfig = it.contextConfig.copy(
                    selectedText = clean,
                    includeSelectedText = true
                )
            )
        }
        if (!ensureAiReadyForPrompt(PendingAiAction.ExplainSelection(clean), bypassConsentCheck)) return
        openSheetIfTerminalNonSplit()
        val userMsg = AiChatMessage(
            role = AiMessageRole.USER,
            text = UiText.Resource(R.string.ai_user_explain_selection)
        )
        val request = AiPromptRequest(
            kind = AiPromptKind.EXPLAIN_SELECTION,
            userPrompt = "",
            context = buildFilteredContext(currentHandle(), forceIncludeSelected = clean)
        )
        startStreamingPrompt(userMessage = userMsg, request = request, historyText = null)
    }

    fun requestCommandSuggestion(defaultGoal: String, goalOverride: String? = null) {
        requestCommandSuggestionInternal(defaultGoal, goalOverride, bypassConsentCheck = false)
    }

    private fun requestCommandSuggestionInternal(
        defaultGoal: String,
        goalOverride: String?,
        bypassConsentCheck: Boolean
    ) {
        if (!ensureAiReadyForPrompt(PendingAiAction.SuggestCommand(defaultGoal, goalOverride), bypassConsentCheck)) return
        val handle = currentHandle() ?: return
        val goal = goalOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: aiLocalState.value.input.trim().takeIf { it.isNotEmpty() }
            ?: defaultGoal

        aiLocalState.update { it.copy(input = "") }
        val userMsg = AiChatMessage(
            role = AiMessageRole.USER,
            text = UiText.Resource(R.string.ai_user_suggest_cmd, goal)
        )
        val request = AiPromptRequest(
            kind = AiPromptKind.SUGGEST_COMMAND,
            userPrompt = goal,
            context = buildFilteredContext(handle)
        )
        startStreamingPrompt(userMessage = userMsg, request = request, historyText = goal)
    }

    fun requestShellScript(defaultPurpose: String, purposeOverride: String? = null) {
        requestShellScriptInternal(defaultPurpose, purposeOverride, bypassConsentCheck = false)
    }

    private fun requestShellScriptInternal(
        defaultPurpose: String,
        purposeOverride: String?,
        bypassConsentCheck: Boolean
    ) {
        if (!ensureAiReadyForPrompt(PendingAiAction.WriteScript(defaultPurpose, purposeOverride), bypassConsentCheck)) return
        val handle = currentHandle() ?: return
        val purpose = purposeOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: aiLocalState.value.input.trim().takeIf { it.isNotEmpty() }
            ?: defaultPurpose

        aiLocalState.update { it.copy(input = "") }
        val userMsg = AiChatMessage(
            role = AiMessageRole.USER,
            text = UiText.Resource(R.string.ai_user_write_script, purpose)
        )
        val request = AiPromptRequest(
            kind = AiPromptKind.WRITE_SCRIPT,
            userPrompt = purpose,
            context = buildFilteredContext(handle)
        )
        startStreamingPrompt(userMessage = userMsg, request = request, historyText = purpose)
    }

    fun onWriteCommandChipClicked() {
        val currentInput = aiLocalState.value.input.trim()
        if (currentInput.isNotEmpty()) {
            requestNaturalLanguageCommandInternal(goalOverride = null, bypassConsentCheck = false)
        } else {
            aiLocalState.update { it.copy(isWriteCommandMode = !it.isWriteCommandMode) }
        }
    }

    fun requestNaturalLanguageCommand(goalOverride: String? = null) {
        requestNaturalLanguageCommandInternal(goalOverride = goalOverride, bypassConsentCheck = false)
    }

    private fun requestNaturalLanguageCommandInternal(
        goalOverride: String?,
        bypassConsentCheck: Boolean
    ) {
        val handle = currentHandle()
        val goal = (goalOverride ?: aiLocalState.value.input).trim()
        if (goal.isEmpty()) {
            aiLocalState.update { it.copy(isWriteCommandMode = true) }
            return
        }
        if (!ensureAiReadyForPrompt(PendingAiAction.WriteCommand(goalOverride), bypassConsentCheck)) return

        if (goalOverride == null) {
            aiLocalState.update { it.copy(input = "", isWriteCommandMode = false) }
        } else {
            aiLocalState.update { it.copy(isWriteCommandMode = false) }
        }
        openSheetIfTerminalNonSplit()

        val cfg = aiLocalState.value.contextConfig
        val commandContext = AiPromptContext(
            cwd = if (cfg.includeCwd) (handle?.cwd?.value ?: "~") else null,
            lastError = null,
            selectedText = if (cfg.includeSelectedText && cfg.selectedText.isNotBlank()) {
                cfg.selectedText
            } else {
                null
            },
            recentOutput = if (cfg.includeRecentOutput && handle != null) {
                handle.readRecentText(maxLines = cfg.recentLineCount).takeIf { it.isNotBlank() }
            } else {
                null
            },
            shell = AiAssistantRepository.DEFAULT_SHELL
        )
        val userMsg = AiChatMessage(
            role = AiMessageRole.USER,
            text = UiText.Resource(R.string.ai_user_write_command, goal)
        )
        val request = AiPromptRequest(
            kind = AiPromptKind.WRITE_COMMAND,
            userPrompt = goal,
            context = commandContext
        )
        startStreamingPrompt(userMessage = userMsg, request = request, historyText = goal)
    }

    fun explainCommand(command: String) {
        explainCommandInternal(command = command, bypassConsentCheck = false)
    }

    private fun explainCommandInternal(command: String, bypassConsentCheck: Boolean) {
        val raw = command.trim()
        if (raw.isEmpty()) return
        if (!ensureAiReadyForPrompt(PendingAiAction.ExplainCommand(raw), bypassConsentCheck)) return

        activeExplainJob?.cancel()
        val redactedPreview = SensitiveDataRedactor.redact(raw)
        val request = AiPromptRequest(
            kind = AiPromptKind.EXPLAIN_COMMAND,
            userPrompt = raw,
            context = AiPromptContext(shell = AiAssistantRepository.DEFAULT_SHELL)
        )
        aiLocalState.update {
            it.copy(
                explanationState = CommandExplanationState(
                    visible = true,
                    command = redactedPreview,
                    rawCommand = raw,
                    explanationText = UiText.Dynamic(""),
                    isLoading = true
                )
            )
        }

        activeExplainJob = viewModelScope.launch {
            try {
                aiRepository.streamPrompt(request).collect { update ->
                    when (update) {
                        is AiStreamUpdate.Delta -> {
                            aiLocalState.update { current ->
                                current.copy(
                                    explanationState = current.explanationState.copy(
                                        explanationText = UiText.Dynamic(update.accumulatedRawText),
                                        isLoading = true,
                                        keyLabel = update.keyLabel,
                                        modelUsed = update.modelUsed
                                    )
                                )
                            }
                        }

                        is AiStreamUpdate.Completed -> {
                            aiLocalState.update { current ->
                                current.copy(
                                    explanationState = current.explanationState.copy(
                                        explanationText = update.text,
                                        isLoading = false,
                                        errorUiText = null,
                                        isTruncated = update.isTruncated,
                                        keyLabel = update.keyLabel,
                                        modelUsed = update.modelUsed
                                    )
                                )
                            }
                        }

                        is AiStreamUpdate.Interrupted -> {
                            aiLocalState.update { current ->
                                current.copy(
                                    explanationState = current.explanationState.copy(
                                        explanationText = update.partialText,
                                        isLoading = false,
                                        errorUiText = UiText.Resource(
                                            R.string.ai_stream_interrupted,
                                            update.errorUiText
                                        ),
                                        keyLabel = update.keyLabel,
                                        modelUsed = update.modelUsed
                                    )
                                )
                            }
                        }

                        is AiStreamUpdate.Failed -> {
                            aiLocalState.update { current ->
                                current.copy(
                                    explanationState = current.explanationState.copy(
                                        isLoading = false,
                                        errorUiText = update.errorUiText
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                activeExplainJob = null
                aiLocalState.update { current ->
                    if (current.explanationState.isLoading) {
                        current.copy(
                            explanationState = current.explanationState.copy(isLoading = false)
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun dismissCommandExplanation() {
        activeExplainJob?.cancel()
        activeExplainJob = null
        aiLocalState.update { it.copy(explanationState = CommandExplanationState()) }
    }

    fun retryCommandExplanation() {
        val target = aiLocalState.value.explanationState.let {
            it.rawCommand.ifBlank { it.command }
        }
        if (target.isNotBlank()) {
            explainCommandInternal(command = target, bypassConsentCheck = false)
        }
    }

    fun summarizeTerminalOutput(selectedOverride: String? = null) {
        analyzeOutputInternal(
            kind = AiPromptKind.SUMMARIZE_OUTPUT,
            selectedOverride = selectedOverride,
            bypassConsentCheck = false
        )
    }

    fun findRootCauseInOutput(selectedOverride: String? = null) {
        analyzeOutputInternal(
            kind = AiPromptKind.FIND_ROOT_CAUSE,
            selectedOverride = selectedOverride,
            bypassConsentCheck = false
        )
    }

    private fun analyzeOutputInternal(
        kind: AiPromptKind,
        selectedOverride: String?,
        bypassConsentCheck: Boolean
    ) {
        val handle = currentHandle()
        val cfg = aiLocalState.value.contextConfig
        val explicitSelection = selectedOverride?.trim()?.takeIf { it.isNotEmpty() }
        if (explicitSelection != null) {
            aiLocalState.update {
                it.copy(
                    contextConfig = it.contextConfig.copy(
                        selectedText = explicitSelection,
                        includeSelectedText = true
                    )
                )
            }
        }
        val activeSelection = explicitSelection ?: cfg.selectedText.trim().takeIf {
            cfg.includeSelectedText && it.isNotEmpty()
        }
        val isFromSelection = activeSelection != null
        val outputSource = activeSelection
            ?: handle?.readRecentText(maxLines = cfg.recentLineCount)?.trim()?.takeIf { it.isNotEmpty() }

        if (outputSource == null) {
            aiLocalState.update {
                it.copy(statusBanner = UiText.Resource(R.string.ai_empty_output_notice))
            }
            return
        }

        if (!ensureAiReadyForPrompt(PendingAiAction.OutputAnalysis(kind, outputSource), bypassConsentCheck)) {
            return
        }

        openSheetIfTerminalNonSplit()
        val userLabelRes = when (kind) {
            AiPromptKind.FIND_ROOT_CAUSE -> if (isFromSelection) {
                R.string.ai_user_root_cause_selection
            } else {
                R.string.ai_user_root_cause_recent
            }
            else -> if (isFromSelection) {
                R.string.ai_user_summarize_selection
            } else {
                R.string.ai_user_summarize_recent
            }
        }
        val userMsg = AiChatMessage(
            role = AiMessageRole.USER,
            text = UiText.Resource(userLabelRes)
        )
        val context = AiPromptContext(
            cwd = if (cfg.includeCwd) (handle?.cwd?.value ?: "~") else null,
            lastError = if (kind == AiPromptKind.FIND_ROOT_CAUSE && cfg.includeLastError) {
                handle?.lastErrorContext?.value
            } else {
                null
            },
            selectedText = if (isFromSelection) outputSource else null,
            recentOutput = if (!isFromSelection) outputSource else null,
            shell = AiAssistantRepository.DEFAULT_SHELL
        )
        val request = AiPromptRequest(
            kind = kind,
            userPrompt = "",
            context = context
        )
        startStreamingPrompt(userMessage = userMsg, request = request, historyText = null)
    }

    fun sendAiMessage() {
        if (aiLocalState.value.isWriteCommandMode) {
            requestNaturalLanguageCommandInternal(goalOverride = null, bypassConsentCheck = false)
        } else {
            sendAiMessageInternal(bypassConsentCheck = false)
        }
    }

    private fun sendAiMessageInternal(bypassConsentCheck: Boolean) {
        val handle = currentHandle()
        val prompt = aiLocalState.value.input.trim()
        if (prompt.isEmpty() || aiLocalState.value.isLoading) return
        if (!ensureAiReadyForPrompt(PendingAiAction.SendMessage, bypassConsentCheck)) return

        aiLocalState.update { it.copy(input = "", isWriteCommandMode = false) }
        val userMsg = AiChatMessage(
            role = AiMessageRole.USER,
            text = UiText.Dynamic(prompt)
        )
        val request = AiPromptRequest(
            kind = AiPromptKind.CHAT,
            userPrompt = prompt,
            context = buildFilteredContext(handle)
        )
        startStreamingPrompt(userMessage = userMsg, request = request, historyText = prompt)
    }

    fun stopAiGeneration() {
        val job = activeAiJob ?: return
        activeAiJob = null
        job.cancel()

        aiLocalState.update { current ->
            val streamId = current.activeStreamingMessageId
            val raw = current.activeStreamingRawText
            val updatedMessages = current.messages.map { msg ->
                if (msg.id == streamId) {
                    if (raw.isBlank()) {
                        msg.copy(
                            text = UiText.Resource(R.string.ai_stream_stopped),
                            isStreaming = false,
                            canRetry = msg.retryRequest != null
                        )
                    } else {
                        val maxSuggestions = aiRepository.maxSuggestionsForKind(
                            msg.retryRequest?.kind ?: AiPromptKind.CHAT
                        )
                        val (clean, suggestions) = aiRepository.parseResponse(raw, maxSuggestions = maxSuggestions)
                        msg.copy(
                            text = clean,
                            suggestions = suggestions,
                            isStreaming = false,
                            streamInterruptedError = UiText.Resource(R.string.ai_stream_stopped),
                            canRetry = msg.retryRequest != null
                        )
                    }
                } else {
                    msg
                }
            }
            current.copy(
                isLoading = false,
                activeStreamingMessageId = null,
                activeStreamingRawText = "",
                messages = updatedMessages
            )
        }
    }

    fun retryAiMessage(messageId: String) {
        retryAiMessageInternal(messageId, bypassConsentCheck = false)
    }

    private fun retryAiMessageInternal(messageId: String, bypassConsentCheck: Boolean) {
        if (aiLocalState.value.isLoading) return
        val targetMsg = aiLocalState.value.messages.firstOrNull { it.id == messageId } ?: return
        val req = targetMsg.retryRequest ?: return
        if (!ensureAiReadyForPrompt(PendingAiAction.RetryMessage(messageId), bypassConsentCheck)) return

        // Re-stream directly into a fresh assistant slot while clearing retry flag on old message
        aiLocalState.update { current ->
            current.copy(
                messages = current.messages.map {
                    if (it.id == messageId) it.copy(canRetry = false, streamInterruptedError = null) else it
                }
            )
        }
        startStreamingPrompt(userMessage = null, request = req, historyText = null)
    }

    private fun startStreamingPrompt(
        userMessage: AiChatMessage?,
        request: AiPromptRequest,
        historyText: String?
    ) {
        activeAiJob?.cancel()

        val assistantPlaceholder = AiChatMessage(
            role = AiMessageRole.ASSISTANT,
            text = UiText.Dynamic(""),
            isStreaming = true,
            retryRequest = request
        )

        aiLocalState.update { current ->
            val added = buildList {
                if (userMessage != null) add(userMessage)
                add(assistantPlaceholder)
            }
            current.copy(
                isLoading = true,
                activeStreamingMessageId = assistantPlaceholder.id,
                activeStreamingRawText = "",
                messages = current.messages + added
            )
        }

        if (!historyText.isNullOrBlank()) {
            viewModelScope.launch {
                promptHistoryStore.recordPrompt(historyText)
            }
        }

        activeAiJob = viewModelScope.launch {
            try {
                aiRepository.streamPrompt(request).collect { update ->
                    val ws = workspaceState.value
                    val isAiCurrentlyVisible = aiLocalState.value.isAiPanelOpen ||
                        ws.section == WorkspaceSection.AI ||
                        ws.splitMode == SplitMode.TERM_AND_AI

                    when (update) {
                        is AiStreamUpdate.Delta -> {
                            aiLocalState.update { current ->
                                current.copy(
                                    activeStreamingRawText = update.accumulatedRawText,
                                    messages = current.messages.map { msg ->
                                        if (msg.id == assistantPlaceholder.id) {
                                            msg.copy(
                                                text = UiText.Dynamic(update.accumulatedRawText),
                                                keyLabel = update.keyLabel,
                                                modelUsed = update.modelUsed,
                                                isStreaming = true
                                            )
                                        } else {
                                            msg
                                        }
                                    }
                                )
                            }
                        }

                        is AiStreamUpdate.Completed -> {
                            aiLocalState.update { current ->
                                current.copy(
                                    isLoading = false,
                                    activeStreamingMessageId = null,
                                    activeStreamingRawText = "",
                                    hasUnreadAiReply = if (!isAiCurrentlyVisible) true else current.hasUnreadAiReply,
                                    messages = current.messages.map { msg ->
                                        if (msg.id == assistantPlaceholder.id) {
                                            msg.copy(
                                                text = update.text,
                                                suggestions = update.suggestions,
                                                keyLabel = update.keyLabel,
                                                modelUsed = update.modelUsed,
                                                isStreaming = false,
                                                isTruncated = update.isTruncated,
                                                streamInterruptedError = null,
                                                canRetry = false
                                            )
                                        } else {
                                            msg
                                        }
                                    }
                                )
                            }
                        }

                        is AiStreamUpdate.Interrupted -> {
                            aiLocalState.update { current ->
                                current.copy(
                                    isLoading = false,
                                    activeStreamingMessageId = null,
                                    activeStreamingRawText = "",
                                    hasUnreadAiReply = if (!isAiCurrentlyVisible) true else current.hasUnreadAiReply,
                                    messages = current.messages.map { msg ->
                                        if (msg.id == assistantPlaceholder.id) {
                                            msg.copy(
                                                text = update.partialText,
                                                suggestions = update.suggestions,
                                                keyLabel = update.keyLabel,
                                                modelUsed = update.modelUsed,
                                                isStreaming = false,
                                                streamInterruptedError = UiText.Resource(
                                                    R.string.ai_stream_interrupted,
                                                    update.errorUiText
                                                ),
                                                canRetry = true,
                                                retryRequest = request
                                            )
                                        } else {
                                            msg
                                        }
                                    }
                                )
                            }
                        }

                        is AiStreamUpdate.Failed -> {
                            aiLocalState.update { current ->
                                current.copy(
                                    isLoading = false,
                                    activeStreamingMessageId = null,
                                    activeStreamingRawText = "",
                                    hasUnreadAiReply = if (!isAiCurrentlyVisible) true else current.hasUnreadAiReply,
                                    messages = current.messages.map { msg ->
                                        if (msg.id == assistantPlaceholder.id) {
                                            msg.copy(
                                                role = AiMessageRole.ERROR,
                                                text = update.errorUiText,
                                                isStreaming = false,
                                                canRetry = true,
                                                retryRequest = request
                                            )
                                        } else {
                                            msg
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                activeAiJob = null
                aiLocalState.update { current ->
                    if (current.activeStreamingMessageId == assistantPlaceholder.id) {
                        current.copy(isLoading = false, activeStreamingMessageId = null)
                    } else {
                        current
                    }
                }
            }
        }
    }

    // --- Prompt History ---

    fun reusePromptFromHistory(prompt: String) {
        aiLocalState.update { it.copy(input = prompt) }
    }

    fun clearPromptHistory() {
        viewModelScope.launch {
            promptHistoryStore.clearAll()
        }
    }

    // --- Snippets Library ---

    fun saveSnippet(title: String, command: String, tags: List<String>, existingId: String? = null) {
        if (command.isBlank()) return
        viewModelScope.launch {
            snippetStore.saveSnippet(title = title, command = command, tags = tags, existingId = existingId)
            aiLocalState.update {
                it.copy(statusBanner = UiText.Resource(R.string.snippets_saved_banner))
            }
        }
    }

    fun deleteSnippet(id: String) {
        viewModelScope.launch {
            snippetStore.deleteSnippet(id)
            aiLocalState.update {
                it.copy(statusBanner = UiText.Resource(R.string.snippets_deleted_banner))
            }
        }
    }

    fun buildSnippetsExportJson(): String = snippetStore.exportToJson(uiState.value.snippets)

    fun notifySnippetsExported(count: Int) {
        aiLocalState.update {
            it.copy(statusBanner = UiText.Resource(R.string.snippets_exported_banner, count))
        }
    }

    fun importSnippetsJson(rawJson: String) {
        viewModelScope.launch {
            val result = snippetStore.importFromJson(rawJson)
            result.onSuccess { count ->
                aiLocalState.update {
                    it.copy(statusBanner = UiText.Resource(R.string.snippets_imported_banner, count))
                }
            }.onFailure { err ->
                val bannerText = if (err is SnippetImportException) {
                    UiText.Resource(err.messageRes, *err.formatArgs.toTypedArray())
                } else {
                    UiText.Resource(
                        R.string.snippets_io_error_banner,
                        err.message ?: "Invalid JSON"
                    )
                }
                aiLocalState.update {
                    it.copy(statusBanner = bannerText)
                }
            }
        }
    }

    fun notifySnippetIoError(message: String) {
        aiLocalState.update {
            it.copy(statusBanner = UiText.Resource(R.string.snippets_io_error_banner, message))
        }
    }

    class Factory(
        private val terminalBackend: TerminalBackend,
        private val aiRepository: AiAssistantRepository,
        private val keyManager: KeyManager,
        private val settingsStore: SettingsStore,
        private val snippetStore: SnippetStore,
        private val promptHistoryStore: PromptHistoryStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TerminalViewModel(
                terminalBackend = terminalBackend,
                aiRepository = aiRepository,
                keyManager = keyManager,
                settingsStore = settingsStore,
                snippetStore = snippetStore,
                promptHistoryStore = promptHistoryStore
            ) as T
        }
    }

    companion object {
        const val SCREEN_FRAME_INTERVAL_MS = 16L
    }
}
