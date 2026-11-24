package com.example.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Locale-safe UI text abstraction so ViewModels, Repositories, and KeyManager
 * never hold hardcoded English user-facing strings.
 */
sealed interface UiText {
    data class Resource(
        @StringRes val resId: Int,
        val args: List<Any> = emptyList()
    ) : UiText {
        constructor(@StringRes resId: Int, vararg args: Any) : this(resId, args.toList())
    }

    data class Dynamic(val value: String) : UiText

    fun asString(context: Context): String = when (this) {
        is Dynamic -> value
        is Resource -> {
            val resolvedArgs = args.map { arg ->
                if (arg is UiText) arg.asString(context) else arg
            }.toTypedArray()
            if (resolvedArgs.isEmpty()) context.getString(resId)
            else context.getString(resId, *resolvedArgs)
        }
    }

    @Composable
    fun asString(): String = asString(LocalContext.current)
}
