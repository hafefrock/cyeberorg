package com.example.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ai.AiChatMessage
import com.example.ai.AiMessageRole
import com.example.ai.CommandSuggestion
import com.example.ai.KeyManagerSnapshot
import com.example.data.GeminiModels
import com.example.terminal.TerminalErrorContext
import com.example.ui.UiText
import com.example.ui.terminal.AiContextConfig
import com.example.ui.terminal.SnippetEditorDialog
import com.example.ui.terminal.SnippetEditorState
import com.example.ui.theme.StudioElevation
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.chamferInset

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiBottomSheetOverlay(
    visible: Boolean,
    messages: List<AiChatMessage>,
    input: String,
    isLoading: Boolean,
    contextConfig: AiContextConfig,
    activeCwd: String,
    activeLastError: TerminalErrorContext?,
    selectedModel: String?,
    keySnapshot: KeyManagerSnapshot,
    onSelectModel: (String) -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
    onRetryMessage: (String) -> Unit,
    onExplainLastError: () -> Unit,
    onFixLastError: () -> Unit = {},
    onSuggestCommand: () -> Unit,
    onWriteScript: () -> Unit,
    onToggleContextCwd: () -> Unit,
    onToggleContextLastError: () -> Unit,
    onToggleContextSelectedText: () -> Unit,
    onClearSelectedTextContext: () -> Unit,
    onToggleContextRecentOutput: () -> Unit,
    onInsertCommand: (String) -> Unit,
    onCopyCommand: (String) -> Unit,
    onSaveSnippet: (title: String, command: String, tags: List<String>) -> Unit,
    onOpenSettings: () -> Unit,
    onViewFullConversation: () -> Unit,
    onDismiss: () -> Unit,
    onOpenApiKeysSettings: () -> Unit = onOpenSettings,
    isWriteCommandMode: Boolean = false,
    onToggleWriteCommandMode: () -> Unit = {},
    onWriteCommand: () -> Unit = {},
    onSummarizeOutput: () -> Unit = {},
    onFindRootCause: () -> Unit = {},
    onExplainCommand: (String) -> Unit = {},
    fullScreenHeight: Dp = Dp.Unspecified,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val validSelectedModel = remember(selectedModel) { GeminiModels.normalizeSelectedModel(selectedModel) }
    val hasModelSelected = validSelectedModel != null
    val hasActiveKey = keySnapshot.hasUsableActiveKey()

    var isExpanded by remember { mutableStateOf(false) }
    var dragDeltaDp by remember { mutableFloatStateOf(0f) }
    var saveCandidate by remember { mutableStateOf<CommandSuggestion?>(null) }
    var wasVisible by remember { mutableStateOf(visible) }

    val currentErrorToken = remember(activeLastError) {
        activeLastError?.let {
            "${it.sessionId}:${it.exitCode}:${it.command}:${it.errorOutput.hashCode()}"
        }
    }
    var handledErrorToken by remember { mutableStateOf<String?>(null) }
    val lastMessage = messages.lastOrNull()
    val explainErrResId = R.string.ai_user_explain_error
    val fixErrResId = R.string.ai_user_fix_error
    LaunchedEffect(lastMessage?.id, currentErrorToken) {
        if (currentErrorToken != null && lastMessage?.role == AiMessageRole.USER) {
            val resId = (lastMessage.text as? UiText.Resource)?.resId
            if (resId == explainErrResId || resId == fixErrResId) {
                handledErrorToken = currentErrorToken
            }
        }
    }
    val showErrorCard = activeLastError != null && currentErrorToken != handledErrorToken

    LaunchedEffect(visible) {
        if (visible) {
            isExpanded = false
            dragDeltaDp = 0f
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        } else if (wasVisible) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
        wasVisible = visible
    }

    val dismissSheet = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onDismiss()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerHeight = maxHeight
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val imeBottomDp = with(density) { imeBottomPx.toDp() }
        val isImeOpen = WindowInsets.isImeVisible || imeBottomPx > 0
        val referenceFullHeight = when {
            fullScreenHeight != Dp.Unspecified && fullScreenHeight > StudioElevation.none -> fullScreenHeight
            else -> containerHeight + imeBottomDp
        }

        val baseCollapsedHeight = (referenceFullHeight * 0.45f)
            .coerceAtLeast(dimens.sheetCollapsedMinHeight)
        val collapsedHeight = (if (isImeOpen) {
            maxOf(baseCollapsedHeight, dimens.sheetImeMinHeight)
        } else {
            baseCollapsedHeight
        }).coerceAtMost(containerHeight)

        val expandedHeight = (referenceFullHeight * 0.85f)
            .coerceAtLeast(collapsedHeight)
            .coerceAtMost(containerHeight)

        val minSheetHeight = (if (isImeOpen) dimens.sheetImeMinHeight else dimens.sheetMinHeight)
            .coerceAtMost(containerHeight)
        val targetHeight = ((if (isExpanded) expandedHeight else collapsedHeight) - dragDeltaDp.dp)
            .coerceIn(minSheetHeight, containerHeight)
        val animatedHeight by animateDpAsState(
            targetValue = targetHeight,
            animationSpec = tween(durationMillis = tokens.motionDurationMs),
            label = "aiSheetHeight"
        )

        // 30% dark scrim behind the sheet
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(tokens.motionDurationMs)),
            exit = fadeOut(animationSpec = tween(tokens.motionDurationMs))
        ) {
            val scrimDesc = stringResource(R.string.cd_ai_sheet_scrim)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.30f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = dismissSheet
                    )
                    .semantics { contentDescription = scrimDesc }
                    .testTag("ai_bottom_sheet_scrim")
            )
        }

        // Non-resizing overlay sheet anchored to bottom above ExtraKeysBar
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                animationSpec = tween(tokens.motionDurationMs),
                initialOffsetY = { it }
            ) + fadeIn(animationSpec = tween(tokens.motionDurationMs)),
            exit = slideOutVertically(
                animationSpec = tween(tokens.motionDurationMs),
                targetOffsetY = { it }
            ) + fadeOut(animationSpec = tween(tokens.motionDurationMs)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(animatedHeight)
                    .testTag("ai_bottom_sheet"),
                color = tokens.layer1,
                shape = tokens.sheetShape,
                border = BorderStroke(tokens.borderWidth, tokens.borderStart),
                shadowElevation = StudioElevation.floating
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 1. Handle
                    val handleDesc = stringResource(R.string.cd_ai_sheet_handle)
                    val topClearance = maxOf(StudioSpacing.sm, tokens.sheetShape.chamferInset() / 2)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { isExpanded = !isExpanded }
                            .pointerInput(containerHeight, isExpanded) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        val currentHeightDp = (if (isExpanded) expandedHeight else collapsedHeight) - dragDeltaDp.dp
                                        val midPointDp = (collapsedHeight + expandedHeight) / 2f
                                        val dismissThreshold = collapsedHeight * 0.68f
                                        when {
                                            currentHeightDp < dismissThreshold -> dismissSheet()
                                            currentHeightDp >= midPointDp -> isExpanded = true
                                            else -> isExpanded = false
                                        }
                                        dragDeltaDp = 0f
                                    },
                                    onDragCancel = { dragDeltaDp = 0f },
                                    onVerticalDrag = { _, dragAmountPx ->
                                        val deltaDp = with(density) { dragAmountPx.toDp().value }
                                        dragDeltaDp += deltaDp
                                    }
                                )
                            }
                            .padding(top = topClearance, bottom = StudioSpacing.xs)
                            .semantics {
                                role = Role.Button
                                contentDescription = handleDesc
                            }
                            .testTag("ai_bottom_sheet_handle"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(dimens.sheetHandleWidth)
                                .height(dimens.sheetHandleHeight)
                                .clip(tokens.pillShape)
                                .background(tokens.textMuted.copy(alpha = 0.65f))
                        )
                    }

                    val clearFocusBeforeChip: (() -> Unit) -> () -> Unit = { action ->
                        {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            action()
                        }
                    }
                    val explainErrorAction = {
                        handledErrorToken = currentErrorToken
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onExplainLastError()
                    }
                    val fixErrorAction = {
                        handledErrorToken = currentErrorToken
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onFixLastError()
                    }

                    // 2. Single-line header (status dot + clickable model name, expand/collapse, full view, close)
                    AiStatusBar(
                        selectedModel = selectedModel,
                        keySnapshot = keySnapshot,
                        contextConfig = contextConfig,
                        activeCwd = activeCwd,
                        activeLastError = activeLastError,
                        onSelectModel = onSelectModel,
                        onToggleContextCwd = onToggleContextCwd,
                        onToggleContextLastError = onToggleContextLastError,
                        onToggleContextSelectedText = onToggleContextSelectedText,
                        onClearSelectedTextContext = onClearSelectedTextContext,
                        onToggleContextRecentOutput = onToggleContextRecentOutput,
                        onOpenSettings = onOpenSettings,
                        onOpenApiKeysSettings = onOpenApiKeysSettings,
                        onViewFullConversation = onViewFullConversation,
                        isSheetExpanded = isExpanded,
                        onToggleExpand = { isExpanded = !isExpanded },
                        onClose = dismissSheet,
                        modifier = Modifier.testTag("ai_sheet_header")
                    )

                    HorizontalDivider(
                        thickness = dimens.hairlineWidth,
                        color = tokens.borderStart.copy(alpha = 0.30f)
                    )

                    // 3. Body (scrollable, fills remaining space: no-key card, error card, empty suggestions, or messages)
                    AiMessageList(
                        messages = messages,
                        compactMode = true,
                        hasModelSelected = hasModelSelected,
                        keySnapshot = keySnapshot,
                        isLoading = isLoading,
                        onSelectModel = onSelectModel,
                        onOpenSettings = onOpenApiKeysSettings,
                        onInsertCommand = onInsertCommand,
                        onCopyCommand = onCopyCommand,
                        onSaveSnippet = { suggestion -> saveCandidate = suggestion },
                        onRetryMessage = onRetryMessage,
                        onExplainCommand = onExplainCommand,
                        onSelectEmptySuggestion = onInputChange,
                        activeLastError = activeLastError,
                        showErrorCard = showErrorCard,
                        onDismissLastError = { handledErrorToken = currentErrorToken },
                        onExplainLastError = explainErrorAction,
                        onFixLastError = fixErrorAction,
                        onWriteCommandAction = clearFocusBeforeChip(onWriteCommand),
                        onSummarizeAction = clearFocusBeforeChip(onSummarizeOutput),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_sheet_message_list")
                    )

                    HorizontalDivider(
                        thickness = dimens.hairlineWidth,
                        color = tokens.borderStart.copy(alpha = 0.30f)
                    )

                    // 4. Quick action chips ("Explain error" only enabled when activeLastError != null)
                    AiQuickActionsBar(
                        hasLastError = activeLastError != null,
                        isLoading = isLoading,
                        hasModelSelected = hasModelSelected,
                        isWriteCommandMode = isWriteCommandMode,
                        onExplainLastError = explainErrorAction,
                        onSuggestCommand = clearFocusBeforeChip(onSuggestCommand),
                        onWriteScript = clearFocusBeforeChip(onWriteScript),
                        onWriteCommand = clearFocusBeforeChip(onWriteCommand),
                        onSummarizeOutput = clearFocusBeforeChip(onSummarizeOutput),
                        onFindRootCause = clearFocusBeforeChip(onFindRootCause),
                        hasActiveKey = hasActiveKey,
                        modifier = Modifier.padding(vertical = StudioSpacing.xxs)
                    )

                    HorizontalDivider(
                        thickness = dimens.hairlineWidth,
                        color = tokens.borderStart.copy(alpha = 0.30f)
                    )

                    // 5. Docked prompt input
                    AiPromptInput(
                        input = input,
                        isLoading = isLoading,
                        hasModelSelected = hasModelSelected,
                        onInputChange = onInputChange,
                        onSend = onSend,
                        onStopGeneration = onStopGeneration,
                        isWriteCommandMode = isWriteCommandMode,
                        onToggleWriteCommandMode = onToggleWriteCommandMode,
                        hasActiveKey = hasActiveKey,
                        modifier = Modifier.testTag("ai_sheet_prompt_input_container")
                    )
                }
            }
        }
    }

    saveCandidate?.let { suggestion ->
        val defaultTitle = suggestion.command.lines().firstOrNull()?.take(40).orEmpty()
        SnippetEditorDialog(
            initialState = SnippetEditorState(
                title = defaultTitle,
                command = suggestion.command,
                tagsCsv = "ai, ${suggestion.language}",
                isNew = true
            ),
            dialogTitle = stringResource(R.string.snippets_dialog_save_from_ai_title),
            onDismiss = { saveCandidate = null },
            onConfirm = { title, command, tags ->
                onSaveSnippet(title, command, tags)
                saveCandidate = null
            }
        )
    }
}
