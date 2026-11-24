package com.example.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.example.R
import com.example.ai.KeyHealthStatus
import com.example.ai.ManagedKeyStatus
import com.example.data.EncryptedVaultUnavailableException
import com.example.data.KeyRoutingStrategy
import com.example.data.VaultStatus
import com.example.ui.UiText
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioTouchTarget
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ApiKeysScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val settings = state.settings
    val keySnap = state.keySnapshot
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val isVaultReady = state.vaultStatus is VaultStatus.Ready
    val isVaultCorrupted = state.vaultStatus is VaultStatus.Corrupted

    var showAddKeyDialog by remember { mutableStateOf(false) }
    var addKeyDialogError by remember { mutableStateOf<UiText?>(null) }
    var isSavingKey by remember { mutableStateOf(false) }
    var pendingDeleteKey by remember { mutableStateOf<ManagedKeyStatus?>(null) }
    var showResetVaultConfirm by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val hasVaultError = state.vaultErrorMessage != null

    LaunchedEffect(hasVaultError) {
        if (hasVaultError) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.keyActionEvents.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            when (event) {
                is ApiKeyActionEvent.RecheckAuthOrForbidden -> {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.key_err_rejected_by_google),
                        duration = SnackbarDuration.Long
                    )
                }
                is ApiKeyActionEvent.RecheckSuccess -> {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.settings_key_recheck_ok),
                        duration = SnackbarDuration.Short
                    )
                }
                is ApiKeyActionEvent.RecheckNetworkRetry -> {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.key_note_network_retry),
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("settings_api_keys_screen"),
            contentPadding = PaddingValues(StudioSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
        ) {
        state.vaultErrorMessage?.let { errorMsg ->
            item(key = "vault_error_banner") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_error_banner"),
                    color = tokens.error.copy(alpha = 0.14f),
                    shape = tokens.cardShape,
                    border = BorderStroke(tokens.borderWidth, tokens.error.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV)),
                        verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = tokens.error,
                                modifier = Modifier.size(dimens.iconSize)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(
                                        if (isVaultCorrupted) R.string.settings_vault_corrupted_title
                                        else R.string.settings_vault_error_title
                                    ),
                                    style = compactType.cardTitle,
                                    color = tokens.error
                                )
                                Text(errorMsg.asString(), style = compactType.body, color = tokens.textPrimary)
                            }
                            if (isVaultReady) {
                                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                                    TextButton(
                                        onClick = viewModel::dismissVaultError,
                                        modifier = Modifier
                                            .height(dimens.controlHeight)
                                            .studioTouchTarget(
                                                minSize = dimens.minTouchTarget,
                                                onOutOfBoundsTap = viewModel::dismissVaultError
                                            )
                                    ) {
                                        Text(stringResource(R.string.action_dismiss), style = compactType.tabOrChip, color = tokens.error)
                                    }
                                }
                            }
                        }
                        if (isVaultCorrupted) {
                            val resetClick = { showResetVaultConfirm = true }
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                                OutlinedButton(
                                    onClick = resetClick,
                                    shape = tokens.buttonShape,
                                    border = BorderStroke(tokens.borderWidth, tokens.error),
                                    contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(dimens.controlHeight)
                                        .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = resetClick)
                                        .testTag("reset_vault_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_reset_vault_btn),
                                        fontFamily = JetBrainsMonoFamily,
                                        style = compactType.tabOrChip,
                                        color = tokens.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "vault_status") {
            SettingsSection(title = stringResource(R.string.settings_sec_vault_status)) {
                Surface(
                    color = tokens.layer2,
                    shape = tokens.smallShape,
                    border = BorderStroke(tokens.borderWidth, if (isVaultReady) tokens.accent.copy(alpha = 0.28f) else tokens.error.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                tint = if (isVaultReady) tokens.accent else tokens.error,
                                modifier = Modifier.size(dimens.iconSize)
                            )
                            Column {
                                val activeName = keySnap.activeKeyLabel ?: stringResource(R.string.settings_none)
                                Text(
                                    text = stringResource(R.string.settings_active_key_label, activeName),
                                    fontFamily = JetBrainsMonoFamily,
                                    style = compactType.cardTitle,
                                    color = tokens.textPrimary
                                )
                                Text(
                                    text = when {
                                        isVaultReady -> stringResource(R.string.settings_vault_ready_summary, keySnap.usableKeyCount, keySnap.keys.size)
                                        isVaultCorrupted -> stringResource(R.string.settings_vault_corrupted_summary)
                                        else -> stringResource(R.string.settings_vault_unavailable_summary)
                                    },
                                    style = compactType.body,
                                    color = if (isVaultReady) tokens.textSecondary else tokens.error
                                )
                            }
                        }

                        val hasCooldownOrError = keySnap.keys.any {
                            it.status == KeyHealthStatus.COOLDOWN || it.status == KeyHealthStatus.FORBIDDEN_DELAY || it.status == KeyHealthStatus.AUTH_ERROR
                        }
                        if (hasCooldownOrError) {
                            val resetCdDesc = stringResource(R.string.cd_reset_cooldowns)
                            Box(
                                modifier = Modifier
                                    .size(dimens.controlHeight)
                                    .studioTouchTarget(
                                        minSize = dimens.minTouchTarget,
                                        onOutOfBoundsTap = viewModel::clearCooldowns
                                    )
                                    .clickable(role = Role.Button, onClick = viewModel::clearCooldowns)
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = resetCdDesc
                                    }
                                    .testTag("clear_cooldowns_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = resetCdDesc,
                                    tint = tokens.warning,
                                    modifier = Modifier.size(dimens.iconSize)
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "api_keys_list") {
            SettingsSection(title = stringResource(R.string.settings_sec_api_keys, keySnap.keys.size)) {
                if (keySnap.keys.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = StudioSpacing.sm),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                    ) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = tokens.textMuted,
                            modifier = Modifier.size(dimens.navIconSize)
                        )
                        Text(stringResource(R.string.settings_empty_keys_title), style = compactType.cardTitle, color = tokens.textSecondary)
                        Text(stringResource(R.string.settings_empty_keys_desc), style = compactType.body, color = tokens.textMuted)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)) {
                        keySnap.keys.forEach { managed ->
                            ApiKeyRow(
                                managed = managed,
                                onToggleEnabled = { viewModel.setApiKeyEnabled(managed.metadata.id, it) },
                                onRecheck = { viewModel.recheckApiKey(managed.metadata.id) },
                                onRequestDelete = { pendingDeleteKey = managed }
                            )
                        }
                    }
                }

                val openAddKey = {
                    addKeyDialogError = null
                    showAddKeyDialog = true
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    OutlinedButton(
                        onClick = openAddKey,
                        enabled = isVaultReady,
                        shape = tokens.buttonShape,
                        contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                enabled = isVaultReady,
                                onOutOfBoundsTap = openAddKey
                            )
                            .testTag("add_api_key_button"),
                        border = BorderStroke(tokens.borderWidth, if (isVaultReady) tokens.accent.copy(alpha = 0.6f) else tokens.borderStart)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.cd_add_api_key),
                            tint = if (isVaultReady) tokens.accent else tokens.textMuted,
                            modifier = Modifier.size(dimens.iconSizeSmall)
                        )
                        Spacer(modifier = Modifier.width(StudioSpacing.sm))
                        Text(
                            text = stringResource(if (isVaultReady) R.string.settings_add_key_btn else R.string.settings_vault_disabled_btn),
                            fontFamily = JetBrainsMonoFamily,
                            style = compactType.tabOrChip,
                            color = if (isVaultReady) tokens.accent else tokens.textMuted
                        )
                    }
                }
            }
        }

        item(key = "api_keys_advanced") {
            val advTitle = stringResource(R.string.settings_advanced_title)
            val advDesc = stringResource(R.string.settings_advanced_desc)
            val toggleAdv = { advancedExpanded = !advancedExpanded }
            SettingsSection(title = advTitle) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = dimens.controlHeight)
                        .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = toggleAdv)
                        .background(tokens.layer2, tokens.smallShape)
                        .border(tokens.borderWidth, tokens.thinBorderBrush, tokens.smallShape)
                        .clickable(role = Role.Button, onClick = toggleAdv)
                        .semantics(mergeDescendants = true) { contentDescription = "$advTitle, $advDesc" }
                        .testTag("api_keys_advanced_toggle")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.md)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(dimens.navIconSize))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(advTitle, style = compactType.cardTitle, color = tokens.textPrimary)
                            Text(advDesc, style = compactType.body, color = tokens.textSecondary)
                        }
                        Icon(
                            imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(R.string.cd_expand_section),
                            tint = tokens.textSecondary,
                            modifier = Modifier.size(dimens.iconSize)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = advancedExpanded,
                    enter = fadeIn(tween(tokens.motionDurationMs)) + expandVertically(tween(tokens.motionDurationMs)),
                    exit = fadeOut(tween(tokens.motionDurationMs)) + shrinkVertically(tween(tokens.motionDurationMs))
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(dimens.listSpacing),
                        modifier = Modifier.padding(top = StudioSpacing.xs)
                    ) {
                        Text(stringResource(R.string.settings_routing_strategy), style = compactType.cardTitle, color = tokens.textPrimary)
                        Column(verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)) {
                            KeyRoutingStrategy.entries.forEach { strategy ->
                                SettingsChoiceRow(
                                    title = stringResource(strategy.labelRes),
                                    description = stringResource(strategy.subtitleRes),
                                    selected = settings.routingStrategy == strategy,
                                    onClick = { viewModel.setRoutingStrategy(strategy) },
                                    useMonospaceTitle = true,
                                    modifier = Modifier.testTag("routing_${strategy.id}")
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.settings_cooldown_label, settings.cooldownSeconds),
                            fontFamily = JetBrainsMonoFamily,
                            style = compactType.cardTitle,
                            color = tokens.textPrimary
                        )
                        Slider(
                            value = settings.cooldownSeconds.toFloat(),
                            onValueChange = { viewModel.setCooldownSeconds(it.roundToInt()) },
                            valueRange = 15f..180f,
                            steps = 10,
                            modifier = Modifier.testTag("cooldown_slider")
                        )
                    }
                }
            }
        }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(StudioSpacing.md)
                .testTag("api_keys_snackbar_host")
        ) { snackbarData ->
            Snackbar(
                snackbarData = snackbarData,
                shape = tokens.smallShape,
                containerColor = tokens.floatingGlass,
                contentColor = tokens.textPrimary,
                actionColor = tokens.accent
            )
        }
    }

    if (showAddKeyDialog) {
        AddApiKeyDialog(
            errorMessage = addKeyDialogError,
            isSaving = isSavingKey,
            onClearError = { addKeyDialogError = null },
            onDismiss = {
                addKeyDialogError = null
                showAddKeyDialog = false
            },
            onAdd = { label, rawKey ->
                coroutineScope.launch {
                    isSavingKey = true
                    val result = viewModel.addApiKeyAndAwait(label = label, rawKey = rawKey)
                    isSavingKey = false
                    if (result.isSuccess) {
                        val savedMeta = result.getOrThrow()
                        addKeyDialogError = null
                        showAddKeyDialog = false
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val actionRes = snackbarHostState.showSnackbar(
                            message = context.getString(R.string.settings_key_saved_snackbar),
                            actionLabel = context.getString(R.string.settings_action_test_key),
                            duration = SnackbarDuration.Long
                        )
                        if (actionRes == SnackbarResult.ActionPerformed) {
                            viewModel.recheckApiKey(savedMeta.id)
                        }
                    } else {
                        val ex = result.exceptionOrNull()
                        addKeyDialogError = (ex as? EncryptedVaultUnavailableException)?.uiReason
                            ?: UiText.Resource(R.string.vault_err_invalid_format)
                    }
                }
            }
        )
    }

    pendingDeleteKey?.let { target ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.settings_delete_key_title),
            message = stringResource(R.string.settings_delete_key_desc, target.metadata.label),
            confirmLabel = stringResource(R.string.settings_delete_key_confirm),
            onConfirm = {
                viewModel.removeApiKey(target.metadata.id)
                pendingDeleteKey = null
            },
            onDismiss = { pendingDeleteKey = null },
            dialogTestTag = "confirm_delete_key_dialog",
            confirmTestTag = "confirm_delete_key_button",
            cancelTestTag = "cancel_delete_key_button"
        )
    }

    if (showResetVaultConfirm) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.settings_reset_vault_confirm_title),
            message = stringResource(R.string.settings_reset_vault_confirm_desc),
            confirmLabel = stringResource(R.string.settings_reset_vault_confirm_btn),
            onConfirm = {
                viewModel.resetCorruptedVault()
                showResetVaultConfirm = false
            },
            onDismiss = { showResetVaultConfirm = false },
            dialogTestTag = "confirm_reset_vault_dialog",
            confirmTestTag = "confirm_reset_vault_button",
            cancelTestTag = "cancel_reset_vault_button"
        )
    }
}
