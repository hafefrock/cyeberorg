package com.example.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lightweight neon halo drawn in [drawBehind] using two low-alpha outline strokes.
 * No heavy RenderEffect/BlurMaskFilter allocations, and becomes a true no-op when
 * [radius] <= 0.dp or [color] is transparent (including when Reduce Effects is enabled).
 */
fun Modifier.studioGlow(
    color: Color,
    radius: Dp,
    shape: Shape = RectangleShape
): Modifier {
    if (radius <= 0.dp || color.alpha <= 0.001f) {
        return this
    }
    return this.drawBehind {
        if (size.width <= 0f || size.height <= 0f) return@drawBehind
        val outline = shape.createOutline(size, layoutDirection, this)
        val radiusPx = radius.toPx()
        drawOutline(
            outline = outline,
            color = color.copy(alpha = (color.alpha * 0.42f).coerceIn(0f, 1f)),
            style = Stroke(width = (radiusPx * 1.4f).coerceAtLeast(2f))
        )
        drawOutline(
            outline = outline,
            color = color.copy(alpha = (color.alpha * 0.85f).coerceIn(0f, 1f)),
            style = Stroke(width = (radiusPx * 0.65f).coerceAtLeast(1.2f))
        )
    }
}

/**
 * Ensures compact interactive controls keep at least a [minSize] (default 48.dp) touch target:
 * - When unconstrained by a visual token modifier (`height`/`size`), expands layout bounds to [minSize]
 *   within incoming constraints (never overflowing a parent's max constraints).
 * - When constrained by an explicit outer token size (`36.dp`, `32.dp`, `28.dp`, `20.dp`), preserves
 *   the compact visual bounds while Compose UI's `NodeCoordinator.touchBoundsInRoot` /
 *   `hitInMinimumTouchTarget` keeps interactive touch bounds >= 48.dp x 48.dp.
 */
fun Modifier.studioTouchTarget(
    minSize: Dp = 48.dp,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") onOutOfBoundsTap: (() -> Unit)? = null
): Modifier {
    if (!enabled) return this
    return this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val minPx = minSize.roundToPx()
        val width = maxOf(placeable.width, minPx).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = maxOf(placeable.height, minPx).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            val x = (width - placeable.width) / 2
            val y = (height - placeable.height) / 2
            placeable.placeRelative(x, y)
        }
    }
}

