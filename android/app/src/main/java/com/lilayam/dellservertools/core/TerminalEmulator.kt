package com.lilayam.dellservertools.core

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * A small VT100-style screen emulator.
 *
 * It understands enough of the escape sequences used by the iDRAC6 shell, Linux
 * shells and the PowerEdge BIOS serial redirection (cursor movement, erase,
 * scrolling) to render them as plain text. Colors and other attributes are
 * ignored. Lines that scroll off the top of the screen go to a scrollback buffer.
 */
class TerminalEmulator(
    val columns: Int = 80,
    val rows: Int = 24,
    private val maxScrollback: Int = 2000,
) {
    data class Snapshot(val lines: List<String>, val cursorLine: Int, val cursorColumn: Int)

    private val scrollback = ArrayDeque<String>()
    private var screen = Array(rows) { blankRow() }
    private var cursorRow = 0
    private var cursorCol = 0
    private var wrapPending = false
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var savedRow = 0
    private var savedCol = 0

    private enum class State { NORMAL, ESCAPE, CSI, OSC, OSC_ESCAPE, CHARSET }

    private var state = State.NORMAL
    private val csiParams = StringBuilder()

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pendingBytes = ByteArray(0)

    @Synchronized
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        val input = ByteBuffer.wrap(pendingBytes + bytes.copyOfRange(offset, offset + length))
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        pendingBytes = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        while (out.hasRemaining()) {
            process(out.get())
        }
    }

    @Synchronized
    fun feed(text: String) {
        text.forEach { process(it) }
    }

    @Synchronized
    fun clear() {
        scrollback.clear()
        screen = Array(rows) { blankRow() }
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
    }

    /**
     * Scrollback plus the visible screen. Trailing blank screen rows below the
     * cursor are dropped so a mostly empty screen doesn't show as padding.
     */
    @Synchronized
    fun snapshot(): Snapshot {
        val lines = ArrayList<String>(scrollback.size + rows)
        lines.addAll(scrollback)
        var lastRow = cursorRow
        for (r in rows - 1 downTo cursorRow + 1) {
            if (screen[r].any { it != ' ' }) {
                lastRow = r
                break
            }
        }
        for (r in 0..lastRow) {
            lines.add(String(screen[r]).trimEnd())
        }
        return Snapshot(lines, scrollback.size + cursorRow, cursorCol)
    }

    private fun blankRow() = CharArray(columns) { ' ' }

    private fun process(c: Char) {
        when (state) {
            State.NORMAL -> processNormal(c)
            State.ESCAPE -> processEscape(c)
            State.CSI -> processCsi(c)
            State.OSC -> when (c) {
                '\u0007' -> state = State.NORMAL
                '\u001b' -> state = State.OSC_ESCAPE
                else -> Unit
            }
            State.OSC_ESCAPE -> state = if (c == '\\') State.NORMAL else State.OSC
            State.CHARSET -> state = State.NORMAL
        }
    }

    private fun processNormal(c: Char) {
        when (c) {
            '\u001b' -> state = State.ESCAPE
            '\r' -> {
                cursorCol = 0
                wrapPending = false
            }
            '\n', '\u000b', '\u000c' -> lineFeed()
            '\b' -> {
                if (cursorCol > 0) cursorCol--
                wrapPending = false
            }
            '\t' -> {
                cursorCol = minOf(columns - 1, (cursorCol / 8 + 1) * 8)
                wrapPending = false
            }
            '\u0007', '\u0000', '\u000e', '\u000f', '\u007f' -> Unit
            else -> if (c >= ' ') putChar(c)
        }
    }

    private fun processEscape(c: Char) {
        state = State.NORMAL
        when (c) {
            '[' -> {
                csiParams.setLength(0)
                state = State.CSI
            }
            ']' -> state = State.OSC
            '(', ')', '*', '+' -> state = State.CHARSET
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> {
                cursorCol = 0
                lineFeed()
            }
            'M' -> reverseIndex()
            'c' -> {
                clear()
                scrollTop = 0
                scrollBottom = rows - 1
            }
            else -> Unit
        }
    }

    private fun processCsi(c: Char) {
        if (c in '0'..'9' || c == ';' || c == '?' || c == '>' || c == '!' || c == '=' || c == ' ') {
            if (csiParams.length < 64) csiParams.append(c)
            return
        }
        state = State.NORMAL
        if (c.code !in 0x40..0x7e) return

        val isPrivate = csiParams.startsWith("?")
        val params = csiParams.toString().trimStart('?', '>', '!', '=').trim()
            .split(';').map { it.trim().toIntOrNull() }

        fun arg(i: Int, default: Int): Int = params.getOrNull(i)?.takeIf { it != 0 } ?: default
        fun rawArg(i: Int): Int = params.getOrNull(i) ?: 0

        wrapPending = false
        when (c) {
            'A' -> cursorRow = maxOf(if (cursorRow >= scrollTop) scrollTop else 0, cursorRow - arg(0, 1))
            'B' -> cursorRow = minOf(if (cursorRow <= scrollBottom) scrollBottom else rows - 1, cursorRow + arg(0, 1))
            'C' -> cursorCol = minOf(columns - 1, cursorCol + arg(0, 1))
            'D' -> cursorCol = maxOf(0, cursorCol - arg(0, 1))
            'E' -> {
                cursorRow = minOf(rows - 1, cursorRow + arg(0, 1))
                cursorCol = 0
            }
            'F' -> {
                cursorRow = maxOf(0, cursorRow - arg(0, 1))
                cursorCol = 0
            }
            'G', '`' -> cursorCol = (arg(0, 1) - 1).coerceIn(0, columns - 1)
            'd' -> cursorRow = (arg(0, 1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorRow = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (arg(1, 1) - 1).coerceIn(0, columns - 1)
            }
            'J' -> eraseInDisplay(rawArg(0))
            'K' -> eraseInLine(rawArg(0))
            'L' -> insertLines(arg(0, 1))
            'M' -> deleteLines(arg(0, 1))
            'P' -> deleteChars(arg(0, 1))
            '@' -> insertChars(arg(0, 1))
            'X' -> {
                val end = minOf(columns, cursorCol + arg(0, 1))
                for (i in cursorCol until end) screen[cursorRow][i] = ' '
            }
            'S' -> repeat(arg(0, 1)) { scrollUp(scrollTop, scrollBottom) }
            'T' -> repeat(arg(0, 1)) { scrollDown(scrollTop, scrollBottom) }
            'r' -> if (!isPrivate) {
                val top = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (arg(1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                    cursorRow = 0
                    cursorCol = 0
                }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h', 'l' -> if (isPrivate && params.any { it == 1049 || it == 47 || it == 1047 }) {
                // Switching to/from the alternate screen: start from a clean page.
                pushScreenToScrollback()
            }
            else -> Unit // 'm' (colors), 'n' (status reports) and the rest are ignored.
        }
    }

    private fun putChar(c: Char) {
        if (wrapPending) {
            cursorCol = 0
            lineFeed()
            wrapPending = false
        }
        screen[cursorRow][cursorCol] = c
        if (cursorCol == columns - 1) {
            wrapPending = true
        } else {
            cursorCol++
        }
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorRow == scrollBottom) {
            scrollUp(scrollTop, scrollBottom)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) {
            scrollDown(scrollTop, scrollBottom)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    private fun scrollUp(top: Int, bottom: Int) {
        if (top == 0) {
            addScrollback(String(screen[0]).trimEnd())
        }
        for (r in top until bottom) screen[r] = screen[r + 1]
        screen[bottom] = blankRow()
    }

    private fun scrollDown(top: Int, bottom: Int) {
        for (r in bottom downTo top + 1) screen[r] = screen[r - 1]
        screen[top] = blankRow()
    }

    private fun addScrollback(line: String) {
        scrollback.addLast(line)
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
    }

    private fun pushScreenToScrollback() {
        var last = -1
        for (r in 0 until rows) if (screen[r].any { it != ' ' }) last = r
        for (r in 0..last) addScrollback(String(screen[r]).trimEnd())
        screen = Array(rows) { blankRow() }
        cursorRow = 0
        cursorCol = 0
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (r in cursorRow + 1 until rows) screen[r] = blankRow()
            }
            1 -> {
                for (r in 0 until cursorRow) screen[r] = blankRow()
                eraseInLine(1)
            }
            else -> {
                // Keep what was on screen readable instead of throwing it away.
                val row = cursorRow
                val col = cursorCol
                pushScreenToScrollback()
                cursorRow = row
                cursorCol = col
            }
        }
    }

    private fun eraseInLine(mode: Int) {
        val line = screen[cursorRow]
        val range = when (mode) {
            0 -> cursorCol until columns
            1 -> 0..cursorCol
            else -> 0 until columns
        }
        for (i in range) line[i] = ' '
    }

    private fun insertLines(n: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(n) { scrollDown(cursorRow, scrollBottom) }
    }

    private fun deleteLines(n: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(n) {
            for (r in cursorRow until scrollBottom) screen[r] = screen[r + 1]
            screen[scrollBottom] = blankRow()
        }
    }

    private fun deleteChars(n: Int) {
        val line = screen[cursorRow]
        val count = minOf(n, columns - cursorCol)
        System.arraycopy(line, cursorCol + count, line, cursorCol, columns - cursorCol - count)
        for (i in columns - count until columns) line[i] = ' '
    }

    private fun insertChars(n: Int) {
        val line = screen[cursorRow]
        val count = minOf(n, columns - cursorCol)
        System.arraycopy(line, cursorCol, line, cursorCol + count, columns - cursorCol - count)
        for (i in cursorCol until cursorCol + count) line[i] = ' '
    }

    private fun saveCursor() {
        savedRow = cursorRow
        savedCol = cursorCol
    }

    private fun restoreCursor() {
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, columns - 1)
        wrapPending = false
    }
}
