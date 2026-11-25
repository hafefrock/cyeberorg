package com.example

import com.example.termux.TermuxEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TermuxEnvTest {

    @Test
    fun build_containsCoreVariables() {
        val env = TermuxEnv.build(
            prefix = File("/data/data/com.termux/files/usr"),
            home = File("/data/data/com.termux/files/home"),
            systemEnv = mapOf("ANDROID_DATA" to "/data"),
        ).toList()
        assertTrue(env.contains("HOME=/data/data/com.termux/files/home"))
        assertTrue(env.contains("PREFIX=/data/data/com.termux/files/usr"))
        assertTrue(env.contains("PATH=/data/data/com.termux/files/usr/bin"))
        assertTrue(env.contains("TERM=xterm-256color"))
        assertTrue(env.contains("ANDROID_ROOT=/system"))
    }

    @Test
    fun ansiCode_mapsPalette() {
        assertEquals(31, TermuxEnv.ansiCodeForIndex(1, background = false))
        assertEquals(41, TermuxEnv.ansiCodeForIndex(1, background = true))
        assertEquals(92, TermuxEnv.ansiCodeForIndex(10, background = false))
        assertEquals(-1, TermuxEnv.ansiCodeForIndex(256, background = false))
        assertEquals(-1, TermuxEnv.ansiCodeForIndex(100, background = true))
    }
}
