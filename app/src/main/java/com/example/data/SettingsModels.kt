package com.example.data

import androidx.annotation.StringRes
import com.example.R
import com.example.terminal.ExtraKeyCode
import com.example.ui.theme.AppThemePreset
import com.example.ui.theme.TerminalColorScheme
import com.squareup.moshi.JsonClass
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ExtraKeysLayout(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val keys: List<ExtraKeyCode>
) {
    STANDARD(
        id = "standard",
        labelRes = R.string.layout_standard,
        descriptionRes = R.string.layout_standard_desc,
        keys = listOf(
            ExtraKeyCode.ESC,
            ExtraKeyCode.TAB,
            ExtraKeyCode.CTRL,
            ExtraKeyCode.ALT,
            ExtraKeyCode.ARROW_UP,
            ExtraKeyCode.ARROW_DOWN,
            ExtraKeyCode.ARROW_LEFT,
            ExtraKeyCode.ARROW_RIGHT,
            ExtraKeyCode.PIPE,
            ExtraKeyCode.TILDE,
            ExtraKeyCode.SLASH,
            ExtraKeyCode.MINUS
        )
    ),
    DEVELOPER(
        id = "developer",
        labelRes = R.string.layout_developer,
        descriptionRes = R.string.layout_developer_desc,
        keys = listOf(
            ExtraKeyCode.ESC,
            ExtraKeyCode.TAB,
            ExtraKeyCode.CTRL,
            ExtraKeyCode.ALT,
            ExtraKeyCode.ARROW_UP,
            ExtraKeyCode.ARROW_DOWN,
            ExtraKeyCode.ARROW_LEFT,
            ExtraKeyCode.ARROW_RIGHT,
            ExtraKeyCode.PIPE,
            ExtraKeyCode.TILDE,
            ExtraKeyCode.SLASH,
            ExtraKeyCode.MINUS,
            ExtraKeyCode.LBRACE,
            ExtraKeyCode.RBRACE,
            ExtraKeyCode.LBRACKET,
            ExtraKeyCode.RBRACKET,
            ExtraKeyCode.DOLLAR,
            ExtraKeyCode.AMPERSAND,
            ExtraKeyCode.GREATER
        )
    ),
    COMPACT(
        id = "compact",
        labelRes = R.string.layout_compact,
        descriptionRes = R.string.layout_compact_desc,
        keys = listOf(
            ExtraKeyCode.ESC,
            ExtraKeyCode.TAB,
            ExtraKeyCode.CTRL,
            ExtraKeyCode.ARROW_UP,
            ExtraKeyCode.ARROW_DOWN,
            ExtraKeyCode.PIPE,
            ExtraKeyCode.TILDE,
            ExtraKeyCode.SLASH,
            ExtraKeyCode.MINUS
        )
    );

    companion object {
        fun fromId(id: String?): ExtraKeysLayout =
            entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

enum class KeyRoutingStrategy(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val subtitleRes: Int
) {
    ROUND_ROBIN(
        id = "round_robin",
        labelRes = R.string.routing_round_robin,
        subtitleRes = R.string.routing_round_robin_desc
    ),
    FAILOVER(
        id = "failover",
        labelRes = R.string.routing_failover,
        subtitleRes = R.string.routing_failover_desc
    );

    companion object {
        fun fromId(id: String?): KeyRoutingStrategy =
            entries.firstOrNull { it.id == id } ?: ROUND_ROBIN
    }
}

data class GeminiModelOption(
    val id: String,
    val displayName: String,
    val badge: String,
    @StringRes val descriptionRes: Int,
    val supportsThinkingConfig: Boolean = false,
    val thinkingLevel: String? = null,
    val supportsSamplingParams: Boolean = true
)

object GeminiModels {
    val available = listOf(
        GeminiModelOption(
            id = "gemini-4-hydrogen",
            displayName = "Gemini 4 Hydrogen",
            badge = "HQ",
            descriptionRes = R.string.model_desc_hydrogen,
            supportsThinkingConfig = true,
            thinkingLevel = "low",
            supportsSamplingParams = false
        ),
        GeminiModelOption(
            id = "gemini-3.8-flash",
            displayName = "Gemini 3.8 Flash",
            badge = "3.8",
            descriptionRes = R.string.model_desc_3_8_flash
        ),
        GeminiModelOption(
            id = "gemini-3.6-flash",
            displayName = "Gemini 3.6 Flash",
            badge = "3.6",
            descriptionRes = R.string.model_desc_3_6_flash
        ),
        GeminiModelOption(
            id = "gemini-3.5-flash-lite",
            displayName = "Gemini 3.5 Flash-Lite",
            badge = "Lite",
            descriptionRes = R.string.model_desc_3_5_flash_lite
        )
    )

    fun byId(id: String?): GeminiModelOption? {
        if (id.isNullOrBlank()) return null
        return available.firstOrNull { it.id == id }
    }

    fun normalizeSelectedModel(id: String?): String? = byId(id)?.id

    fun displayNameOrId(id: String?): String? = byId(id)?.displayName ?: id
}

@JsonClass(generateAdapter = true)
data class FallbackModelEntry(
    val modelId: String,
    val enabled: Boolean = true
)

@JsonClass(generateAdapter = true)
data class ApiKeyMetadata(
    val id: String,
    val label: String,
    val maskedPreview: String,
    val enabled: Boolean = true,
    val addedAtMillis: Long = System.currentTimeMillis()
)

data class AppSettings(
    val appTheme: AppThemePreset = AppThemePreset.HACKER_PRO,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val onboardingCompleted: Boolean = false,
    val fontSizeSp: Float = 12f,
    val extraKeysLayout: ExtraKeysLayout = ExtraKeysLayout.STANDARD,
    val aiEnabled: Boolean = true,
    val aiConsentAccepted: Boolean = false,
    val selectedModel: String? = null,
    val fallbackModels: List<FallbackModelEntry> = emptyList(),
    val routingStrategy: KeyRoutingStrategy = KeyRoutingStrategy.ROUND_ROBIN,
    val cooldownSeconds: Int = 60,
    val apiKeys: List<ApiKeyMetadata> = emptyList(),
    val systemAnimationsDisabled: Boolean = false,
    val reduceEffectsOverride: Boolean? = null,
    val fullscreenTerminal: Boolean = false,
    val keepScreenOn: Boolean = false,
    val notifyLongCommands: Boolean = false,
    val longCommandThresholdSeconds: Int = LongCommandNotificationConfig.DEFAULT_THRESHOLD_SECONDS,
    val terminalFontId: String = com.example.terminal.TerminalFontRegistry.DEFAULT_FONT_ID
) {
    val terminalColorScheme: TerminalColorScheme
        get() = appTheme.terminalScheme

    val reduceEffects: Boolean
        get() = reduceEffectsOverride ?: systemAnimationsDisabled
}

object LongCommandNotificationConfig {
    const val MIN_THRESHOLD_SECONDS: Int = 5
    const val MAX_THRESHOLD_SECONDS: Int = 120
    const val DEFAULT_THRESHOLD_SECONDS: Int = 15

    fun normalizeThresholdSeconds(seconds: Int): Int =
        seconds.coerceIn(MIN_THRESHOLD_SECONDS, MAX_THRESHOLD_SECONDS)
}

object TerminalFontConfig {
    const val MIN_FONT_SIZE_SP: Float = 8f
    const val MAX_FONT_SIZE_SP: Float = 28f
    const val DEFAULT_FONT_SIZE_SP: Float = 12f
    const val FONT_SIZE_STEP_SP: Float = 0.5f
    const val PINCH_SCALE_DEADBAND: Float = 0.02f

    fun normalizeFontSizeSp(sizeSp: Float): Float {
        if (!sizeSp.isFinite()) return DEFAULT_FONT_SIZE_SP
        val clamped = sizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        val snapped = (clamped / FONT_SIZE_STEP_SP).roundToInt() * FONT_SIZE_STEP_SP
        return snapped.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
    }

    fun calculatePinchFontSizeSp(currentSizeSp: Float, zoomScale: Float): Float {
        val base = normalizeFontSizeSp(currentSizeSp)
        if (!zoomScale.isFinite() || zoomScale <= 0f) return base
        if (abs(zoomScale - 1f) < PINCH_SCALE_DEADBAND) return base
        return normalizeFontSizeSp(base * zoomScale)
    }

    fun formatFontSizeValue(sizeSp: Float): String {
        val normalized = normalizeFontSizeSp(sizeSp)
        val doubled = (normalized * 2f).roundToInt()
        return if (doubled % 2 == 0) {
            (doubled / 2).toString()
        } else {
            "${doubled / 2}.5"
        }
    }
}
