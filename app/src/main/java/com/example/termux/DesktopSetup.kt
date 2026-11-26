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

    /**
     * Desktop size (WxH, multiples of 8) that maps roughly 1:1 onto the viewer area for the screen's
     * current orientation, so text stays readable. Pure and tested.
     */
    fun geometryFor(screenWidthPx: Int, screenHeightPx: Int): String {
        if (screenWidthPx <= 0 || screenHeightPx <= 0) return DEFAULT_GEOMETRY
        val landscape = screenWidthPx > screenHeightPx
        val heightShare = if (landscape) 0.8 else 0.68
        val w = (minOf(screenWidthPx, 1600) / 8) * 8
        val h = ((screenHeightPx * heightShare).toInt() / 8) * 8
        return "${maxOf(w, 640)}x${maxOf(h, 360)}"
    }

    /** Copies the bundled scripts into Ubuntu's /root and prepares the phone-storage mount point. */
    fun writeScripts(context: Context, rootfs: File) {
        val dir = File(rootfs, "root")
        dir.mkdirs()
        File(dir, "Android").mkdirs()
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

    fun startCommand(
        distro: String,
        geometry: String = DEFAULT_GEOMETRY,
        shareStorage: Boolean = false
    ): String =
        "proot-distro login $distro${DistroPaths.bindArgs(shareStorage)} -- bash /root/$START_SCRIPT $geometry\r"

    fun stopCommand(distro: String): String =
        "proot-distro login $distro -- pkill Xvnc\r"
}
