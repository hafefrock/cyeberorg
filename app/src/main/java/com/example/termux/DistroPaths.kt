package com.example.termux

import java.io.File

/** proot-distro locations and the commands the Distro menu types into a new session (pure, tested). */
object DistroPaths {
    const val UBUNTU = "ubuntu"

    /** New (OCI based) proot-distro keeps containers here. */
    fun containerRootfs(prefix: File, distro: String): File =
        File(prefix, "var/lib/proot-distro/containers/$distro/rootfs")

    /** Old (tarball based) proot-distro layout. */
    fun legacyRootfs(prefix: File, distro: String): File =
        File(prefix, "var/lib/proot-distro/installed-rootfs/$distro")

    /** The rootfs that exists on disk; the new layout wins, else the legacy one (even if missing). */
    fun rootfs(prefix: File, distro: String): File {
        val modern = containerRootfs(prefix, distro)
        return if (modern.isDirectory) modern else legacyRootfs(prefix, distro)
    }

    fun isInstalled(prefix: File, distro: String): Boolean =
        containerRootfs(prefix, distro).isDirectory || legacyRootfs(prefix, distro).isDirectory

    fun installCommand(distro: String): String =
        "pkg install -y proot-distro && proot-distro install $distro\r"

    fun loginCommand(distro: String): String = "proot-distro login $distro\r"

    fun removeCommand(distro: String): String = "proot-distro remove $distro\r"
}
