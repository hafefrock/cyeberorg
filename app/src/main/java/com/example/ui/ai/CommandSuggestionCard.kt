package com.example.ui.ai

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ai.CommandRiskAssessment
import com.example.ai.CommandRiskLevel
import com.example.ai.CommandSuggestion
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.RiskBadgeStyle
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioGlow
import com.example.ui.theme.studioTouchTarget

@Composable
fun CommandSuggestionCard(
    suggestion: CommandSuggestion,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSaveSnippet: (CommandSuggestion) -> Unit = {},
    onExplain: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    AiCommandCard(
        suggestion = suggestion,
        onInsert = onInsert,
        onCopy = onCopy,
        onSaveSnippet = onSaveSnippet,
        onExplain = onExplain,
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiCommandCard(
    suggestion: CommandSuggestion,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSaveSnippet: (CommandSuggestion) -> Unit = {},
    onExplain: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current
    var showHighRiskDialog by remember(suggestion.id) { mutableStateOf(false) }

    val cardBorder = when (suggestion.risk.level) {
        CommandRiskLevel.HIGH -> BorderStroke(tokens.borderWidth, tokens.error.copy(alpha = 0.72f))
        CommandRiskLevel.MEDIUM -> BorderStroke(tokens.borderWidth, tokens.warning.copy(alpha = 0.62f))
        CommandRiskLevel.LOW -> BorderStroke(tokens.borderWidth, tokens.borderStart)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .studioGlow(
                color = tokens.glowColor,
                radius = tokens.glowRadius,
                shape = tokens.cardShape
            )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_command_card_${suggestion.id}"),
            color = tokens.layer1,
            shape = tokens.cardShape,
            border = cardBorder
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV)),
                verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = tokens.accent,
                            modifier = Modifier.size(dimens.iconSize)
                        )
                        Text(
                            text = tokens.formatHeading(suggestion.title.asString()),
                            fontFamily = tokens.headingFont,
                            letterSpacing = tokens.headingLetterSpacing,
                            style = compactType.cardTitle,
                            color = tokens.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(StudioSpacing.sm))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                    ) {
                        CommandRiskBadge(risk = suggestion.risk)

                        Surface(
                            color = tokens.layer3,
                            shape = tokens.smallShape,
                            modifier = Modifier.height(dimens.chipHeight)
                        ) {
                            Box(
                                modifier = Modifier.padding(safeContentPadding(tokens.smallShape, StudioSpacing.sm)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = suggestion.language.uppercase(),
                                    style = compactType.tabOrChip,
                                    color = tokens.textSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = tokens.baseCanvas,
                    shape = tokens.smallShape,
                    border = BorderStroke(tokens.borderWidth, tokens.borderEnd)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV))
                    ) {
                        Text(
                            text = suggestion.command,
                            style = compactType.monoPreview,
                            color = tokens.textPrimary
                        )
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                ) {
                    CommandActionButton(
                        label = stringResource(R.string.ai_btn_insert),
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDesc = stringResource(R.string.cd_insert_command),
                        containerColor = if (suggestion.risk.level == CommandRiskLevel.HIGH) {
                            tokens.error
                        } else {
                            tokens.primaryButtonContainer
                        },
                        contentColor = if (suggestion.risk.level == CommandRiskLevel.HIGH) {
                            tokens.onAccent
                        } else {
                            tokens.primaryButtonContent
                        },
                        glowPrimary = suggestion.risk.level != CommandRiskLevel.HIGH,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (suggestion.risk.level == CommandRiskLevel.HIGH) {
                                showHighRiskDialog = true
                            } else {
                                onInsert(suggestion.command)
                            }
                        },
                        modifier = Modifier.testTag("insert_command_button")
                    )

                    CommandActionButton(
                        label = stringResource(R.string.ai_btn_copy),
                        icon = Icons.Default.ContentCopy,
                        contentDesc = stringResource(R.string.cd_copy_command),
                        containerColor = tokens.layer2,
                        contentColor = tokens.textPrimary,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCopy(suggestion.command)
                        },
                        modifier = Modifier.testTag("copy_command_button")
                    )

                    CommandActionButton(
                        label = stringResource(R.string.ai_btn_save),
                        icon = Icons.Default.BookmarkAdd,
                        contentDesc = stringResource(R.string.cd_save_snippet),
                        containerColor = tokens.layer2,
                        contentColor = tokens.accent,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSaveSnippet(suggestion)
                        },
                        modifier = Modifier.testTag("save_command_button")
                    )

                    CommandActionButton(
                        label = stringResource(R.string.ai_btn_explain),
                        icon = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDesc = stringResource(R.string.cd_explain_command),
                        containerColor = tokens.layer2,
                        contentColor = tokens.info,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onExplain(suggestion.command)
                        },
                        modifier = Modifier.testTag("explain_command_button")
                    )
                }
            }
        }
    }

    if (showHighRiskDialog) {
        HighRiskCommandConfirmDialog(
            command = suggestion.command,
            risk = suggestion.risk,
            onDismiss = { showHighRiskDialog = false },
            onConfirmInsert = {
                showHighRiskDialog = false
                onInsert(suggestion.command)
            }
        )
    }
}

@Composable
fun CommandRiskBadge(
    risk: CommandRiskAssessment,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    var showAdvisoryDialog by remember(risk) { mutableStateOf(false) }

    val disclaimer = stringResource(R.string.risk_heuristic_disclaimer)
    val labelRes = when (tokens.badgeStyle) {
        RiskBadgeStyle.BRACKET_MONO -> when (risk.level) {
            CommandRiskLevel.LOW -> R.string.risk_badge_low_bracket
            CommandRiskLevel.MEDIUM -> R.string.risk_badge_medium_bracket
            CommandRiskLevel.HIGH -> R.string.risk_badge_high_bracket
        }
        RiskBadgeStyle.CHAMFER_ICON -> when (risk.level) {
            CommandRiskLevel.LOW -> R.string.risk_badge_low
            CommandRiskLevel.MEDIUM -> R.string.risk_badge_medium
            CommandRiskLevel.HIGH -> R.string.risk_badge_high
        }
    }
    val labelText = stringResource(labelRes)

    val containerColor: Color
    val fgColor: Color
    val icon: ImageVector?
    val borderStroke: BorderStroke

    when (risk.level) {
        CommandRiskLevel.LOW -> {
            containerColor = tokens.layer2
            fgColor = tokens.textSecondary
            icon = if (tokens.badgeStyle == RiskBadgeStyle.CHAMFER_ICON) Icons.Default.Shield else null
            borderStroke = BorderStroke(tokens.borderWidth, tokens.borderStart)
        }
        CommandRiskLevel.MEDIUM -> {
            containerColor = tokens.warning.copy(alpha = 0.14f)
            fgColor = tokens.warning
            icon = if (tokens.badgeStyle == RiskBadgeStyle.CHAMFER_ICON) Icons.Default.Warning else null
            borderStroke = BorderStroke(tokens.borderWidth, tokens.warning.copy(alpha = 0.55f))
        }
        CommandRiskLevel.HIGH -> {
            containerColor = tokens.error.copy(alpha = 0.14f)
            fgColor = tokens.error
            icon = if (tokens.badgeStyle == RiskBadgeStyle.CHAMFER_ICON) Icons.Default.Dangerous else null
            borderStroke = BorderStroke(tokens.borderWidth, tokens.error.copy(alpha = 0.6f))
        }
    }

    val badgeShape = when (tokens.badgeStyle) {
        RiskBadgeStyle.BRACKET_MONO -> tokens.smallShape
        RiskBadgeStyle.CHAMFER_ICON -> tokens.pillShape
    }

    val openAdvisory = { showAdvisoryDialog = true }
    Box(
        modifier = modifier
            .height(dimens.chipHeight)
            .studioTouchTarget(
                minSize = dimens.minTouchTarget,
                onOutOfBoundsTap = openAdvisory
            )
            .background(containerColor, badgeShape)
            .border(borderStroke, badgeShape)
            .clickable(role = Role.Button, onClick = openAdvisory)
            .semantics {
                role = Role.Button
                contentDescription = "$labelText: $disclaimer"
            }
            .testTag("risk_badge_${risk.level.name.lowercase()}"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(safeContentPadding(badgeShape, StudioSpacing.sm, StudioSpacing.xxs)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(dimens.badgeIconSize)
                )
            }
            Text(
                text = labelText,
                style = compactType.tabOrChip,
                fontWeight = FontWeight.Bold,
                color = fgColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }

    if (showAdvisoryDialog) {
        CommandRiskAdvisoryDialog(
            risk = risk,
            onDismiss = { showAdvisoryDialog = false }
        )
    }
}

@Composable
fun CommandRiskAdvisoryDialog(
    risk: CommandRiskAssessment,
    onDismiss: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val detailRes = risk.reason.messageRes ?: when (risk.level) {
        CommandRiskLevel.LOW -> R.string.risk_info_low_desc
        CommandRiskLevel.MEDIUM -> R.string.risk_info_medium_desc
        CommandRiskLevel.HIGH -> R.string.risk_dialog_warning
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = tokens.floatingGlass,
        shape = tokens.cardShape,
        title = {
            Text(
                text = stringResource(R.string.risk_info_dialog_title),
                style = compactType.screenTitle,
                color = tokens.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)) {
                Text(
                    text = stringResource(R.string.risk_heuristic_disclaimer),
                    style = compactType.tabOrChip,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.accent
                )
                Text(
                    text = stringResource(detailRes),
                    style = compactType.body,
                    color = tokens.textSecondary
                )
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
                    Text(stringResource(R.string.risk_info_ok), style = compactType.tabOrChip, color = tokens.accent)
                }
            }
        },
        modifier = Modifier
            .padding(horizontal = StudioSpacing.lg)
            .widthIn(max = dimens.dialogMaxWidth)
            .testTag("risk_advisory_dialog")
    )
}

@Composable
fun HighRiskCommandConfirmDialog(
    command: String,
    risk: CommandRiskAssessment,
    onDismiss: () -> Unit,
    onConfirmInsert: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current
    val reasonText = risk.reason.messageRes?.let { stringResource(it) } ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = tokens.floatingGlass,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
            ) {
                Icon(
                    imageVector = Icons.Default.Dangerous,
                    contentDescription = null,
                    tint = tokens.error,
                    modifier = Modifier.size(dimens.iconSize)
                )
                Text(
                    text = stringResource(R.string.risk_dialog_title),
                    style = compactType.screenTitle,
                    color = tokens.textPrimary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)) {
                Text(
                    text = stringResource(R.string.risk_heuristic_disclaimer),
                    style = compactType.tabOrChip,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.warning,
                    modifier = Modifier.testTag("risk_dialog_heuristic_label")
                )
                Text(
                    text = stringResource(R.string.risk_dialog_warning),
                    style = compactType.body,
                    color = tokens.textSecondary
                )
                if (reasonText.isNotBlank()) {
                    Surface(
                        color = tokens.error.copy(alpha = 0.12f),
                        shape = tokens.smallShape
                    ) {
                        Text(
                            text = reasonText,
                            style = compactType.body,
                            color = tokens.error,
                            modifier = Modifier.padding(safeContentPadding(tokens.smallShape, StudioSpacing.sm, StudioSpacing.xs))
                        )
                    }
                }
                Surface(
                    color = tokens.baseCanvas,
                    shape = tokens.smallShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = command,
                        style = compactType.monoPreview,
                        color = tokens.textPrimary,
                        modifier = Modifier.padding(safeContentPadding(tokens.smallShape, StudioSpacing.sm, StudioSpacing.xs))
                    )
                }
            }
        },
        confirmButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onConfirmInsert()
                    },
                    shape = tokens.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.error,
                        contentColor = tokens.onAccent
                    ),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = onConfirmInsert)
                        .testTag("confirm_high_risk_insert")
                ) {
                    Text(stringResource(R.string.risk_dialog_confirm), style = compactType.tabOrChip)
                }
            }
        },
        dismissButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = onDismiss)
                ) {
                    Text(stringResource(R.string.action_cancel), style = compactType.tabOrChip, color = tokens.textSecondary)
                }
            }
        },
        shape = tokens.cardShape,
        modifier = Modifier
            .padding(horizontal = StudioSpacing.lg)
            .widthIn(max = dimens.dialogMaxWidth)
            .testTag("high_risk_insert_dialog")
    )
}

@Composable
private fun CommandActionButton(
    label: String,
    icon: ImageVector,
    contentDesc: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    glowPrimary: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    Box(
        modifier = modifier
            .studioTouchTarget(
                minSize = dimens.minTouchTarget,
                onOutOfBoundsTap = onClick
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = contentDesc
            }
            .then(
                if (glowPrimary) {
                    Modifier.studioGlow(
                        color = tokens.glowColor,
                        radius = tokens.glowRadius,
                        shape = tokens.buttonShape
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(dimens.controlHeightSmall)
                .background(containerColor, tokens.buttonShape)
                .then(
                    if (glowPrimary) {
                        Modifier
                    } else {
                        Modifier.border(tokens.borderWidth, tokens.borderEnd, tokens.buttonShape)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.padding(safeContentPadding(tokens.buttonShape, dimens.cardPaddingH)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(dimens.iconSizeSmall)
                )
                Spacer(modifier = Modifier.width(StudioSpacing.xs))
                Text(
                    text = label,
                    fontFamily = tokens.headingFont,
                    style = compactType.tabOrChip,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
