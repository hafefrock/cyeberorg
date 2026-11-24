package com.example.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.AppLanguage
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme

@Composable
fun LanguageSettingsScreen(
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
            .testTag("settings_language_screen"),
        contentPadding = PaddingValues(StudioSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
    ) {
        item(key = "language_picker") {
            SettingsSection(title = stringResource(R.string.settings_group_language)) {
                Text(
                    text = stringResource(R.string.settings_language_desc),
                    style = compactType.body,
                    color = tokens.textSecondary
                )
                Column(verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)) {
                    AppLanguage.entries.forEach { lang ->
                        SettingsChoiceRow(
                            title = stringResource(lang.labelRes),
                            selected = settings.appLanguage == lang,
                            onClick = { viewModel.setAppLanguage(lang) },
                            modifier = Modifier.testTag("lang_${lang.id}")
                        )
                    }
                }
            }
        }
    }
}
