package com.example

import com.example.termux.DistroPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DistroPathsTest {
    @Test
    fun rootfsPath() {
        assertEquals(
            "/p/var/lib/proot-distro/installed-rootfs/ubuntu",
            DistroPaths.rootfs(File("/p"), "ubuntu").path,
        )
    }

    @Test
    fun missingRootfsIsNotInstalled() {
        assertFalse(DistroPaths.isInstalled(File("/definitely/not/here"), "ubuntu"))
    }

    @Test
    fun commandsEndWithEnter() {
        assertTrue(DistroPaths.installCommand("ubuntu").endsWith("\r"))
        assertEquals("proot-distro login ubuntu\r", DistroPaths.loginCommand("ubuntu"))
        assertEquals("proot-distro remove ubuntu\r", DistroPaths.removeCommand("ubuntu"))
        assertTrue(DistroPaths.installCommand("ubuntu").contains("proot-distro install ubuntu"))
    }
}
