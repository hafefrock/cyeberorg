package com.example.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.BuildConfig
import com.example.R
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme

@Composable
fun AboutScreen(
    viewModel: SettingsViewModel,
    onOpenOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    var showConfirmReopenOnboarding by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_about_screen"),
        contentPadding = PaddingValues(StudioSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
    ) {
        item(key = "about_info") {
            SettingsSection(title = stringResource(R.string.settings_group_about)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("about_version_row"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.md)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = tokens.accent,
                        modifier = Modifier.size(dimens.navIconSize)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontFamily = tokens.headingFont,
                            style = compactType.screenTitle,
                            color = tokens.textPrimary
                        )
                        Text(
                            text = stringResource(R.string.settings_about_app_desc),
                            style = compactType.body,
                            color = tokens.textSecondary
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.xxs)) {
                    Text(
                        text = stringResource(R.string.settings_about_version_title),
                        style = compactType.tabOrChip,
                        color = tokens.textSecondary
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_about_version_value,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE
                        ),
                        fontFamily = JetBrainsMonoFamily,
                        style = compactType.monoPreview,
                        color = tokens.textPrimary
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.xxs)) {
                    Text(
                        text = stringResource(R.string.settings_about_package_title),
                        style = compactType.tabOrChip,
                        color = tokens.textSecondary
                    )
                    Text(
                        text = BuildConfig.APPLICATION_ID,
                        fontFamily = JetBrainsMonoFamily,
                        style = compactType.monoPreview,
                        color = tokens.textPrimary
                    )
                }
            }
        }

        item(key = "about_danger_zone") {
            DangerZoneSection {
                SettingsActionRow(
                    icon = Icons.Default.RestartAlt,
                    title = stringResource(R.string.settings_reopen_onboarding),
                    description = stringResource(R.string.settings_reopen_onboarding_desc),
                    isDestructive = true,
                    onClick = { showConfirmReopenOnboarding = true },
                    modifier = Modifier.testTag("reopen_onboarding_button")
                )
            }
        }
    }

    if (showConfirmReopenOnboarding) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.settings_reopen_onboarding_confirm_title),
            message = stringResource(R.string.settings_reopen_onboarding_confirm_desc),
            confirmLabel = stringResource(R.string.settings_reopen_onboarding_confirm_btn),
            onConfirm = {
                showConfirmReopenOnboarding = false
                viewModel.reopenOnboarding()
                onOpenOnboarding()
            },
            onDismiss = { showConfirmReopenOnboarding = false },
            dialogTestTag = "confirm_reopen_onboarding_dialog",
            confirmTestTag = "confirm_reopen_onboarding_button",
            cancelTestTag = "cancel_reopen_onboarding_button"
        )
    }
}
