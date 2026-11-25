package com.example

import com.example.termux.DistroPaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistroPathsTest {
    private fun tempPrefix(): File =
        File.createTempFile("prefix", "").also { it.delete(); it.mkdirs() }

    @Test
    fun modernLayoutIsDetected() {
        val prefix = tempPrefix()
        assertFalse(DistroPaths.isInstalled(prefix, "ubuntu"))
        val rootfs = File(prefix, "var/lib/proot-distro/containers/ubuntu/rootfs")
        assertTrue(rootfs.mkdirs())
        assertTrue(DistroPaths.isInstalled(prefix, "ubuntu"))
        assertEquals(rootfs, DistroPaths.rootfs(prefix, "ubuntu"))
        prefix.deleteRecursively()
    }

    @Test
    fun legacyLayoutStillDetected() {
        val prefix = tempPrefix()
        val legacy = File(prefix, "var/lib/proot-distro/installed-rootfs/ubuntu")
        assertTrue(legacy.mkdirs())
        assertTrue(DistroPaths.isInstalled(prefix, "ubuntu"))
        assertEquals(legacy, DistroPaths.rootfs(prefix, "ubuntu"))
        prefix.deleteRecursively()
    }

    @Test
    fun commandsEndWithCarriageReturn() {
        assertEquals("proot-distro login ubuntu\r", DistroPaths.loginCommand("ubuntu"))
        assertEquals("proot-distro remove ubuntu\r", DistroPaths.removeCommand("ubuntu"))
        assertTrue(DistroPaths.installCommand("ubuntu").endsWith("proot-distro install ubuntu\r"))
    }
}
