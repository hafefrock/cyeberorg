package com.example.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.CyberorgApp
import com.example.R
import com.example.termux.DesktopSetup
import com.example.termux.DistroPaths
import com.example.termux.TermuxPaths
import com.example.termux.TermuxSessionBackend
import com.example.ui.desktop.DesktopViewerDialog
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme

/** Settings > Terminal: install the XFCE desktop inside Ubuntu, start it and view it in-app over VNC. */
@Composable
fun DesktopSettingsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as CyberorgApp
    val backend = app.appContainer.terminalBackend
    val paths = remember { TermuxPaths(context.filesDir) }
    val tokens = StudioTheme.tokens
    val compactType = StudioTheme.compactTypography
    val lifecycleOwner = LocalLifecycleOwner.current

    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val ready = backend is TermuxSessionBackend
    val distro = DistroPaths.UBUNTU
    val ubuntuInstalled = remember(tick) { DistroPaths.isInstalled(paths.prefix, distro) }
    val desktopInstalled = remember(tick) {
        ubuntuInstalled && DesktopSetup.isInstalled(DistroPaths.rootfs(paths.prefix, distro))
    }
    var showViewer by remember { mutableStateOf(false) }

    fun runInNewSession(title: String, command: String) {
        val handle = backend.createSession(title)
        handle.rename(title)
        handle.write(command.toByteArray(Charsets.UTF_8))
    }

    fun prepare(): Boolean = try {
        DesktopSetup.writeScripts(context, DistroPaths.rootfs(paths.prefix, distro))
        true
    } catch (e: Exception) {
        Toast.makeText(context, e.message ?: "error", Toast.LENGTH_LONG).show()
        false
    }

    SettingsSection(
        title = stringResource(R.string.desktop_section_title),
        modifier = modifier.testTag("desktop_section")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)) {
            Text(
                text = stringResource(
                    if (desktopInstalled) R.string.desktop_status_installed else R.string.desktop_status_not_installed
                ),
                style = compactType.cardTitle,
                color = if (desktopInstalled) tokens.accent else tokens.textPrimary
            )
            Text(
                text = stringResource(
                    when {
                        !ubuntuInstalled -> R.string.desktop_needs_ubuntu
                        desktopInstalled -> R.string.desktop_desc_installed
                        else -> R.string.desktop_desc_not_installed
                    }
                ),
                style = compactType.body,
                color = tokens.textSecondary
            )
        }

        if (ubuntuInstalled && !desktopInstalled) {
            SettingsActionRow(
                icon = Icons.Default.Download,
                title = stringResource(R.string.desktop_action_install),
                onClick = {
                    if (prepare()) {
                        runInNewSession("desktop-setup", DesktopSetup.installCommand(distro))
                        Toast.makeText(context, R.string.distro_session_opened, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = ready,
                trailingIcon = null,
                modifier = Modifier.testTag("desktop_install")
            )
        }
        if (desktopInstalled) {
            SettingsActionRow(
                icon = Icons.Default.PlayArrow,
                title = stringResource(R.string.desktop_action_start),
                onClick = {
                    if (prepare()) {
                        runInNewSession("desktop", DesktopSetup.startCommand(distro))
                        showViewer = true
                    }
                },
                enabled = ready,
                trailingIcon = null,
                modifier = Modifier.testTag("desktop_start")
            )
            SettingsActionRow(
                icon = Icons.Default.PlayArrow,
                title = stringResource(R.string.desktop_action_open),
                onClick = { showViewer = true },
                enabled = ready,
                trailingIcon = null,
                modifier = Modifier.testTag("desktop_open")
            )
            SettingsActionRow(
                icon = Icons.Default.Delete,
                title = stringResource(R.string.desktop_action_stop),
                onClick = { runInNewSession("desktop-stop", DesktopSetup.stopCommand(distro)) },
                enabled = ready,
                isDestructive = true,
                trailingIcon = null,
                modifier = Modifier.testTag("desktop_stop")
            )
        }
    }

    if (showViewer) {
        DesktopViewerDialog(onDismiss = { showViewer = false })
    }
}
