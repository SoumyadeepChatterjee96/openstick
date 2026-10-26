package org.openstick.terminal

import android.view.KeyEvent
import java.io.ByteArrayOutputStream

/**
 * Key translator converting Android KeyEvents and mobile keyboard actions
 * to standard VT100 / ANSI escape sequences.
 */
object TerminalKeys {

    enum class Named {
        UP, DOWN, LEFT, RIGHT,
        HOME, END, PAGE_UP, PAGE_DOWN,
        INSERT, DELETE, TAB, ESCAPE, ENTER, BACKSPACE
    }

    data class Mods(
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false
    ) {
        val none: Boolean get() = !ctrl && !alt && !shift

        val param: Int
            get() = (if (shift) 1 else 0) + 1 + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
    }

    fun toBytes(event: KeyEvent, appCursor: Boolean = false): ByteArray? {
        val keyCode = event.keyCode

        // Enter
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return byteArrayOf(13) // \r
        }
        // Backspace
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            return byteArrayOf(127) // DEL (0x7F)
        }
        // Forward Delete
        if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            return seq('[', '3', '~')
        }
        // Escape
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            return byteArrayOf(27)
        }
        // Tab
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            return byteArrayOf(9)
        }
        // Arrow keys
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return cursor('A', appCursor)
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return cursor('B', appCursor)
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return cursor('C', appCursor)
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return cursor('D', appCursor)

        // Home / End / Page
        if (keyCode == KeyEvent.KEYCODE_MOVE_HOME) return seq('[', 'H')
        if (keyCode == KeyEvent.KEYCODE_MOVE_END) return seq('[', 'F')
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP) return seq('[', '5', '~')
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) return seq('[', '6', '~')
        if (keyCode == KeyEvent.KEYCODE_INSERT) return seq('[', '2', '~')

        // Function keys F1-F12
        if (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            return fkey(keyCode - KeyEvent.KEYCODE_F1 + 1)
        }

        // Ctrl combinations
        if (event.isCtrlPressed) {
            if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                return byteArrayOf((keyCode - KeyEvent.KEYCODE_A + 1).toByte())
            }
            if (keyCode == KeyEvent.KEYCODE_SPACE) return byteArrayOf(0)
            if (keyCode == KeyEvent.KEYCODE_LEFT_BRACKET) return byteArrayOf(27)
            if (keyCode == KeyEvent.KEYCODE_BACKSLASH) return byteArrayOf(28)
            if (keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET) return byteArrayOf(29)
        }

        // Alt combinations
        if (event.isAltPressed) {
            val unicodeChar = event.unicodeChar
            if (unicodeChar != 0) {
                val chars = Character.toChars(unicodeChar)
                val bytes = String(chars).toByteArray(Charsets.UTF_8)
                return byteArrayOf(27) + bytes
            }
        }

        // Return null for regular printable characters so BasicTextField.onValueChange handles them
        return null
    }

    fun named(k: Named, mods: Mods = Mods(), appCursor: Boolean = false): ByteArray {
        val param = mods.param
        return when (k) {
            Named.UP -> if (param == 1) cursor('A', appCursor) else seq('[', '1', ';', (param + 48).toChar(), 'A')
            Named.DOWN -> if (param == 1) cursor('B', appCursor) else seq('[', '1', ';', (param + 48).toChar(), 'B')
            Named.RIGHT -> if (param == 1) cursor('C', appCursor) else seq('[', '1', ';', (param + 48).toChar(), 'C')
            Named.LEFT -> if (param == 1) cursor('D', appCursor) else seq('[', '1', ';', (param + 48).toChar(), 'D')
            Named.HOME -> if (param == 1) seq('[', 'H') else seqStr("[1;${param}H")
            Named.END -> if (param == 1) seq('[', 'F') else seqStr("[1;${param}F")
            Named.PAGE_UP -> if (param == 1) seqStr("[5~") else seqStr("[5;${param}~")
            Named.PAGE_DOWN -> if (param == 1) seqStr("[6~") else seqStr("[6;${param}~")
            Named.INSERT -> if (param == 1) seqStr("[2~") else seqStr("[2;${param}~")
            Named.DELETE -> if (param == 1) seqStr("[3~") else seqStr("[3;${param}~")
            Named.TAB -> if (mods.shift) seq('[', 'Z') else withAlt(byteArrayOf(9), mods.alt)
            Named.ESCAPE -> byteArrayOf(27)
            Named.ENTER -> withAlt(byteArrayOf(13), mods.alt)
            Named.BACKSPACE -> withAlt(byteArrayOf(127), mods.alt)
        }
    }

    fun text(s: String, mods: Mods = Mods()): ByteArray {
        if (mods.none) return s.toByteArray(Charsets.UTF_8)
        if (s.isEmpty()) return byteArrayOf()

        val out = ByteArrayOutputStream()
        for (ch in s) {
            var b = if (mods.ctrl) ctrlByte(ch) else ch.toString().toByteArray(Charsets.UTF_8)
            if (mods.alt) b = byteArrayOf(27) + b
            out.write(b)
        }
        return out.toByteArray()
    }

    fun ctrl(c: Char): ByteArray {
        val upper = c.uppercaseChar()
        return if (upper in 'A'..'Z') byteArrayOf((upper - '@').toByte()) else byteArrayOf(c.code.toByte())
    }

    private fun ctrlByte(c: Char): ByteArray {
        val upper = c.uppercaseChar()
        if (upper in 'A'..'Z') return byteArrayOf((upper - '@').toByte())
        if (c == ' ' || c == '@') return byteArrayOf(0)
        if (c == '[') return byteArrayOf(27)
        if (c == '\\') return byteArrayOf(28)
        if (c == ']') return byteArrayOf(29)
        if (c == '^') return byteArrayOf(30)
        if (c == '_') return byteArrayOf(31)
        if (c == '?') return byteArrayOf(127)
        return c.toString().toByteArray(Charsets.UTF_8)
    }

    private fun cursor(letter: Char, app: Boolean): ByteArray =
        seq(if (app) 'O' else '[', letter)

    private fun fkey(n: Int): ByteArray {
        return when (n) {
            1 -> seq('O', 'P')
            2 -> seq('O', 'Q')
            3 -> seq('O', 'R')
            4 -> seq('O', 'S')
            5 -> seq('[', '1', '5', '~')
            6 -> seq('[', '1', '7', '~')
            7 -> seq('[', '1', '8', '~')
            8 -> seq('[', '1', '9', '~')
            9 -> seq('[', '2', '0', '~')
            10 -> seq('[', '2', '1', '~')
            11 -> seq('[', '2', '3', '~')
            else -> seq('[', '2', '4', '~')
        }
    }

    private fun withAlt(b: ByteArray, alt: Boolean): ByteArray =
        if (alt) byteArrayOf(27) + b else b

    private fun seqStr(s: String): ByteArray =
        byteArrayOf(27) + s.toByteArray(Charsets.US_ASCII)

    private fun seq(vararg chars: Char): ByteArray {
        val b = ByteArray(chars.size + 1)
        b[0] = 27
        for (i in chars.indices) {
            b[i + 1] = chars[i].code.toByte()
        }
        return b
    }
}
