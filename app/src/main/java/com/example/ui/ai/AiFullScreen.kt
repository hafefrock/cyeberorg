package com.example.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.R
import com.example.ai.AiChatMessage
import com.example.ai.CommandSuggestion
import com.example.ai.KeyManagerSnapshot
import com.example.data.GeminiModels
import com.example.data.PromptHistoryItem
import com.example.terminal.TerminalErrorContext
import com.example.ui.terminal.AiContextConfig
import com.example.ui.terminal.SnippetEditorDialog
import com.example.ui.terminal.SnippetEditorState
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.studioTouchTarget

@Composable
fun AiFullScreen(
    messages: List<AiChatMessage>,
    input: String,
    isLoading: Boolean,
    contextConfig: AiContextConfig,
    activeCwd: String,
    activeLastError: TerminalErrorContext?,
    promptHistory: List<PromptHistoryItem>,
    selectedModel: String?,
    keySnapshot: KeyManagerSnapshot,
    showCloseButton: Boolean = false,
    onSelectModel: (String) -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
    onRetryMessage: (String) -> Unit,
    onToggleContextCwd: () -> Unit,
    onToggleContextLastError: () -> Unit,
    onToggleContextSelectedText: () -> Unit,
    onClearSelectedTextContext: () -> Unit,
    onToggleContextRecentOutput: () -> Unit,
    onClearChat: () -> Unit,
    onInsertCommand: (String) -> Unit,
    onCopyCommand: (String) -> Unit,
    onSaveSnippet: (title: String, command: String, tags: List<String>) -> Unit,
    onReuseHistoryPrompt: (String) -> Unit,
    onClearPromptHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenApiKeysSettings: () -> Unit = onOpenSettings,
    onClosePanel: () -> Unit = {},
    isWriteCommandMode: Boolean = false,
    onToggleWriteCommandMode: () -> Unit = {},
    onExplainCommand: (String) -> Unit = {},
    onExplainLastError: () -> Unit = {},
    onFixLastError: () -> Unit = {},
    onSummarizeOutput: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current
    val validSelectedModel = remember(selectedModel) { GeminiModels.normalizeSelectedModel(selectedModel) }
    val hasModelSelected = validSelectedModel != null
    val hasActiveKey = keySnapshot.hasUsableActiveKey()

    var showHistoryDialog by remember { mutableStateOf(false) }
    var saveCandidate by remember { mutableStateOf<CommandSuggestion?>(null) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("ai_full_screen"),
        color = tokens.layer1
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = StudioSpacing.lg,
                        end = StudioSpacing.xs,
                        top = StudioSpacing.xs,
                        bottom = StudioSpacing.xs
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = tokens.accent,
                        modifier = Modifier.size(dimens.iconSize)
                    )
                    Text(
                        text = tokens.formatHeading(stringResource(R.string.ai_panel_title)),
                        fontFamily = tokens.headingFont,
                        letterSpacing = tokens.headingLetterSpacing,
                        style = compactType.screenTitle,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val openHistory = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showHistoryDialog = true
                }
                val historyDesc = stringResource(R.string.cd_prompt_history)
                Box(
                    modifier = Modifier
                        .size(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = openHistory
                        )
                        .clickable(role = Role.Button, onClick = openHistory)
                        .semantics {
                            role = Role.Button
                            contentDescription = historyDesc
                        }
                        .testTag("ai_history_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = historyDesc,
                        tint = tokens.textSecondary,
                        modifier = Modifier.size(dimens.iconSize)
                    )
                }

                val clearAction = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClearChat()
                }
                val clearDesc = stringResource(R.string.cd_clear_ai_chat)
                Box(
                    modifier = Modifier
                        .size(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = clearAction
                        )
                        .clickable(role = Role.Button, onClick = clearAction)
                        .semantics {
                            role = Role.Button
                            contentDescription = clearDesc
                        }
                        .testTag("clear_ai_chat_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = clearDesc,
                        tint = tokens.textSecondary,
                        modifier = Modifier.size(dimens.iconSize)
                    )
                }

                if (showCloseButton) {
                    val closeDesc = stringResource(R.string.cd_ai_star_open)
                    Box(
                        modifier = Modifier
                            .size(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = onClosePanel
                            )
                            .clickable(role = Role.Button, onClick = onClosePanel)
                            .semantics {
                                role = Role.Button
                                contentDescription = closeDesc
                            }
                            .testTag("close_ai_panel_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = closeDesc,
                            tint = tokens.textSecondary,
                            modifier = Modifier.size(dimens.iconSize)
                        )
                    }
                }
            }

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
                onOpenApiKeysSettings = onOpenApiKeysSettings
            )

            HorizontalDivider(
                thickness = dimens.hairlineWidth,
                color = tokens.borderStart.copy(alpha = 0.30f)
            )

            AiMessageList(
                messages = messages,
                compactMode = false,
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
                showErrorCard = false,
                onExplainLastError = onExplainLastError,
                onFixLastError = onFixLastError,
                onWriteCommandAction = onToggleWriteCommandMode,
                onSummarizeAction = onSummarizeOutput,
                modifier = Modifier.weight(1f)
            )

            HorizontalDivider(
                thickness = dimens.hairlineWidth,
                color = tokens.borderStart.copy(alpha = 0.30f)
            )

            AiPromptInput(
                input = input,
                isLoading = isLoading,
                hasModelSelected = hasModelSelected,
                onInputChange = onInputChange,
                onSend = onSend,
                onStopGeneration = onStopGeneration,
                isWriteCommandMode = isWriteCommandMode,
                onToggleWriteCommandMode = onToggleWriteCommandMode,
                hasActiveKey = hasActiveKey
            )
        }
    }

    if (showHistoryDialog) {
        PromptHistoryDialog(
            items = promptHistory,
            onDismiss = { showHistoryDialog = false },
            onSelectPrompt = { prompt ->
                showHistoryDialog = false
                onReuseHistoryPrompt(prompt)
            },
            onClearAll = onClearPromptHistory
        )
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
