package com.example

import com.example.termux.SessionNaming
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionNamingTest {
    @Test
    fun emptyStartsAtOne() = assertEquals(1, SessionNaming.lowestFreeNumber(emptyList()))

    @Test
    fun reusesGap() {
        assertEquals(2, SessionNaming.lowestFreeNumber(listOf(1, 3)))
        assertEquals(4, SessionNaming.lowestFreeNumber(listOf(1, 2, 3)))
        assertEquals(1, SessionNaming.lowestFreeNumber(listOf(2, 3)))
    }
}
