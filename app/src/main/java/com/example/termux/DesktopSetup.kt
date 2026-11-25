package com.example.termux

import android.content.Context
import java.io.File

/** Scripts and commands for the Ubuntu desktop (XFCE over a local VNC server). */
object DesktopSetup {
    const val INSTALL_SCRIPT = "cyberorg-install-desktop.sh"
    const val START_SCRIPT = "cyberorg-start-desktop.sh"
    const val MARKER = ".cyberorg-desktop-installed"
    const val DEFAULT_GEOMETRY = "1280x720"

    fun isInstalled(rootfs: File): Boolean = File(rootfs, "root/$MARKER").exists()

    /** Copies the bundled scripts into Ubuntu's /root so they can be run inside proot. */
    fun writeScripts(context: Context, rootfs: File) {
        val dir = File(rootfs, "root")
        dir.mkdirs()
        copyAsset(context, "desktop/install-desktop.sh", File(dir, INSTALL_SCRIPT))
        copyAsset(context, "desktop/start-desktop.sh", File(dir, START_SCRIPT))
    }

    private fun copyAsset(context: Context, name: String, target: File) {
        context.assets.open(name).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }

    fun installCommand(distro: String): String =
        "proot-distro login $distro -- bash /root/$INSTALL_SCRIPT\r"

    fun startCommand(distro: String, geometry: String = DEFAULT_GEOMETRY): String =
        "proot-distro login $distro -- bash /root/$START_SCRIPT $geometry\r"

    fun stopCommand(distro: String): String =
        "proot-distro login $distro -- pkill Xvnc\r"
}
