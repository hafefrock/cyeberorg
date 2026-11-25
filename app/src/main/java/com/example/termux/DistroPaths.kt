package com.example.termux

import java.io.File

/** proot-distro locations and the commands the Distro menu types into a new session (pure, tested). */
object DistroPaths {
    const val UBUNTU = "ubuntu"

    fun rootfs(prefix: File, distro: String): File =
        File(prefix, "var/lib/proot-distro/installed-rootfs/$distro")

    fun isInstalled(prefix: File, distro: String): Boolean = rootfs(prefix, distro).isDirectory

    fun installCommand(distro: String): String =
        "pkg install -y proot-distro && proot-distro install $distro\r"

    fun loginCommand(distro: String): String = "proot-distro login $distro\r"

    fun removeCommand(distro: String): String = "proot-distro remove $distro\r"
}
