package com.example

import com.example.termux.DesktopSetup
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopGeometryTest {
    @Test
    fun portraitUsesFullWidth() {
        assertEquals("720x1120", DesktopSetup.geometryFor(720, 1650))
    }

    @Test
    fun landscapeIsCappedAndAligned() {
        assertEquals("1600x576", DesktopSetup.geometryFor(2000, 720))
    }

    @Test
    fun invalidFallsBack() {
        assertEquals(DesktopSetup.DEFAULT_GEOMETRY, DesktopSetup.geometryFor(0, 0))
    }
}
