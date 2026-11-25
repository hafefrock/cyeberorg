package com.example

import com.example.termux.TermuxEnv
import com.example.termux.TermuxStateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            extra = mapOf("CYBERORG_STATE_FILE" to "/x/state"),
        ).toList()
        assertTrue(env.contains("HOME=/data/data/com.termux/files/home"))
        assertTrue(env.contains("PREFIX=/data/data/com.termux/files/usr"))
        assertTrue(env.contains("PATH=/data/data/com.termux/files/usr/bin"))
        assertTrue(env.contains("TERM=xterm-256color"))
        assertTrue(env.contains("ANDROID_ROOT=/system"))
        assertTrue(env.contains("CYBERORG_STATE_FILE=/x/state"))
    }

    @Test
    fun ansiCode_mapsPalette() {
        assertEquals(31, TermuxEnv.ansiCodeForIndex(1, background = false))
        assertEquals(41, TermuxEnv.ansiCodeForIndex(1, background = true))
        assertEquals(92, TermuxEnv.ansiCodeForIndex(10, background = false))
        assertEquals(-1, TermuxEnv.ansiCodeForIndex(256, background = false))
        assertEquals(-1, TermuxEnv.ansiCodeForIndex(100, background = true))
    }

    @Test
    fun approximate_handlesIndexedAndTruecolor() {
        assertEquals(31, TermuxEnv.approximateAnsiCode(1, background = false))
        assertEquals(91, TermuxEnv.approximateAnsiCode(196, background = false)) // cube red
        assertEquals(-1, TermuxEnv.approximateAnsiCode(256, background = false)) // default fg
        assertEquals(-1, TermuxEnv.approximateAnsiCode(257, background = true)) // default bg
        assertEquals(94, TermuxEnv.approximateAnsiCode(0xff5c5cff.toInt(), background = false))
        assertEquals(-1, TermuxEnv.approximateAnsiCode(0xff000000.toInt(), background = true))
    }

    @Test
    fun stateParser_readsAllFields() {
        val st = TermuxStateParser.parse("exit=2\ncwd=/data/data/com.termux/files/home\nstart=100\nend=105\ncmd=ls /nope\n")!!
        assertEquals(2, st.exitCode)
        assertEquals(100L, st.startSec)
        assertEquals(105L, st.endSec)
        assertEquals("ls /nope", st.command)
    }

    @Test
    fun stateParser_emptyStartAndInvalid() {
        val st = TermuxStateParser.parse("exit=0\ncwd=/x\nstart=\nend=5\ncmd=\n")!!
        assertNull(st.startSec)
        assertEquals("", st.command)
        assertNull(TermuxStateParser.parse("garbage"))
    }
}
