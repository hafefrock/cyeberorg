package com.example.ui.terminal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioElevation
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.chamferInset
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioGlow
import com.example.ui.theme.studioTouchTarget

@Composable
fun SessionTabsBar(
    sessions: List<SessionTabItem>,
    activeSessionId: String,
    aiEnabled: Boolean,
    isAiPanelOpen: Boolean,
    hasUnreadAiReply: Boolean = false,
    showSplitButton: Boolean,
    splitMode: SplitMode,
    onSelectSession: (String) -> Unit,
    onAddSession: () -> Unit,
    onCloseSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onToggleAiPanel: () -> Unit,
    onToggleSplitMode: () -> Unit,
    modifier: Modifier = Modifier,
    showMenuButton: Boolean = false,
    isSidebarOpen: Boolean = false,
    onOpenSidebar: () -> Unit = {}
) {
    var renameTarget by remember { mutableStateOf<SessionTabItem?>(null) }
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = tokens.layer1,
        tonalElevation = StudioElevation.surface
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            val maxTabTitleWidth = if (maxWidth < dimens.narrowWidthThreshold) {
                dimens.tabTitleCompactMaxWidth
            } else {
                dimens.tabTitleMaxWidth
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = dimens.minTouchTarget)
                        .padding(horizontal = StudioSpacing.sm, vertical = StudioSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                ) {
                    if (showMenuButton) {
                        val menuStateDesc = stringResource(
                            if (isSidebarOpen) R.string.sidebar_state_open else R.string.sidebar_state_closed
                        )
                        val menuContentDesc = stringResource(R.string.cd_open_sidebar_menu)
                        val menuAction = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenSidebar()
                        }
                        Box(
                            modifier = Modifier
                                .size(dimens.controlHeight)
                                .studioTouchTarget(
                                    minSize = dimens.minTouchTarget,
                                    onOutOfBoundsTap = menuAction
                                )
                                .clickable(role = Role.Button, onClick = menuAction)
                                .semantics {
                                    role = Role.Button
                                    contentDescription = menuContentDesc
                                    stateDescription = menuStateDesc
                                }
                                .testTag("open_sidebar_menu_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = null,
                                tint = if (isSidebarOpen) tokens.accent else tokens.textSecondary,
                                modifier = Modifier.size(dimens.navIconSize)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = dimens.minTouchTarget)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                    ) {
                        sessions.forEach { session ->
                            val isActive = session.id == activeSessionId
                            SessionTabChip(
                                session = session,
                                isActive = isActive,
                                maxTitleWidth = maxTabTitleWidth,
                                onSelect = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectSession(session.id)
                                },
                                onRenameRequest = { renameTarget = session },
                                onClose = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onCloseSession(session.id)
                                }
                            )
                        }

                    val addAction = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onAddSession()
                    }
                    Box(
                        modifier = Modifier
                            .size(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = addAction
                            )
                            .clickable(role = Role.Button, onClick = addAction)
                            .testTag("add_session_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.cd_new_session),
                            tint = tokens.accent,
                            modifier = Modifier.size(dimens.navIconSize)
                        )
                    }
                }

                if (showSplitButton) {
                    val splitAction = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleSplitMode()
                    }
                    Box(
                        modifier = Modifier
                            .size(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = splitAction
                            )
                            .clickable(role = Role.Button, onClick = splitAction)
                            .testTag("toggle_split_mode_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ViewColumn,
                            contentDescription = stringResource(R.string.cd_split_workspace),
                            tint = if (splitMode != SplitMode.SINGLE) tokens.accent else tokens.textSecondary,
                            modifier = Modifier.size(dimens.iconSize)
                        )
                    }
                }

                if (aiEnabled) {
                    val starStateDesc = stringResource(
                        when {
                            isAiPanelOpen -> R.string.ai_star_state_open
                            hasUnreadAiReply -> R.string.ai_star_state_unread
                            else -> R.string.ai_star_state_normal
                        }
                    )
                    val starContentDesc = stringResource(
                        when {
                            isAiPanelOpen -> R.string.cd_ai_star_open
                            hasUnreadAiReply -> R.string.cd_ai_star_unread
                            else -> R.string.cd_toggle_ai_panel
                        }
                    )
                    val starAction = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleAiPanel()
                    }
                    Box(
                        modifier = Modifier
                            .size(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = starAction
                            )
                            .clickable(role = Role.Button, onClick = starAction)
                            .semantics {
                                role = Role.Button
                                stateDescription = starStateDesc
                            }
                            .testTag("toggle_ai_panel_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier.size(dimens.tabIconVisualSize + StudioSpacing.xs),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isAiPanelOpen) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
                                contentDescription = starContentDesc,
                                tint = when {
                                    isAiPanelOpen -> tokens.accent
                                    hasUnreadAiReply -> tokens.accent
                                    else -> tokens.textSecondary
                                },
                                modifier = Modifier.size(dimens.navIconSize)
                            )
                            if (!isAiPanelOpen && hasUnreadAiReply) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .studioGlow(
                                            color = tokens.glowColor,
                                            radius = tokens.glowRadius,
                                            shape = CircleShape
                                        )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(dimens.statusDotSize)
                                            .clip(CircleShape)
                                            .background(tokens.accent)
                                            .testTag("ai_star_unread_dot")
                                    )
                                }
                            }
                        }
                    }
                }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dimens.hairlineWidth)
                        .background(tokens.thinBorderBrush)
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameSessionDialog(
            currentTitle = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle ->
                onRenameSession(target.id, newTitle)
                renameTarget = null
            }
        )
    }
}

@Composable
private fun SessionTabChip(
    session: SessionTabItem,
    isActive: Boolean,
    maxTitleWidth: Dp,
    onSelect: () -> Unit,
    onRenameRequest: () -> Unit,
    onClose: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val reduceEffects = StudioTheme.reduceEffects
    val bgColor by animateColorAsState(
        targetValue = if (isActive) tokens.accentMuted else tokens.layer2,
        animationSpec = tween(durationMillis = tokens.motionDurationMs),
        label = "tabBg"
    )

    val exitCode = session.lastExitCode ?: 0
    val pulseAlpha = if (!reduceEffects && isActive && exitCode == 0) {
        val pulseTransition = rememberInfiniteTransition(label = "sessionDotPulse")
        pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        ).value
    } else {
        1f
    }

    val dotColor = when {
        exitCode != 0 -> tokens.error
        isActive -> tokens.accent
        else -> tokens.success.copy(alpha = 0.72f)
    }

    val tabBorderBrush = if (isActive) tokens.accentBorderBrush else tokens.thinBorderBrush

    Box(
        modifier = if (isActive) {
            Modifier.studioGlow(
                color = tokens.glowColor,
                radius = tokens.glowRadius,
                shape = tokens.smallShape
            )
        } else {
            Modifier
        }
    ) {
        // Non-clipping container so child icon buttons (20.dp visual) preserve 48.dp touch bounds
        Box(
            modifier = Modifier
                .height(dimens.tabHeight)
                .studioTouchTarget(
                    minSize = dimens.minTouchTarget,
                    onOutOfBoundsTap = onSelect
                )
                .background(bgColor, tokens.smallShape)
                .border(tokens.borderWidth, tabBorderBrush, tokens.smallShape)
                .clickable(role = Role.Tab, onClick = onSelect)
                .testTag("session_tab_${session.id}"),
            contentAlignment = Alignment.Center
        ) {
            val chipHorizontalCut = tokens.smallShape.chamferInset()
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(
                        start = maxOf(dimens.cardPaddingH, chipHorizontalCut),
                        end = maxOf(StudioSpacing.xs, chipHorizontalCut)
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
            ) {
                Box(
                    modifier = Modifier
                        .size(dimens.statusDotSize)
                        .alpha(if (isActive && exitCode == 0) pulseAlpha else 1f)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    text = session.title,
                    style = compactType.tabOrChip,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isActive) tokens.textPrimary else tokens.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = maxTitleWidth)
                )
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .size(dimens.tabIconVisualSize)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = onRenameRequest
                            )
                            .background(Color.Transparent, CircleShape)
                            .clickable(role = Role.Button, onClick = onRenameRequest)
                            .testTag("rename_session_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.cd_rename_session),
                            tint = tokens.textSecondary,
                            modifier = Modifier.size(dimens.iconSizeSmall)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(dimens.tabIconVisualSize)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onClose
                        )
                        .background(Color.Transparent, CircleShape)
                        .clickable(role = Role.Button, onClick = onClose)
                        .testTag("close_session_${session.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close_session),
                        tint = tokens.textMuted,
                        modifier = Modifier.size(dimens.iconSizeSmall)
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameSessionDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by remember { mutableStateOf(currentTitle) }
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = StudioSpacing.lg)
            .widthIn(max = dimens.dialogMaxWidth),
        containerColor = tokens.floatingGlass,
        title = {
            Text(
                text = stringResource(R.string.session_rename_title),
                style = compactType.cardTitle,
                color = tokens.textPrimary
            )
        },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                textStyle = compactType.body.copy(color = tokens.textPrimary),
                label = { Text(stringResource(R.string.session_tab_name_label), style = compactType.tabOrChip) },
                shape = tokens.smallShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rename_session_input")
            )
        },
        confirmButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Button(
                    onClick = { onConfirm(title) },
                    enabled = title.isNotBlank(),
                    shape = tokens.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.primaryButtonContainer,
                        contentColor = tokens.primaryButtonContent
                    ),
                    contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            enabled = title.isNotBlank(),
                            onOutOfBoundsTap = { if (title.isNotBlank()) onConfirm(title) }
                        )
                        .testTag("rename_session_confirm")
                ) {
                    Text(stringResource(R.string.action_save), style = compactType.tabOrChip)
                }
            }
        },
        dismissButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = dimens.cardPaddingH),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onDismiss
                        )
                ) {
                    Text(stringResource(R.string.action_cancel), style = compactType.tabOrChip, color = tokens.textSecondary)
                }
            }
        },
        shape = tokens.cardShape
    )
}
