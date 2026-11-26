package com.example.ui.desktop

import kotlin.math.abs

/** Where the framebuffer is drawn inside the viewer box: scale and top-left offset in pixels. */
data class ViewState(val scale: Float, val ox: Float, val oy: Float)

/** Zoom/pan math for the desktop viewer (pure, tested). Pan is relative to the centred position. */
object ViewTransform {
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 4f

    fun baseScale(fbW: Int, fbH: Int, boxW: Int, boxH: Int): Float {
        if (fbW <= 0 || fbH <= 0 || boxW <= 0 || boxH <= 0) return 1f
        return minOf(boxW.toFloat() / fbW, boxH.toFloat() / fbH)
    }

    fun clampPan(
        fbW: Int, fbH: Int, boxW: Int, boxH: Int,
        zoom: Float, panX: Float, panY: Float
    ): Pair<Float, Float> {
        val s = baseScale(fbW, fbH, boxW, boxH) * zoom
        val extraX = maxOf(0f, fbW * s - boxW) / 2f
        val extraY = maxOf(0f, fbH * s - boxH) / 2f
        return panX.coerceIn(-extraX, extraX) to panY.coerceIn(-extraY, extraY)
    }

    fun view(
        fbW: Int, fbH: Int, boxW: Int, boxH: Int,
        zoom: Float, panX: Float, panY: Float
    ): ViewState {
        val s = baseScale(fbW, fbH, boxW, boxH) * zoom
        val (px, py) = clampPan(fbW, fbH, boxW, boxH, zoom, panX, panY)
        val ox = (boxW - fbW * s) / 2f + px
        val oy = (boxH - fbH * s) / 2f + py
        return ViewState(s, ox, oy)
    }

    /** New (clamped) pan so the framebuffer point under ([cx],[cy]) stays put when zoom becomes [newZoom]. */
    fun panForZoom(
        fbW: Int, fbH: Int, boxW: Int, boxH: Int,
        old: ViewState, newZoom: Float, cx: Float, cy: Float
    ): Pair<Float, Float> {
        val zoom = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val s = baseScale(fbW, fbH, boxW, boxH) * zoom
        val qx = (cx - old.ox) / old.scale
        val qy = (cy - old.oy) / old.scale
        val ox = cx - qx * s
        val oy = cy - qy * s
        val px = ox - (boxW - fbW * s) / 2f
        val py = oy - (boxH - fbH * s) / 2f
        return clampPan(fbW, fbH, boxW, boxH, zoom, px, py)
    }

    fun nearlyEqual(a: Float, b: Float, eps: Float = 0.001f): Boolean = abs(a - b) < eps
}
