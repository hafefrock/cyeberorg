package com.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.data.AppContainer

/**
 * Custom [Application] owning the single process-wide [AppContainer].
 * Keeps PTY sessions, [com.example.terminal.TerminalBackend], and
 * [com.example.ai.KeyManager] state alive across Activity recreation
 * (screen rotation, locale switches, and theme changes).
 */
class HackerStudioApp : Application() {

    lateinit var appContainer: AppContainer
        private set

    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(applicationContext)
        appContainer.setAppForeground(false)

        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivityCount++
                    appContainer.setAppForeground(startedActivityCount > 0)
                }

                override fun onActivityStopped(activity: Activity) {
                    if (!activity.isChangingConfigurations) {
                        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                        appContainer.setAppForeground(startedActivityCount > 0)
                    } else {
                        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }
}
