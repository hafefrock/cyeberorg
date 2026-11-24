package com.example.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ai.AiChatMessage
import com.example.ai.AiMessageRole
import com.example.ai.CommandSuggestion
import com.example.ai.KeyHealthStatus
import com.example.ai.KeyManagerSnapshot
import com.example.data.GeminiModels
import com.example.data.PromptHistoryItem
import com.example.terminal.FakeTerminalBackend
import com.example.terminal.TerminalErrorContext
import com.example.ui.terminal.AiContextConfig
import com.example.ui.terminal.CommandExplanationState
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioGlow
import com.example.ui.theme.studioTouchTarget

internal fun computeCompactMessages(messages: List<AiChatMessage>): List<AiChatMessage> {
    if (messages.isEmpty()) return emptyList()
    val lastUserIdx = messages.indexOfLast { it.role == AiMessageRole.USER }
    if (lastUserIdx == -1) {
        return listOf(messages.last())
    }
    val lastMsgIdx = messages.lastIndex
    return if (lastMsgIdx > lastUserIdx) {
        listOf(messages[lastUserIdx], messages[lastMsgIdx])
    } else {
        listOf(messages[lastUserIdx])
    }
}

internal fun shortenCwd(cwd: String): String = when {
    cwd == FakeTerminalBackend.HOME_DIR -> "~"
    cwd.startsWith("${FakeTerminalBackend.HOME_DIR}/") ->
        "~/${cwd.removePrefix("${FakeTerminalBackend.HOME_DIR}/")}"
    cwd.isEmpty() -> "~"
    else -> cwd
}

internal fun KeyManagerSnapshot.hasUsableActiveKey(): Boolean =
    usableKeyCount > 0 ||
        keys.any { it.status == KeyHealthStatus.ACTIVE || it.status == KeyHealthStatus.READY } ||
        (activeKeyId != null && activeKeyLabel != null)

@Composable
fun AiStatusBar(
    selectedModel: String?,
    keySnapshot: KeyManagerSnapshot,
    contextConfig: AiContextConfig,
    activeCwd: String,
    activeLastError: TerminalErrorContext?,
    onSelectModel: (String) -> Unit,
    onToggleContextCwd: () -> Unit,
    onToggleContextLastError: () -> Unit,
    onToggleContextSelectedText: () -> Unit,
    onClearSelectedTextContext: () -> Unit,
    onToggleContextRecentOutput: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenApiKeysSettings: () -> Unit = onOpenSettings,
    onViewFullConversation: (() -> Unit)? = null,
    isSheetExpanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current
    var showQuickSettings by remember { mutableStateOf(false) }

    val validModel = remember(selectedModel) { GeminiModels.normalizeSelectedModel(selectedModel) }
    val modelLabel = GeminiModels.displayNameOrId(validModel)
        ?: stringResource(R.string.ai_model_unselected_short)
    val hasActiveKey = keySnapshot.hasUsableActiveKey()
    val keyLabel = keySnapshot.activeKeyLabel ?: stringResource(R.string.ai_no_active_key)
    val ctxCount = contextConfig.activeCount(hasLastError = activeLastError != null)

    val summaryText = stringResource(R.string.ai_status_bar_text, modelLabel, keyLabel, ctxCount)
    val statusContentDesc = stringResource(R.string.cd_ai_status_bar, summaryText)
    val viewFullLabel = stringResource(R.string.ai_view_full_conversation)
    val expandDesc = stringResource(
        if (isSheetExpanded) R.string.cd_ai_collapse_sheet else R.string.cd_ai_expand_sheet
    )
    val closeDesc = stringResource(R.string.cd_ai_star_open)

    val openQuickSettings = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        showQuickSettings = true
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ai_status_bar"),
        color = tokens.layer2
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = StudioSpacing.sm,
                    top = StudioSpacing.xxs,
                    bottom = StudioSpacing.xxs
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
        ) {
            // Left: Small key status dot + 1-line model name (opens quick settings / model picker)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = dimens.minTouchTarget)
                    .studioTouchTarget(
                        minSize = dimens.minTouchTarget,
                        onOutOfBoundsTap = openQuickSettings
                    )
                    .clickable(role = Role.Button, onClick = openQuickSettings)
                    .semantics {
                        role = Role.Button
                        contentDescription = statusContentDesc
                    }
                    .testTag("ai_header_model_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
            ) {
                Box(
                    modifier = Modifier
                        .size(dimens.statusDotSize)
                        .background(
                            if (hasActiveKey) tokens.success else tokens.error,
                            CircleShape
                        )
                        .testTag("ai_key_status_dot")
                )
                Text(
                    text = modelLabel,
                    style = compactType.tabOrChip,
                    fontWeight = FontWeight.SemiBold,
                    color = if (validModel == null) tokens.warning else tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_header_model_name")
                )
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = tokens.textMuted,
                    modifier = Modifier.size(dimens.iconSizeSmall)
                )
            }

            // Right: Expand/collapse sheet, view full conversation, close (each >= 48dp touch target)
            Row(verticalAlignment = Alignment.CenterVertically) {
            if (onToggleExpand != null) {
                val toggleAction = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggleExpand()
                }
                Box(
                    modifier = Modifier
                        .size(dimens.minTouchTarget)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = toggleAction
                        )
                        .clickable(role = Role.Button, onClick = toggleAction)
                        .semantics {
                            role = Role.Button
                            contentDescription = expandDesc
                        }
                        .testTag("ai_sheet_expand_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSheetExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = expandDesc,
                        tint = tokens.textSecondary,
                        modifier = Modifier.size(dimens.navIconSize)
                    )
                }
            }

            if (onViewFullConversation != null) {
                val openFull = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onViewFullConversation()
                }
                Box(
                    modifier = Modifier
                        .size(dimens.minTouchTarget)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = openFull
                        )
                        .clickable(role = Role.Button, onClick = openFull)
                        .semantics {
                            role = Role.Button
                            contentDescription = viewFullLabel
                        }
                        .testTag("ai_view_full_conversation_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = viewFullLabel,
                        tint = tokens.accent,
                        modifier = Modifier.size(dimens.iconSize)
                    )
                }
            }

            if (onClose != null) {
                val closeAction = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClose()
                }
                Box(
                    modifier = Modifier
                        .size(dimens.minTouchTarget)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = closeAction
                        )
                        .clickable(role = Role.Button, onClick = closeAction)
                        .semantics {
                            role = Role.Button
                            contentDescription = closeDesc
                        }
                        .testTag("ai_sheet_close_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = closeDesc,
                        tint = tokens.textSecondary,
                        modifier = Modifier.size(dimens.navIconSize)
                    )
                }
            }
            }
        }
    }

    if (showQuickSettings) {
        AiQuickSettingsModalSheet(
            validSelectedModel = validModel,
            keySnapshot = keySnapshot,
            contextConfig = contextConfig,
            activeCwd = activeCwd,
            activeLastError = activeLastError,
            onDismiss = { showQuickSettings = false },
            onSelectModel = onSelectModel,
            onToggleContextCwd = onToggleContextCwd,
            onToggleContextLastError = onToggleContextLastError,
            onToggleContextSelectedText = onToggleContextSelectedText,
            onClearSelectedTextContext = onClearSelectedTextContext,
            onToggleContextRecentOutput = onToggleContextRecentOutput,
            onOpenSettings = {
                showQuickSettings = false
                onOpenApiKeysSettings()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiQuickSettingsModalSheet(
    validSelectedModel: String?,
    keySnapshot: KeyManagerSnapshot,
    contextConfig: AiContextConfig,
    activeCwd: String,
    activeLastError: TerminalErrorContext?,
    onDismiss: () -> Unit,
    onSelectModel: (String) -> Unit,
    onToggleContextCwd: () -> Unit,
    onToggleContextLastError: () -> Unit,
    onToggleContextSelectedText: () -> Unit,
    onClearSelectedTextContext: () -> Unit,
    onToggleContextRecentOutput: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shortCwd = remember(activeCwd) { shortenCwd(activeCwd) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = tokens.sheetShape,
        containerColor = tokens.layer1,
        contentColor = tokens.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(safeContentPadding(tokens.sheetShape, StudioSpacing.lg, StudioSpacing.lg)),
            verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
        ) {
            Text(
                text = tokens.formatHeading(stringResource(R.string.ai_quick_settings_title)),
                fontFamily = tokens.headingFont,
                letterSpacing = tokens.headingLetterSpacing,
                style = compactType.screenTitle,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary
            )

            Text(
                text = stringResource(R.string.ai_quick_settings_model_sec),
                style = compactType.cardTitle,
                color = tokens.textSecondary
            )

            Column(verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)) {
                GeminiModels.available.forEach { option ->
                    val isSelected = option.id == validSelectedModel
                    val selectAction = { onSelectModel(option.id) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = selectAction
                            )
                            .background(
                                if (isSelected) tokens.accentMuted else tokens.layer2,
                                tokens.smallShape
                            )
                            .border(
                                tokens.borderWidth,
                                if (isSelected) tokens.focusRingColor else tokens.borderStart.copy(alpha = 0.4f),
                                tokens.smallShape
                            )
                            .clickable(role = Role.RadioButton, onClick = selectAction)
                            .testTag("quick_settings_model_${option.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.displayName,
                                    fontFamily = JetBrainsMonoFamily,
                                    style = compactType.cardTitle,
                                    color = if (isSelected) tokens.accent else tokens.textPrimary
                                )
                                Text(
                                    text = stringResource(option.descriptionRes),
                                    style = compactType.body,
                                    color = tokens.textMuted
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = tokens.accent,
                                    modifier = Modifier.size(dimens.iconSize)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = tokens.borderStart.copy(alpha = 0.4f))

            Text(
                text = stringResource(R.string.ai_quick_settings_keys_sec),
                style = compactType.cardTitle,
                color = tokens.textSecondary
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = tokens.layer2,
                shape = tokens.smallShape,
                border = BorderStroke(tokens.borderWidth, tokens.borderStart)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val activeLabel = keySnapshot.activeKeyLabel
                            ?: stringResource(R.string.ai_no_active_key)
                        Text(
                            text = activeLabel,
                            fontFamily = JetBrainsMonoFamily,
                            style = compactType.cardTitle,
                            color = tokens.textPrimary
                        )
                        Text(
                            text = stringResource(
                                R.string.ai_quick_settings_keys_summary,
                                keySnapshot.usableKeyCount,
                                keySnapshot.keys.size
                            ),
                            style = compactType.body,
                            color = tokens.textMuted
                        )
                    }
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        TextButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .height(dimens.controlHeight)
                                .studioTouchTarget(
                                    minSize = dimens.minTouchTarget,
                                    onOutOfBoundsTap = onOpenSettings
                                )
                                .semantics { role = Role.Button }
                                .testTag("quick_settings_manage_keys_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = tokens.accent,
                                modifier = Modifier.size(dimens.iconSize)
                            )
                            Spacer(modifier = Modifier.width(StudioSpacing.xs))
                            Text(
                                text = stringResource(R.string.ai_quick_settings_manage_keys),
                                style = compactType.tabOrChip,
                                color = tokens.accent
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = tokens.borderStart.copy(alpha = 0.4f))

            Text(
                text = stringResource(R.string.ai_quick_settings_ctx_sec),
                style = compactType.cardTitle,
                color = tokens.textSecondary
            )

            ContextSwitchRow(
                label = stringResource(R.string.ai_ctx_toggle_cwd, shortCwd),
                subtitle = null,
                checked = contextConfig.includeCwd,
                onToggle = onToggleContextCwd,
                modifier = Modifier.testTag("quick_ctx_cwd")
            )

            ContextSwitchRow(
                label = stringResource(R.string.ai_ctx_toggle_last_error),
                subtitle = if (activeLastError != null) {
                    stringResource(R.string.ai_ctx_toggle_last_error_code, activeLastError.exitCode)
                } else {
                    stringResource(R.string.ai_ctx_toggle_last_error_none)
                },
                checked = contextConfig.includeLastError,
                onToggle = onToggleContextLastError,
                modifier = Modifier.testTag("quick_ctx_error")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ContextSwitchRow(
                    label = stringResource(R.string.ai_ctx_toggle_selected),
                    subtitle = if (contextConfig.selectedText.isNotBlank()) {
                        stringResource(R.string.ai_ctx_toggle_selected_chars, contextConfig.selectedText.length)
                    } else {
                        stringResource(R.string.ai_ctx_toggle_selected_none)
                    },
                    checked = contextConfig.includeSelectedText,
                    onToggle = onToggleContextSelectedText,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("quick_ctx_selected")
                )
                if (contextConfig.selectedText.isNotBlank()) {
                    val clearSelDesc = stringResource(R.string.cd_ai_clear_selected_ctx)
                    Box(
                        modifier = Modifier
                            .size(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = onClearSelectedTextContext
                            )
                            .clickable(role = Role.Button, onClick = onClearSelectedTextContext)
                            .semantics {
                                role = Role.Button
                                contentDescription = clearSelDesc
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = clearSelDesc,
                            tint = tokens.textMuted,
                            modifier = Modifier.size(dimens.iconSize)
                        )
                    }
                }
            }

            ContextSwitchRow(
                label = stringResource(R.string.ai_ctx_toggle_recent, contextConfig.recentLineCount),
                subtitle = null,
                checked = contextConfig.includeRecentOutput,
                onToggle = onToggleContextRecentOutput,
                modifier = Modifier.testTag("quick_ctx_recent")
            )
        }
    }
}

@Composable
private fun ContextSwitchRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimens.controlHeight)
            .studioTouchTarget(
                minSize = dimens.minTouchTarget,
                onOutOfBoundsTap = onToggle
            )
            .clickable(role = Role.Switch, onClick = onToggle)
            .padding(safeContentPadding(tokens.smallShape, StudioSpacing.xs, StudioSpacing.xs)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = compactType.body,
                color = tokens.textPrimary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = compactType.navLabel,
                    color = tokens.textMuted
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.onAccent,
                checkedTrackColor = tokens.accent
            )
        )
    }
}

private data class EmptySuggestionAction(
    val label: String,
    val tag: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
fun AiMessageList(
    messages: List<AiChatMessage>,
    compactMode: Boolean,
    hasModelSelected: Boolean,
    keySnapshot: KeyManagerSnapshot,
    isLoading: Boolean,
    onSelectModel: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onInsertCommand: (String) -> Unit,
    onCopyCommand: (String) -> Unit,
    onSaveSnippet: (CommandSuggestion) -> Unit,
    onRetryMessage: (String) -> Unit,
    onExplainCommand: (String) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onViewFullConversation: (() -> Unit)? = null,
    onSelectEmptySuggestion: ((String) -> Unit)? = null,
    activeLastError: TerminalErrorContext? = null,
    showErrorCard: Boolean = activeLastError != null,
    onDismissLastError: () -> Unit = {},
    onExplainLastError: () -> Unit = {},
    onFixLastError: () -> Unit = {},
    onWriteCommandAction: (() -> Unit)? = null,
    onSummarizeAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dimens = StudioTheme.dimens
    val listState = rememberLazyListState()
    val hasActiveKey = keySnapshot.hasUsableActiveKey()

    if (!hasActiveKey) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = StudioSpacing.md, vertical = StudioSpacing.sm),
            contentAlignment = Alignment.Center
        ) {
            AiNoKeyCard(onAddKey = onOpenSettings)
        }
        return
    }

    val displayedMessages = remember(messages, compactMode) {
        if (compactMode) computeCompactMessages(messages) else messages
    }

    val lastMsgTextLen = displayedMessages.lastOrNull()?.text?.asString()?.length ?: 0
    LaunchedEffect(displayedMessages.size, lastMsgTextLen, isLoading) {
        if (displayedMessages.isNotEmpty()) {
            listState.animateScrollToItem(displayedMessages.lastIndex)
        }
    }
    LaunchedEffect(showErrorCard, activeLastError) {
        if (showErrorCard && activeLastError != null && displayedMessages.isEmpty()) {
            listState.scrollToItem(0)
        }
    }

    val explainErrorLabel = stringResource(R.string.ai_empty_suggest_explain_error)
    val writeCommandLabel = stringResource(R.string.ai_quick_write_command)
    val summarizeOutputLabel = stringResource(R.string.ai_quick_summarize_output)

    val emptySuggestions = buildList {
        if (activeLastError != null) {
            add(
                EmptySuggestionAction(
                    label = explainErrorLabel,
                    tag = "ai_empty_suggestion_explain_error",
                    icon = Icons.Default.BugReport,
                    onClick = onExplainLastError
                )
            )
        }
        add(
            EmptySuggestionAction(
                label = writeCommandLabel,
                tag = "ai_empty_suggestion_write_command",
                icon = Icons.Default.AutoFixHigh,
                onClick = {
                    (onWriteCommandAction ?: { onSelectEmptySuggestion?.invoke(writeCommandLabel) }).invoke()
                }
            )
        )
        add(
            EmptySuggestionAction(
                label = summarizeOutputLabel,
                tag = "ai_empty_suggestion_summarize",
                icon = Icons.Default.Summarize,
                onClick = {
                    (onSummarizeAction ?: { onSelectEmptySuggestion?.invoke(summarizeOutputLabel) }).invoke()
                }
            )
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = StudioSpacing.md, vertical = StudioSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)
    ) {
        if (showErrorCard && activeLastError != null) {
            item(key = "ai_last_error_card") {
                AiLastErrorCard(
                    lastError = activeLastError,
                    enabled = !isLoading && hasModelSelected,
                    onExplain = onExplainLastError,
                    onFix = onFixLastError,
                    onDismiss = onDismissLastError
                )
            }
        }

        if (!hasModelSelected) {
            item(key = "model_chooser_card") {
                AiModelChooserCard(onSelectModel = onSelectModel)
            }
        }

        if (displayedMessages.isEmpty()) {
            item(key = "ai_empty_state") {
                AiEmptyStateCard(suggestions = emptySuggestions)
            }
        }

        items(
            items = displayedMessages,
            key = { it.id }
        ) { message ->
            val rawBody = message.text.asString()
            if (message.isStreaming && rawBody.isEmpty()) {
                AiThinkingIndicatorCard(
                    keyLabel = message.keyLabel ?: keySnapshot.activeKeyLabel ?: "KeyManager"
                )
            } else {
                AiMessageBubble(
                    message = message,
                    canRetryNow = !isLoading && message.canRetry,
                    onInsertCommand = onInsertCommand,
                    onCopyCommand = onCopyCommand,
                    onSaveSnippet = onSaveSnippet,
                    onExplainCommand = onExplainCommand,
                    onRetry = { onRetryMessage(message.id) }
                )
            }
        }
    }
}

@Composable
private fun AiNoKeyCard(
    onAddKey: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current
    val addKeyLabel = stringResource(R.string.ai_add_key_cta)
    val openKeysAction = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onAddKey()
    }

    Surface(
        modifier = Modifier
            .widthIn(max = dimens.emptyCardMaxWidth)
            .fillMaxWidth()
            .testTag("ai_no_key_card"),
        color = tokens.layer2,
        shape = tokens.cardShape,
        border = BorderStroke(tokens.borderWidth, tokens.borderStart.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(safeContentPadding(tokens.cardShape, StudioSpacing.lg, StudioSpacing.lg)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(dimens.emptyStateIconContainerSize)
                    .background(tokens.warning.copy(alpha = 0.14f), tokens.smallShape)
                    .border(tokens.borderWidth, tokens.warning.copy(alpha = 0.45f), tokens.smallShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = tokens.warning,
                    modifier = Modifier.size(dimens.navIconSize)
                )
            }

            Text(
                text = stringResource(R.string.ai_no_api_key_title),
                style = compactType.cardTitle,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("ai_no_key_title")
            )

            Box(
                modifier = Modifier
                    .heightIn(min = dimens.minTouchTarget)
                    .studioTouchTarget(
                        minSize = dimens.minTouchTarget,
                        onOutOfBoundsTap = openKeysAction
                    )
                    .background(tokens.primaryButtonContainer, tokens.buttonShape)
                    .border(tokens.borderWidth, tokens.accent.copy(alpha = 0.55f), tokens.buttonShape)
                    .clickable(role = Role.Button, onClick = openKeysAction)
                    .semantics {
                        role = Role.Button
                        contentDescription = addKeyLabel
                    }
                    .testTag("ai_add_key_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.padding(
                        safeContentPadding(tokens.buttonShape, StudioSpacing.lg, StudioSpacing.sm)
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = tokens.primaryButtonContent,
                        modifier = Modifier.size(dimens.iconSizeSmall)
                    )
                    Text(
                        text = addKeyLabel,
                        style = compactType.tabOrChip,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.primaryButtonContent,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun AiLastErrorCard(
    lastError: TerminalErrorContext,
    enabled: Boolean,
    onExplain: () -> Unit,
    onFix: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current

    val rawFirstLine = remember(lastError) {
        lastError.errorOutput
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: lastError.command.trim()
    }
    val summaryText = stringResource(R.string.ai_error_card_summary, lastError.exitCode, rawFirstLine)
    val dismissDesc = stringResource(R.string.action_dismiss)
    val explainLabel = stringResource(R.string.ai_btn_explain)
    val fixLabel = stringResource(R.string.ai_btn_fix)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_last_error_card"),
        color = tokens.layer2,
        shape = tokens.cardShape,
        border = BorderStroke(tokens.borderWidth, tokens.error.copy(alpha = 0.50f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV)),
            verticalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = tokens.error,
                    modifier = Modifier.size(dimens.iconSizeSmall)
                )
                Text(
                    text = summaryText,
                    style = compactType.body,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_last_error_summary")
                )
                Box(
                    modifier = Modifier
                        .size(dimens.controlHeightSmall)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onDismiss
                        )
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .semantics {
                            role = Role.Button
                            contentDescription = dismissDesc
                        }
                        .testTag("ai_error_card_dismiss_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = dismissDesc,
                        tint = tokens.textMuted,
                        modifier = Modifier.size(dimens.iconSizeSmall)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val explainAction = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onExplain()
                }
                Box(
                    modifier = Modifier
                        .heightIn(min = dimens.controlHeightSmall)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            enabled = enabled,
                            onOutOfBoundsTap = explainAction
                        )
                        .alpha(if (enabled) 1f else 0.45f)
                        .background(tokens.accentMuted, tokens.buttonShape)
                        .border(tokens.borderWidth, tokens.accent.copy(alpha = 0.50f), tokens.buttonShape)
                        .clickable(enabled = enabled, role = Role.Button, onClick = explainAction)
                        .padding(safeContentPadding(tokens.buttonShape, StudioSpacing.md, StudioSpacing.xs))
                        .testTag("ai_error_card_explain_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = explainLabel,
                        style = compactType.tabOrChip,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.accent,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                val fixAction = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onFix()
                }
                Box(
                    modifier = Modifier
                        .heightIn(min = dimens.controlHeightSmall)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            enabled = enabled,
                            onOutOfBoundsTap = fixAction
                        )
                        .alpha(if (enabled) 1f else 0.45f)
                        .background(tokens.error.copy(alpha = 0.14f), tokens.buttonShape)
                        .border(tokens.borderWidth, tokens.error.copy(alpha = 0.55f), tokens.buttonShape)
                        .clickable(enabled = enabled, role = Role.Button, onClick = fixAction)
                        .padding(safeContentPadding(tokens.buttonShape, StudioSpacing.md, StudioSpacing.xs))
                        .testTag("ai_error_card_fix_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = fixLabel,
                        style = compactType.tabOrChip,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.error,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun AiEmptyStateCard(
    suggestions: List<EmptySuggestionAction>
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_empty_state"),
        color = tokens.layer2,
        shape = tokens.cardShape,
        border = BorderStroke(tokens.borderWidth, tokens.borderStart.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV)),
            verticalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = tokens.accent,
                    modifier = Modifier.size(dimens.iconSizeSmall)
                )
                Text(
                    text = stringResource(R.string.ai_empty_title),
                    style = compactType.cardTitle,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.xs)) {
                suggestions.forEach { suggestion ->
                    val onPick: () -> Unit = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        suggestion.onClick()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = dimens.controlHeight)
                            .background(tokens.layer1, tokens.smallShape)
                            .border(tokens.borderWidth, tokens.borderStart.copy(alpha = 0.45f), tokens.smallShape)
                            .clickable(role = Role.Button, onClick = onPick)
                            .padding(safeContentPadding(tokens.smallShape, StudioSpacing.sm, StudioSpacing.xs))
                            .testTag(suggestion.tag),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                    ) {
                        Icon(
                            imageVector = suggestion.icon,
                            contentDescription = null,
                            tint = tokens.accent,
                            modifier = Modifier.size(dimens.iconSizeSmall)
                        )
                        Text(
                            text = suggestion.label,
                            style = compactType.body,
                            color = tokens.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiModelChooserCard(
    onSelectModel: (String) -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_choose_model_card"),
        color = tokens.layer2,
        shape = tokens.cardShape,
        border = BorderStroke(tokens.borderWidth, tokens.accentBorderBrush)
    ) {
        Column(
            modifier = Modifier.padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV)),
            verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)
        ) {
            Text(
                text = tokens.formatHeading(stringResource(R.string.ai_choose_model_title)),
                fontFamily = tokens.headingFont,
                letterSpacing = tokens.headingLetterSpacing,
                style = compactType.cardTitle,
                fontWeight = FontWeight.Bold,
                color = tokens.accent
            )
            Text(
                text = stringResource(R.string.ai_choose_model_desc),
                style = compactType.body,
                color = tokens.textSecondary
            )
            GeminiModels.available.forEach { option ->
                val selectAction = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectModel(option.id)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = selectAction
                        )
                        .background(tokens.layer1, tokens.smallShape)
                        .border(tokens.borderWidth, tokens.borderStart, tokens.smallShape)
                        .clickable(role = Role.Button, onClick = selectAction)
                        .testTag("ai_choose_model_${option.id}")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV))
                    ) {
                        Text(
                            text = option.displayName,
                            fontFamily = JetBrainsMonoFamily,
                            style = compactType.cardTitle,
                            color = tokens.textPrimary
                        )
                        Text(
                            text = stringResource(option.descriptionRes),
                            style = compactType.body,
                            color = tokens.textMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiThinkingIndicatorCard(keyLabel: String) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val thinkingText = stringResource(R.string.ai_thinking, keyLabel)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = dimens.thinkingCardMaxWidth)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("ai_loading_skeleton"),
            color = tokens.layer2,
            shape = tokens.cardShape,
            border = BorderStroke(tokens.borderWidth, tokens.borderStart)
        ) {
            Row(
                modifier = Modifier.padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = tokens.accent,
                    modifier = Modifier.size(dimens.iconSize)
                )
                Text(
                    text = thinkingText,
                    style = compactType.monoPreview,
                    color = tokens.textSecondary
                )
            }
        }
    }
}

@Composable
private fun AiMessageBubble(
    message: AiChatMessage,
    canRetryNow: Boolean,
    onInsertCommand: (String) -> Unit,
    onCopyCommand: (String) -> Unit,
    onSaveSnippet: (CommandSuggestion) -> Unit,
    onExplainCommand: (String) -> Unit,
    onRetry: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current
    val isUser = message.role == AiMessageRole.USER
    val isError = message.role == AiMessageRole.ERROR

    if (isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.86f),
                color = tokens.accentMuted,
                shape = tokens.cardShape,
                border = BorderStroke(tokens.borderWidth, tokens.accent.copy(alpha = 0.4f))
            ) {
                Text(
                    text = message.text.asString(),
                    style = compactType.body,
                    color = tokens.textPrimary,
                    modifier = Modifier.padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV))
                )
            }
        }
        return
    }

    val containerColor = if (isError) tokens.error.copy(alpha = 0.12f) else tokens.layer2
    val displayText = message.text.asString().let { raw ->
        if (message.isStreaming && raw.isNotEmpty()) "$raw ▌" else raw
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f),
            color = containerColor,
            shape = tokens.cardShape,
            border = BorderStroke(
                tokens.borderWidth,
                if (isError) tokens.error.copy(alpha = 0.5f) else tokens.borderEnd
            )
        ) {
            Column(
                modifier = Modifier.padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV)),
                verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)
            ) {
                val showHeader = isError || message.isStreaming || message.modelUsed != null || message.keyLabel != null
                if (showHeader) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                        ) {
                            Text(
                                text = stringResource(
                                    if (isError) R.string.ai_role_error else R.string.ai_role_assistant
                                ),
                                style = compactType.tabOrChip,
                                fontWeight = FontWeight.Bold,
                                color = if (isError) tokens.error else tokens.accent
                            )
                            if (message.isStreaming) {
                                Text(
                                    text = stringResource(R.string.ai_streaming_badge),
                                    style = compactType.tabOrChip,
                                    color = tokens.info
                                )
                            }
                        }

                        if (message.modelUsed != null || message.keyLabel != null) {
                            val parts = buildList {
                                message.modelUsed?.let {
                                    add(GeminiModels.displayNameOrId(it) ?: it)
                                }
                                message.keyLabel?.let { add(it) }
                            }
                            Text(
                                text = parts.joinToString(" · "),
                                style = compactType.monoPreview,
                                color = tokens.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("ai_message_model_${message.id}")
                            )
                        }
                    }
                }

                if (displayText.isNotEmpty()) {
                    val liveModifier = when {
                        isError -> Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                        message.isStreaming -> Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        else -> Modifier
                    }
                    Text(
                        text = displayText,
                        style = compactType.body,
                        color = if (isError) tokens.error else tokens.textPrimary,
                        modifier = liveModifier
                    )
                }

                message.suggestions.forEach { suggestion ->
                    AiCommandCard(
                        suggestion = suggestion,
                        onInsert = onInsertCommand,
                        onCopy = onCopyCommand,
                        onSaveSnippet = onSaveSnippet,
                        onExplain = onExplainCommand
                    )
                }

                AnimatedVisibility(
                    visible = message.isTruncated,
                    enter = fadeIn(animationSpec = tween(tokens.motionDurationMs)),
                    exit = fadeOut(animationSpec = tween(tokens.motionDurationMs))
                ) {
                    Surface(
                        color = tokens.warning.copy(alpha = 0.14f),
                        shape = tokens.smallShape,
                        border = BorderStroke(tokens.borderWidth, tokens.warning.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_truncated_notice")
                    ) {
                        Text(
                            text = stringResource(R.string.ai_response_truncated),
                            style = compactType.body,
                            color = tokens.warning,
                            modifier = Modifier.padding(safeContentPadding(tokens.smallShape, StudioSpacing.sm, StudioSpacing.xs))
                        )
                    }
                }

                if (message.streamInterruptedError != null) {
                    Surface(
                        color = tokens.warning.copy(alpha = 0.14f),
                        shape = tokens.smallShape,
                        border = BorderStroke(tokens.borderWidth, tokens.warning.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    ) {
                        Text(
                            text = message.streamInterruptedError.asString(),
                            style = compactType.body,
                            color = tokens.warning,
                            modifier = Modifier.padding(safeContentPadding(tokens.smallShape, StudioSpacing.sm, StudioSpacing.xs))
                        )
                    }
                }

                if (canRetryNow) {
                    val retryDesc = stringResource(R.string.cd_retry_ai)
                    val retryAction = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRetry()
                    }
                    Box(
                        modifier = Modifier
                            .height(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = retryAction
                            )
                            .background(tokens.accentMuted, tokens.buttonShape)
                            .border(tokens.borderWidth, tokens.accent.copy(alpha = 0.55f), tokens.buttonShape)
                            .clickable(role = Role.Button, onClick = retryAction)
                            .semantics {
                                role = Role.Button
                                contentDescription = retryDesc
                            }
                            .testTag("retry_ai_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier.padding(safeContentPadding(tokens.buttonShape, dimens.cardPaddingH)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = tokens.accent,
                                modifier = Modifier.size(dimens.iconSizeSmall)
                            )
                            Text(
                                text = stringResource(R.string.ai_btn_retry),
                                style = compactType.tabOrChip,
                                color = tokens.accent,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiQuickActionsBar(
    hasLastError: Boolean,
    isLoading: Boolean,
    hasModelSelected: Boolean,
    onExplainLastError: () -> Unit,
    onSuggestCommand: () -> Unit,
    onWriteScript: () -> Unit,
    isWriteCommandMode: Boolean = false,
    onWriteCommand: () -> Unit = {},
    onSummarizeOutput: () -> Unit = {},
    onFindRootCause: () -> Unit = {},
    hasActiveKey: Boolean = true,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val haptic = LocalHapticFeedback.current
    val explainEnabled = hasActiveKey && hasLastError && !isLoading && hasModelSelected
    val generalEnabled = hasActiveKey && !isLoading && hasModelSelected

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = StudioSpacing.md, vertical = StudioSpacing.xs)
            .testTag("ai_quick_actions_bar"),
        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
    ) {
        QuickActionChip(
            label = stringResource(R.string.ai_quick_write_command),
            icon = Icons.Default.AutoFixHigh,
            accent = tokens.accent,
            enabled = generalEnabled,
            selected = isWriteCommandMode,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onWriteCommand()
            },
            modifier = Modifier.testTag("ai_write_command_chip")
        )
        QuickActionChip(
            label = stringResource(R.string.ai_quick_summarize_output),
            icon = Icons.Default.Summarize,
            accent = tokens.info,
            enabled = generalEnabled,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSummarizeOutput()
            },
            modifier = Modifier.testTag("ai_summarize_output_chip")
        )
        QuickActionChip(
            label = stringResource(R.string.ai_quick_root_cause),
            icon = Icons.AutoMirrored.Filled.ManageSearch,
            accent = tokens.warning,
            enabled = generalEnabled,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onFindRootCause()
            },
            modifier = Modifier.testTag("ai_root_cause_chip")
        )
        QuickActionChip(
            label = stringResource(R.string.ai_quick_explain),
            icon = Icons.Default.BugReport,
            accent = if (explainEnabled) tokens.error else tokens.textMuted,
            enabled = explainEnabled,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onExplainLastError()
            },
            modifier = Modifier.testTag("ai_explain_error_chip")
        )
        QuickActionChip(
            label = stringResource(R.string.ai_quick_suggest),
            icon = Icons.Default.Terminal,
            accent = tokens.accent,
            enabled = generalEnabled,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSuggestCommand()
            },
            modifier = Modifier.testTag("ai_suggest_command_chip")
        )
        QuickActionChip(
            label = stringResource(R.string.ai_quick_script),
            icon = Icons.Default.Code,
            accent = tokens.info,
            enabled = generalEnabled,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onWriteScript()
            },
            modifier = Modifier.testTag("ai_write_script_chip")
        )
    }
}

@Composable
private fun QuickActionChip(
    label: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    Box(
        modifier = modifier
            .height(dimens.controlHeightSmall)
            .studioTouchTarget(
                minSize = dimens.minTouchTarget,
                enabled = enabled,
                onOutOfBoundsTap = onClick
            )
            .alpha(if (enabled) 1f else 0.45f)
            .background(
                if (selected) tokens.accentMuted else tokens.layer2,
                tokens.pillShape
            )
            .border(
                tokens.borderWidth,
                if (selected) tokens.focusRingColor else tokens.borderStart,
                tokens.pillShape
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(safeContentPadding(tokens.pillShape, StudioSpacing.sm)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) accent else tokens.textMuted,
                modifier = Modifier.size(dimens.iconSizeSmall)
            )
            Text(
                text = label,
                style = compactType.tabOrChip,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    !enabled -> tokens.textMuted
                    selected -> tokens.accent
                    else -> tokens.textPrimary
                },
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
fun AiPromptInput(
    input: String,
    isLoading: Boolean,
    hasModelSelected: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
    isWriteCommandMode: Boolean = false,
    @Suppress("UNUSED_PARAMETER") onToggleWriteCommandMode: (() -> Unit)? = null,
    hasActiveKey: Boolean = true,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current
    var isFocused by remember { mutableStateOf(false) }

    val borderColor = when {
        !hasActiveKey -> tokens.borderStart.copy(alpha = 0.50f)
        isFocused && isWriteCommandMode -> tokens.accent
        isFocused -> tokens.focusRingColor
        isWriteCommandMode -> tokens.accent.copy(alpha = 0.65f)
        else -> tokens.borderStart
    }

    val placeholderText = stringResource(
        when {
            !hasModelSelected -> R.string.ai_err_select_model_first
            isWriteCommandMode -> R.string.ai_write_command_placeholder
            else -> R.string.ai_prompt_placeholder
        }
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (hasActiveKey) 1f else 0.45f),
        color = tokens.layer2
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StudioSpacing.md, vertical = StudioSpacing.sm)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = dimens.minTouchTarget),
                color = tokens.layer1,
                shape = tokens.smallShape,
                border = BorderStroke(tokens.borderWidth, borderColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(safeContentPadding(tokens.smallShape, StudioSpacing.md, StudioSpacing.sm)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        enabled = hasActiveKey,
                        singleLine = false,
                        maxLines = 3,
                        textStyle = compactType.body.copy(color = tokens.textPrimary),
                        cursorBrush = SolidColor(tokens.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (hasActiveKey && !isLoading && hasModelSelected && input.isNotBlank()) onSend()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused }
                            .testTag("ai_prompt_input"),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (input.isEmpty()) {
                                    Text(
                                        text = placeholderText,
                                        style = compactType.body,
                                        color = tokens.textMuted,
                                        maxLines = 2,
                                        softWrap = true,
                                        modifier = Modifier.testTag("ai_prompt_placeholder")
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }

            if (isLoading) {
                val stopDesc = stringResource(R.string.cd_stop_ai)
                val stopAction = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStopGeneration()
                }
                Box(
                    modifier = Modifier
                        .width(dimens.minTouchTarget)
                        .fillMaxHeight()
                        .heightIn(min = dimens.minTouchTarget)
                        .background(tokens.error, tokens.buttonShape)
                        .clickable(enabled = hasActiveKey, role = Role.Button, onClick = stopAction)
                        .semantics {
                            role = Role.Button
                            contentDescription = stopDesc
                        }
                        .testTag("stop_ai_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = stopDesc,
                        tint = tokens.onAccent,
                        modifier = Modifier.size(dimens.navIconSize)
                    )
                }
            } else {
                val canSend = hasActiveKey && input.isNotBlank() && hasModelSelected
                val sendDesc = stringResource(R.string.cd_send_ai_prompt)
                val sendAction = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSend()
                }
                Box(
                    modifier = Modifier
                        .width(dimens.minTouchTarget)
                        .fillMaxHeight()
                        .heightIn(min = dimens.minTouchTarget)
                        .then(
                            if (canSend) {
                                Modifier.studioGlow(
                                    color = tokens.glowColor,
                                    radius = tokens.glowRadius,
                                    shape = tokens.buttonShape
                                )
                            } else {
                                Modifier
                            }
                        )
                        .background(
                            if (canSend) tokens.primaryButtonContainer else tokens.layer3,
                            tokens.buttonShape
                        )
                        .border(
                            tokens.borderWidth,
                            if (canSend) tokens.accent.copy(alpha = 0.55f) else tokens.borderStart,
                            tokens.buttonShape
                        )
                        .clickable(enabled = canSend, role = Role.Button, onClick = sendAction)
                        .semantics {
                            role = Role.Button
                            contentDescription = sendDesc
                        }
                        .testTag("send_ai_prompt_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = sendDesc,
                        tint = if (canSend) tokens.primaryButtonContent else tokens.textMuted,
                        modifier = Modifier.size(dimens.iconSize)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandExplanationBottomSheet(
    state: CommandExplanationState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    if (!state.visible) return
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val closeDesc = stringResource(R.string.ai_explain_sheet_close)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tokens.floatingGlass,
        contentColor = tokens.textPrimary,
        shape = tokens.sheetShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(safeContentPadding(tokens.sheetShape, StudioSpacing.lg, StudioSpacing.lg))
                .padding(bottom = StudioSpacing.xl)
                .testTag("command_explanation_sheet"),
            verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    tint = tokens.info,
                    modifier = Modifier.size(dimens.iconSize)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tokens.formatHeading(stringResource(R.string.ai_explain_sheet_title)),
                        fontFamily = tokens.headingFont,
                        letterSpacing = tokens.headingLetterSpacing,
                        style = compactType.screenTitle,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary
                    )
                    Text(
                        text = stringResource(R.string.ai_explain_sheet_subtitle),
                        style = compactType.tabOrChip,
                        color = tokens.textSecondary
                    )
                }
                Box(
                    modifier = Modifier
                        .size(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onDismiss
                        )
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .semantics {
                            role = Role.Button
                            contentDescription = closeDesc
                        }
                        .testTag("dismiss_command_explanation_button"),
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

            Surface(
                color = tokens.baseCanvas,
                shape = tokens.smallShape,
                border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.command,
                    style = compactType.monoPreview,
                    color = tokens.accent,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV))
                )
            }

            val bodyText = state.explanationText.asString()
            when {
                state.isLoading && bodyText.isBlank() -> {
                    Text(
                        text = stringResource(R.string.ai_explain_sheet_loading),
                        style = compactType.body,
                        color = tokens.textSecondary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                bodyText.isNotBlank() -> {
                    Text(
                        text = if (state.isLoading) "$bodyText ▌" else bodyText,
                        style = compactType.body,
                        color = tokens.textPrimary
                    )
                }
            }

            val err = state.errorUiText
            if (err != null) {
                Surface(
                    color = tokens.error.copy(alpha = 0.12f),
                    shape = tokens.smallShape,
                    border = BorderStroke(tokens.borderWidth, tokens.error.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Assertive }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = err.asString(),
                            style = compactType.body,
                            color = tokens.error,
                            modifier = Modifier.weight(1f)
                        )
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                            TextButton(
                                onClick = onRetry,
                                modifier = Modifier
                                    .height(dimens.controlHeight)
                                    .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = onRetry)
                                    .testTag("retry_explain_command_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.ai_btn_retry),
                                    style = compactType.tabOrChip,
                                    color = tokens.accent
                                )
                            }
                        }
                    }
                }
            }

            if (state.isTruncated) {
                Text(
                    text = stringResource(R.string.ai_response_truncated),
                    style = compactType.tabOrChip,
                    color = tokens.warning
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .height(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = onDismiss
                            )
                            .testTag("close_command_explanation_button")
                    ) {
                        Text(
                            text = closeDesc,
                            style = compactType.tabOrChip,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.accent
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PromptHistoryDialog(
    items: List<PromptHistoryItem>,
    onDismiss: () -> Unit,
    onSelectPrompt: (String) -> Unit,
    onClearAll: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = StudioSpacing.lg)
            .widthIn(max = dimens.dialogMaxWidth),
        containerColor = tokens.floatingGlass,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.history_title),
                    style = compactType.screenTitle,
                    color = tokens.textPrimary
                )
                Text(
                    text = stringResource(R.string.history_subtitle, 30),
                    style = compactType.tabOrChip,
                    color = tokens.textSecondary
                )
            }
        },
        text = {
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = StudioSpacing.lg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = compactType.body,
                        color = tokens.textSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = dimens.dialogMaxWidth)
                        .fillMaxWidth()
                        .heightIn(max = dimens.historyListMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                ) {
                    items(items, key = { it.id }) { entry ->
                        val pickAction = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectPrompt(entry.prompt)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = dimens.controlHeight)
                                .studioTouchTarget(
                                    minSize = dimens.minTouchTarget,
                                    onOutOfBoundsTap = pickAction
                                )
                                .background(tokens.layer1, tokens.smallShape)
                                .border(tokens.borderWidth, tokens.borderStart, tokens.smallShape)
                                .clickable(role = Role.Button, onClick = pickAction)
                        ) {
                            Text(
                                text = entry.prompt,
                                style = compactType.body,
                                color = tokens.textPrimary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV))
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = onDismiss)
                ) {
                    Text(stringResource(R.string.action_dismiss), style = compactType.tabOrChip, color = tokens.accent)
                }
            }
        },
        dismissButton = {
            if (items.isNotEmpty()) {
                val clearAction = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClearAll()
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    TextButton(
                        onClick = clearAction,
                        modifier = Modifier
                            .height(dimens.controlHeight)
                            .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = clearAction)
                            .testTag("clear_prompt_history_button")
                    ) {
                        Text(stringResource(R.string.history_clear_all), style = compactType.tabOrChip, color = tokens.error)
                    }
                }
            }
        },
        shape = tokens.cardShape
    )
}
