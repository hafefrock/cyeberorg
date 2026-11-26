package com.example

import com.example.termux.AppCatalog
import com.example.termux.DesktopSetup
import com.example.termux.DistroPaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxFeaturesTest {
    @Test
    fun storageBindIsOptional() {
        assertEquals("proot-distro login ubuntu\r", DistroPaths.loginCommand("ubuntu"))
        assertEquals(
            "proot-distro login ubuntu --bind /storage/emulated/0:/root/Android\r",
            DistroPaths.loginCommand("ubuntu", shareStorage = true)
        )
        assertTrue(
            DesktopSetup.startCommand("ubuntu", "720x1120", shareStorage = true)
                .startsWith("proot-distro login ubuntu --bind /storage/emulated/0:/root/Android -- bash ")
        )
        assertTrue(DesktopSetup.startCommand("ubuntu", "720x1120").contains(" -- bash /root/"))
    }

    @Test
    fun appIdsAreUniqueAndScriptsInstallTheirPackages() {
        val ids = AppCatalog.apps.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        AppCatalog.apps.forEach { app ->
            val script = AppCatalog.script(app)
            assertTrue(script.startsWith("#!/bin/bash"))
            app.packages.forEach { assertTrue(script.contains(it)) }
            assertTrue(AppCatalog.installCommand("ubuntu", app).contains(AppCatalog.scriptFileName(app)))
        }
        val firefox = AppCatalog.apps.first { it.id == "firefox" }
        assertTrue(AppCatalog.script(firefox).contains("packages.mozilla.org"))
    }

    @Test
    fun installedIsDetectedFromDpkgFileLists() {
        val rootfs = File.createTempFile("rootfs", "").also { it.delete(); it.mkdirs() }
        val info = File(rootfs, "var/lib/dpkg/info").also { it.mkdirs() }
        val git = AppCatalog.apps.first { it.id == "git" }
        val python = AppCatalog.apps.first { it.id == "python" }
        assertFalse(AppCatalog.isInstalled(rootfs, git))
        File(info, "git.list").writeText("")
        File(info, "python3-pip:all.list").writeText("")
        assertTrue(AppCatalog.isInstalled(rootfs, git))
        assertTrue(AppCatalog.isInstalled(rootfs, python))
        rootfs.deleteRecursively()
    }
}
