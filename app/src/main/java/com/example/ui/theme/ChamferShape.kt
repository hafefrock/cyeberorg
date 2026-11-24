package com.example.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Chamfered polygon slicing the top-end (top-right in LTR) and bottom-start (bottom-left in LTR)
 * corners. Cuts are clamped safely into [0, min(width, height) / 2] so vertices never escape bounds.
 */
class ChamferShape(
    val cutTopEnd: Dp,
    val cutBottomStart: Dp = cutTopEnd
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) {
            return Outline.Rectangle(
                Rect(0f, 0f, size.width.coerceAtLeast(0f), size.height.coerceAtLeast(0f))
            )
        }
        val topEndPx = with(density) { cutTopEnd.toPx() }
        val bottomStartPx = with(density) { cutBottomStart.toPx() }
        val vertices = computeVertices(
            size = size,
            topEndPx = topEndPx,
            bottomStartPx = bottomStartPx,
            isRtl = layoutDirection == LayoutDirection.Rtl
        )
        val path = Path().apply {
            moveTo(vertices.first().x, vertices.first().y)
            for (i in 1 until vertices.size) {
                lineTo(vertices[i].x, vertices[i].y)
            }
            close()
        }
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChamferShape) return false
        return cutTopEnd == other.cutTopEnd && cutBottomStart == other.cutBottomStart
    }

    override fun hashCode(): Int {
        var result = cutTopEnd.hashCode()
        result = 31 * result + cutBottomStart.hashCode()
        return result
    }

    companion object {
        fun computeVertices(
            size: Size,
            topEndPx: Float,
            bottomStartPx: Float,
            isRtl: Boolean = false
        ): List<Offset> {
            val w = size.width.coerceAtLeast(0f)
            val h = size.height.coerceAtLeast(0f)
            val maxCut = minOf(w, h) / 2f
            val te = topEndPx.coerceIn(0f, maxCut)
            val bs = bottomStartPx.coerceIn(0f, maxCut)

            return if (!isRtl) {
                listOf(
                    Offset(0f, 0f),
                    Offset((w - te).coerceIn(0f, w), 0f),
                    Offset(w, te.coerceIn(0f, h)),
                    Offset(w, h),
                    Offset(bs.coerceIn(0f, w), h),
                    Offset(0f, (h - bs).coerceIn(0f, h))
                )
            } else {
                listOf(
                    Offset(te.coerceIn(0f, w), 0f),
                    Offset(w, 0f),
                    Offset(w, (h - bs).coerceIn(0f, h)),
                    Offset((w - bs).coerceIn(0f, w), h),
                    Offset(0f, h),
                    Offset(0f, te.coerceIn(0f, h))
                )
            }
        }

        val None: Shape = ChamferShape(0.dp, 0.dp)
    }
}
