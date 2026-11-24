package com.example.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.theme.StudioTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onOpenOnboarding: () -> Unit,
    initialRoute: SettingsRoute = SettingsRoute.Home
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    var currentRoute by rememberSaveable(stateSaver = SettingsRoute.Saver) {
        mutableStateOf(initialRoute)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= dimens.wideScreenBreakpoint

        if (!isWide && currentRoute != SettingsRoute.Home) {
            BackHandler {
                currentRoute = SettingsRoute.Home
            }
        }

        val activeGroup = SettingsGroup.fromRoute(currentRoute)
        val topBarTitleRes = if (!isWide && activeGroup != null) {
            activeGroup.titleRes
        } else {
            R.string.settings_title
        }

        Scaffold(
            containerColor = tokens.baseCanvas,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = tokens.formatHeading(stringResource(topBarTitleRes)),
                            fontFamily = tokens.headingFont,
                            letterSpacing = tokens.headingLetterSpacing,
                            style = compactType.screenTitle,
                            color = tokens.textPrimary,
                            modifier = Modifier.testTag("settings_top_title")
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (!isWide && currentRoute != SettingsRoute.Home) {
                                    currentRoute = SettingsRoute.Home
                                } else {
                                    onNavigateBack()
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("settings_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_navigate_back),
                                tint = tokens.textPrimary,
                                modifier = Modifier.size(dimens.navIconSize)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = tokens.layer1
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWide) {
                    val wideSelectedGroup = activeGroup ?: SettingsGroup.AI_ASSISTANT
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("settings_two_pane_layout")
                    ) {
                        SettingsHomeScreen(
                            state = state,
                            selectedGroup = wideSelectedGroup,
                            onSelectGroup = { group -> currentRoute = group.route },
                            modifier = Modifier
                                .widthIn(min = dimens.settingsWidePaneMinWidth, max = dimens.settingsWidePaneMaxWidth)
                                .weight(0.38f)
                                .fillMaxHeight()
                        )
                        Box(
                            modifier = Modifier
                                .width(dimens.hairlineWidth)
                                .fillMaxHeight()
                                .background(tokens.borderStart.copy(alpha = 0.35f))
                        )
                        Box(
                            modifier = Modifier
                                .weight(0.62f)
                                .fillMaxHeight()
                                .testTag("settings_detail_pane"),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            SettingsDetailContent(
                                route = wideSelectedGroup.route,
                                state = state,
                                viewModel = viewModel,
                                onOpenOnboarding = onOpenOnboarding,
                                modifier = Modifier
                                    .widthIn(max = dimens.settingsDetailMaxWidth)
                                    .fillMaxSize()
                            )
                        }
                    }
                } else {
                    AnimatedContent(
                        targetState = currentRoute,
                        transitionSpec = {
                            fadeIn(tween(tokens.motionDurationMs)) togetherWith
                                fadeOut(tween(tokens.motionDurationMs))
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("settings_single_pane_layout"),
                        label = "settingsRoute"
                    ) { targetRoute ->
                        if (targetRoute == SettingsRoute.Home) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                SettingsHomeScreen(
                                    state = state,
                                    selectedGroup = null,
                                    onSelectGroup = { group -> currentRoute = group.route },
                                    modifier = Modifier
                                        .widthIn(max = dimens.settingsDetailMaxWidth)
                                        .fillMaxSize()
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("settings_detail_pane"),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                SettingsDetailContent(
                                    route = targetRoute,
                                    state = state,
                                    viewModel = viewModel,
                                    onOpenOnboarding = onOpenOnboarding,
                                    modifier = Modifier
                                        .widthIn(max = dimens.settingsDetailMaxWidth)
                                        .fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDetailContent(
    route: SettingsRoute,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (route) {
        SettingsRoute.AiAssistant -> AiSettingsScreen(
            state = state,
            viewModel = viewModel,
            modifier = modifier
        )
        SettingsRoute.ApiKeys -> ApiKeysScreen(
            state = state,
            viewModel = viewModel,
            modifier = modifier
        )
        SettingsRoute.Appearance -> AppearanceSettingsScreen(
            state = state,
            viewModel = viewModel,
            modifier = modifier
        )
        SettingsRoute.Terminal -> TerminalSettingsScreen(
            state = state,
            viewModel = viewModel,
            modifier = modifier
        )
        SettingsRoute.Language -> LanguageSettingsScreen(
            state = state,
            viewModel = viewModel,
            modifier = modifier
        )
        SettingsRoute.DataPrivacy -> DataPrivacySettingsScreen(
            modifier = modifier
        )
        SettingsRoute.About -> AboutScreen(
            viewModel = viewModel,
            onOpenOnboarding = onOpenOnboarding,
            modifier = modifier
        )
        SettingsRoute.Home -> Unit
    }
}
