package com.example.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.GeminiModels
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioTouchTarget

@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: Color? = null,
    borderColor: Color? = null,
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = tokens.formatHeading(title),
            fontFamily = tokens.headingFont,
            letterSpacing = tokens.headingLetterSpacing,
            style = compactType.tabOrChip,
            color = titleColor ?: tokens.accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = StudioSpacing.xs, bottom = StudioSpacing.xs)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = containerColor ?: tokens.layer1,
            shape = tokens.cardShape,
            border = borderColor?.let { BorderStroke(tokens.borderWidth, it) }
                ?: BorderStroke(tokens.borderWidth, tokens.thinBorderBrush)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV)),
                verticalArrangement = Arrangement.spacedBy(dimens.listSpacing),
                content = content
            )
        }
    }
}

@Composable
fun SettingsGroupRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimens.controlHeight)
            .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = onClick)
            .background(if (selected) tokens.accentMuted else tokens.layer1, tokens.cardShape)
            .border(
                tokens.borderWidth,
                if (selected) tokens.focusRingColor else tokens.borderStart.copy(alpha = 0.7f),
                tokens.cardShape
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = "$title, $summary"
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.md)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) tokens.accent else tokens.textSecondary,
                modifier = Modifier.size(dimens.navIconSize)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = compactType.cardTitle,
                    color = if (selected) tokens.accent else tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(StudioSpacing.xxs))
                Text(
                    text = summary,
                    style = compactType.body,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (selected) tokens.accent else tokens.textMuted,
                modifier = Modifier.size(dimens.iconSize)
            )
        }
    }
}

@Composable
fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    switchTestTag: String? = null
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val a11yLabel = if (description.isNullOrBlank()) title else "$title, $description"
    val toggleAction = { onCheckedChange(!checked) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimens.controlHeight)
            .studioTouchTarget(
                minSize = dimens.minTouchTarget,
                enabled = enabled,
                onOutOfBoundsTap = toggleAction
            )
            .background(tokens.layer2, tokens.smallShape)
            .border(tokens.borderWidth, tokens.thinBorderBrush, tokens.smallShape)
            .clickable(enabled = enabled, role = Role.Switch, onClick = toggleAction)
            .semantics { contentDescription = a11yLabel }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.md)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (checked) tokens.accent else tokens.textSecondary,
                modifier = Modifier.size(dimens.navIconSize)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = compactType.cardTitle, color = tokens.textPrimary)
                if (!description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(StudioSpacing.xxs))
                    Text(text = description, style = compactType.body, color = tokens.textSecondary)
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = title }
                    .let { if (switchTestTag != null) it.testTag(switchTestTag) else it }
            )
        }
    }
}

@Composable
fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String? = null,
    enabled: Boolean = true,
    useMonospaceTitle: Boolean = false
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val a11yLabel = if (description.isNullOrBlank()) title else "$title, $description"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimens.controlHeight)
            .studioTouchTarget(
                minSize = dimens.minTouchTarget,
                enabled = enabled,
                onOutOfBoundsTap = onClick
            )
            .background(if (selected) tokens.accentMuted else tokens.layer2, tokens.smallShape)
            .border(
                BorderStroke(
                    tokens.borderWidth,
                    if (selected) tokens.focusRingColor else tokens.borderStart
                ),
                tokens.smallShape
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = a11yLabel }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.md)
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) tokens.accent else tokens.textSecondary,
                    modifier = Modifier.size(dimens.navIconSize)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontFamily = if (useMonospaceTitle) JetBrainsMonoFamily else tokens.bodyFont,
                    style = compactType.cardTitle,
                    color = if (selected) tokens.accent else tokens.textPrimary
                )
                if (!description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(StudioSpacing.xxs))
                    Text(text = description, style = compactType.body, color = tokens.textSecondary)
                }
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = tokens.accent,
                    modifier = Modifier.size(dimens.iconSize)
                )
            }
        }
    }
}

@Composable
fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = Icons.AutoMirrored.Filled.KeyboardArrowRight
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val a11yLabel = if (description.isNullOrBlank()) title else "$title, $description"
    val fgColor = when {
        !enabled -> tokens.textMuted
        isDestructive -> tokens.error
        else -> tokens.textPrimary
    }
    val iconTint = when {
        !enabled -> tokens.textMuted
        isDestructive -> tokens.error
        else -> tokens.accent
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimens.controlHeight)
            .studioTouchTarget(
                minSize = dimens.minTouchTarget,
                enabled = enabled,
                onOutOfBoundsTap = onClick
            )
            .background(if (isDestructive) tokens.error.copy(alpha = 0.10f) else tokens.layer2, tokens.smallShape)
            .border(
                BorderStroke(
                    tokens.borderWidth,
                    if (isDestructive) tokens.error.copy(alpha = 0.50f) else tokens.borderStart
                ),
                tokens.smallShape
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = a11yLabel }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.md)
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(dimens.navIconSize))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = compactType.cardTitle, color = fgColor)
                if (!description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(StudioSpacing.xxs))
                    Text(text = description, style = compactType.body, color = tokens.textSecondary)
                }
            }
            if (trailingIcon != null) {
                Icon(
                    trailingIcon,
                    contentDescription = null,
                    tint = if (isDestructive) tokens.error else tokens.textMuted,
                    modifier = Modifier.size(dimens.iconSize)
                )
            }
        }
    }
}

@Composable
fun DangerZoneSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tokens = StudioTheme.tokens
    SettingsSection(
        title = stringResource(R.string.settings_sec_danger_zone),
        modifier = modifier.testTag("danger_zone_section"),
        titleColor = tokens.error,
        borderColor = tokens.error.copy(alpha = 0.45f),
        containerColor = tokens.error.copy(alpha = 0.06f),
        content = content
    )
}

@Composable
fun DestructiveConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dialogTestTag: String,
    confirmTestTag: String,
    cancelTestTag: String
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = StudioSpacing.lg)
            .widthIn(max = dimens.dialogMaxWidth)
            .testTag(dialogTestTag),
        containerColor = tokens.floatingGlass,
        shape = tokens.cardShape,
        title = { Text(title, color = tokens.error, style = compactType.screenTitle) },
        text = { Text(message, color = tokens.textSecondary, style = compactType.body) },
        confirmButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Button(
                    onClick = onConfirm,
                    shape = tokens.buttonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.error, contentColor = tokens.onAccent),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = onConfirm)
                        .testTag(confirmTestTag)
                ) { Text(confirmLabel, style = compactType.tabOrChip) }
            }
        },
        dismissButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = onDismiss)
                        .testTag(cancelTestTag)
                ) {
                    Text(stringResource(R.string.action_cancel), style = compactType.tabOrChip, color = tokens.textSecondary)
                }
            }
        }
    )
}

@Composable
fun GeminiModelChoiceList(
    selectedModelId: String?,
    onSelectModel: (String) -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    val dimens = StudioTheme.dimens
    val normalizedPrimary = GeminiModels.normalizeSelectedModel(selectedModelId)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)
    ) {
        GeminiModels.available.forEach { option ->
            SettingsChoiceRow(
                title = option.displayName,
                description = stringResource(option.descriptionRes),
                selected = normalizedPrimary == option.id,
                onClick = { onSelectModel(option.id) },
                useMonospaceTitle = true,
                modifier = Modifier.testTag("$testTagPrefix${option.id}")
            )
        }
    }
}
