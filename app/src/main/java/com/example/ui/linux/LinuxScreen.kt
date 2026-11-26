package com.example.ui.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.example.R
import com.example.settings.LinuxSettingsScreen
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme

/** Lets the sidebar open the Ubuntu & Desktop page without threading a callback through every screen. */
val LocalOpenLinux = compositionLocalOf<() -> Unit> { {} }

/** Ubuntu & Desktop page, opened from the main sidebar. */
@Composable
fun LinuxScreen(onNavigateBack: () -> Unit) {
    val tokens = StudioTheme.tokens
    val compactType = StudioTheme.compactTypography
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.baseCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("linux_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StudioSpacing.md, vertical = StudioSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_cancel),
                tint = tokens.textPrimary,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onNavigateBack)
                    .padding(StudioSpacing.sm)
                    .testTag("linux_back")
            )
            Text(
                text = stringResource(R.string.settings_group_linux),
                style = compactType.screenTitle,
                color = tokens.textPrimary
            )
        }
        LinuxSettingsScreen(modifier = Modifier.weight(1f))
    }
}
