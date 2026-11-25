package com.example.termux.vnc

/** X11 keysyms for the keys the desktop viewer sends (pure, tested). */
object VncKeys {
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val DELETE = 0xFFFF
    const val SHIFT_L = 0xFFE1
    const val CONTROL_L = 0xFFE3
    const val ALT_L = 0xFFE9

    const val BUTTON_LEFT = 1
    const val BUTTON_RIGHT = 4
    const val WHEEL_UP = 8
    const val WHEEL_DOWN = 16

    /** Keysym for a typed character: Latin-1 maps to itself, other code points use the Unicode range. */
    fun keysymForChar(codePoint: Int): Int = when {
        codePoint == '\n'.code || codePoint == '\r'.code -> RETURN
        codePoint == '\t'.code -> TAB
        codePoint in 0x20..0x7E || codePoint in 0xA0..0xFF -> codePoint
        codePoint > 0xFF -> 0x01000000 + codePoint
        else -> 0
    }

    /** Letters need Ctrl+<lowercase letter>; returns the keysym or 0. */
    fun ctrlLetter(codePoint: Int): Int = when (codePoint) {
        in 'a'.code..'z'.code -> codePoint
        in 'A'.code..'Z'.code -> codePoint + 32
        else -> 0
    }
}
