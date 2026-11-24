package com.example.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class TerminalCursorStyle {
    BLOCK,
    UNDERLINE_GLOW
}

enum class RiskBadgeStyle {
    BRACKET_MONO,
    CHAMFER_ICON
}

enum class NavIndicatorStyle {
    TOP_LINE,
    BOTTOM_NEON_GLOW
}

enum class StudioIconStyle {
    THIN_OUTLINE,
    FILLED_TECH
}

@Immutable
object StudioSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 6.dp
    val md: Dp = 10.dp
    val lg: Dp = 12.dp
    val xl: Dp = 18.dp
    val xxl: Dp = 24.dp
}

@Immutable
object StudioRadius {
    val xs: Dp = 4.dp
    val sm: Dp = 6.dp
    val md: Dp = 8.dp
    val lg: Dp = 12.dp
    val xl: Dp = 16.dp
    val pill: Dp = 999.dp
}

@Immutable
object StudioElevation {
    val none: Dp = 0.dp
    val surface: Dp = 1.dp
    val card: Dp = 2.dp
    val floating: Dp = 8.dp
}

@Immutable
data class StudioDimens(
    val controlHeight: Dp = 32.dp,
    val controlHeightSmall: Dp = 28.dp,
    val chipHeight: Dp = 24.dp,
    val iconSize: Dp = 16.dp,
    val iconSizeSmall: Dp = 14.dp,
    val navIconSize: Dp = 18.dp,
    val tabIconVisualSize: Dp = 18.dp,
    val tabHeight: Dp = 28.dp,
    val terminalHeaderHeight: Dp = 28.dp,
    val bottomBarHeight: Dp = 48.dp,
    val railWidth: Dp = 56.dp,
    val fabSize: Dp = 40.dp,
    val cardPaddingH: Dp = 10.dp,
    val cardPaddingV: Dp = 8.dp,
    val listSpacing: Dp = 6.dp,
    val minTouchTarget: Dp = 48.dp,
    val hairlineWidth: Dp = 1.dp,
    val statusDotSize: Dp = 6.dp,
    val navIndicatorThickness: Dp = 2.dp,
    val navIndicatorGlowThickness: Dp = 3.dp,
    val navIndicatorLength: Dp = 24.dp,
    val extraKeyRowHeight: Dp = 38.dp,
    val extraKeyGap: Dp = 2.dp,
    val tabTitleMaxWidth: Dp = 112.dp,
    val tabTitleCompactMaxWidth: Dp = 64.dp,
    val sidebarWidth: Dp = 260.dp,
    val sidebarItemHeight: Dp = 40.dp,
    val sidebarGestureZone: Dp = 20.dp,
    val emptyStateIconContainerSize: Dp = 40.dp,
    val dialogMaxWidth: Dp = 460.dp,
    val historyListMaxHeight: Dp = 340.dp,
    val thinkingCardMaxWidth: Dp = 340.dp,
    val emptyCardMaxWidth: Dp = 400.dp,
    val sheetHandleWidth: Dp = 36.dp,
    val sheetHandleHeight: Dp = 4.dp,
    val sheetCollapsedMinHeight: Dp = 220.dp,
    val sheetMinHeight: Dp = 160.dp,
    val sheetImeMinHeight: Dp = 320.dp,
    val sidePanelWidth: Dp = 360.dp,
    val settingsWidePaneMinWidth: Dp = 240.dp,
    val settingsWidePaneMaxWidth: Dp = 320.dp,
    val settingsDetailMaxWidth: Dp = 720.dp,
    val badgeIconSize: Dp = 12.dp,
    val progressIndicatorSize: Dp = 14.dp,
    val progressStrokeWidth: Dp = 2.dp,
    val radioIndicatorSize: Dp = 20.dp,
    val cursorBlockWidth: Dp = 7.dp,
    val cursorBlockHeight: Dp = 13.dp,
    val cursorUnderlineWidth: Dp = 9.dp,
    val cursorUnderlineThickness: Dp = 2.dp,
    val narrowWidthThreshold: Dp = 360.dp,
    val wideScreenBreakpoint: Dp = 600.dp,
    val splitWideBreakpoint: Dp = 720.dp,
    val selectedBorderDelta: Dp = 0.5.dp
)

/**
 * Returns the maximum diagonal chamfer cut distance for [ChamferShape], or `0.dp` for rounded/flat
 * shapes. Used to ensure text and inner elements clear angled corners identically across themes.
 */
@Stable
fun Shape.chamferInset(): Dp = when (this) {
    is ChamferShape -> maxOf(cutTopEnd, cutBottomStart)
    else -> 0.dp
}

/**
 * Single pure helper computing safe inner [androidx.compose.foundation.layout.PaddingValues] from
 * a theme [Shape] so Hacker Pro (small rounded corners) and Cyberpunk (chamfered corners) both
 * keep text clear of container corners without theme-specific UI branching.
 */
@Stable
fun safeContentPadding(
    shape: Shape,
    baseHorizontal: Dp,
    baseVertical: Dp = 0.dp
): androidx.compose.foundation.layout.PaddingValues {
    val cut = shape.chamferInset()
    val horizontal = maxOf(baseHorizontal, cut)
    val vertical = if (baseVertical > 0.dp) maxOf(baseVertical, cut / 2) else 0.dp
    return androidx.compose.foundation.layout.PaddingValues(
        horizontal = horizontal,
        vertical = vertical
    )
}

object StudioMotion {
    fun <T> snappySpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    fun <T> gentleSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    val slideOffsetSpring = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}

/**
 * Unified compact typography scale used across workspace UI, AI panels, Scriptbook, and Settings.
 * All text styles enforce a strict >= 11.sp floor for legibility and respond to system fontScale.
 */
@Immutable
data class StudioCompactTypography(
    val screenTitle: TextStyle,
    val cardTitle: TextStyle,
    val body: TextStyle,
    val tabOrChip: TextStyle,
    val navLabel: TextStyle,
    val monoPreview: TextStyle
) {
    companion object {
        fun forTokens(t: StudioSurfaceTokens): StudioCompactTypography = StudioCompactTypography(
            screenTitle = TextStyle(
                fontFamily = t.headingFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                letterSpacing = t.headingLetterSpacing
            ),
            cardTitle = TextStyle(
                fontFamily = t.headingFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                letterSpacing = t.headingLetterSpacing * 0.5f
            ),
            body = TextStyle(
                fontFamily = t.bodyFont,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                letterSpacing = 0.1.sp
            ),
            tabOrChip = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                letterSpacing = 0.1.sp
            ),
            navLabel = TextStyle(
                fontFamily = t.bodyFont,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.1.sp
            ),
            monoPreview = TextStyle(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                letterSpacing = 0.sp
            )
        )
    }
}

@Immutable
data class StudioSurfaceTokens(
    val isDark: Boolean,
    val baseCanvas: Color,
    val layer1: Color,
    val layer2: Color,
    val layer3: Color,
    val floatingGlass: Color,
    val borderStart: Color,
    val borderEnd: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentMuted: Color,
    val onAccent: Color,
    val secondaryAccent: Color,
    val onSecondaryAccent: Color,
    val primaryButtonContainer: Color,
    val primaryButtonContent: Color,
    val focusRingColor: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
    val info: Color,
    // Holistic theme geometry, typography, density, motion & indicators
    val cardShape: Shape,
    val buttonShape: Shape,
    val smallShape: Shape,
    val pillShape: Shape,
    val sheetShape: Shape,
    val borderWidth: Dp,
    val glowRadius: Dp,
    val glowColor: Color,
    val cursorGlowColor: Color,
    val headingFont: FontFamily,
    val headingLetterSpacing: TextUnit,
    val headingUppercase: Boolean,
    val bodyFont: FontFamily,
    val density: Float,
    val motionDurationMs: Int,
    val promptGlyph: String,
    val cursorStyle: TerminalCursorStyle,
    val badgeStyle: RiskBadgeStyle,
    val navIndicatorStyle: NavIndicatorStyle,
    val iconStyle: StudioIconStyle,
    val canvasGradientColors: List<Color>
) {
    val thinBorderBrush: Brush
        get() = Brush.linearGradient(colors = listOf(borderStart, borderEnd))

    val accentBorderBrush: Brush
        get() = Brush.linearGradient(
            colors = listOf(focusRingColor.copy(alpha = 0.85f), borderEnd)
        )

    @Stable
    fun spacing(base: Dp): Dp = (base.value * density).dp

    fun formatHeading(text: String): String =
        if (headingUppercase) text.uppercase() else text

    fun withReducedEffects(reduce: Boolean): StudioSurfaceTokens {
        if (!reduce) return this
        return copy(
            glowRadius = 0.dp,
            glowColor = Color.Transparent,
            cursorGlowColor = Color.Transparent,
            motionDurationMs = 0
        )
    }
}

val LocalStudioTokens = staticCompositionLocalOf { AppThemePreset.HACKER_PRO.tokens }

val LocalStudioDimens = staticCompositionLocalOf { StudioDimens() }

val LocalCompactTypography = staticCompositionLocalOf {
    StudioCompactTypography.forTokens(AppThemePreset.HACKER_PRO.tokens)
}

val LocalReduceEffects = staticCompositionLocalOf { false }

object StudioTheme {
    val tokens: StudioSurfaceTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioTokens.current

    val dimens: StudioDimens
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioDimens.current

    val compactTypography: StudioCompactTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalCompactTypography.current

    val reduceEffects: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalReduceEffects.current
}
