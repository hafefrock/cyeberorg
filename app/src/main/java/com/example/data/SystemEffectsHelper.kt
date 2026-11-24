package com.example.data

import android.content.Context
import android.provider.Settings

object SystemEffectsHelper {
    /**
     * Checks system animator duration scale. When the user or device has disabled system
     * animations (scale <= 0f), Cyberorg defaults "Reduce effects" to true.
     */
    fun areSystemAnimationsDisabled(context: Context): Boolean {
        return runCatching {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            scale <= 0f
        }.getOrDefault(false)
    }
}
