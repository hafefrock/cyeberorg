package com.example.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.termux.BootstrapInstaller
import com.example.termux.BootstrapProgress
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Settings > Terminal: install / reinstall the Termux base system (bootstrap). */
@Composable
fun BootstrapSettingsSection(modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val installer = remember { BootstrapInstaller(appContext) }
    val scope = rememberCoroutineScope()
    val tokens = StudioTheme.tokens
    val compactType = StudioTheme.compactTypography

    var installed by remember { mutableStateOf(installer.isInstalled()) }
    var progress by remember { mutableStateOf<BootstrapProgress>(BootstrapProgress.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    val running = job?.isActive == true &&
        progress !is BootstrapProgress.Done && progress !is BootstrapProgress.Failed

    SettingsSection(
        title = stringResource(R.string.bootstrap_section_title),
        modifier = modifier.testTag("bootstrap_section")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)) {
            Text(
                text = stringResource(
                    if (installed) R.string.bootstrap_status_installed else R.string.bootstrap_status_not_installed
                ),
                style = compactType.cardTitle,
                color = if (installed) tokens.accent else tokens.textPrimary
            )
            Text(
                text = stringResource(
                    if (installed) R.string.bootstrap_desc_installed else R.string.bootstrap_desc_not_installed
                ),
                style = compactType.body,
                color = tokens.textSecondary
            )

            when (val p = progress) {
                is BootstrapProgress.Downloading -> {
                    val mb = 1024L * 1024L
                    Text(
                        text = stringResource(
                            R.string.bootstrap_progress_downloading,
                            (p.bytes / mb).toInt(),
                            (p.total / mb).toInt()
                        ),
                        style = compactType.body,
                        color = tokens.textPrimary
                    )
                    LinearProgressIndicator(
                        progress = { if (p.total > 0) (p.bytes.toFloat() / p.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is BootstrapProgress.Verifying -> {
                    Text(stringResource(R.string.bootstrap_progress_verifying), style = compactType.body, color = tokens.textPrimary)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is BootstrapProgress.Extracting -> {
                    Text(
                        text = stringResource(R.string.bootstrap_progress_extracting, p.done, p.total),
                        style = compactType.body,
                        color = tokens.textPrimary
                    )
                    LinearProgressIndicator(
                        progress = { if (p.total > 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is BootstrapProgress.Finalizing -> {
                    Text(stringResource(R.string.bootstrap_progress_finalizing), style = compactType.body, color = tokens.textPrimary)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is BootstrapProgress.Done -> {
                    Text(stringResource(R.string.bootstrap_done), style = compactType.body, color = tokens.accent)
                }
                is BootstrapProgress.Failed -> {
                    Text(
                        text = stringResource(R.string.bootstrap_failed, p.message),
                        style = compactType.body,
                        color = tokens.error,
                        modifier = Modifier.testTag("bootstrap_error")
                    )
                }
                is BootstrapProgress.Idle -> Unit
            }
        }

        if (running) {
            SettingsActionRow(
                icon = Icons.Default.Close,
                title = stringResource(R.string.bootstrap_action_cancel),
                onClick = {
                    job?.cancel()
                    progress = BootstrapProgress.Idle
                },
                trailingIcon = null,
                modifier = Modifier.testTag("bootstrap_cancel")
            )
        } else {
            SettingsActionRow(
                icon = Icons.Default.Download,
                title = stringResource(
                    if (installed) R.string.bootstrap_action_reinstall else R.string.bootstrap_action_install
                ),
                onClick = {
                    progress = BootstrapProgress.Idle
                    job = scope.launch {
                        installer.install().collect { p ->
                            progress = p
                            if (p is BootstrapProgress.Done) installed = installer.isInstalled()
                        }
                    }
                },
                trailingIcon = null,
                modifier = Modifier.testTag("bootstrap_install")
            )
        }
    }
}
