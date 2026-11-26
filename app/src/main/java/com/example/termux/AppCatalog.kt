package com.example.termux

import androidx.annotation.StringRes
import com.example.R
import java.io.File

/** An app that can be installed inside Ubuntu with one tap. The first package is the one used to detect "installed". */
data class LinuxApp(
    val id: String,
    val name: String,
    @StringRes val descRes: Int,
    val approxMb: Int,
    val packages: List<String>,
    val preInstall: String = ""
)

object AppCatalog {
    private val firefoxPreInstall = """
apt-get install -y --no-install-recommends ca-certificates wget gnupg
install -d -m 0755 /etc/apt/keyrings
wget -q https://packages.mozilla.org/apt/repo-signing-key.gpg -O /etc/apt/keyrings/packages.mozilla.org.asc
echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main" > /etc/apt/sources.list.d/mozilla.list
printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' > /etc/apt/preferences.d/mozilla
apt-get update
""".trim()

    val apps: List<LinuxApp> = listOf(
        LinuxApp("firefox", "Firefox", R.string.app_desc_firefox, 300, listOf("firefox"), firefoxPreInstall),
        LinuxApp("mousepad", "Mousepad", R.string.app_desc_mousepad, 5, listOf("mousepad")),
        LinuxApp("geany", "Geany", R.string.app_desc_geany, 15, listOf("geany")),
        LinuxApp("python", "Python 3", R.string.app_desc_python, 40, listOf("python3-pip", "python3", "python3-venv")),
        LinuxApp("node", "Node.js", R.string.app_desc_node, 150, listOf("nodejs", "npm")),
        LinuxApp("git", "Git", R.string.app_desc_git, 30, listOf("git")),
        LinuxApp(
            "libreoffice", "LibreOffice", R.string.app_desc_libreoffice, 700,
            listOf("libreoffice-writer", "libreoffice-calc", "libreoffice-impress", "libreoffice-gtk3")
        ),
        LinuxApp("gimp", "GIMP", R.string.app_desc_gimp, 300, listOf("gimp")),
        LinuxApp("evince", "Evince", R.string.app_desc_evince, 40, listOf("evince")),
        LinuxApp("tools", "htop, curl, nano, vim", R.string.app_desc_tools, 25, listOf("htop", "curl", "wget", "nano", "vim"))
    )

    fun scriptFileName(app: LinuxApp): String = "cyberorg-app-${app.id}.sh"

    /** The shell script that runs inside Ubuntu (pure, tested). */
    fun script(app: LinuxApp): String = buildString {
        appendLine("#!/bin/bash")
        appendLine("set -e")
        appendLine("export DEBIAN_FRONTEND=noninteractive")
        appendLine("echo '== Cyberorg: installing ${app.name} =='")
        appendLine("apt-get update")
        if (app.preInstall.isNotBlank()) appendLine(app.preInstall)
        appendLine("apt-get install -y --no-install-recommends ${app.packages.joinToString(" ")}")
        appendLine("echo '== Done. =='")
    }

    fun installCommand(distro: String, app: LinuxApp): String =
        "proot-distro login $distro -- bash /root/${scriptFileName(app)}\r"

    fun writeScript(rootfs: File, app: LinuxApp) {
        val dir = File(rootfs, "root")
        dir.mkdirs()
        File(dir, scriptFileName(app)).writeText(script(app))
    }

    /** True when the app's main package has a dpkg file list in the rootfs. */
    fun isInstalled(rootfs: File, app: LinuxApp): Boolean {
        val main = app.packages.firstOrNull() ?: return false
        val names = File(rootfs, "var/lib/dpkg/info").list() ?: return false
        return names.any { it == "$main.list" || (it.startsWith("$main:") && it.endsWith(".list")) }
    }
}
