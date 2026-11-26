package com.example.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
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
import com.example.termux.AppCatalog
import com.example.termux.DistroPaths
import com.example.termux.TermuxPaths
import com.example.termux.TermuxSessionBackend
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme

/** Settings > Ubuntu & Desktop: install common apps inside Ubuntu with one tap. */
@Composable
fun AppsSettingsSection(modifier: Modifier = Modifier) {
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
    val rootfs = DistroPaths.rootfs(paths.prefix, distro)

    SettingsSection(
        title = stringResource(R.string.apps_section_title),
        modifier = modifier.testTag("apps_section")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)) {
            Text(
                text = stringResource(
                    if (ubuntuInstalled) R.string.apps_intro else R.string.apps_needs_ubuntu
                ),
                style = compactType.body,
                color = tokens.textSecondary
            )
        }

        AppCatalog.apps.forEach { linuxApp ->
            val installed = remember(tick, ubuntuInstalled) {
                ubuntuInstalled && AppCatalog.isInstalled(rootfs, linuxApp)
            }
            SettingsActionRow(
                icon = if (installed) Icons.Default.CheckCircle else Icons.Default.Download,
                title = "${linuxApp.name} (~${linuxApp.approxMb} MB)" + if (installed) " ✓" else "",
                description = stringResource(linuxApp.descRes),
                onClick = {
                    try {
                        AppCatalog.writeScript(rootfs, linuxApp)
                        val title = "apt-${linuxApp.id}"
                        val handle = backend.createSession(title)
                        handle.rename(title)
                        handle.write(AppCatalog.installCommand(distro, linuxApp).toByteArray(Charsets.UTF_8))
                        handle.resize(80, 24)
                        Toast.makeText(context, R.string.distro_session_opened, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "error", Toast.LENGTH_LONG).show()
                    }
                },
                enabled = ready && ubuntuInstalled,
                trailingIcon = null,
                modifier = Modifier.testTag("app_${linuxApp.id}")
            )
        }
    }
}
