package com.example.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.example.R
import com.example.data.GeminiModels
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioTouchTarget

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettingsScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val settings = state.settings
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val normalizedPrimary = GeminiModels.normalizeSelectedModel(settings.selectedModel)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_ai_screen"),
        contentPadding = PaddingValues(StudioSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
    ) {
        item(key = "ai_general") {
            SettingsSection(title = stringResource(R.string.settings_sec_general)) {
                SettingsToggleRow(
                    icon = Icons.Default.AutoAwesome,
                    title = stringResource(R.string.settings_ai_toggle_title),
                    description = stringResource(R.string.settings_ai_toggle_desc),
                    checked = settings.aiEnabled,
                    onCheckedChange = viewModel::setAiEnabled,
                    switchTestTag = "ai_enabled_switch"
                )
            }
        }

        item(key = "ai_consent") {
            SettingsSection(
                title = stringResource(R.string.settings_ai_privacy_title),
                modifier = Modifier.testTag("ai_consent_section")
            ) {
                SettingsActionRow(
                    icon = Icons.Default.Security,
                    title = stringResource(R.string.settings_ai_consent_revoke_title),
                    description = stringResource(
                        if (settings.aiConsentAccepted) {
                            R.string.settings_ai_consent_granted_desc
                        } else {
                            R.string.settings_ai_consent_revoked_desc
                        }
                    ),
                    enabled = settings.aiConsentAccepted,
                    isDestructive = settings.aiConsentAccepted,
                    onClick = viewModel::revokeAiConsent,
                    trailingIcon = null,
                    modifier = Modifier.testTag("revoke_ai_consent_button")
                )
            }
        }

        item(key = "ai_primary_model") {
            SettingsSection(title = stringResource(R.string.settings_primary_model)) {
                if (normalizedPrimary == null) {
                    Text(
                        text = stringResource(R.string.settings_primary_model_unset),
                        style = compactType.body,
                        color = tokens.warning,
                        modifier = Modifier.testTag("primary_model_unset_notice")
                    )
                }
                GeminiModelChoiceList(
                    selectedModelId = normalizedPrimary,
                    onSelectModel = viewModel::setSelectedModel,
                    testTagPrefix = "primary_model_"
                )
            }
        }

        item(key = "ai_fallback_models") {
            SettingsSection(title = stringResource(R.string.settings_fallback_models)) {
                Text(
                    text = stringResource(R.string.settings_fallback_models_desc),
                    style = compactType.body,
                    color = tokens.textSecondary
                )

                if (settings.fallbackModels.isEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fallback_models_empty_notice"),
                        color = tokens.layer2,
                        shape = tokens.smallShape,
                        border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_fallback_empty),
                            style = compactType.body,
                            color = tokens.textSecondary,
                            modifier = Modifier.padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV))
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)) {
                        settings.fallbackModels.forEachIndexed { idx, entry ->
                            val option = GeminiModels.byId(entry.modelId) ?: return@forEachIndexed
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("fallback_row_${entry.modelId}"),
                                color = tokens.layer2,
                                shape = tokens.smallShape,
                                border = if (entry.enabled) {
                                    BorderStroke(tokens.borderWidth, tokens.accent.copy(alpha = 0.35f))
                                } else {
                                    BorderStroke(tokens.borderWidth, tokens.thinBorderBrush)
                                }
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
                                            text = "${idx + 1}. ${option.displayName}",
                                            fontFamily = JetBrainsMonoFamily,
                                            style = compactType.cardTitle,
                                            color = if (entry.enabled) tokens.textPrimary else tokens.textMuted
                                        )
                                        Text(
                                            text = stringResource(option.descriptionRes),
                                            style = compactType.body,
                                            color = tokens.textSecondary
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val canMoveUp = idx > 0
                                        val upDesc = stringResource(R.string.cd_move_up)
                                        val moveUpAction = { viewModel.moveFallbackModel(entry.modelId, -1) }
                                        Box(
                                            modifier = Modifier
                                                .size(dimens.controlHeightSmall)
                                                .studioTouchTarget(
                                                    minSize = dimens.minTouchTarget,
                                                    enabled = canMoveUp,
                                                    onOutOfBoundsTap = moveUpAction
                                                )
                                                .clickable(enabled = canMoveUp, role = Role.Button, onClick = moveUpAction)
                                                .semantics {
                                                    role = Role.Button
                                                    contentDescription = upDesc
                                                }
                                                .testTag("fallback_up_${entry.modelId}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ArrowUpward,
                                                contentDescription = upDesc,
                                                tint = if (canMoveUp) tokens.textPrimary else tokens.textMuted,
                                                modifier = Modifier.size(dimens.iconSize)
                                            )
                                        }

                                        val canMoveDown = idx < settings.fallbackModels.lastIndex
                                        val downDesc = stringResource(R.string.cd_move_down)
                                        val moveDownAction = { viewModel.moveFallbackModel(entry.modelId, 1) }
                                        Box(
                                            modifier = Modifier
                                                .size(dimens.controlHeightSmall)
                                                .studioTouchTarget(
                                                    minSize = dimens.minTouchTarget,
                                                    enabled = canMoveDown,
                                                    onOutOfBoundsTap = moveDownAction
                                                )
                                                .clickable(enabled = canMoveDown, role = Role.Button, onClick = moveDownAction)
                                                .semantics {
                                                    role = Role.Button
                                                    contentDescription = downDesc
                                                }
                                                .testTag("fallback_down_${entry.modelId}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ArrowDownward,
                                                contentDescription = downDesc,
                                                tint = if (canMoveDown) tokens.textPrimary else tokens.textMuted,
                                                modifier = Modifier.size(dimens.iconSize)
                                            )
                                        }

                                        Switch(
                                            checked = entry.enabled,
                                            onCheckedChange = { enabled ->
                                                viewModel.setFallbackModelEnabled(entry.modelId, enabled)
                                            },
                                            modifier = Modifier.testTag("fallback_toggle_${entry.modelId}")
                                        )

                                        val removeDesc = stringResource(R.string.cd_remove_fallback)
                                        val removeAction = { viewModel.removeFallbackModel(entry.modelId) }
                                        Box(
                                            modifier = Modifier
                                                .size(dimens.controlHeightSmall)
                                                .studioTouchTarget(
                                                    minSize = dimens.minTouchTarget,
                                                    onOutOfBoundsTap = removeAction
                                                )
                                                .clickable(role = Role.Button, onClick = removeAction)
                                                .semantics {
                                                    role = Role.Button
                                                    contentDescription = removeDesc
                                                }
                                                .testTag("fallback_remove_${entry.modelId}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = removeDesc,
                                                tint = tokens.textMuted,
                                                modifier = Modifier.size(dimens.iconSizeSmall)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val availableToAdd = GeminiModels.available.filter { opt ->
                    settings.fallbackModels.none { it.modelId == opt.id }
                }
                if (availableToAdd.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(StudioSpacing.xs))
                    Text(
                        text = stringResource(R.string.settings_fallback_add_label),
                        style = compactType.tabOrChip,
                        color = tokens.textSecondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                    ) {
                        availableToAdd.forEach { opt ->
                            val addAction = { viewModel.addFallbackModel(opt.id) }
                            Box(
                                modifier = Modifier
                                    .height(dimens.chipHeight)
                                    .studioTouchTarget(
                                        minSize = dimens.minTouchTarget,
                                        onOutOfBoundsTap = addAction
                                    )
                                    .background(tokens.layer2, tokens.pillShape)
                                    .border(tokens.borderWidth, tokens.borderStart, tokens.pillShape)
                                    .clickable(role = Role.Button, onClick = addAction)
                                    .testTag("add_fallback_${opt.id}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    modifier = Modifier.padding(safeContentPadding(tokens.pillShape, StudioSpacing.sm)),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = tokens.accent,
                                        modifier = Modifier.size(dimens.iconSizeSmall)
                                    )
                                    Text(
                                        text = opt.displayName,
                                        style = compactType.tabOrChip,
                                        color = tokens.textPrimary,
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
    }
}
