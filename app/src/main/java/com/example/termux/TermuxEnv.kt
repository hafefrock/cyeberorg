package com.example.termux

import java.io.File

/** Environment and process arguments for a Termux shell (pure, unit tested). */
object TermuxEnv {

    fun build(
        prefix: File,
        home: File,
        systemEnv: Map<String, String> = System.getenv(),
    ): Array<String> {
        val tmp = File(prefix, "tmp")
        val env = linkedMapOf(
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "HOME" to home.path,
            "PREFIX" to prefix.path,
            "TMPDIR" to tmp.path,
            "PATH" to File(prefix, "bin").path,
            "LANG" to "en_US.UTF-8",
            "TERMUX_VERSION" to "0.118.0",
            "ANDROID_DATA" to (systemEnv["ANDROID_DATA"] ?: "/data"),
            "ANDROID_ROOT" to (systemEnv["ANDROID_ROOT"] ?: "/system"),
        )
        systemEnv["EXTERNAL_STORAGE"]?.let { env["EXTERNAL_STORAGE"] = it }
        val termuxExec = File(prefix, "lib/libtermux-exec.so")
        if (termuxExec.exists()) env["LD_PRELOAD"] = termuxExec.path
        return env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    /** Returns the shell executable: login if present, otherwise sh. */
    fun shell(prefix: File): File {
        val login = File(prefix, "bin/login")
        return if (login.exists()) login else File(prefix, "bin/sh")
    }

    /** Maps a 0..15 palette index to the ANSI SGR code used by the app's color scheme (fg/bg). */
    fun ansiCodeForIndex(index: Int, background: Boolean): Int = when (index) {
        in 0..7 -> (if (background) 40 else 30) + index
        in 8..15 -> (if (background) 40 else 90) + (index - 8)
        else -> -1
    }
}
