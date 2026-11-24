package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.data.AppContainer
import com.example.data.AppSettings
import com.example.data.LocaleHelper
import com.example.terminal.LongCommandNotifier
import com.example.terminal.TerminalBackend
import com.example.ui.navigation.HackerStudioNavGraph
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.HackerStudioTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    internal val appContainer: AppContainer
        get() = (application as HackerStudioApp).appContainer

    internal val terminalBackend: TerminalBackend
        get() = appContainer.terminalBackend

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        val container = appContainer
        handleNotificationSessionIntent(intent)
        var initialSettings by mutableStateOf<AppSettings?>(null)
        splashScreen.setKeepOnScreenCondition { initialSettings == null }

        lifecycleScope.launch {
            val loaded = container.settingsRepository.settingsFlow.first()
            LocaleHelper.applyPerAppLocale(applicationContext, loaded.appLanguage)
            initialSettings = loaded
        }

        setContent {
            val loaded = initialSettings
            if (loaded == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AppThemePreset.HACKER_PRO.tokens.baseCanvas)
                )
                return@setContent
            }

            val settings by container.settingsRepository.settingsFlow
                .collectAsStateWithLifecycle(initialValue = loaded)
            val isDark = settings.appTheme.tokens.isDark

            LaunchedEffect(isDark) {
                val style = if (isDark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                }
                enableEdgeToEdge(
                    statusBarStyle = style,
                    navigationBarStyle = style
                )
            }

            LaunchedEffect(settings.appLanguage) {
                LocaleHelper.applyPerAppLocale(applicationContext, settings.appLanguage)
            }

            val baseContext = LocalContext.current
            val localizedContext = remember(baseContext, settings.appLanguage) {
                LocaleHelper.createLocalizedContext(baseContext, settings.appLanguage)
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalOnBackPressedDispatcherOwner provides this@MainActivity
            ) {
                HackerStudioTheme(
                    appTheme = settings.appTheme,
                    reduceEffects = settings.reduceEffects
                ) {
                    HackerStudioNavGraph(
                        appContainer = container,
                        initialOnboardingCompleted = loaded.onboardingCompleted
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationSessionIntent(intent)
    }

    private fun handleNotificationSessionIntent(intent: Intent?) {
        val targetId = intent?.getStringExtra(LongCommandNotifier.EXTRA_TARGET_SESSION_ID)
        if (!targetId.isNullOrBlank()) {
            terminalBackend.switchSession(targetId)
        }
    }
}
