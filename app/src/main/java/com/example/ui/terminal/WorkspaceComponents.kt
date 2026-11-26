package com.example.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.example.BuildConfig
import com.example.R
import com.example.ai.KeyManagerSnapshot
import com.example.data.GeminiModels
import com.example.terminal.FakeTerminalBackend
import com.example.ui.files.FileBrowserPane
import com.example.ui.linux.LocalOpenLinux
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.NavIndicatorStyle
import com.example.ui.theme.StudioElevation
import com.example.ui.theme.StudioIconStyle
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioGlow
import com.example.ui.theme.studioTouchTarget

@Composable
fun WorkspaceNavigationRail(
    selectedSection: WorkspaceSection,
    aiEnabled: Boolean,
    onSelectSection: (WorkspaceSection) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .width(dimens.railWidth)
            .fillMaxHeight()
            .testTag("workspace_navigation_rail"),
        color = tokens.layer1,
        tonalElevation = StudioElevation.surface
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectableGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
            ) {
                Spacer(modifier = Modifier.height(StudioSpacing.sm))
                workspaceNavItems(aiEnabled).forEach { item ->
                    if (item.section == WorkspaceSection.SETTINGS) {
                        NavGroupDivider()
                        LinuxRailEntry()
                        NavGroupDivider()
                    }
                    val selected = selectedSection == item.section
                    val navIcon = when (tokens.iconStyle) {
                        StudioIconStyle.THIN_OUTLINE -> item.icon
                        StudioIconStyle.FILLED_TECH -> if (selected) item.selectedIcon else item.icon
                    }
                    val labelText = stringResource(item.labelRes)
                    val tintColor = if (selected) tokens.accent else tokens.textSecondary
                    Box(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = dimens.bottomBarHeight)
                                .selectable(
                                    selected = selected,
                                    role = Role.Tab,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (item.section == WorkspaceSection.SETTINGS) {
                                            onOpenSettings()
                                        } else {
                                            onSelectSection(item.section)
                                        }
                                    }
                                )
                                .testTag("rail_${item.section.name.lowercase()}")
                        ) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = StudioSpacing.xxs, vertical = StudioSpacing.xs),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(StudioSpacing.xxs, Alignment.CenterVertically)
                            ) {
                                Icon(
                                    imageVector = navIcon,
                                    contentDescription = labelText,
                                    tint = tintColor,
                                    modifier = Modifier.size(dimens.navIconSize)
                                )
                                Text(
                                    text = labelText,
                                    style = compactType.navLabel,
                                    color = tintColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (selected) {
                            when (tokens.navIndicatorStyle) {
                                NavIndicatorStyle.TOP_LINE -> {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .width(dimens.navIndicatorGlowThickness)
                                            .height(dimens.navIndicatorLength)
                                            .background(tokens.accent)
                                            .testTag("nav_indicator_${tokens.navIndicatorStyle.name.lowercase()}")
                                    )
                                }
                                NavIndicatorStyle.BOTTOM_NEON_GLOW -> {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .width(dimens.navIndicatorLength)
                                            .height(dimens.navIndicatorGlowThickness)
                                            .studioGlow(
                                                color = tokens.glowColor,
                                                radius = tokens.glowRadius,
                                                shape = tokens.smallShape
                                            )
                                            .background(tokens.accent)
                                            .testTag("nav_indicator_${tokens.navIndicatorStyle.name.lowercase()}")
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .width(dimens.hairlineWidth)
                    .fillMaxHeight()
                    .background(tokens.thinBorderBrush)
            )
        }
    }
}

@Composable
fun WorkspaceSidebarDrawer(
    visible: Boolean,
    selectedSection: WorkspaceSection,
    aiEnabled: Boolean,
    activeTheme: AppThemePreset,
    onSelectSection: (WorkspaceSection) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var dragOffsetPx by remember(visible) { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val drawerWidth = minOf(dimens.sidebarWidth, maxWidth * 0.80f)

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(tokens.motionDurationMs)),
            exit = fadeOut(animationSpec = tween(tokens.motionDurationMs))
        ) {
            val scrimDesc = stringResource(R.string.cd_sidebar_scrim)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.30f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onDismiss
                    )
                    .semantics { contentDescription = scrimDesc }
                    .testTag("workspace_sidebar_scrim")
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(
                animationSpec = tween(tokens.motionDurationMs),
                initialOffsetX = { -it }
            ) + fadeIn(animationSpec = tween(tokens.motionDurationMs)),
            exit = slideOutHorizontally(
                animationSpec = tween(tokens.motionDurationMs),
                targetOffsetX = { -it }
            ) + fadeOut(animationSpec = tween(tokens.motionDurationMs)),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            val panelDesc = stringResource(R.string.cd_sidebar_panel)
            val closeDragThresholdPx = with(density) { dimens.minTouchTarget.toPx() }
            Surface(
                modifier = Modifier
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .pointerInput(drawerWidth, closeDragThresholdPx) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragOffsetPx <= -closeDragThresholdPx) {
                                    onDismiss()
                                }
                                dragOffsetPx = 0f
                            },
                            onDragCancel = { dragOffsetPx = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetPx = (dragOffsetPx + dragAmount).coerceAtMost(0f)
                            }
                        )
                    }
                    .offset { IntOffset(dragOffsetPx.toInt(), 0) }
                    .studioGlow(
                        color = tokens.glowColor,
                        radius = tokens.glowRadius,
                        shape = tokens.cardShape
                    )
                    .semantics {
                        paneTitle = panelDesc
                        contentDescription = panelDesc
                    }
                    .testTag("workspace_sidebar"),
                color = tokens.layer1,
                shape = tokens.cardShape,
                border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush),
                tonalElevation = StudioElevation.floating,
                shadowElevation = StudioElevation.floating
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(safeContentPadding(tokens.cardShape, dimens.cardPaddingH, StudioSpacing.md)),
                    verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = StudioSpacing.xs),
                        verticalArrangement = Arrangement.spacedBy(StudioSpacing.xxs)
                    ) {
                        Text(
                            text = tokens.formatHeading(stringResource(R.string.app_name)),
                            style = compactType.screenTitle,
                            color = tokens.textPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(
                                R.string.sidebar_header_meta,
                                stringResource(activeTheme.labelRes),
                                BuildConfig.VERSION_NAME
                            ),
                            style = compactType.monoPreview,
                            color = tokens.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(dimens.hairlineWidth)
                            .background(tokens.thinBorderBrush)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                    ) {
                        workspaceNavItems(aiEnabled).forEach { item ->
                            if (item.section == WorkspaceSection.SETTINGS) {
                                NavGroupDivider()
                                LinuxDrawerEntry(onDismiss = onDismiss)
                                NavGroupDivider()
                            }
                            val selected = selectedSection == item.section
                            val navIcon = when (tokens.iconStyle) {
                                StudioIconStyle.THIN_OUTLINE -> item.icon
                                StudioIconStyle.FILLED_TECH -> if (selected) item.selectedIcon else item.icon
                            }
                            val labelText = stringResource(item.labelRes)
                            val tintColor = if (selected) tokens.accent else tokens.textSecondary
                            val itemAction = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (item.section == WorkspaceSection.SETTINGS) {
                                    onOpenSettings()
                                } else {
                                    onSelectSection(item.section)
                                }
                                onDismiss()
                            }

                            Box(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(dimens.sidebarItemHeight)
                                        .studioTouchTarget(
                                            minSize = dimens.minTouchTarget,
                                            onOutOfBoundsTap = itemAction
                                        )
                                        .background(
                                            color = if (selected) tokens.accentMuted else Color.Transparent,
                                            shape = tokens.smallShape
                                        )
                                        .then(
                                            if (selected) {
                                                Modifier.border(tokens.borderWidth, tokens.accentBorderBrush, tokens.smallShape)
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .selectable(
                                            selected = selected,
                                            role = Role.Tab,
                                            onClick = itemAction
                                        )
                                        .testTag("sidebar_${item.section.name.lowercase()}")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH)),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.md)
                                    ) {
                                        Icon(
                                            imageVector = navIcon,
                                            contentDescription = null,
                                            tint = tintColor,
                                            modifier = Modifier.size(dimens.navIconSize)
                                        )
                                        Text(
                                            text = labelText,
                                            style = compactType.body,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = tintColor,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (selected) {
                                    when (tokens.navIndicatorStyle) {
                                        NavIndicatorStyle.TOP_LINE -> {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterStart)
                                                    .width(dimens.navIndicatorThickness)
                                                    .height(dimens.navIndicatorLength)
                                                    .background(tokens.accent)
                                                    .testTag("nav_indicator_${tokens.navIndicatorStyle.name.lowercase()}")
                                            )
                                        }
                                        NavIndicatorStyle.BOTTOM_NEON_GLOW -> {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterStart)
                                                    .width(dimens.navIndicatorGlowThickness)
                                                    .height(dimens.navIndicatorLength)
                                                    .studioGlow(
                                                        color = tokens.glowColor,
                                                        radius = tokens.glowRadius,
                                                        shape = tokens.smallShape
                                                    )
                                                    .background(tokens.accent)
                                                    .testTag("nav_indicator_${tokens.navIndicatorStyle.name.lowercase()}")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class WorkspaceNavItem(
    val section: WorkspaceSection,
    val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

private fun workspaceNavItems(aiEnabled: Boolean): List<WorkspaceNavItem> = buildList {
    add(WorkspaceNavItem(WorkspaceSection.TERMINAL, R.string.nav_terminal, Icons.Outlined.Terminal, Icons.Filled.Terminal))
    add(WorkspaceNavItem(WorkspaceSection.FILES, R.string.nav_files, Icons.Outlined.Folder, Icons.Filled.Folder))
    if (aiEnabled) {
        add(WorkspaceNavItem(WorkspaceSection.AI, R.string.nav_ai, Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome))
    }
    add(WorkspaceNavItem(WorkspaceSection.SNIPPETS, R.string.nav_snippets, Icons.Outlined.Code, Icons.Filled.Code))
    add(WorkspaceNavItem(WorkspaceSection.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings))
}

@Composable
private fun NavGroupDivider() {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = StudioSpacing.md)
            .height(dimens.hairlineWidth)
            .background(tokens.thinBorderBrush)
    )
}

@Composable
private fun LinuxRailEntry() {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val openLinux = LocalOpenLinux.current
    val label = stringResource(R.string.settings_group_linux)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.bottomBarHeight)
            .clickable(role = Role.Button, onClick = openLinux)
            .testTag("rail_linux")
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = StudioSpacing.xxs, vertical = StudioSpacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(StudioSpacing.xxs, Alignment.CenterVertically)
        ) {
            Icon(
                imageVector = Icons.Outlined.Computer,
                contentDescription = label,
                tint = tokens.textSecondary,
                modifier = Modifier.size(dimens.navIconSize)
            )
            Text(
                text = "Ubuntu",
                style = compactType.navLabel,
                color = tokens.textSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LinuxDrawerEntry(onDismiss: () -> Unit) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val haptic = LocalHapticFeedback.current
    val openLinux = LocalOpenLinux.current
    val action = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onDismiss()
        openLinux()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimens.sidebarItemHeight)
            .studioTouchTarget(minSize = dimens.minTouchTarget, onOutOfBoundsTap = action)
            .clickable(role = Role.Button, onClick = action)
            .testTag("sidebar_linux")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(safeContentPadding(tokens.smallShape, dimens.cardPaddingH)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.md)
        ) {
            Icon(
                imageVector = Icons.Outlined.Computer,
                contentDescription = null,
                tint = tokens.textSecondary,
                modifier = Modifier.size(dimens.navIconSize)
            )
            Text(
                text = stringResource(R.string.settings_group_linux),
                style = compactType.body,
                color = tokens.textSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun WorkspaceStatusBar(
    cwd: String,
    lastExitCode: Int?,
    keySnapshot: KeyManagerSnapshot,
    selectedModel: String?,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val shortCwd = when {
        cwd == FakeTerminalBackend.HOME_DIR -> "~"
        cwd.startsWith("${FakeTerminalBackend.HOME_DIR}/") -> "~/${cwd.removePrefix("${FakeTerminalBackend.HOME_DIR}/")}"
        cwd.isEmpty() -> "~"
        else -> cwd
    }
    val exitCode = lastExitCode ?: 0
    val keyLabel = keySnapshot.activeKeyLabel ?: stringResource(R.string.status_no_key)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("workspace_status_bar"),
        color = tokens.layer2
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StudioSpacing.md, vertical = StudioSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = shortCwd,
                    style = compactType.navLabel,
                    fontFamily = JetBrainsMonoFamily,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .size(dimens.statusDotSize)
                        .clip(CircleShape)
                        .background(if (exitCode == 0) tokens.success else tokens.error)
                )
                Text(
                    text = stringResource(R.string.status_exit_code, exitCode),
                    style = compactType.navLabel,
                    fontFamily = JetBrainsMonoFamily,
                    color = if (exitCode == 0) tokens.success else tokens.error,
                    maxLines = 1
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
            ) {
                Text(
                    text = keyLabel,
                    style = compactType.navLabel,
                    fontFamily = JetBrainsMonoFamily,
                    color = if (keySnapshot.usableKeyCount > 0) tokens.accent else tokens.textMuted,
                    maxLines = 1
                )
                Text(
                    text = "•",
                    style = compactType.navLabel,
                    color = tokens.textMuted
                )
                val normalizedModel = GeminiModels.normalizeSelectedModel(selectedModel)
                Text(
                    text = GeminiModels.displayNameOrId(normalizedModel)
                        ?: stringResource(R.string.ai_model_unselected_short),
                    style = compactType.navLabel,
                    fontFamily = JetBrainsMonoFamily,
                    color = if (normalizedModel != null) tokens.textSecondary else tokens.warning,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun EmptySessionsCard(
    onCreateSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(StudioSpacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = dimens.emptyCardMaxWidth)
                .fillMaxWidth()
                .testTag("empty_sessions_card"),
            color = tokens.layer1,
            shape = tokens.cardShape,
            border = BorderStroke(tokens.borderWidth, tokens.thinBorderBrush)
        ) {
            Column(
                modifier = Modifier.padding(safeContentPadding(tokens.cardShape, StudioSpacing.xl, StudioSpacing.xl)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(StudioSpacing.md)
            ) {
                Box(
                    modifier = Modifier
                        .size(dimens.emptyStateIconContainerSize)
                        .clip(CircleShape)
                        .background(tokens.accentMuted),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = tokens.accent,
                        modifier = Modifier.size(dimens.navIconSize)
                    )
                }
                Text(
                    text = tokens.formatHeading(stringResource(R.string.empty_sessions_title)),
                    style = compactType.screenTitle,
                    color = tokens.textPrimary
                )
                Text(
                    text = stringResource(R.string.empty_sessions_subtitle),
                    style = compactType.body,
                    color = tokens.textSecondary
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .studioGlow(
                            color = tokens.glowColor,
                            radius = tokens.glowRadius,
                            shape = tokens.buttonShape
                        )
                ) {
                    Button(
                        onClick = onCreateSession,
                        shape = tokens.buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.primaryButtonContainer,
                            contentColor = tokens.primaryButtonContent
                        ),
                        contentPadding = safeContentPadding(tokens.buttonShape, dimens.cardPaddingH),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(dimens.controlHeight)
                            .studioTouchTarget(
                                minSize = dimens.minTouchTarget,
                                onOutOfBoundsTap = onCreateSession
                            )
                            .testTag("empty_new_session_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(dimens.iconSize)
                        )
                        Spacer(modifier = Modifier.width(StudioSpacing.xs))
                        Text(
                            text = stringResource(R.string.empty_sessions_cta),
                            style = compactType.tabOrChip,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FilesPlaceholderPane(
    onInspectCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    FileBrowserPane(onTerminalCommand = onInspectCommand, modifier = modifier)
}
