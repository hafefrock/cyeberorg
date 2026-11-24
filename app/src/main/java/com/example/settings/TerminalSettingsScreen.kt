package com.example.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.ExtraKeysLayout
import com.example.data.LongCommandNotificationConfig
import com.example.data.TerminalFontConfig
import com.example.terminal.LongCommandNotificationStatus
import com.example.terminal.LongCommandNotifier
import com.example.terminal.NotificationPermissionState
import com.example.terminal.TerminalFontRegistry
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioTouchTarget
import com.example.ui.theme.terminalComposeFontFamily
import kotlin.math.roundToInt

@Composable
fun TerminalSettingsScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val settings = state.settings
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionState by remember(context) {
        mutableStateOf(LongCommandNotifier.getPermissionState(context))
    }
    var showPermissionRationaleDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionState = LongCommandNotifier.getPermissionState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionState = LongCommandNotifier.resolvePermissionState(
            sdkInt = Build.VERSION.SDK_INT,
            hasPostNotificationsPermission = granted
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_terminal_screen"),
        contentPadding = PaddingValues(StudioSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
    ) {
        item(key = "extra_keys_layout") {
            SettingsSection(title = stringResource(R.string.settings_extra_keys_layout)) {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)) {
                    ExtraKeysLayout.entries.forEach { layout ->
                        SettingsChoiceRow(
                            title = stringResource(layout.labelRes),
                            description = stringResource(layout.descriptionRes),
                            selected = settings.extraKeysLayout == layout,
                            onClick = { viewModel.setExtraKeysLayout(layout) },
                            useMonospaceTitle = true,
                            modifier = Modifier.testTag("extra_layout_${layout.id}")
                        )
                    }
                }
            }
        }

        item(key = "terminal_font") {
            val activeFont = TerminalFontRegistry.fromId(settings.terminalFontId)
            val previewFontFamily = terminalComposeFontFamily(activeFont.id)
            val previewFontSizeSp = TerminalFontConfig.normalizeFontSizeSp(settings.fontSizeSp)

            SettingsSection(title = stringResource(R.string.settings_sec_terminal_font)) {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)) {
                    TerminalFontRegistry.entries.forEach { fontOption ->
                        SettingsChoiceRow(
                            title = stringResource(fontOption.labelRes),
                            description = stringResource(fontOption.descriptionRes),
                            selected = activeFont.id == fontOption.id,
                            onClick = { viewModel.setTerminalFontId(fontOption.id) },
                            useMonospaceTitle = true,
                            modifier = Modifier.testTag("terminal_font_${fontOption.id}")
                        )
                    }
                    Surface(
                        color = settings.terminalColorScheme.background,
                        shape = tokens.smallShape,
                        border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("terminal_font_preview_card")
                    ) {
                        Text(
                            text = stringResource(R.string.settings_font_preview, tokens.promptGlyph),
                            fontFamily = previewFontFamily,
                            fontSize = previewFontSizeSp.sp,
                            color = settings.terminalColorScheme.promptColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                safeContentPadding(
                                    tokens.smallShape,
                                    dimens.cardPaddingH,
                                    dimens.cardPaddingV
                                )
                            )
                        )
                    }
                }
            }
        }

        item(key = "terminal_display") {
            SettingsSection(title = stringResource(R.string.settings_sec_terminal_display)) {
                SettingsToggleRow(
                    icon = Icons.Default.Fullscreen,
                    title = stringResource(R.string.settings_fullscreen_terminal_title),
                    description = stringResource(R.string.settings_fullscreen_terminal_desc),
                    checked = settings.fullscreenTerminal,
                    onCheckedChange = viewModel::setFullscreenTerminal,
                    switchTestTag = "fullscreen_terminal_switch"
                )
                SettingsToggleRow(
                    icon = Icons.Default.Visibility,
                    title = stringResource(R.string.settings_keep_screen_on_title),
                    description = stringResource(R.string.settings_keep_screen_on_desc),
                    checked = settings.keepScreenOn,
                    onCheckedChange = viewModel::setKeepScreenOn,
                    switchTestTag = "keep_screen_on_switch"
                )
            }
        }

        item(key = "long_command_notifications") {
            val status = LongCommandNotifier.resolveSettingStatus(
                enabled = settings.notifyLongCommands,
                permissionState = permissionState
            )
            val thresholdSec = LongCommandNotificationConfig.normalizeThresholdSeconds(
                settings.longCommandThresholdSeconds
            )

            SettingsSection(title = stringResource(R.string.settings_sec_long_command_notif)) {
                SettingsToggleRow(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.settings_notify_long_commands_title),
                    description = stringResource(R.string.settings_notify_long_commands_desc),
                    checked = settings.notifyLongCommands,
                    onCheckedChange = { enable ->
                        if (enable) {
                            viewModel.setNotifyLongCommands(true)
                            permissionState = LongCommandNotifier.getPermissionState(context)
                            if (permissionState == NotificationPermissionState.NEEDS_PERMISSION) {
                                showPermissionRationaleDialog = true
                            }
                        } else {
                            viewModel.setNotifyLongCommands(false)
                        }
                    },
                    switchTestTag = "notify_long_commands_switch"
                )

                if (settings.notifyLongCommands && status == LongCommandNotificationStatus.NEEDS_PERMISSION) {
                    Surface(
                        color = tokens.warning.copy(alpha = 0.12f),
                        shape = tokens.smallShape,
                        border = BorderStroke(tokens.borderWidth, tokens.warning.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("notif_status_needs_permission")
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                safeContentPadding(
                                    tokens.smallShape,
                                    dimens.cardPaddingH,
                                    dimens.cardPaddingV
                                )
                            ),
                            verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsOff,
                                    contentDescription = null,
                                    tint = tokens.warning,
                                    modifier = Modifier.size(dimens.iconSize)
                                )
                                Text(
                                    text = stringResource(R.string.settings_notify_needs_permission_title),
                                    style = compactType.cardTitle,
                                    color = tokens.warning
                                )
                            }
                            Text(
                                text = stringResource(R.string.settings_notify_needs_permission_desc),
                                style = compactType.body,
                                color = tokens.textSecondary
                            )
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                                OutlinedButton(
                                    onClick = { showPermissionRationaleDialog = true },
                                    shape = tokens.buttonShape,
                                    border = BorderStroke(tokens.borderWidth, tokens.warning.copy(alpha = 0.7f)),
                                    modifier = Modifier
                                        .height(dimens.controlHeight)
                                        .studioTouchTarget(
                                            minSize = dimens.minTouchTarget,
                                            onOutOfBoundsTap = { showPermissionRationaleDialog = true }
                                        )
                                        .testTag("notif_grant_permission_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_notify_grant_permission_btn),
                                        style = compactType.tabOrChip,
                                        color = tokens.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.settings_notify_threshold_seconds, thresholdSec),
                    style = compactType.cardTitle,
                    color = tokens.textPrimary
                )
                Slider(
                    value = thresholdSec.toFloat(),
                    onValueChange = { raw ->
                        viewModel.setLongCommandThresholdSeconds(raw.roundToInt())
                    },
                    valueRange = LongCommandNotificationConfig.MIN_THRESHOLD_SECONDS.toFloat()..
                        LongCommandNotificationConfig.MAX_THRESHOLD_SECONDS.toFloat(),
                    modifier = Modifier.testTag("notify_threshold_slider")
                )
            }
        }
    }

    if (showPermissionRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionRationaleDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .padding(horizontal = StudioSpacing.lg)
                .widthIn(max = dimens.dialogMaxWidth)
                .testTag("notif_permission_dialog"),
            containerColor = tokens.floatingGlass,
            shape = tokens.cardShape,
            icon = {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = tokens.accent
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.notif_perm_dialog_title),
                    style = compactType.screenTitle,
                    color = tokens.textPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.notif_perm_dialog_body),
                    style = compactType.body,
                    color = tokens.textPrimary
                )
            },
            confirmButton = {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Button(
                        onClick = {
                            showPermissionRationaleDialog = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                        },
                        shape = tokens.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.primaryButtonContainer,
                            contentColor = tokens.primaryButtonContent
                        ),
                        modifier = Modifier
                            .height(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = {
                                    showPermissionRationaleDialog = false
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    }
                                }
                            )
                            .testTag("notif_perm_allow_button")
                    ) {
                        Text(
                            text = stringResource(R.string.notif_perm_dialog_allow),
                            style = compactType.tabOrChip
                        )
                    }
                }
            },
            dismissButton = {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    TextButton(
                        onClick = { showPermissionRationaleDialog = false },
                        modifier = Modifier
                            .height(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = { showPermissionRationaleDialog = false }
                            )
                            .testTag("notif_perm_dismiss_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            style = compactType.tabOrChip,
                            color = tokens.textSecondary
                        )
                    }
                }
            }
        )
    }
}
