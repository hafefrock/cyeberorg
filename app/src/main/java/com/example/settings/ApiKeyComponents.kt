package com.example.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ai.KeyHealthStatus
import com.example.ai.ManagedKeyStatus
import com.example.data.ApiKeyValidator
import com.example.ui.UiText
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioTouchTarget

@Composable
internal fun ApiKeyRow(
    managed: ManagedKeyStatus,
    onToggleEnabled: (Boolean) -> Unit,
    onRecheck: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val statusColor = when (managed.status) {
        KeyHealthStatus.ACTIVE -> tokens.success
        KeyHealthStatus.READY -> tokens.info
        KeyHealthStatus.COOLDOWN, KeyHealthStatus.FORBIDDEN_DELAY, KeyHealthStatus.MODEL_UNAVAILABLE -> tokens.warning
        KeyHealthStatus.AUTH_ERROR -> tokens.error
        KeyHealthStatus.DISABLED -> tokens.textMuted
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("api_key_row_${managed.metadata.id}"),
        color = tokens.layer2,
        shape = tokens.cardShape,
        border = BorderStroke(tokens.borderWidth, statusColor.copy(alpha = 0.38f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV)),
            verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
                    ) {
                        Text(managed.metadata.label, style = compactType.cardTitle, color = tokens.textPrimary)
                        Surface(
                            color = statusColor.copy(alpha = 0.16f),
                            shape = tokens.smallShape,
                            border = BorderStroke(tokens.borderWidth, statusColor.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = managed.lastStatusNote?.asString() ?: managed.status.name,
                                style = compactType.tabOrChip,
                                color = statusColor,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(
                                    safeContentPadding(tokens.smallShape, StudioSpacing.sm, StudioSpacing.xxs)
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(StudioSpacing.xxs))
                    Text(
                        text = stringResource(R.string.settings_key_metrics, managed.metadata.maskedPreview, managed.requestCount, managed.errorCount),
                        style = compactType.monoPreview,
                        color = tokens.textSecondary
                    )
                    if (managed.status == KeyHealthStatus.AUTH_ERROR ||
                        managed.status == KeyHealthStatus.FORBIDDEN_DELAY
                    ) {
                        Spacer(modifier = Modifier.height(StudioSpacing.xxs))
                        Text(
                            text = stringResource(R.string.key_err_rejected_by_google),
                            style = compactType.body,
                            color = tokens.error,
                            modifier = Modifier.testTag("key_rejected_notice_${managed.metadata.id}")
                        )
                    }
                }
                Switch(
                    checked = managed.metadata.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("toggle_key_${managed.metadata.id}")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val canRecheck = managed.metadata.enabled && !managed.isRechecking
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    OutlinedButton(
                        onClick = onRecheck,
                        enabled = canRecheck,
                        shape = tokens.buttonShape,
                        contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                        modifier = Modifier
                            .height(dimens.controlHeightSmall)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                enabled = canRecheck,
                                onOutOfBoundsTap = onRecheck
                            )
                            .testTag("recheck_key_${managed.metadata.id}"),
                        border = BorderStroke(tokens.borderWidth, tokens.accent.copy(alpha = 0.48f))
                    ) {
                        if (managed.isRechecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(dimens.progressIndicatorSize),
                                strokeWidth = dimens.progressStrokeWidth,
                                color = tokens.accent
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.cd_recheck_key),
                                tint = tokens.accent,
                                modifier = Modifier.size(dimens.iconSizeSmall)
                            )
                        }
                        Spacer(modifier = Modifier.width(StudioSpacing.xs))
                        Text(
                            text = stringResource(if (managed.isRechecking) R.string.settings_btn_checking else R.string.settings_btn_recheck),
                            style = compactType.tabOrChip,
                            color = tokens.accent,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Spacer(modifier = Modifier.width(StudioSpacing.sm))
                val deleteDesc = stringResource(R.string.cd_delete_api_key)
                Box(
                    modifier = Modifier
                        .size(dimens.controlHeightSmall)
                        .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = onRequestDelete)
                        .clickable(role = Role.Button, onClick = onRequestDelete)
                        .semantics {
                            role = Role.Button
                            contentDescription = deleteDesc
                        }
                        .testTag("delete_key_${managed.metadata.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = deleteDesc,
                        tint = tokens.error,
                        modifier = Modifier.size(dimens.iconSize)
                    )
                }
            }
        }
    }
}

@Composable
internal fun AddApiKeyDialog(
    errorMessage: UiText?,
    isSaving: Boolean,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
    onAdd: (label: String, rawKey: String) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var rawKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography

    val submitAction = {
        if (!isSaving) {
            onAdd(label, ApiKeyValidator.cleanInput(rawKey))
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = StudioSpacing.lg)
            .widthIn(max = dimens.dialogMaxWidth)
            .testTag("add_api_key_dialog"),
        containerColor = tokens.floatingGlass,
        shape = tokens.cardShape,
        title = { Text(stringResource(R.string.dialog_add_key_title), style = compactType.screenTitle, color = tokens.textPrimary) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
            ) {
                Text(stringResource(R.string.dialog_add_key_desc), style = compactType.body, color = tokens.textSecondary)
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.dialog_add_key_label), style = compactType.tabOrChip) },
                    textStyle = compactType.body.copy(color = tokens.textPrimary),
                    singleLine = true,
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = tokens.smallShape,
                    modifier = Modifier.fillMaxWidth().testTag("api_key_label_input")
                )
                OutlinedTextField(
                    value = rawKey,
                    onValueChange = {
                        rawKey = it
                        if (errorMessage != null) onClearError()
                    },
                    label = { Text(stringResource(R.string.dialog_add_key_secret), style = compactType.tabOrChip) },
                    textStyle = compactType.monoPreview.copy(color = tokens.textPrimary),
                    singleLine = true,
                    enabled = !isSaving,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { err ->
                        {
                            Text(
                                text = err.asString(),
                                style = compactType.body,
                                color = tokens.error,
                                modifier = Modifier.testTag("add_key_error_text")
                            )
                        }
                    },
                    shape = tokens.smallShape,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { submitAction() }
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }, enabled = !isSaving) {
                            Icon(
                                imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(if (showKey) R.string.cd_hide_key else R.string.cd_show_key),
                                modifier = Modifier.size(dimens.iconSize)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("api_key_secret_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    val pasteAction = {
                        clipboardManager.getText()?.text?.let { pasted ->
                            rawKey = ApiKeyValidator.cleanInput(pasted)
                            if (errorMessage != null) onClearError()
                        }
                    }
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        OutlinedButton(
                            onClick = { pasteAction() },
                            enabled = !isSaving,
                            shape = tokens.buttonShape,
                            contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                            border = BorderStroke(tokens.borderWidth, tokens.accent.copy(alpha = 0.48f)),
                            modifier = Modifier
                                .height(dimens.controlHeightSmall)
                                .studioTouchTarget(
                                    minSize = dimens.minTouchTarget,
                                    enabled = !isSaving,
                                    onOutOfBoundsTap = { pasteAction() }
                                )
                                .testTag("paste_api_key_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = tokens.accent,
                                modifier = Modifier.size(dimens.iconSizeSmall)
                            )
                            Spacer(modifier = Modifier.width(StudioSpacing.xs))
                            Text(
                                text = stringResource(R.string.action_paste),
                                style = compactType.tabOrChip,
                                color = tokens.accent,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Button(
                    onClick = submitAction,
                    enabled = !isSaving,
                    shape = tokens.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.primaryButtonContainer,
                        contentColor = tokens.primaryButtonContent
                    ),
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            enabled = !isSaving,
                            onOutOfBoundsTap = submitAction
                        )
                        .testTag("confirm_add_api_key")
                ) { Text(stringResource(R.string.dialog_add_key_save), style = compactType.tabOrChip) }
            }
        },
        dismissButton = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    modifier = Modifier
                        .height(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            enabled = !isSaving,
                            onOutOfBoundsTap = onDismiss
                        )
                ) {
                    Text(stringResource(R.string.action_cancel), style = compactType.tabOrChip, color = tokens.textSecondary)
                }
            }
        }
    )
}
