package com.example.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.ui.theme.StudioSpacing

/** Settings > Ubuntu & Desktop: Linux environment, Ubuntu (proot-distro) and the XFCE desktop in one place. */
@Composable
fun LinuxSettingsScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_linux_screen"),
        contentPadding = PaddingValues(StudioSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
    ) {
        item(key = "bootstrap_install") {
            BootstrapSettingsSection()
        }
        item(key = "ubuntu_and_desktop") {
            DistroSettingsSection()
        }
    }
}
