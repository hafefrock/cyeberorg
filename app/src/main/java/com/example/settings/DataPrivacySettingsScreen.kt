package com.example.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme

@Composable
fun DataPrivacySettingsScreen(
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val compactType = StudioTheme.compactTypography
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_data_privacy_screen"),
        contentPadding = PaddingValues(StudioSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
    ) {
        item(key = "data_privacy_info") {
            SettingsSection(title = stringResource(R.string.settings_group_data)) {
                Text(
                    text = stringResource(R.string.settings_data_empty),
                    style = compactType.body,
                    color = tokens.textSecondary
                )
            }
        }
    }
}
