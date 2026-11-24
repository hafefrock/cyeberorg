package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.AppContainer
import com.example.settings.SettingsRoute
import com.example.settings.SettingsScreen
import com.example.settings.SettingsViewModel
import com.example.ui.onboarding.OnboardingScreen
import com.example.ui.terminal.TerminalScreen
import com.example.ui.terminal.TerminalViewModel

object HackerStudioRoutes {
    const val ONBOARDING = "onboarding"
    const val TERMINAL = "terminal"
    const val SETTINGS = "settings"
    const val SETTINGS_API_KEYS = "settings/api_keys"
}

@Composable
fun HackerStudioNavGraph(
    appContainer: AppContainer,
    initialOnboardingCompleted: Boolean
) {
    val navController = rememberNavController()

    val terminalViewModel: TerminalViewModel = viewModel(
        factory = TerminalViewModel.Factory(
            terminalBackend = appContainer.terminalBackend,
            aiRepository = appContainer.aiAssistantRepository,
            keyManager = appContainer.keyManager,
            settingsStore = appContainer.settingsRepository,
            snippetStore = appContainer.snippetRepository,
            promptHistoryStore = appContainer.promptHistoryRepository
        )
    )

    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            settingsStore = appContainer.settingsRepository,
            keyManager = appContainer.keyManager
        )
    )

    val settingsState = settingsViewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(settingsState.settings.onboardingCompleted) {
        if (settingsState.settings.onboardingCompleted &&
            navController.currentBackStackEntry?.destination?.route == HackerStudioRoutes.ONBOARDING
        ) {
            navController.navigate(HackerStudioRoutes.TERMINAL) {
                popUpTo(HackerStudioRoutes.ONBOARDING) { inclusive = true }
            }
        }
    }

    val startDestination = if (initialOnboardingCompleted) {
        HackerStudioRoutes.TERMINAL
    } else {
        HackerStudioRoutes.ONBOARDING
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(route = HackerStudioRoutes.ONBOARDING) {
            OnboardingScreen(
                viewModel = settingsViewModel,
                onCompleted = {
                    navController.navigate(HackerStudioRoutes.TERMINAL) {
                        popUpTo(HackerStudioRoutes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(route = HackerStudioRoutes.TERMINAL) {
            TerminalScreen(
                viewModel = terminalViewModel,
                onNavigateToSettings = {
                    navController.navigate(HackerStudioRoutes.SETTINGS) {
                        launchSingleTop = true
                    }
                },
                onNavigateToApiKeysSettings = {
                    navController.navigate(HackerStudioRoutes.SETTINGS_API_KEYS) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(route = HackerStudioRoutes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenOnboarding = {
                    navController.navigate(HackerStudioRoutes.ONBOARDING) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(route = HackerStudioRoutes.SETTINGS_API_KEYS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenOnboarding = {
                    navController.navigate(HackerStudioRoutes.ONBOARDING) {
                        launchSingleTop = true
                    }
                },
                initialRoute = SettingsRoute.ApiKeys
            )
        }
    }
}
