package com.example.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.example.R
import com.example.terminal.DetectedTerminalToken
import com.example.terminal.TerminalTokenKind
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioTouchTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalTokenActionSheet(
    token: DetectedTerminalToken,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopy: (String) -> Unit,
    onInsertPath: (String) -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showUrlConfirmation by remember(token) { mutableStateOf(false) }

    val kindTitleRes = when (token.kind) {
        TerminalTokenKind.URL -> R.string.token_kind_url
        TerminalTokenKind.IPV4 -> R.string.token_kind_ipv4
        TerminalTokenKind.FILE_PATH -> R.string.token_kind_path
    }
    val dismissLabel = stringResource(R.string.action_cancel)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tokens.floatingGlass,
        contentColor = tokens.textPrimary,
        shape = tokens.sheetShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(safeContentPadding(tokens.sheetShape, StudioSpacing.lg, StudioSpacing.lg))
                .padding(bottom = StudioSpacing.xl)
                .testTag("terminal_token_action_sheet"),
            verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = tokens.accent,
                        modifier = Modifier.size(dimens.iconSize)
                    )
                    Text(
                        text = tokens.formatHeading(stringResource(kindTitleRes)),
                        fontFamily = tokens.headingFont,
                        letterSpacing = tokens.headingLetterSpacing,
                        style = compactType.screenTitle,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary
                    )
                }
                Box(
                    modifier = Modifier
                        .size(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onDismiss
                        )
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .semantics {
                            role = Role.Button
                            contentDescription = dismissLabel
                        }
                        .testTag("token_sheet_dismiss_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = dismissLabel,
                        tint = tokens.textSecondary,
                        modifier = Modifier.size(dimens.iconSize)
                    )
                }
            }

            Surface(
                color = tokens.baseCanvas,
                shape = tokens.smallShape,
                border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = token.text,
                    fontFamily = JetBrainsMonoFamily,
                    style = compactType.monoPreview,
                    color = tokens.accent,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV))
                        .testTag("token_sheet_address_text")
                )
            }

            if (showUrlConfirmation && token.kind == TerminalTokenKind.URL) {
                Surface(
                    color = tokens.warning.copy(alpha = 0.12f),
                    shape = tokens.smallShape,
                    border = BorderStroke(tokens.borderWidth, tokens.warning.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("token_open_url_confirm_box")
                ) {
                    Column(
                        modifier = Modifier.padding(
                            safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV)
                        ),
                        verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
                    ) {
                        Text(
                            text = stringResource(R.string.token_open_url_confirm_prompt, token.text),
                            style = compactType.body,
                            color = tokens.textPrimary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                                TextButton(
                                    onClick = { showUrlConfirmation = false },
                                    modifier = Modifier
                                        .height(dimens.controlHeight)
                                        .studioTouchTarget(
                                            minSize = dimens.minTouchTarget,
                                            onOutOfBoundsTap = { showUrlConfirmation = false }
                                        )
                                ) {
                                    Text(
                                        text = dismissLabel,
                                        style = compactType.tabOrChip,
                                        color = tokens.textSecondary
                                    )
                                }
                                Button(
                                    onClick = {
                                        onOpenUrl(token.text)
                                        onDismiss()
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
                                                onOpenUrl(token.text)
                                                onDismiss()
                                            }
                                        )
                                        .testTag("token_open_url_confirm_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.token_action_open_confirm),
                                        style = compactType.tabOrChip
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)) {
                if (token.kind == TerminalTokenKind.URL) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        Button(
                            onClick = { showUrlConfirmation = true },
                            shape = tokens.buttonShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.primaryButtonContainer,
                                contentColor = tokens.primaryButtonContent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = dimens.controlHeight)
                                .studioTouchTarget(
                                    minSize = dimens.minTouchTarget,
                                    onOutOfBoundsTap = { showUrlConfirmation = true }
                                )
                                .testTag("token_action_open_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(dimens.iconSizeSmall)
                            )
                            Text(
                                text = stringResource(R.string.token_action_open),
                                style = compactType.tabOrChip,
                                modifier = Modifier.padding(start = StudioSpacing.sm)
                            )
                        }
                    }
                }

                if (token.kind != TerminalTokenKind.URL) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        Button(
                            onClick = {
                                onInsertPath(token.text)
                                onDismiss()
                            },
                            shape = tokens.buttonShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.primaryButtonContainer,
                                contentColor = tokens.primaryButtonContent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = dimens.controlHeight)
                                .studioTouchTarget(
                                    minSize = dimens.minTouchTarget,
                                    onOutOfBoundsTap = {
                                        onInsertPath(token.text)
                                        onDismiss()
                                    }
                                )
                                .testTag("token_action_insert_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Input,
                                contentDescription = null,
                                modifier = Modifier.size(dimens.iconSizeSmall)
                            )
                            Text(
                                text = stringResource(R.string.token_action_insert_terminal),
                                style = compactType.tabOrChip,
                                modifier = Modifier.padding(start = StudioSpacing.sm)
                            )
                        }
                    }
                }

                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    OutlinedButton(
                        onClick = {
                            onCopy(token.text)
                            onDismiss()
                        },
                        shape = tokens.buttonShape,
                        border = BorderStroke(tokens.borderWidth, tokens.borderStart),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = {
                                    onCopy(token.text)
                                    onDismiss()
                                }
                            )
                            .testTag("token_action_copy_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = tokens.accent,
                            modifier = Modifier.size(dimens.iconSizeSmall)
                        )
                        Text(
                            text = stringResource(R.string.terminal_sel_copy),
                            style = compactType.tabOrChip,
                            color = tokens.textPrimary,
                            modifier = Modifier.padding(start = StudioSpacing.sm)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalShareOutputSheet(
    hasSelection: Boolean,
    onDismiss: () -> Unit,
    onShareText: () -> Unit,
    onShareImage: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cancelLabel = stringResource(R.string.action_cancel)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tokens.floatingGlass,
        contentColor = tokens.textPrimary,
        shape = tokens.sheetShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(safeContentPadding(tokens.sheetShape, StudioSpacing.lg, StudioSpacing.lg))
                .padding(bottom = StudioSpacing.xl)
                .testTag("terminal_share_output_sheet"),
            verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = tokens.accent,
                        modifier = Modifier.size(dimens.iconSize)
                    )
                    Text(
                        text = tokens.formatHeading(stringResource(R.string.share_output_title)),
                        fontFamily = tokens.headingFont,
                        letterSpacing = tokens.headingLetterSpacing,
                        style = compactType.screenTitle,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textPrimary
                    )
                }
                Box(
                    modifier = Modifier
                        .size(dimens.controlHeight)
                        .studioTouchTarget(
                            minSize = dimens.minTouchTarget,
                            onOutOfBoundsTap = onDismiss
                        )
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .semantics {
                            role = Role.Button
                            contentDescription = cancelLabel
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = cancelLabel,
                        tint = tokens.textSecondary,
                        modifier = Modifier.size(dimens.iconSize)
                    )
                }
            }

            Surface(
                color = tokens.warning.copy(alpha = 0.12f),
                shape = tokens.smallShape,
                border = BorderStroke(tokens.borderWidth, tokens.warning.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("share_output_sensitive_warning")
            ) {
                Row(
                    modifier = Modifier.padding(
                        safeContentPadding(tokens.smallShape, dimens.cardPaddingH, dimens.cardPaddingV)
                    ),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = tokens.warning,
                        modifier = Modifier.size(dimens.iconSizeSmall)
                    )
                    Text(
                        text = stringResource(R.string.share_output_sensitive_warning),
                        style = compactType.body,
                        color = tokens.textPrimary
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Button(
                        onClick = {
                            onShareText()
                            onDismiss()
                        },
                        shape = tokens.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.primaryButtonContainer,
                            contentColor = tokens.primaryButtonContent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = {
                                    onShareText()
                                    onDismiss()
                                }
                            )
                            .testTag("share_output_text_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.TextFields,
                            contentDescription = null,
                            modifier = Modifier.size(dimens.iconSizeSmall)
                        )
                        Text(
                            text = stringResource(
                                if (hasSelection) {
                                    R.string.share_output_text_selection
                                } else {
                                    R.string.share_output_text_screen
                                }
                            ),
                            style = compactType.tabOrChip,
                            modifier = Modifier.padding(start = StudioSpacing.sm)
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            onShareImage()
                            onDismiss()
                        },
                        shape = tokens.buttonShape,
                        border = BorderStroke(tokens.borderWidth, tokens.borderStart),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = {
                                    onShareImage()
                                    onDismiss()
                                }
                            )
                            .testTag("share_output_image_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = tokens.accent,
                            modifier = Modifier.size(dimens.iconSizeSmall)
                        )
                        Text(
                            text = stringResource(R.string.share_output_image_png),
                            style = compactType.tabOrChip,
                            color = tokens.textPrimary,
                            modifier = Modifier.padding(start = StudioSpacing.sm)
                        )
                    }
                }
            }
        }
    }
}
