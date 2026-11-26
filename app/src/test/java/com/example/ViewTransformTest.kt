package com.example

import com.example.ui.desktop.ViewTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewTransformTest {
    @Test
    fun fitCentersTheFramebuffer() {
        val v = ViewTransform.view(1000, 500, 500, 500, 1f, 0f, 0f)
        assertEquals(0.5f, v.scale, 0.0001f)
        assertEquals(0f, v.ox, 0.0001f)
        assertEquals(125f, v.oy, 0.0001f)
    }

    @Test
    fun panIsClampedToTheImage() {
        val (px, py) = ViewTransform.clampPan(1000, 500, 500, 500, 2f, 9999f, 9999f)
        assertEquals(250f, px, 0.0001f) // image 1000 wide in a 500 box
        assertEquals(0f, py, 0.0001f) // image 500 tall fits the 500 box
    }

    @Test
    fun zoomKeepsThePointUnderTheFingers() {
        val old = ViewTransform.view(1000, 500, 500, 500, 1f, 0f, 0f)
        val cx = 300f
        val cy = 250f
        val qx = (cx - old.ox) / old.scale
        val (px, py) = ViewTransform.panForZoom(1000, 500, 500, 500, old, 2f, cx, cy)
        val v = ViewTransform.view(1000, 500, 500, 500, 2f, px, py)
        assertEquals(cx, v.ox + qx * v.scale, 0.01f)
        assertTrue(v.scale > old.scale)
    }
}
