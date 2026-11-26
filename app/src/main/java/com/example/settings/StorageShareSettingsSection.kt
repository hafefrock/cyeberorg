package com.example.settings

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.example.R
import com.example.termux.LinuxPrefs

/** Settings > Ubuntu & Desktop: show the phone's files inside Ubuntu (~/Android). */
@Composable
fun StorageShareSettingsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(LinuxPrefs.shareStorage(context)) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            LinuxPrefs.setShareStorage(context, true)
            enabled = true
        } else {
            Toast.makeText(context, R.string.storage_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    SettingsSection(
        title = stringResource(R.string.storage_section_title),
        modifier = modifier.testTag("storage_section")
    ) {
        SettingsToggleRow(
            icon = Icons.Default.Folder,
            title = stringResource(R.string.storage_share_title),
            description = stringResource(R.string.storage_share_desc),
            checked = enabled,
            onCheckedChange = { on ->
                if (!on) {
                    LinuxPrefs.setShareStorage(context, false)
                    enabled = false
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        LinuxPrefs.setShareStorage(context, true)
                        enabled = true
                    } else {
                        launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
            },
            switchTestTag = "share_storage_switch"
        )
    }
}
