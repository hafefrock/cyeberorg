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
import com.example.termux.DistroPaths
import com.example.termux.LinuxPrefs
import com.example.termux.TermuxPaths
import com.example.termux.TermuxSessionBackend
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import java.io.File

/** Settings > Ubuntu & Desktop: Ubuntu (proot-distro) followed by the desktop section. */
@Composable
fun DistroSettingsSection(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)) {
        UbuntuSettingsSection()
        DesktopSettingsSection()
    }
}

/** Install, open and remove Ubuntu via proot-distro (commands run in a new session). */
@Composable
private fun UbuntuSettingsSection(modifier: Modifier = Modifier) {
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
    val installed = remember(tick) { DistroPaths.isInstalled(paths.prefix, DistroPaths.UBUNTU) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun runInNewSession(command: String) {
        val handle = backend.createSession(DistroPaths.UBUNTU)
        handle.rename(DistroPaths.UBUNTU)
        handle.write(command.toByteArray(Charsets.UTF_8))
        handle.resize(80, 24)
        Toast.makeText(context, R.string.distro_session_opened, Toast.LENGTH_LONG).show()
    }

    fun openUbuntu() {
        val share = LinuxPrefs.effectiveShareStorage(context)
        if (share) {
            runCatching { File(DistroPaths.rootfs(paths.prefix, DistroPaths.UBUNTU), "root/Android").mkdirs() }
        }
        runInNewSession(DistroPaths.loginCommand(DistroPaths.UBUNTU, share))
    }

    SettingsSection(
        title = stringResource(R.string.distro_section_title),
        modifier = modifier.testTag("distro_section")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)) {
            Text(
                text = stringResource(
                    if (installed) R.string.distro_status_installed else R.string.distro_status_not_installed
                ),
                style = compactType.cardTitle,
                color = if (installed) tokens.accent else tokens.textPrimary
            )
            Text(
                text = stringResource(
                    when {
                        !ready -> R.string.distro_not_ready
                        installed -> R.string.distro_desc_installed
                        else -> R.string.distro_desc_not_installed
                    }
                ),
                style = compactType.body,
                color = tokens.textSecondary
            )
        }

        if (installed) {
            SettingsActionRow(
                icon = Icons.Default.PlayArrow,
                title = stringResource(R.string.distro_action_login),
                onClick = { openUbuntu() },
                enabled = ready,
                trailingIcon = null,
                modifier = Modifier.testTag("distro_login")
            )
            SettingsActionRow(
                icon = Icons.Default.Delete,
                title = stringResource(R.string.distro_action_remove),
                onClick = { confirmRemove = true },
                enabled = ready,
                isDestructive = true,
                trailingIcon = null,
                modifier = Modifier.testTag("distro_remove")
            )
        } else {
            SettingsActionRow(
                icon = Icons.Default.Download,
                title = stringResource(R.string.distro_action_install),
                onClick = { runInNewSession(DistroPaths.installCommand(DistroPaths.UBUNTU)) },
                enabled = ready,
                trailingIcon = null,
                modifier = Modifier.testTag("distro_install")
            )
        }
    }

    if (confirmRemove) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.distro_remove_title),
            message = stringResource(R.string.distro_remove_message),
            confirmLabel = stringResource(R.string.distro_remove_confirm),
            onConfirm = {
                confirmRemove = false
                runInNewSession(DistroPaths.removeCommand(DistroPaths.UBUNTU))
            },
            onDismiss = { confirmRemove = false },
            dialogTestTag = "distro_remove_dialog",
            confirmTestTag = "distro_remove_confirm",
            cancelTestTag = "distro_remove_cancel"
        )
    }
}
