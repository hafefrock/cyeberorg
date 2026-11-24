package com.example.ui.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.ai.AiBottomSheetOverlay
import com.example.ui.ai.AiFullScreen
import com.example.ui.ai.CommandExplanationBottomSheet
import com.example.ui.theme.StudioElevation
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.studioTouchTarget
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToApiKeysSettings: () -> Unit = onNavigateToSettings
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

    val defaultGoal = stringResource(R.string.ai_goal_default)
    val defaultScript = stringResource(R.string.ai_script_default)

    val onCopyCommand = remember(clipboardManager, viewModel) {
        { cmd: String ->
            clipboardManager.setText(AnnotatedString(cmd))
            viewModel.showCopiedFeedback()
        }
    }
    val onInsertSuggestedCommand = remember(clipboardManager, viewModel) {
        { cmd: String ->
            viewModel.insertSuggestedCommand(cmd) { copied ->
                clipboardManager.setText(AnnotatedString(copied))
            }
        }
    }
    val onInsertSnippetCommand = remember(clipboardManager, viewModel) {
        { cmd: String ->
            viewModel.insertSnippetCommand(cmd, userConfirmed = true) { copied ->
                clipboardManager.setText(AnnotatedString(copied))
            }
        }
    }
    val onCopyRawCommand = remember(clipboardManager) {
        { cmd: String ->
            clipboardManager.setText(AnnotatedString(cmd))
        }
    }
    val onSaveSnippetTriple = remember(viewModel) {
        { title: String, cmd: String, tags: List<String> ->
            viewModel.saveSnippet(title, cmd, tags)
        }
    }
    val onOpenSidebar = remember(viewModel) { { viewModel.toggleSidebar() } }
    val onSelectTabSession = remember(viewModel) {
        { id: String ->
            viewModel.switchSession(id)
            viewModel.selectWorkspaceSection(WorkspaceSection.TERMINAL)
        }
    }
    val onToggleAiPanel = remember(viewModel) { { viewModel.toggleAiPanel() } }
    val onOpenSettingsFromSidebar = remember(viewModel, onNavigateToSettings) {
        {
            viewModel.closeSidebar()
            onNavigateToSettings()
        }
    }

    LaunchedEffect(state.statusBannerMessage) {
        val uiText = state.statusBannerMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(uiText.asString(context))
        viewModel.clearStatusBanner()
    }

    TerminalWindowController(
        isTerminalScreenActive = state.activeSection == WorkspaceSection.TERMINAL,
        fullscreenEnabled = state.settings.fullscreenTerminal,
        keepScreenOnEnabled = state.settings.keepScreenOn
    )

    val rootDensity = LocalDensity.current
    var fullScreenHeightDp by remember { mutableStateOf(Dp.Unspecified) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.height > 0) {
                    fullScreenHeightDp = with(rootDensity) { size.height.toDp() }
                }
            }
            .drawBehind {
                if (tokens.canvasGradientColors.size >= 2) {
                    drawRect(brush = Brush.verticalGradient(colors = tokens.canvasGradientColors))
                } else {
                    drawRect(tokens.baseCanvas)
                }
                if (tokens.glowRadius > StudioElevation.none) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                tokens.accent.copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.14f, size.height * 0.10f),
                            radius = size.maxDimension * 0.65f
                        )
                    )
                }
            }
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= dimens.wideScreenBreakpoint
            val isWideSplitCapable = maxWidth >= dimens.splitWideBreakpoint
            val sidebarVisible = !useRail && state.isSidebarOpen
            val density = LocalDensity.current
            val isImeOpen = WindowInsets.isImeVisible || WindowInsets.ime.getBottom(density) > 0

            // Mutually-exclusive layered BackHandlers:
            // 1) Sidebar drawer, 2) AI bottom sheet on Terminal, 3) Non-Terminal tab -> Terminal
            BackHandler(enabled = sidebarVisible) {
                viewModel.closeSidebar()
            }
            BackHandler(
                enabled = !sidebarVisible &&
                    state.activeSection == WorkspaceSection.TERMINAL &&
                    state.isAiPanelOpen &&
                    state.splitMode != SplitMode.TERM_AND_AI
            ) {
                viewModel.toggleAiPanel(forceOpen = false)
            }
            BackHandler(
                enabled = !sidebarVisible &&
                    state.activeSection != WorkspaceSection.TERMINAL
            ) {
                viewModel.selectWorkspaceSection(WorkspaceSection.TERMINAL)
            }

            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
            ) { _ ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                    .pointerInput(useRail, state.isSidebarOpen) {
                        if (useRail || state.isSidebarOpen) return@pointerInput
                        val edgeZonePx = dimens.sidebarGestureZone.toPx()
                        val openThresholdPx = (dimens.sidebarGestureZone * 1.5f).toPx()
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            if (down.position.x > edgeZonePx) return@awaitEachGesture
                            var tracking = true
                            var horizontalLocked = false
                            var totalDx = 0f
                            while (tracking) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.positionChange()
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (!horizontalLocked) {
                                    when {
                                        abs(dy) > touchSlop && abs(dy) >= abs(dx) -> tracking = false
                                        dx > touchSlop && dx > abs(dy) * 1.5f -> horizontalLocked = true
                                    }
                                }
                                if (horizontalLocked) {
                                    change.consume()
                                    totalDx += delta.x
                                    if (dx >= openThresholdPx || totalDx >= openThresholdPx) {
                                        viewModel.openSidebar()
                                        tracking = false
                                    }
                                }
                            }
                        }
                    }
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (useRail) {
                        WorkspaceNavigationRail(
                            selectedSection = state.activeSection,
                            aiEnabled = state.settings.aiEnabled,
                            onSelectSection = viewModel::selectWorkspaceSection,
                            onOpenSettings = onNavigateToSettings
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        SessionTabsBar(
                            sessions = state.sessionTabs,
                            activeSessionId = state.activeSessionId,
                            aiEnabled = state.settings.aiEnabled,
                            isAiPanelOpen = state.isAiPanelOpen,
                            hasUnreadAiReply = state.hasUnreadAiReply,
                            showSplitButton = isWideSplitCapable && state.activeSection == WorkspaceSection.TERMINAL,
                            splitMode = state.splitMode,
                            showMenuButton = !useRail,
                            isSidebarOpen = state.isSidebarOpen,
                            onOpenSidebar = onOpenSidebar,
                            onSelectSession = onSelectTabSession,
                            onAddSession = viewModel::createSession,
                            onCloseSession = viewModel::closeSession,
                            onRenameSession = viewModel::renameSession,
                            onToggleAiPanel = onToggleAiPanel,
                            onToggleSplitMode = viewModel::toggleSplitMode
                        )

                    AnimatedContent(
                        targetState = state.activeSection,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(tokens.motionDurationMs)) togetherWith
                                fadeOut(animationSpec = tween(tokens.motionDurationMs))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        label = "workspaceSection"
                    ) { section ->
                        when (section) {
                            WorkspaceSection.FILES -> {
                                FilesPlaceholderPane(
                                    onInspectCommand = onInsertSuggestedCommand
                                )
                            }

                            WorkspaceSection.SNIPPETS -> {
                                SnippetsScreen(
                                    snippets = state.snippets,
                                    onInsertSnippet = onInsertSnippetCommand,
                                    onCopySnippet = onCopyCommand,
                                    onExplainSnippet = viewModel::explainCommand,
                                    onSaveSnippet = viewModel::saveSnippet,
                                    onDeleteSnippet = viewModel::deleteSnippet,
                                    onBuildExportJson = viewModel::buildSnippetsExportJson,
                                    onExportSuccess = viewModel::notifySnippetsExported,
                                    onImportJson = viewModel::importSnippetsJson,
                                    onIoError = viewModel::notifySnippetIoError
                                )
                            }

                            WorkspaceSection.AI -> {
                                AiFullScreen(
                                    messages = state.aiMessages,
                                    input = state.aiInput,
                                    isLoading = state.isAiLoading,
                                    isWriteCommandMode = state.isWriteCommandMode,
                                    contextConfig = state.aiContextConfig,
                                    activeCwd = state.activeCwd,
                                    activeLastError = state.activeLastError,
                                    promptHistory = state.promptHistory,
                                    selectedModel = state.settings.selectedModel,
                                    keySnapshot = state.keySnapshot,
                                    showCloseButton = false,
                                    onSelectModel = viewModel::selectAiModel,
                                    onInputChange = viewModel::updateAiInput,
                                    onToggleWriteCommandMode = viewModel::onWriteCommandChipClicked,
                                    onSend = viewModel::sendAiMessage,
                                    onStopGeneration = viewModel::stopAiGeneration,
                                    onRetryMessage = viewModel::retryAiMessage,
                                    onToggleContextCwd = viewModel::toggleContextCwd,
                                    onToggleContextLastError = viewModel::toggleContextLastError,
                                    onToggleContextSelectedText = viewModel::toggleContextSelectedText,
                                    onClearSelectedTextContext = viewModel::clearSelectedTextContext,
                                    onToggleContextRecentOutput = viewModel::toggleContextRecentOutput,
                                    onClearChat = viewModel::clearAiChat,
                                    onInsertCommand = onInsertSuggestedCommand,
                                    onCopyCommand = onCopyCommand,
                                    onSaveSnippet = onSaveSnippetTriple,
                                    onExplainCommand = viewModel::explainCommand,
                                    onExplainLastError = viewModel::explainLastError,
                                    onFixLastError = viewModel::fixLastError,
                                    onSummarizeOutput = { viewModel.summarizeTerminalOutput() },
                                    onReuseHistoryPrompt = viewModel::reusePromptFromHistory,
                                    onClearPromptHistory = viewModel::clearPromptHistory,
                                    onOpenSettings = onNavigateToSettings,
                                    onOpenApiKeysSettings = onNavigateToApiKeysSettings,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            WorkspaceSection.TERMINAL, WorkspaceSection.SETTINGS -> {
                                TerminalWorkspaceBody(
                                    state = state,
                                    isWideSplitCapable = isWideSplitCapable,
                                    fullScreenHeight = fullScreenHeightDp,
                                    defaultGoal = defaultGoal,
                                    defaultScript = defaultScript,
                                    viewModel = viewModel,
                                    onCopyCommand = onCopyCommand,
                                    onInsertCommand = onInsertSuggestedCommand,
                                    onCopyRawCommand = onCopyRawCommand,
                                    onSaveSnippet = onSaveSnippetTriple,
                                    onNavigateToSettings = onNavigateToSettings,
                                    onNavigateToApiKeysSettings = onNavigateToApiKeysSettings
                                )
                            }
                        }
                    }

                    if (!isImeOpen) {
                        WorkspaceStatusBar(
                            cwd = state.activeCwd,
                            lastExitCode = state.activeLastExitCode,
                            keySnapshot = state.keySnapshot,
                            selectedModel = state.settings.selectedModel
                        )
                    }
                }
            }

            if (!useRail) {
                WorkspaceSidebarDrawer(
                    visible = state.isSidebarOpen,
                    selectedSection = state.activeSection,
                    aiEnabled = state.settings.aiEnabled,
                    activeTheme = state.settings.appTheme,
                    onSelectSection = viewModel::selectWorkspaceSection,
                    onOpenSettings = onOpenSettingsFromSidebar,
                    onDismiss = viewModel::closeSidebar
                )
            }
        }
    }
    }
    }

    if (state.isAiConsentDialogVisible) {
        AiDataConsentDialog(
            onAccept = viewModel::acceptAiConsentAndContinue,
            onDecline = viewModel::dismissAiConsentDialog
        )
    }

    CommandExplanationBottomSheet(
        state = state.commandExplanation,
        onDismiss = viewModel::dismissCommandExplanation,
        onRetry = viewModel::retryCommandExplanation
    )
}

@Composable
private fun AiDataConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    AlertDialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = StudioSpacing.lg)
            .widthIn(max = dimens.dialogMaxWidth)
            .testTag("ai_consent_dialog"),
        containerColor = tokens.floatingGlass,
        shape = tokens.cardShape,
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = tokens.accent
            )
        },
        title = {
            Text(
                text = stringResource(R.string.ai_consent_dialog_title),
                style = compactType.screenTitle,
                color = tokens.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)) {
                Text(
                    text = stringResource(R.string.ai_consent_dialog_body_data),
                    style = compactType.body,
                    color = tokens.textPrimary
                )
                Text(
                    text = stringResource(R.string.ai_consent_dialog_body_free_tier),
                    style = compactType.body,
                    color = tokens.warning
                )
                Text(
                    text = stringResource(R.string.ai_consent_dialog_body_redaction),
                    style = compactType.body,
                    color = tokens.textSecondary
                )
            }
        },
        confirmButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Button(
                    onClick = onAccept,
                    shape = tokens.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.primaryButtonContainer,
                        contentColor = tokens.primaryButtonContent
                    ),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = onAccept)
                        .testTag("confirm_ai_consent_button")
                ) {
                    Text(stringResource(R.string.ai_consent_dialog_accept), style = compactType.tabOrChip)
                }
            }
        },
        dismissButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                TextButton(
                    onClick = onDecline,
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = onDecline)
                        .testTag("cancel_ai_consent_button")
                ) {
                    Text(stringResource(R.string.action_cancel), style = compactType.tabOrChip, color = tokens.textSecondary)
                }
            }
        }
    )
}

@Composable
private fun TerminalWorkspaceBody(
    state: TerminalScreenUiState,
    isWideSplitCapable: Boolean,
    fullScreenHeight: Dp,
    defaultGoal: String,
    defaultScript: String,
    viewModel: TerminalViewModel,
    onCopyCommand: (String) -> Unit,
    onInsertCommand: (String) -> Unit,
    onCopyRawCommand: (String) -> Unit,
    onSaveSnippet: (String, String, List<String>) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToApiKeysSettings: () -> Unit = onNavigateToSettings
) {
    val tokens = StudioTheme.tokens
    val activeTitle = remember(state.sessionTabs, state.activeSessionId) {
        state.sessionTabs.firstOrNull { it.id == state.activeSessionId }?.title.orEmpty()
    }

    if (state.sessionTabs.isEmpty()) {
        EmptySessionsCard(onCreateSession = viewModel::createSession)
        return
    }

    val useSplitAiPane = isWideSplitCapable &&
        state.splitMode == SplitMode.TERM_AND_AI &&
        state.settings.aiEnabled

    val sheetVisible = !useSplitAiPane &&
        state.isAiPanelOpen &&
        state.settings.aiEnabled

    val onCloseSplitPanel = remember(viewModel) { { viewModel.setSplitMode(SplitMode.SINGLE) } }

    if (isWideSplitCapable) {
        Row(modifier = Modifier.fillMaxSize()) {
            PrimaryTerminalWithSheet(
                activeTitle = activeTitle,
                state = state,
                sheetVisible = sheetVisible,
                fullScreenHeight = fullScreenHeight,
                defaultGoal = defaultGoal,
                defaultScript = defaultScript,
                viewModel = viewModel,
                onCopyCommand = onCopyCommand,
                onInsertCommand = onInsertCommand,
                onCopyRawCommand = onCopyRawCommand,
                onSaveSnippet = onSaveSnippet,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToApiKeysSettings = onNavigateToApiKeysSettings,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            // Dual-terminal split pane
            if (state.splitMode == SplitMode.DUAL_TERMINAL) {
                val second = state.secondarySession
                if (second != null) {
                    val secondId = second.id
                    val onPaneFocused = remember(viewModel, secondId) { { viewModel.switchSession(secondId) } }
                    val onResizeSecond = remember(viewModel, secondId) {
                        { c: Int, r: Int -> viewModel.resizeSession(secondId, c, r) }
                    }
                    val onImeTextSecond = remember(viewModel, secondId) {
                        { txt: String -> viewModel.onImeTextInputForSession(secondId, txt) }
                    }
                    val onImeBackspaceSecond = remember(viewModel, secondId) {
                        { viewModel.onImeBackspaceForSession(secondId) }
                    }
                    val onImeEnterSecond = remember(viewModel, secondId) {
                        { viewModel.onImeEnterForSession(secondId) }
                    }
                    val onSendRawBytesSecond = remember(viewModel, secondId) {
                        { bytes: ByteArray -> viewModel.writeRawBytesToSession(secondId, bytes) }
                    }
                    val onClearSecond = remember(viewModel, secondId) {
                        { viewModel.clearSession(secondId) }
                    }
                    val onExplainSecond = remember(viewModel, secondId) {
                        {
                            viewModel.switchSession(secondId)
                            viewModel.explainLastError()
                        }
                    }
                    val onFixSecond = remember(viewModel, secondId) {
                        {
                            viewModel.switchSession(secondId)
                            viewModel.fixLastError()
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        TerminalConsoleView(
                            sessionTitle = second.title,
                            cwd = second.cwd,
                            lastExitCode = second.lastExitCode,
                            lastError = second.lastError,
                            screenFlow = viewModel.secondaryScreen,
                            ctrlArmed = state.ctrlArmed,
                            altArmed = state.altArmed,
                            fontSizeSp = state.settings.fontSizeSp,
                            terminalFontId = state.settings.terminalFontId,
                            colorScheme = state.settings.terminalColorScheme,
                            aiEnabled = state.settings.aiEnabled,
                            isFocusedPane = false,
                            onPaneFocused = onPaneFocused,
                            onResize = onResizeSecond,
                            onImeTextInput = onImeTextSecond,
                            onImeBackspace = onImeBackspaceSecond,
                            onImeEnter = onImeEnterSecond,
                            onSendRawBytes = onSendRawBytesSecond,
                            onClearOutput = onClearSecond,
                            onExplainLastError = onExplainSecond,
                            onFixLastError = onFixSecond,
                            onCopySelection = onCopyCommand,
                            onAskAiWithSelection = viewModel::askAiWithSelection,
                            onExplainSelection = viewModel::explainSelectedText,
                            onSummarizeSelection = viewModel::summarizeTerminalOutput,
                            onFindRootCauseSelection = viewModel::findRootCauseInOutput,
                            onFontSizeChange = viewModel::setFontSizeSp,
                            onInsertToken = onInsertCommand,
                            onShowBanner = viewModel::showMessageBanner,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(StudioSpacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.split_second_session_hint),
                            color = tokens.textSecondary
                        )
                    }
                }
            }

            // Permanent side-by-side AI pane in TERM_AND_AI split mode (bottom sheet not used here)
            AnimatedVisibility(
                visible = useSplitAiPane,
                enter = slideInHorizontally(
                    animationSpec = tween(tokens.motionDurationMs),
                    initialOffsetX = { it }
                ) + fadeIn(animationSpec = tween(tokens.motionDurationMs)),
                exit = slideOutHorizontally(
                    animationSpec = tween(tokens.motionDurationMs),
                    targetOffsetX = { it }
                ) + fadeOut(animationSpec = tween(tokens.motionDurationMs))
            ) {
                AiFullScreen(
                    messages = state.aiMessages,
                    input = state.aiInput,
                    isLoading = state.isAiLoading,
                    isWriteCommandMode = state.isWriteCommandMode,
                    contextConfig = state.aiContextConfig,
                    activeCwd = state.activeCwd,
                    activeLastError = state.activeLastError,
                    promptHistory = state.promptHistory,
                    selectedModel = state.settings.selectedModel,
                    keySnapshot = state.keySnapshot,
                    showCloseButton = true,
                    onSelectModel = viewModel::selectAiModel,
                    onInputChange = viewModel::updateAiInput,
                    onToggleWriteCommandMode = viewModel::onWriteCommandChipClicked,
                    onSend = viewModel::sendAiMessage,
                    onStopGeneration = viewModel::stopAiGeneration,
                    onRetryMessage = viewModel::retryAiMessage,
                    onToggleContextCwd = viewModel::toggleContextCwd,
                    onToggleContextLastError = viewModel::toggleContextLastError,
                    onToggleContextSelectedText = viewModel::toggleContextSelectedText,
                    onClearSelectedTextContext = viewModel::clearSelectedTextContext,
                    onToggleContextRecentOutput = viewModel::toggleContextRecentOutput,
                    onClearChat = viewModel::clearAiChat,
                    onInsertCommand = onInsertCommand,
                    onCopyCommand = onCopyCommand,
                    onSaveSnippet = onSaveSnippet,
                    onExplainCommand = viewModel::explainCommand,
                    onExplainLastError = viewModel::explainLastError,
                    onFixLastError = viewModel::fixLastError,
                    onSummarizeOutput = { viewModel.summarizeTerminalOutput() },
                    onReuseHistoryPrompt = viewModel::reusePromptFromHistory,
                    onClearPromptHistory = viewModel::clearPromptHistory,
                    onOpenSettings = onNavigateToSettings,
                    onOpenApiKeysSettings = onNavigateToApiKeysSettings,
                    onClosePanel = onCloseSplitPanel,
                    modifier = Modifier
                        .width(StudioTheme.dimens.sidePanelWidth)
                        .testTag("ai_side_panel")
                )
            }
        }
    } else {
        PrimaryTerminalWithSheet(
            activeTitle = activeTitle,
            state = state,
            sheetVisible = sheetVisible,
            fullScreenHeight = fullScreenHeight,
            defaultGoal = defaultGoal,
            defaultScript = defaultScript,
            viewModel = viewModel,
            onCopyCommand = onCopyCommand,
            onInsertCommand = onInsertCommand,
            onCopyRawCommand = onCopyRawCommand,
            onSaveSnippet = onSaveSnippet,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToApiKeysSettings = onNavigateToApiKeysSettings,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PrimaryTerminalWithSheet(
    activeTitle: String,
    state: TerminalScreenUiState,
    sheetVisible: Boolean,
    fullScreenHeight: Dp,
    defaultGoal: String,
    defaultScript: String,
    viewModel: TerminalViewModel,
    onCopyCommand: (String) -> Unit,
    onInsertCommand: (String) -> Unit,
    onCopyRawCommand: (String) -> Unit,
    onSaveSnippet: (String, String, List<String>) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToApiKeysSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onSuggestCommand = remember(viewModel, defaultGoal) {
        { viewModel.requestCommandSuggestion(defaultGoal) }
    }
    val onWriteScript = remember(viewModel, defaultScript) {
        { viewModel.requestShellScript(defaultScript) }
    }
    val onInsertInSheet = remember(viewModel, onCopyRawCommand) {
        { cmd: String -> viewModel.insertSuggestedCommand(cmd, onCopyRawCommand) }
    }
    val onDismissSheet = remember(viewModel) {
        { viewModel.toggleAiPanel(forceOpen = false) }
    }

    Column(modifier = modifier) {
        // Box keeps TerminalConsoleView fillMaxSize() so AiBottomSheetOverlay never resizes the terminal
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            TerminalConsoleView(
                sessionTitle = activeTitle,
                cwd = state.activeCwd,
                lastExitCode = state.activeLastExitCode,
                lastError = state.activeLastError,
                screenFlow = viewModel.activeScreen,
                ctrlArmed = state.ctrlArmed,
                altArmed = state.altArmed,
                fontSizeSp = state.settings.fontSizeSp,
                terminalFontId = state.settings.terminalFontId,
                colorScheme = state.settings.terminalColorScheme,
                aiEnabled = state.settings.aiEnabled,
                isFocusedPane = true,
                isAiPanelOpen = sheetVisible,
                onResize = viewModel::resizeActiveSession,
                onImeTextInput = viewModel::onImeTextInput,
                onImeBackspace = viewModel::onImeBackspace,
                onImeEnter = viewModel::onImeEnter,
                onSendRawBytes = viewModel::writeRawBytes,
                onClearOutput = viewModel::clearActiveSession,
                onExplainLastError = viewModel::explainLastError,
                onFixLastError = viewModel::fixLastError,
                onCopySelection = onCopyCommand,
                onAskAiWithSelection = viewModel::askAiWithSelection,
                onExplainSelection = viewModel::explainSelectedText,
                onSummarizeSelection = viewModel::summarizeTerminalOutput,
                onFindRootCauseSelection = viewModel::findRootCauseInOutput,
                onFontSizeChange = viewModel::setFontSizeSp,
                onInsertToken = onInsertCommand,
                onShowBanner = viewModel::showMessageBanner,
                modifier = Modifier.fillMaxSize()
            )

            AiBottomSheetOverlay(
                visible = sheetVisible,
                messages = state.aiMessages,
                input = state.aiInput,
                isLoading = state.isAiLoading,
                isWriteCommandMode = state.isWriteCommandMode,
                contextConfig = state.aiContextConfig,
                activeCwd = state.activeCwd,
                activeLastError = state.activeLastError,
                selectedModel = state.settings.selectedModel,
                keySnapshot = state.keySnapshot,
                onSelectModel = viewModel::selectAiModel,
                onInputChange = viewModel::updateAiInput,
                onToggleWriteCommandMode = viewModel::onWriteCommandChipClicked,
                onSend = viewModel::sendAiMessage,
                onStopGeneration = viewModel::stopAiGeneration,
                onRetryMessage = viewModel::retryAiMessage,
                onExplainLastError = viewModel::explainLastError,
                onFixLastError = viewModel::fixLastError,
                onSuggestCommand = onSuggestCommand,
                onWriteScript = onWriteScript,
                onWriteCommand = viewModel::onWriteCommandChipClicked,
                onSummarizeOutput = { viewModel.summarizeTerminalOutput() },
                onFindRootCause = { viewModel.findRootCauseInOutput() },
                onToggleContextCwd = viewModel::toggleContextCwd,
                onToggleContextLastError = viewModel::toggleContextLastError,
                onToggleContextSelectedText = viewModel::toggleContextSelectedText,
                onClearSelectedTextContext = viewModel::clearSelectedTextContext,
                onToggleContextRecentOutput = viewModel::toggleContextRecentOutput,
                onInsertCommand = onInsertInSheet,
                onCopyCommand = onCopyCommand,
                onSaveSnippet = onSaveSnippet,
                onExplainCommand = viewModel::explainCommand,
                onOpenSettings = onNavigateToSettings,
                onOpenApiKeysSettings = onNavigateToApiKeysSettings,
                onViewFullConversation = viewModel::openFullAiTab,
                onDismiss = onDismissSheet,
                fullScreenHeight = fullScreenHeight
            )
        }

        if (!sheetVisible) {
            ExtraKeysBar(
                layout = state.settings.extraKeysLayout,
                ctrlArmed = state.ctrlArmed,
                altArmed = state.altArmed,
                onKeyClick = viewModel::sendExtraKey
            )
        }
    }
}
