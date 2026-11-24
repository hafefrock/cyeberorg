package com.example.data

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import com.example.R
import java.util.Locale

enum class AppLanguage(
    val id: String,
    val languageTag: String?,
    @StringRes val labelRes: Int
) {
    SYSTEM("system", null, R.string.lang_system),
    INDONESIAN("id", "id", R.string.lang_indonesian),
    ENGLISH("en", "en", R.string.lang_english);

    fun resolveEffectiveLocale(): Locale {
        return when (this) {
            SYSTEM -> Locale.getDefault()
            INDONESIAN -> Locale.forLanguageTag("id")
            ENGLISH -> Locale.forLanguageTag("en")
        }
    }

    fun isIndonesian(): Boolean {
        val lang = resolveEffectiveLocale().language.lowercase(Locale.ROOT)
        return lang == "id" || lang == "in"
    }

    companion object {
        fun fromId(id: String?): AppLanguage =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

object LocaleHelper {
    fun applyPerAppLocale(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val targetList = when (val tag = language.languageTag) {
                null -> LocaleList.getEmptyLocaleList()
                else -> LocaleList.forLanguageTags(tag)
            }
            if (localeManager != null && localeManager.applicationLocales != targetList) {
                localeManager.applicationLocales = targetList
            }
        }
    }

    fun createLocalizedContext(baseContext: Context, language: AppLanguage): Context {
        if (language == AppLanguage.SYSTEM) return baseContext
        val locale = language.resolveEffectiveLocale()
        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        val configContext = baseContext.createConfigurationContext(config)
        return object : ContextWrapper(baseContext) {
            override fun getResources(): Resources = configContext.resources
            override fun getAssets(): AssetManager = configContext.assets
            override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
                configContext.createConfigurationContext(overrideConfiguration)
        }
    }
}
