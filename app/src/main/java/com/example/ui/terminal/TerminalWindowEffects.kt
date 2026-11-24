package com.example.ui.terminal

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

data class TerminalWindowFlags(
    val applyFullscreen: Boolean,
    val applyKeepScreenOn: Boolean
)

fun resolveTerminalWindowFlags(
    isTerminalScreenActive: Boolean,
    isAppForeground: Boolean,
    fullscreenEnabled: Boolean,
    keepScreenOnEnabled: Boolean
): TerminalWindowFlags {
    val active = isTerminalScreenActive && isAppForeground
    return TerminalWindowFlags(
        applyFullscreen = active && fullscreenEnabled,
        applyKeepScreenOn = active && keepScreenOnEnabled
    )
}

@Composable
fun TerminalWindowController(
    isTerminalScreenActive: Boolean,
    fullscreenEnabled: Boolean,
    keepScreenOnEnabled: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() } ?: return

    var isAppForeground by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        )
    }

    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> isAppForeground = true
                Lifecycle.Event.ON_STOP -> isAppForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            applyWindowFlags(
                activity = activity,
                flags = TerminalWindowFlags(applyFullscreen = false, applyKeepScreenOn = false)
            )
        }
    }

    val desiredFlags = resolveTerminalWindowFlags(
        isTerminalScreenActive = isTerminalScreenActive,
        isAppForeground = isAppForeground,
        fullscreenEnabled = fullscreenEnabled,
        keepScreenOnEnabled = keepScreenOnEnabled
    )

    SideEffect {
        applyWindowFlags(activity = activity, flags = desiredFlags)
    }
}

private fun applyWindowFlags(
    activity: Activity,
    flags: TerminalWindowFlags
) {
    val window = activity.window ?: return
    val currentKeepOn = (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
    if (flags.applyKeepScreenOn && !currentKeepOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else if (!flags.applyKeepScreenOn && currentKeepOn) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (flags.applyFullscreen) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
