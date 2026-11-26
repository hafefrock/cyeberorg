package com.example.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme

@Composable
fun SettingsHomeScreen(
    state: SettingsUiState,
    selectedGroup: SettingsGroup?,
    onSelectGroup: (SettingsGroup) -> Unit,
    modifier: Modifier = Modifier,
    groups: List<SettingsGroup> = visibleSettingsGroups()
) {
    val dimens = StudioTheme.dimens
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_home_list"),
        contentPadding = PaddingValues(StudioSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)
    ) {
        items(groups, key = { it.name }) { group ->
            val summary = SettingsSummaries.forGroup(
                group = group,
                settings = state.settings,
                snapshot = state.keySnapshot,
                vaultStatus = state.vaultStatus
            ).asString()

            SettingsGroupRow(
                icon = group.icon,
                title = stringResource(group.titleRes),
                summary = summary,
                selected = selectedGroup == group,
                onClick = { onSelectGroup(group) },
                modifier = Modifier
                    .padding(bottom = StudioSpacing.xxs)
                    .testTag("settings_group_${group.testTagSuffix}")
            )
        }
    }
}
