package com.example.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.R

/**
 * Radio-selectable live preview card rendered with [preset]'s own holistic tokens:
 * displays a prompt line + cursor style, a mini command card, and a risk badge.
 */
@Composable
fun ThemeOptionPreviewCard(
    preset: AppThemePreset,
    selected: Boolean,
    reduceEffects: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val previewTokens = remember(preset, reduceEffects) {
        preset.tokens.withReducedEffects(reduceEffects)
    }
    val termPalette = preset.terminalScheme
    val title = stringResource(preset.labelRes)
    val subtitle = stringResource(preset.descriptionRes)
    val cardContentDesc = stringResource(R.string.cd_theme_preview_card, title, subtitle)

    val outerBorder = if (selected) {
        BorderStroke(previewTokens.borderWidth + dimens.selectedBorderDelta, previewTokens.accent)
    } else {
        BorderStroke(previewTokens.borderWidth, previewTokens.borderStart)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.studioGlow(
                        color = previewTokens.glowColor,
                        radius = previewTokens.glowRadius,
                        shape = previewTokens.cardShape
                    )
                } else {
                    Modifier
                }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.minTouchTarget)
                .background(previewTokens.layer1, previewTokens.cardShape)
                .border(outerBorder, previewTokens.cardShape)
                .clickable(role = Role.RadioButton, onClick = onSelect)
                .semantics {
                    role = Role.RadioButton
                    this.selected = selected
                    contentDescription = cardContentDesc
                }
                .testTag("theme_preview_${preset.id}")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(safeContentPadding(previewTokens.cardShape, dimens.cardPaddingH, dimens.cardPaddingV)),
                verticalArrangement = Arrangement.spacedBy(dimens.listSpacing)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = previewTokens.formatHeading(title),
                            fontFamily = previewTokens.headingFont,
                            letterSpacing = previewTokens.headingLetterSpacing,
                            style = compactType.cardTitle,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) previewTokens.accent else previewTokens.textPrimary
                        )
                        Spacer(modifier = Modifier.height(StudioSpacing.xxs))
                        Text(
                            text = subtitle,
                            fontFamily = previewTokens.bodyFont,
                            style = compactType.body,
                            color = previewTokens.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(StudioSpacing.sm))
                    Box(
                        modifier = Modifier
                            .size(dimens.radioIndicatorSize)
                            .background(
                                if (selected) previewTokens.accent else previewTokens.layer3,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = previewTokens.onAccent,
                                modifier = Modifier.size(dimens.iconSizeSmall)
                            )
                        }
                    }
                }

                // Live mini preview using preset's own geometry, typography, prompt & badge style
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = previewTokens.baseCanvas,
                    shape = previewTokens.smallShape,
                    border = BorderStroke(previewTokens.borderWidth, previewTokens.borderStart.copy(alpha = 0.7f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(safeContentPadding(previewTokens.smallShape, StudioSpacing.sm, StudioSpacing.sm)),
                        verticalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm)
                        ) {
                            Text(
                                text = previewTokens.promptGlyph,
                                style = compactType.body,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                color = termPalette.promptColor
                            )
                            Text(
                                text = stringResource(R.string.theme_preview_prompt_cmd, ""),
                                style = compactType.monoPreview,
                                fontFamily = JetBrainsMonoFamily,
                                color = termPalette.foreground
                            )
                            when (previewTokens.cursorStyle) {
                                TerminalCursorStyle.BLOCK -> {
                                    Box(
                                        modifier = Modifier
                                            .size(
                                                width = dimens.cursorBlockWidth,
                                                height = dimens.cursorBlockHeight
                                            )
                                            .background(termPalette.cursor)
                                    )
                                }
                                TerminalCursorStyle.UNDERLINE_GLOW -> {
                                    Box(
                                        modifier = Modifier
                                            .width(dimens.cursorUnderlineWidth)
                                            .height(dimens.cursorBlockHeight),
                                        contentAlignment = Alignment.BottomCenter
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(dimens.cursorUnderlineThickness)
                                                .studioGlow(
                                                    color = previewTokens.cursorGlowColor,
                                                    radius = previewTokens.glowRadius,
                                                    shape = previewTokens.smallShape
                                                )
                                                .background(termPalette.cursor)
                                        )
                                    }
                                }
                            }
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = previewTokens.layer2,
                            shape = previewTokens.cardShape,
                            border = BorderStroke(previewTokens.borderWidth, previewTokens.borderStart)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        safeContentPadding(
                                            previewTokens.cardShape,
                                            dimens.cardPaddingH,
                                            StudioSpacing.xs
                                        )
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(StudioSpacing.sm),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Terminal,
                                        contentDescription = null,
                                        tint = previewTokens.accent,
                                        modifier = Modifier.size(dimens.iconSizeSmall)
                                    )
                                    Text(
                                        text = stringResource(R.string.theme_preview_card_cmd),
                                        fontFamily = previewTokens.bodyFont,
                                        style = compactType.monoPreview,
                                        color = previewTokens.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(StudioSpacing.xs))

                                when (previewTokens.badgeStyle) {
                                    RiskBadgeStyle.BRACKET_MONO -> {
                                        Surface(
                                            color = previewTokens.layer1,
                                            shape = previewTokens.smallShape,
                                            border = BorderStroke(previewTokens.borderWidth, previewTokens.borderStart)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.risk_badge_low_bracket),
                                                style = compactType.tabOrChip,
                                                fontWeight = FontWeight.Bold,
                                                color = previewTokens.textSecondary,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(
                                                    safeContentPadding(
                                                        previewTokens.smallShape,
                                                        StudioSpacing.xs,
                                                        StudioSpacing.xxs
                                                    )
                                                )
                                            )
                                        }
                                    }
                                    RiskBadgeStyle.CHAMFER_ICON -> {
                                        Surface(
                                            color = previewTokens.layer3,
                                            shape = previewTokens.pillShape,
                                            border = BorderStroke(previewTokens.borderWidth, previewTokens.borderStart)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(
                                                    safeContentPadding(
                                                        previewTokens.pillShape,
                                                        StudioSpacing.sm,
                                                        StudioSpacing.xxs
                                                    )
                                                ),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Shield,
                                                    contentDescription = null,
                                                    tint = previewTokens.textSecondary,
                                                    modifier = Modifier.size(dimens.badgeIconSize)
                                                )
                                                Text(
                                                    text = stringResource(R.string.risk_badge_low),
                                                    style = compactType.tabOrChip,
                                                    fontWeight = FontWeight.Bold,
                                                    color = previewTokens.textSecondary,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
