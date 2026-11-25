package com.example.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.termux.PackageUpdateChecker
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme

/** Shown above the terminal when `pkg` reports upgradable packages; one tap runs the upgrade. */
@Composable
fun PackageUpdateChip(
    onRunCommand: (ByteArray) -> Unit,
    modifier: Modifier = Modifier
) {
    val st by PackageUpdateChecker.state.collectAsStateWithLifecycle()
    if (st.upgradable <= 0) return
    val context = LocalContext.current
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography

    Surface(
        color = tokens.accentMuted,
        shape = tokens.smallShape,
        border = BorderStroke(tokens.borderWidth, tokens.focusRingColor),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = StudioSpacing.sm, vertical = StudioSpacing.xs)
            .testTag("pkg_update_chip")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.minTouchTarget)
                .clickable(role = Role.Button) {
                    onRunCommand("pkg update -y && pkg upgrade -y\r".toByteArray(Charsets.UTF_8))
                    PackageUpdateChecker.markHandled(context)
                }
                .padding(horizontal = StudioSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = tokens.accent,
                modifier = Modifier.size(dimens.iconSize)
            )
            Text(
                text = stringResource(R.string.pkg_update_chip_text, st.upgradable),
                style = compactType.cardTitle,
                color = tokens.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.pkg_update_chip_action),
                style = compactType.tabOrChip,
                color = tokens.accent
            )
        }
    }
}
