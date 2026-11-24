package com.example.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

enum class TerminalColorScheme(
    val id: String,
    val background: Color,
    val foreground: Color,
    val promptColor: Color,
    val cursor: Color,
    val black: Color,
    val red: Color,
    val green: Color,
    val yellow: Color,
    val blue: Color,
    val magenta: Color,
    val cyan: Color,
    val white: Color,
    val brightBlack: Color
) {
    HACKER_PRO(
        id = "hacker_pro",
        background = Color(0xFF0B0F0C),
        foreground = Color(0xFFC6D8C9),
        promptColor = Color(0xFF3DDC84),
        cursor = Color(0xFF3DDC84),
        black = Color(0xFF0B0F0C),
        red = Color(0xFFFF5C5C),
        green = Color(0xFF3DDC84),
        yellow = Color(0xFFE5B80B),
        blue = Color(0xFF6CB6FF),
        magenta = Color(0xFFC89CFF),
        cyan = Color(0xFF66D9EF),
        white = Color(0xFFD7E6D9),
        brightBlack = Color(0xFF8FA394)
    ),
    CYBERPUNK(
        id = "cyberpunk",
        background = Color(0xFF0A0714),
        foreground = Color(0xFFD6D0F5),
        promptColor = Color(0xFF00E5FF),
        cursor = Color(0xFFFF2BD6),
        black = Color(0xFF0A0714),
        red = Color(0xFFFF3D81),
        green = Color(0xFF45F0C5),
        yellow = Color(0xFFFFD400),
        blue = Color(0xFF7B9BFF),
        magenta = Color(0xFFFF2BD6),
        cyan = Color(0xFF00E5FF),
        white = Color(0xFFE8E4FF),
        brightBlack = Color(0xFFA59CC9)
    );

    fun resolveAnsiColor(code: Int): Color = when (code) {
        30, 40 -> black
        31, 41, 91 -> red
        32, 42, 92 -> green
        33, 43, 93 -> yellow
        34, 44, 94 -> blue
        35, 45, 95 -> magenta
        36, 46, 96 -> cyan
        37, 47 -> white
        90 -> brightBlack
        97 -> Color.White
        else -> foreground
    }

    companion object {
        fun fromTheme(preset: AppThemePreset): TerminalColorScheme = preset.terminalScheme

        fun fromId(id: String?): TerminalColorScheme =
            AppThemePreset.fromStoredName(id).terminalScheme
    }
}

enum class AppThemePreset(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val terminalScheme: TerminalColorScheme,
    val tokens: StudioSurfaceTokens
) {
    HACKER_PRO(
        id = "hacker_pro",
        labelRes = R.string.theme_hacker_pro,
        descriptionRes = R.string.theme_hacker_pro_desc,
        terminalScheme = TerminalColorScheme.HACKER_PRO,
        tokens = StudioSurfaceTokens(
            isDark = true,
            baseCanvas = Color(0xFF0B0F0C),
            layer1 = Color(0xFF121812),
            layer2 = Color(0xFF171E17),
            layer3 = Color(0xFF1E271E),
            floatingGlass = Color(0xF2121812),
            borderStart = Color(0xFF2B3A2D),
            borderEnd = Color(0xFF1F2B21),
            textPrimary = Color(0xFFD7E6D9),
            textSecondary = Color(0xFFB5C9B9),
            textMuted = Color(0xFF8FA394),
            accent = Color(0xFF3DDC84),
            accentMuted = Color(0x223DDC84),
            onAccent = Color(0xFF071A0E),
            secondaryAccent = Color(0xFF3DDC84),
            onSecondaryAccent = Color(0xFF071A0E),
            primaryButtonContainer = Color(0xFF3DDC84),
            primaryButtonContent = Color(0xFF071A0E),
            focusRingColor = Color(0xFF3DDC84),
            error = Color(0xFFFF5C5C),
            warning = Color(0xFFE5B80B),
            success = Color(0xFF3DDC84),
            info = Color(0xFF66D9EF),
            cardShape = RoundedCornerShape(4.dp),
            buttonShape = RoundedCornerShape(4.dp),
            smallShape = RoundedCornerShape(4.dp),
            pillShape = RoundedCornerShape(4.dp),
            sheetShape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
            borderWidth = 1.dp,
            glowRadius = 0.dp,
            glowColor = Color.Transparent,
            cursorGlowColor = Color.Transparent,
            headingFont = JetBrainsMonoFamily,
            headingLetterSpacing = 0.6.sp,
            headingUppercase = true,
            bodyFont = JetBrainsMonoFamily,
            density = 0.85f,
            motionDurationMs = 120,
            promptGlyph = "$",
            cursorStyle = TerminalCursorStyle.BLOCK,
            badgeStyle = RiskBadgeStyle.BRACKET_MONO,
            navIndicatorStyle = NavIndicatorStyle.TOP_LINE,
            iconStyle = StudioIconStyle.THIN_OUTLINE,
            canvasGradientColors = emptyList()
        )
    ),
    CYBERPUNK(
        id = "cyberpunk",
        labelRes = R.string.theme_cyberpunk,
        descriptionRes = R.string.theme_cyberpunk_desc,
        terminalScheme = TerminalColorScheme.CYBERPUNK,
        tokens = StudioSurfaceTokens(
            isDark = true,
            baseCanvas = Color(0xFF0A0714),
            layer1 = Color(0xFF150F26),
            layer2 = Color(0xFF1C1433),
            layer3 = Color(0xFF251B42),
            floatingGlass = Color(0xF2150F26),
            borderStart = Color(0xB300E5FF),
            borderEnd = Color(0x4700E5FF),
            textPrimary = Color(0xFFE8E4FF),
            textSecondary = Color(0xFFC8C0EA),
            textMuted = Color(0xFFA59CC9),
            accent = Color(0xFF00E5FF),
            accentMuted = Color(0x2200E5FF),
            onAccent = Color(0xFF04141A),
            secondaryAccent = Color(0xFFFF2BD6),
            onSecondaryAccent = Color(0xFF0A0714),
            primaryButtonContainer = Color(0xFFFF2BD6),
            primaryButtonContent = Color(0xFF0A0714),
            focusRingColor = Color(0xFFFF2BD6),
            error = Color(0xFFFF3D81),
            warning = Color(0xFFFFD400),
            success = Color(0xFF00E5FF),
            info = Color(0xFF00E5FF),
            cardShape = ChamferShape(cutTopEnd = 12.dp, cutBottomStart = 12.dp),
            buttonShape = ChamferShape(cutTopEnd = 8.dp, cutBottomStart = 8.dp),
            smallShape = ChamferShape(cutTopEnd = 6.dp, cutBottomStart = 6.dp),
            pillShape = ChamferShape(cutTopEnd = 8.dp, cutBottomStart = 8.dp),
            sheetShape = ChamferShape(cutTopEnd = 20.dp, cutBottomStart = 0.dp),
            borderWidth = 1.dp,
            glowRadius = 7.dp,
            glowColor = Color(0x4000E5FF),
            cursorGlowColor = Color(0x66FF2BD6),
            headingFont = JetBrainsMonoFamily,
            headingLetterSpacing = 1.4.sp,
            headingUppercase = false,
            bodyFont = InterFamily,
            density = 1.08f,
            motionDurationMs = 220,
            promptGlyph = "▸",
            cursorStyle = TerminalCursorStyle.UNDERLINE_GLOW,
            badgeStyle = RiskBadgeStyle.CHAMFER_ICON,
            navIndicatorStyle = NavIndicatorStyle.BOTTOM_NEON_GLOW,
            iconStyle = StudioIconStyle.FILLED_TECH,
            canvasGradientColors = listOf(Color(0xFF150F26), Color(0xFF0A0714))
        )
    );

    companion object {
        /**
         * Pure migration mapper converting any legacy or unknown stored theme string into one of
         * the two supported presets. All unknown / legacy names (obsidian, midnight, matrix, light)
         * safely map to [HACKER_PRO].
         */
        fun fromStoredName(name: String?): AppThemePreset {
            val normalized = name?.trim()?.lowercase() ?: return HACKER_PRO
            return when (normalized) {
                "cyberpunk", "cyberpunk_futuristik", "cyberpunk_futuristic" -> CYBERPUNK
                else -> HACKER_PRO
            }
        }

        fun fromId(id: String?): AppThemePreset = fromStoredName(id)
    }
}
