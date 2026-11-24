package com.example.terminal

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.core.content.res.ResourcesCompat
import com.example.R

@Immutable
data class TerminalFontOption(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int
)

@Immutable
data class TerminalCellMetrics(
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val baselineOffsetPx: Float
)

object TerminalFontRegistry {
    const val DEFAULT_FONT_ID: String = "jetbrains_mono"
    const val SYSTEM_MONOSPACE_ID: String = "system_monospace"

    val entries: List<TerminalFontOption> = listOf(
        TerminalFontOption(
            id = DEFAULT_FONT_ID,
            labelRes = R.string.font_jetbrains_mono,
            descriptionRes = R.string.font_jetbrains_mono_desc
        ),
        TerminalFontOption(
            id = SYSTEM_MONOSPACE_ID,
            labelRes = R.string.font_system_monospace,
            descriptionRes = R.string.font_system_monospace_desc
        )
    )

    fun fromId(id: String?): TerminalFontOption {
        return entries.firstOrNull { it.id == id } ?: entries.first()
    }

    fun resolveTypeface(fontId: String?, context: Context): Typeface {
        return when (fromId(fontId).id) {
            SYSTEM_MONOSPACE_ID -> Typeface.MONOSPACE
            else -> runCatching {
                ResourcesCompat.getFont(context, R.font.jetbrains_mono)
            }.getOrNull() ?: Typeface.MONOSPACE
        }
    }

    fun computeCellMetrics(
        textPaint: Paint,
        fontSizePx: Float,
        typeface: Typeface
    ): TerminalCellMetrics {
        val safeFontPx = fontSizePx.coerceAtLeast(1f)
        textPaint.typeface = typeface
        textPaint.textSize = safeFontPx
        val fm = textPaint.fontMetrics
        val cellWidth = textPaint.measureText("M").coerceAtLeast(safeFontPx * 0.55f).coerceAtLeast(1f)
        val rawHeight = (fm.descent - fm.ascent).coerceAtLeast(safeFontPx * 1.25f)
        val cellHeight = rawHeight.coerceAtLeast(1f)
        val baseline = (-fm.ascent).coerceIn(safeFontPx * 0.5f, cellHeight)
        return TerminalCellMetrics(
            cellWidthPx = cellWidth,
            cellHeightPx = cellHeight,
            baselineOffsetPx = baseline
        )
    }
}
