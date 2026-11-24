package com.example.ui.terminal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.example.data.ExtraKeysLayout
import com.example.terminal.ExtraKeyCode
import com.example.ui.theme.StudioElevation
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioGlow

@Composable
fun ExtraKeysBar(
    layout: ExtraKeysLayout,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onKeyClick: (ExtraKeyCode) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val haptic = LocalHapticFeedback.current

    val firstRowCount = (layout.keys.size + 1) / 2
    val firstRowKeys = layout.keys.take(firstRowCount)
    val secondRowKeys = layout.keys.drop(firstRowCount)
    val rows = listOf(firstRowKeys, secondRowKeys)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("extra_keys_bar"),
        color = tokens.layer1,
        tonalElevation = StudioElevation.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(safeContentPadding(tokens.smallShape, StudioSpacing.xs, dimens.extraKeyGap)),
            verticalArrangement = Arrangement.spacedBy(dimens.extraKeyGap)
        ) {
            rows.forEachIndexed { rowIndex, rowKeys ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dimens.extraKeyRowHeight)
                        .testTag("extra_keys_row_${rowIndex + 1}"),
                    horizontalArrangement = Arrangement.spacedBy(dimens.extraKeyGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowKeys.forEach { key ->
                        val isStickyActive = (key == ExtraKeyCode.CTRL && ctrlArmed) ||
                            (key == ExtraKeyCode.ALT && altArmed)

                        ExtraKeyCap(
                            key = key,
                            isActive = isStickyActive,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onKeyClick(key)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtraKeyCap(
    key: ExtraKeyCode,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val bgColor by animateColorAsState(
        targetValue = if (isActive) tokens.accentMuted else tokens.layer2,
        animationSpec = tween(durationMillis = tokens.motionDurationMs),
        label = "keyBg"
    )
    val borderColor = if (isActive) tokens.focusRingColor else tokens.borderStart
    val glowModifier = if (isActive) {
        Modifier.studioGlow(
            color = tokens.glowColor,
            radius = tokens.glowRadius,
            shape = tokens.smallShape
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(glowModifier)
            .background(bgColor, tokens.smallShape)
            .border(tokens.borderWidth, borderColor, tokens.smallShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = dimens.hairlineWidth)
            .testTag("extra_key_${key.name.lowercase()}"),
        contentAlignment = Alignment.Center
    ) {
        val labelStyle = when {
            key.label.length >= 4 -> compactType.tabOrChip.copy(letterSpacing = (-0.6).sp)
            key.label.length > 1 -> compactType.tabOrChip.copy(letterSpacing = (-0.4).sp)
            else -> compactType.tabOrChip
        }
        Text(
            text = key.label,
            style = labelStyle,
            fontWeight = if (key.isModifier || isActive) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            color = when {
                isActive -> tokens.accent
                key.isModifier -> tokens.accent.copy(alpha = 0.88f)
                key.label.length == 1 && !key.name.startsWith("ARROW_") -> tokens.textPrimary
                else -> tokens.textSecondary
            }
        )
    }
}
