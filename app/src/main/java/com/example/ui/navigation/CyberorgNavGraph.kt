package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.example.ui.linux.LinuxScreen
import com.example.ui.linux.LocalOpenLinux
import com.example.ui.onboarding.OnboardingScreen
import com.example.ui.terminal.TerminalScreen
import com.example.ui.terminal.TerminalViewModel

object CyberorgRoutes {
    const val ONBOARDING = "onboarding"
    const val TERMINAL = "terminal"
    const val SETTINGS = "settings"
    const val LINUX = "linux"
    const val SETTINGS_API_KEYS = "settings/api_keys"
}

@Composable
fun CyberorgNavGraph(
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
            navController.currentBackStackEntry?.destination?.route == CyberorgRoutes.ONBOARDING
        ) {
            navController.navigate(CyberorgRoutes.TERMINAL) {
                popUpTo(CyberorgRoutes.ONBOARDING) { inclusive = true }
            }
        }
    }

    val startDestination = if (initialOnboardingCompleted) {
        CyberorgRoutes.TERMINAL
    } else {
        CyberorgRoutes.ONBOARDING
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(route = CyberorgRoutes.ONBOARDING) {
            OnboardingScreen(
                viewModel = settingsViewModel,
                onCompleted = {
                    navController.navigate(CyberorgRoutes.TERMINAL) {
                        popUpTo(CyberorgRoutes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(route = CyberorgRoutes.TERMINAL) {
            CompositionLocalProvider(
                LocalOpenLinux provides {
                    navController.navigate(CyberorgRoutes.LINUX) { launchSingleTop = true }
                }
            ) {
                TerminalScreen(
                    viewModel = terminalViewModel,
                    onNavigateToSettings = {
                        navController.navigate(CyberorgRoutes.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToApiKeysSettings = {
                        navController.navigate(CyberorgRoutes.SETTINGS_API_KEYS) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }

        composable(route = CyberorgRoutes.LINUX) {
            LinuxScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(route = CyberorgRoutes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenOnboarding = {
                    navController.navigate(CyberorgRoutes.ONBOARDING) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(route = CyberorgRoutes.SETTINGS_API_KEYS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenOnboarding = {
                    navController.navigate(CyberorgRoutes.ONBOARDING) {
                        launchSingleTop = true
                    }
                },
                initialRoute = SettingsRoute.ApiKeys
            )
        }
    }
}
