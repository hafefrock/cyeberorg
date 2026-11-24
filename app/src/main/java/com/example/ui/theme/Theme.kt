package com.example.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CyberorgTheme(
    appTheme: AppThemePreset = AppThemePreset.HACKER_PRO,
    reduceEffects: Boolean = false,
    content: @Composable () -> Unit
) {
    val t = appTheme.tokens.withReducedEffects(reduceEffects)
    val colorScheme = darkColorScheme(
        primary = t.accent,
        onPrimary = t.onAccent,
        primaryContainer = t.accentMuted,
        onPrimaryContainer = t.accent,
        secondary = t.secondaryAccent,
        onSecondary = t.onSecondaryAccent,
        secondaryContainer = t.layer3,
        onSecondaryContainer = t.textPrimary,
        tertiary = t.info,
        onTertiary = t.onAccent,
        background = t.baseCanvas,
        onBackground = t.textPrimary,
        surface = t.layer1,
        onSurface = t.textPrimary,
        surfaceVariant = t.layer2,
        onSurfaceVariant = t.textSecondary,
        surfaceContainerLowest = t.baseCanvas,
        surfaceContainerLow = t.layer1,
        surfaceContainer = t.layer2,
        surfaceContainerHigh = t.layer3,
        outline = t.borderStart,
        outlineVariant = t.borderEnd,
        error = t.error,
        onError = t.onAccent,
        errorContainer = t.error.copy(alpha = 0.16f),
        onErrorContainer = t.error
    )

    val typography = Typography(
        headlineSmall = TextStyle(
            fontFamily = t.headingFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            letterSpacing = t.headingLetterSpacing
        ),
        titleLarge = TextStyle(
            fontFamily = t.headingFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            letterSpacing = t.headingLetterSpacing
        ),
        titleMedium = TextStyle(
            fontFamily = t.headingFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            letterSpacing = t.headingLetterSpacing
        ),
        titleSmall = TextStyle(
            fontFamily = t.headingFont,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = t.headingLetterSpacing
        ),
        bodyLarge = TextStyle(
            fontFamily = t.bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = t.bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            letterSpacing = 0.1.sp
        ),
        bodySmall = TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            letterSpacing = 0.sp
        ),
        labelLarge = TextStyle(
            fontFamily = t.headingFont,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = t.headingLetterSpacing
        ),
        labelMedium = TextStyle(
            fontFamily = t.headingFont,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            letterSpacing = t.headingLetterSpacing
        ),
        labelSmall = TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.2.sp
        )
    )

    val cornerFallback: CornerBasedShape = (t.smallShape as? CornerBasedShape) ?: RoundedCornerShape(4.dp)
    val cardFallback: CornerBasedShape = (t.cardShape as? CornerBasedShape) ?: RoundedCornerShape(6.dp)
    val shapes = Shapes(
        extraSmall = cornerFallback,
        small = cornerFallback,
        medium = cardFallback,
        large = cardFallback,
        extraLarge = RoundedCornerShape(8.dp)
    )

    CompositionLocalProvider(
        LocalStudioTokens provides t,
        LocalStudioDimens provides StudioDimens(),
        LocalCompactTypography provides StudioCompactTypography.forTokens(t),
        LocalReduceEffects provides reduceEffects
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}
