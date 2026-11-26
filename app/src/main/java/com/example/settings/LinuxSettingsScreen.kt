package com.example.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.termux.BootstrapInstaller
import com.example.termux.DesktopSetup
import com.example.termux.DistroPaths
import com.example.termux.TermuxPaths
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme

/**
 * Ubuntu & Desktop page (opened from the sidebar): a short status checklist first, then the steps.
 * Rarely needed parts are folded away so the page stays short.
 */
@Composable
fun LinuxSettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val paths = remember { TermuxPaths(context.filesDir) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val baseOk = remember(tick) { BootstrapInstaller(context.applicationContext).isInstalled() }
    val ubuntuOk = remember(tick) { DistroPaths.isInstalled(paths.prefix, DistroPaths.UBUNTU) }
    val desktopOk = remember(tick) {
        ubuntuOk && DesktopSetup.isInstalled(DistroPaths.rootfs(paths.prefix, DistroPaths.UBUNTU))
    }

    var showBase by rememberSaveable { mutableStateOf(!baseOk) }
    var showFiles by rememberSaveable { mutableStateOf(false) }
    var showApps by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_linux_screen"),
        contentPadding = PaddingValues(StudioSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
    ) {
        item(key = "overview") {
            LinuxOverviewCard(baseOk = baseOk, ubuntuOk = ubuntuOk, desktopOk = desktopOk)
        }
        item(key = "ubuntu_and_desktop") {
            DistroSettingsSection()
        }
        item(key = "toggle_apps") {
            FoldToggle(
                expanded = showApps,
                nameRes = R.string.linux_group_apps,
                tag = "linux_toggle_apps",
                onToggle = { showApps = !showApps }
            )
        }
        if (showApps) {
            item(key = "desktop_apps") { AppsSettingsSection() }
        }
        item(key = "toggle_files") {
            FoldToggle(
                expanded = showFiles,
                nameRes = R.string.linux_group_files,
                tag = "linux_toggle_files",
                onToggle = { showFiles = !showFiles }
            )
        }
        if (showFiles) {
            item(key = "phone_files") { StorageShareSettingsSection() }
        }
        item(key = "toggle_base") {
            FoldToggle(
                expanded = showBase,
                nameRes = R.string.linux_group_base,
                tag = "linux_toggle_base",
                onToggle = { showBase = !showBase }
            )
        }
        if (showBase) {
            item(key = "bootstrap_install") { BootstrapSettingsSection() }
        }
    }
}

@Composable
private fun FoldToggle(expanded: Boolean, nameRes: Int, tag: String, onToggle: () -> Unit) {
    val name = stringResource(nameRes)
    SettingsActionRow(
        icon = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        title = stringResource(if (expanded) R.string.linux_hide else R.string.linux_show, name),
        onClick = onToggle,
        trailingIcon = null,
        modifier = Modifier.testTag(tag)
    )
}

@Composable
private fun LinuxOverviewCard(baseOk: Boolean, ubuntuOk: Boolean, desktopOk: Boolean) {
    val tokens = StudioTheme.tokens
    val compactType = StudioTheme.compactTypography
    val steps = listOf(
        R.string.linux_step_base to baseOk,
        R.string.linux_step_ubuntu to ubuntuOk,
        R.string.linux_step_desktop to desktopOk
    )
    val hint = when {
        !baseOk -> R.string.linux_next_base
        !ubuntuOk -> R.string.linux_next_ubuntu
        !desktopOk -> R.string.linux_next_desktop
        else -> R.string.linux_next_ready
    }
    SettingsSection(
        title = stringResource(R.string.linux_overview_title),
        modifier = Modifier.testTag("linux_overview")
    ) {
        steps.forEachIndexed { index, (labelRes, ok) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
            ) {
                Icon(
                    imageVector = if (ok) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (ok) tokens.accent else tokens.textMuted,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                Text(
                    text = "${index + 1}. ${stringResource(labelRes)}",
                    style = compactType.body,
                    color = if (ok) tokens.textPrimary else tokens.textSecondary
                )
            }
        }
        Text(
            text = stringResource(hint),
            style = compactType.body,
            color = tokens.accent
        )
    }
}
