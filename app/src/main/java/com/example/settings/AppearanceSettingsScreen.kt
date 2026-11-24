package com.example.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MotionPhotosOff
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.TerminalFontConfig
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.ThemeOptionPreviewCard
import com.example.ui.theme.safeContentPadding

@Composable
fun AppearanceSettingsScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val settings = state.settings
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_appearance_screen"),
        contentPadding = PaddingValues(StudioSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
    ) {
        item(key = "theme_picker") {
            SettingsSection(title = stringResource(R.string.settings_sec_theme)) {
                Text(
                    text = stringResource(R.string.settings_app_theme),
                    style = compactType.body,
                    color = tokens.textSecondary
                )
                Column(verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)) {
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

        item(key = "effects_and_font") {
            SettingsSection(title = stringResource(R.string.settings_sec_effects_font)) {
                SettingsToggleRow(
                    icon = Icons.Default.MotionPhotosOff,
                    title = stringResource(R.string.settings_reduce_effects_title),
                    description = stringResource(R.string.settings_reduce_effects_desc),
                    checked = settings.reduceEffects,
                    onCheckedChange = viewModel::setReduceEffects,
                    switchTestTag = "reduce_effects_switch"
                )

                val displayFontSizeSp = TerminalFontConfig.normalizeFontSizeSp(settings.fontSizeSp)
                Text(
                    text = stringResource(
                        R.string.settings_font_size,
                        TerminalFontConfig.formatFontSizeValue(displayFontSizeSp)
                    ),
                    fontFamily = JetBrainsMonoFamily,
                    style = compactType.cardTitle,
                    color = tokens.textPrimary
                )
                Slider(
                    value = displayFontSizeSp,
                    onValueChange = { viewModel.setFontSizeSp(TerminalFontConfig.normalizeFontSizeSp(it)) },
                    valueRange = TerminalFontConfig.MIN_FONT_SIZE_SP..TerminalFontConfig.MAX_FONT_SIZE_SP,
                    steps = 39,
                    modifier = Modifier.testTag("font_size_slider")
                )
                Surface(
                    color = settings.terminalColorScheme.background,
                    shape = tokens.smallShape,
                    border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.settings_font_preview, tokens.promptGlyph),
                        fontFamily = com.example.ui.theme.terminalComposeFontFamily(settings.terminalFontId),
                        fontSize = displayFontSizeSp.sp,
                        color = settings.terminalColorScheme.promptColor,
                        modifier = Modifier.padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV))
                    )
                }
            }
        }
    }
}
