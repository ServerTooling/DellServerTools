package com.lilayam.dellservertools.core.vnc

/** X11 keysyms, which is what VNC key events carry. */
object Keysyms {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val ENTER = 0xff0d
    const val ESCAPE = 0xff1b
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val INSERT = 0xff63
    const val DELETE = 0xffff
    const val SHIFT = 0xffe1
    const val CTRL = 0xffe3
    const val ALT = 0xffe9
    const val SUPER = 0xffeb
    const val F1 = 0xffbe

    fun f(n: Int): Int {
        require(n in 1..12) { "F$n" }
        return F1 + n - 1
    }

    /** The keysym for a typed character: Latin-1 maps to itself, newline is Enter, anything else Unicode. */
    fun forChar(c: Char): Int = when {
        c == '\n' -> ENTER
        c == '\t' -> TAB
        c.code in 0x20..0x7e || c.code in 0xa0..0xff -> c.code
        else -> 0x01000000 or c.code
    }
}
