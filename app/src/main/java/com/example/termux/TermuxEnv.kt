package com.example.termux

import java.io.File

/** Environment and process arguments for a Termux shell (pure, unit tested). */
object TermuxEnv {

    private val BASE16 = intArrayOf(
        0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
        0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff,
    )
    private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

    fun build(
        prefix: File,
        home: File,
        systemEnv: Map<String, String> = System.getenv(),
        extra: Map<String, String> = emptyMap(),
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
        env.putAll(extra)
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

    private fun rgbForIndex(i: Int): Int = when (i) {
        in 0..15 -> BASE16[i]
        in 16..231 -> {
            val n = i - 16
            (CUBE[n / 36] shl 16) or (CUBE[(n / 6) % 6] shl 8) or CUBE[n % 6]
        }
        in 232..255 -> {
            val v = 8 + 10 * (i - 232)
            (v shl 16) or (v shl 8) or v
        }
        else -> -1
    }

    private fun nearestBase16(rgb: Int): Int {
        var best = 0
        var bestDist = Long.MAX_VALUE
        for (i in BASE16.indices) {
            val c = BASE16[i]
            val dr = ((rgb shr 16) and 0xff) - ((c shr 16) and 0xff)
            val dg = ((rgb shr 8) and 0xff) - ((c shr 8) and 0xff)
            val db = (rgb and 0xff) - (c and 0xff)
            val d = (dr * dr + dg * dg + db * db).toLong()
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /**
     * Converts a Termux cell color (palette index 0..258 or 0xffRRGGBB truecolor) to the nearest
     * ANSI SGR code supported by the app's terminal color scheme; -1 means theme default.
     */
    fun approximateAnsiCode(color: Int, background: Boolean): Int {
        val rgb = if ((color ushr 24) == 0xff) {
            color and 0xffffff
        } else {
            if (color !in 0..255) return -1
            rgbForIndex(color)
        }
        val idx = nearestBase16(rgb)
        // Keep the theme background for pure black backgrounds.
        if (background && idx == 0) return -1
        return ansiCodeForIndex(idx, background)
    }
}
