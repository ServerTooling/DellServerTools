package com.lilayam.dellservertools.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalEmulatorTest {

    private fun lines(term: TerminalEmulator) = term.snapshot().lines

    @Test
    fun `prints lines`() {
        val term = TerminalEmulator(columns = 20, rows = 5)
        term.feed("hello\r\nworld\r\n")
        assertEquals(listOf("hello", "world", ""), lines(term))
    }

    @Test
    fun `carriage return overwrites and erase-in-line clears the rest`() {
        val term = TerminalEmulator(columns = 20, rows = 5)
        term.feed("progress 10%\rprogress 100%")
        assertEquals(listOf("progress 100%"), lines(term))
        term.feed("\rabc\u001b[K")
        assertEquals(listOf("abc"), lines(term))
    }

    @Test
    fun `backspace moves left like shell line editing`() {
        val term = TerminalEmulator(columns = 20, rows = 5)
        term.feed("lsx\b \b")
        assertEquals(listOf("ls"), lines(term))
    }

    @Test
    fun `colors and title sequences are stripped`() {
        val term = TerminalEmulator(columns = 40, rows = 5)
        term.feed("\u001b]0;root@pve: ~\u0007\u001b[01;32mroot@pve\u001b[00m:~# ")
        assertEquals(listOf("root@pve:~#"), lines(term))
    }

    @Test
    fun `cursor positioning draws a BIOS style screen`() {
        val term = TerminalEmulator(columns = 20, rows = 4)
        term.feed("\u001b[2J\u001b[1;1HSetup\u001b[3;5HF2 = Setup\u001b[4;1H")
        assertEquals(listOf("Setup", "", "    F2 = Setup", ""), lines(term))
    }

    @Test
    fun `lines scrolled off the top go to scrollback`() {
        val term = TerminalEmulator(columns = 10, rows = 2)
        term.feed("one\r\ntwo\r\nthree")
        assertEquals(listOf("one", "two", "three"), lines(term))
    }

    @Test
    fun `scrollback is bounded`() {
        val term = TerminalEmulator(columns = 10, rows = 2, maxScrollback = 3)
        repeat(10) { term.feed("line$it\r\n") }
        assertEquals(listOf("line6", "line7", "line8", "line9", ""), lines(term))
    }

    @Test
    fun `long lines wrap at the terminal width`() {
        val term = TerminalEmulator(columns = 5, rows = 3)
        term.feed("abcdefgh")
        assertEquals(listOf("abcde", "fgh"), lines(term))
    }

    @Test
    fun `writing the last column does not wrap until the next character`() {
        val term = TerminalEmulator(columns = 5, rows = 3)
        term.feed("abcde\r\nx")
        assertEquals(listOf("abcde", "x"), lines(term))
    }

    @Test
    fun `utf8 split across reads is decoded`() {
        val term = TerminalEmulator(columns = 10, rows = 2)
        val bytes = "°C".toByteArray(Charsets.UTF_8)
        term.feed(bytes, 0, 1)
        term.feed(bytes, 1, bytes.size - 1)
        assertEquals(listOf("°C"), lines(term))
    }

    @Test
    fun `escape sequence split across reads is handled`() {
        val term = TerminalEmulator(columns = 10, rows = 2)
        term.feed("ab\u001b[")
        term.feed("2Dx")
        assertEquals(listOf("xb"), lines(term))
    }

    @Test
    fun `clear screen keeps old output in scrollback`() {
        val term = TerminalEmulator(columns = 10, rows = 3)
        term.feed("old\r\n\u001b[H\u001b[2Jnew")
        assertEquals(listOf("old", "new"), lines(term))
    }

    @Test
    fun `delete and insert characters`() {
        val term = TerminalEmulator(columns = 10, rows = 2)
        term.feed("abcdef\u001b[4G\u001b[2P")
        assertEquals(listOf("abcf"), lines(term))
        term.feed("\u001b[2G\u001b[1@Z")
        assertEquals(listOf("aZbcf"), lines(term))
    }

    @Test
    fun `clear empties everything`() {
        val term = TerminalEmulator(columns = 10, rows = 2)
        term.feed("a\r\nb\r\nc")
        term.clear()
        assertEquals(listOf(""), lines(term))
    }
}
