package com.example.termux

import android.content.Context
import java.io.File

/** Small per-app preferences for the Ubuntu / desktop features. */
object LinuxPrefs {
    private const val NAME = "cyberorg_linux"
    private const val SHARE_STORAGE = "share_storage"

    fun shareStorage(context: Context): Boolean = runCatching {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(SHARE_STORAGE, false)
    }.getOrDefault(false)

    fun setShareStorage(context: Context, enabled: Boolean) {
        runCatching {
            context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(SHARE_STORAGE, enabled).apply()
        }
    }

    /** True only when sharing is on and the phone's storage is actually readable (permission granted). */
    fun effectiveShareStorage(context: Context): Boolean =
        shareStorage(context) && File(DistroPaths.ANDROID_STORAGE).canRead()
}
