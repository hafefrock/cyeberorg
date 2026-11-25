package com.example

import com.example.termux.BootstrapArch
import com.example.termux.BootstrapManifest
import com.example.termux.BootstrapUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootstrapUtilsTest {

    @Test
    fun parseSymlinks_splitsOnArrow() {
        val list = BootstrapUtils.parseSymlinks("busybox←./bin/sh\n\n../x←./lib/y\nbad-line\n")
        assertEquals(2, list.size)
        assertEquals("busybox", list[0].target)
        assertEquals("./bin/sh", list[0].linkPath)
        assertEquals("./lib/y", list[1].linkPath)
    }

    @Test
    fun safeResolve_blocksTraversal() {
        val base = File(System.getProperty("java.io.tmpdir"), "bootstrap-test-base").apply { mkdirs() }
        assertNull(BootstrapUtils.safeResolve(base, "../evil"))
        assertNull(BootstrapUtils.safeResolve(base, "a/../../evil"))
        assertNull(BootstrapUtils.safeResolve(base, "/etc/passwd"))
        assertNull(BootstrapUtils.safeResolve(base, ""))
        assertNotNull(BootstrapUtils.safeResolve(base, "bin/sh"))
        assertNotNull(BootstrapUtils.safeResolve(base, "./bin/sh"))
    }

    @Test
    fun needsExecutable_matchesTermuxRules() {
        assertTrue(BootstrapUtils.needsExecutable("bin/bash"))
        assertTrue(BootstrapUtils.needsExecutable("libexec/foo"))
        assertTrue(BootstrapUtils.needsExecutable("lib/apt/methods/https"))
        assertFalse(BootstrapUtils.needsExecutable("etc/profile"))
    }

    @Test
    fun arch_mapsAbis() {
        assertEquals("aarch64", BootstrapArch.forAbis(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("arm", BootstrapArch.forAbis(listOf("armeabi-v7a")))
        assertEquals("x86_64", BootstrapArch.forAbis(listOf("x86_64", "x86")))
        assertEquals("i686", BootstrapArch.forAbis(listOf("x86")))
        assertNull(BootstrapArch.forAbis(listOf("mips")))
    }

    @Test
    fun manifest_parses() {
        val m = BootstrapManifest.parse(
            """{"tag":"t","archives":{"arm":{"url":"https://x/y.zip","sha256":"AB","size":5}}}""",
        )
        assertEquals("t", m.tag)
        assertEquals("ab", m.archives["arm"]?.sha256)
        assertEquals(5L, m.archives["arm"]?.size)
    }

    @Test
    fun hex_formatsBytes() {
        assertEquals("00ff10", BootstrapUtils.hex(byteArrayOf(0, -1, 16)))
    }
}
