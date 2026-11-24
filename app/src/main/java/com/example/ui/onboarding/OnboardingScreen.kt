package com.example.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.AppLanguage
import com.example.data.GeminiModels
import com.example.data.VaultStatus
import com.example.settings.GeminiModelChoiceList
import com.example.settings.SettingsViewModel
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.ThemeOptionPreviewCard
import com.example.ui.theme.studioGlow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    viewModel: SettingsViewModel,
    onCompleted: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    val tokens = StudioTheme.tokens
    val isVaultReady = state.vaultStatus is VaultStatus.Ready

    var step by remember { mutableIntStateOf(1) }
    var keyLabel by remember { mutableStateOf("") }
    var rawKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                if (tokens.canvasGradientColors.size >= 2) {
                    drawRect(brush = Brush.verticalGradient(colors = tokens.canvasGradientColors))
                } else {
                    drawRect(tokens.baseCanvas)
                }
                if (tokens.glowRadius > 0.dp) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(tokens.accent.copy(alpha = 0.14f), Color.Transparent),
                            center = Offset(size.width * 0.25f, size.height * 0.15f),
                            radius = size.maxDimension * 0.7f
                        )
                    )
                }
            }
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .padding(tokens.spacing(StudioSpacing.lg))
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .testTag("onboarding_card"),
            color = tokens.layer1,
            shape = tokens.cardShape,
            border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(tokens.spacing(StudioSpacing.xl)),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing(StudioSpacing.lg))
            ) {
                // Header badge & step indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = tokens.accentMuted,
                        shape = tokens.pillShape,
                        border = BorderStroke(tokens.borderWidth, tokens.accent.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = tokens.accent,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.onboarding_badge),
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.accent,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.onboarding_step_indicator, step, 4),
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary
                    )
                }

                AnimatedContent(targetState = step, label = "onboardingStep") { currentStep ->
                    when (currentStep) {
                        1 -> {
                            Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = tokens.accent,
                                    modifier = Modifier.size(30.dp)
                                )
                                Text(
                                    text = stringResource(R.string.onboarding_step1_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = tokens.textPrimary
                                )
                                Text(
                                    text = stringResource(R.string.onboarding_step1_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.textSecondary
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing(StudioSpacing.sm))) {
                                    AppLanguage.entries.forEach { lang ->
                                        val selected = settings.appLanguage == lang
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(tokens.smallShape)
                                                .clickable { viewModel.setAppLanguage(lang) }
                                                .testTag("onboarding_lang_${lang.id}"),
                                            color = if (selected) tokens.accentMuted else tokens.layer2,
                                            shape = tokens.smallShape,
                                            border = if (selected) {
                                                BorderStroke(tokens.borderWidth, tokens.focusRingColor)
                                            } else {
                                                BorderStroke(tokens.borderWidth, tokens.thinBorderBrush)
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(StudioSpacing.md),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = stringResource(lang.labelRes),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = if (selected) tokens.accent else tokens.textPrimary
                                                )
                                                if (selected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = tokens.accent
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing(StudioSpacing.md))) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = null,
                                    tint = tokens.accent,
                                    modifier = Modifier.size(30.dp)
                                )
                                Text(
                                    text = tokens.formatHeading(stringResource(R.string.onboarding_step2_title)),
                                    fontFamily = tokens.headingFont,
                                    letterSpacing = tokens.headingLetterSpacing,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = tokens.textPrimary
                                )
                                Text(
                                    text = stringResource(R.string.onboarding_step2_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.textSecondary
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing(StudioSpacing.sm))) {
                                    AppThemePreset.entries.forEach { preset ->
                                        ThemeOptionPreviewCard(
                                            preset = preset,
                                            selected = settings.appTheme == preset,
                                            reduceEffects = settings.reduceEffects,
                                            onSelect = { viewModel.setAppTheme(preset) }
                                        )
                                    }
                                }
                            }
                        }

                        3 -> {
                            Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = tokens.accent,
                                    modifier = Modifier.size(30.dp)
                                )
                                Text(
                                    text = stringResource(R.string.onboarding_step3_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = tokens.textPrimary
                                )
                                Text(
                                    text = stringResource(R.string.onboarding_step3_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.textSecondary
                                )

                                state.vaultErrorMessage?.let { err ->
                                    Text(
                                        text = err.asString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.error
                                    )
                                }

                                if (settings.apiKeys.isNotEmpty()) {
                                    Surface(
                                        color = tokens.success.copy(alpha = 0.14f),
                                        shape = tokens.smallShape,
                                        border = BorderStroke(tokens.borderWidth, tokens.success.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = stringResource(
                                                R.string.onboarding_key_added_badge,
                                                settings.apiKeys.last().label
                                            ),
                                            fontFamily = JetBrainsMonoFamily,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = tokens.success,
                                            modifier = Modifier.padding(StudioSpacing.md)
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = keyLabel,
                                    onValueChange = { keyLabel = it },
                                    label = { Text(stringResource(R.string.dialog_add_key_label)) },
                                    singleLine = true,
                                    enabled = isVaultReady,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("onboarding_key_label")
                                )
                                OutlinedTextField(
                                    value = rawKey,
                                    onValueChange = { rawKey = it },
                                    label = { Text(stringResource(R.string.dialog_add_key_secret)) },
                                    singleLine = true,
                                    enabled = isVaultReady,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        autoCorrectEnabled = false,
                                        capitalization = KeyboardCapitalization.None
                                    ),
                                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { showKey = !showKey }) {
                                            Icon(
                                                imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = stringResource(
                                                    if (showKey) R.string.cd_hide_key else R.string.cd_show_key
                                                )
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("onboarding_key_secret")
                                )
                            }
                        }

                        else -> {
                            val selectedModelId = GeminiModels.normalizeSelectedModel(settings.selectedModel)
                            Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = tokens.accent,
                                    modifier = Modifier.size(30.dp)
                                )
                                Text(
                                    text = stringResource(R.string.onboarding_step4_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = tokens.textPrimary
                                )
                                Text(
                                    text = stringResource(R.string.onboarding_step4_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.textSecondary
                                )

                                GeminiModelChoiceList(
                                    selectedModelId = selectedModelId,
                                    onSelectModel = viewModel::setSelectedModel,
                                    testTagPrefix = "onboarding_model_"
                                )
                            }
                        }
                    }
                }

                // Bottom stepper controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step > 1) {
                        OutlinedButton(
                            onClick = { step-- },
                            shape = tokens.buttonShape,
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(stringResource(R.string.action_back))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)) {
                        if (step == 3 || step == 4) {
                            TextButton(
                                onClick = {
                                    if (step == 3) {
                                        step = 4
                                    } else {
                                        viewModel.completeOnboarding()
                                        onCompleted()
                                    }
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("onboarding_skip_button")
                            ) {
                                Text(stringResource(R.string.action_skip), color = tokens.textSecondary)
                            }
                        }

                        Box(
                            modifier = Modifier.studioGlow(
                                color = tokens.glowColor,
                                radius = tokens.glowRadius,
                                shape = tokens.buttonShape
                            )
                        ) {
                            Button(
                                onClick = {
                                    when {
                                        step < 3 -> step++
                                        step == 3 -> {
                                            if (rawKey.isNotBlank() && isVaultReady) {
                                                viewModel.addApiKey(keyLabel, rawKey)
                                                rawKey = ""
                                            }
                                            step = 4
                                        }
                                        else -> {
                                            viewModel.completeOnboarding()
                                            onCompleted()
                                        }
                                    }
                                },
                                shape = tokens.buttonShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tokens.primaryButtonContainer,
                                    contentColor = tokens.primaryButtonContent
                                ),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("onboarding_primary_button")
                            ) {
                                Text(
                                    text = stringResource(
                                        if (step < 4) R.string.action_continue else R.string.action_finish
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
