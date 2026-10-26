package org.openstick.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * A minimal but correct VT100/xterm-256color terminal emulator.
 * Parses ANSI escape sequences and maintains a cell grid with scrollback.
 * OpenStick Linux TerminalEmulator architecture.
 */
class TerminalEmulator(
    var cols: Int,
    var rows: Int,
    private val maxScrollback: Int = 5000,
    private val respond: (ByteArray) -> Unit = {},
    private val onTitle: (String) -> Unit = {},
    private val onBell: () -> Unit = {}
) {
    // ─── Data structures ───────────────────────────────────────────

    data class Cell(
        var ch: Char = ' ',
        var fg: Int = DEF_FG,
        var bg: Int = DEF_BG,
        var flags: Int = 0, // bit0=bold, bit1=italic, bit2=underline, bit3=inverse, bit4=dim, bit5=strikethrough
        var wide: Boolean = false
    )

    data class Snapshot(
        val scrollback: List<AnnotatedString>,
        val screen: List<AnnotatedString>,
        val cursorRow: Int,
        val cursorCol: Int,
        val cursorVisible: Boolean,
        val altScreen: Boolean = false
    )

    // ─── State machine ─────────────────────────────────────────────

    private enum class S { TEXT, ESC, CSI, OSC, CHARSET }

    companion object {
        const val DEF_FG = 0xFFE0E0E0.toInt()
        const val DEF_BG = 0xFF000000.toInt()
        private const val WIDE_FILLER = '\u0000'

        val PALETTE: IntArray = run {
            val p = IntArray(256)
            // Standard 8 colors
            p[0] = argb(0, 0, 0)
            p[1] = argb(205, 0, 0)
            p[2] = argb(0, 205, 0)
            p[3] = argb(205, 205, 0)
            p[4] = argb(0, 0, 238)
            p[5] = argb(205, 0, 205)
            p[6] = argb(0, 205, 205)
            p[7] = argb(229, 229, 229)
            // Bright 8 colors
            p[8] = argb(127, 127, 127)
            p[9] = argb(255, 0, 0)
            p[10] = argb(0, 255, 0)
            p[11] = argb(255, 255, 0)
            p[12] = argb(92, 92, 255)
            p[13] = argb(255, 0, 255)
            p[14] = argb(0, 255, 255)
            p[15] = argb(255, 255, 255)
            // 216 color cube (6x6x6)
            for (i in 0 until 216) {
                val r = if (i / 36 == 0) 0 else 55 + (i / 36) * 40
                val g = if ((i / 6) % 6 == 0) 0 else 55 + ((i / 6) % 6) * 40
                val b = if (i % 6 == 0) 0 else 55 + (i % 6) * 40
                p[16 + i] = argb(r, g, b)
            }
            // 24 grayscale
            for (i in 0 until 24) {
                val v = 8 + i * 10
                p[232 + i] = argb(v, v, v)
            }
            p
        }

        private fun argb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        fun charWidth(cp: Int): Int {
            if (cp < 0x1100) return 1
            if (cp in 0x1100..0x115F) return 2
            if (cp in 0x2E80..0x303E || cp in 0x3041..0x33BF ||
                cp in 0x3400..0x4DBF || cp in 0x4E00..0xA4CF ||
                cp in 0xA960..0xA97C || cp in 0xAC00..0xD7A3 ||
                cp in 0xF900..0xFAFF || cp in 0xFE10..0xFE6F ||
                cp in 0xFF01..0xFF60 || cp in 0xFFE0..0xFFE6 ||
                cp in 0x1F000..0x1FAFF || cp in 0x20000..0x2FA1F
            ) return 2
            return 1
        }

        private fun grid(rows: Int, cols: Int): Array<Array<Cell>> =
            Array(rows) { Array(cols) { Cell() } }
    }

    // ─── Grid state ────────────────────────────────────────────────

    private var normal = grid(rows, cols)
    private var alt = grid(rows, cols)
    private var screen = normal
    private var altActive = false
    var altScreenActive = false; private set

    private val scrollback = ArrayDeque<AnnotatedString>()

    // Cursor
    private var cx = 0
    private var cy = 0
    private var top = 0
    private var bottom = rows - 1

    // Attributes
    private var fg = DEF_FG
    private var bg = DEF_BG
    private var flags = 0
    private var autowrap = true
    private var wrapNext = false
    var cursorVisible = true; private set
    private var originMode = false
    private var insertMode = false
    private var lastPrinted: Char = ' '

    // Tabs
    private var tabs = defaultTabs(cols)

    // Character set
    private val gsets = charArrayOf('B', 'B', 'B', 'B')
    private var glActive = 0
    private var charsetTarget = 0

    // Saved cursor
    private var savedCx = 0
    private var savedCy = 0
    private var savedFg = DEF_FG
    private var savedBg = DEF_BG
    private var savedFlags = 0

    // Parser state
    private var state = S.TEXT
    private val params = StringBuilder()
    private var oscPrev = 0

    // UTF-8 decoder
    private var u8rem = 0
    private var u8acc = 0

    // App mode
    var applicationCursorKeys = false; private set
    var bracketedPaste = false; private set
    var mouseMode = 0; private set
    var mouseSgr = false; private set
    val mouseEnabled: Boolean get() = mouseMode > 0

    // ─── Tabs ──────────────────────────────────────────────────────

    private fun defaultTabs(cols: Int): BooleanArray {
        val t = BooleanArray(cols)
        for (i in t.indices) t[i] = (i % 8 == 0)
        return t
    }

    // ─── Resize ────────────────────────────────────────────────────

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        val nc = newCols.coerceAtLeast(1)
        val nr = newRows.coerceAtLeast(1)

        normal = resizeGrid(normal, nr, nc)
        alt = resizeGrid(alt, nr, nc)
        screen = if (altActive) alt else normal

        cols = nc
        rows = nr
        cx = cx.coerceIn(0, nc - 1)
        cy = cy.coerceIn(0, nr - 1)
        top = 0
        bottom = nr - 1
        tabs = defaultTabs(nc)
    }

    private fun resizeGrid(old: Array<Array<Cell>>, nr: Int, nc: Int): Array<Array<Cell>> {
        val g = Array(nr) { r ->
            Array(nc) { c ->
                if (r < old.size && c < old[0].size) {
                    old[r][c].copy()
                } else {
                    Cell()
                }
            }
        }
        return g
    }

    // ─── Feed raw bytes ────────────────────────────────────────────

    fun feed(data: ByteArray, length: Int) {
        var i = 0
        while (i < length) {
            val b = data[i].toInt() and 0xFF
            if (u8rem > 0) {
                if (b and 0xC0 == 0x80) {
                    u8acc = (u8acc shl 6) or (b and 0x3F)
                    u8rem--
                    if (u8rem == 0) {
                        processCodepoint(u8acc)
                    }
                } else {
                    u8rem = 0
                    processCodepoint(0xFFFD)
                    continue
                }
            } else if (b < 0x80) {
                processCodepoint(b)
            } else if (b and 0xE0 == 0xC0) {
                u8acc = b and 0x1F; u8rem = 1
            } else if (b and 0xF0 == 0xE0) {
                u8acc = b and 0x0F; u8rem = 2
            } else if (b and 0xF8 == 0xF0) {
                u8acc = b and 0x07; u8rem = 3
            } else {
                processCodepoint(0xFFFD)
            }
            i++
        }
    }

    private fun processCodepoint(cp: Int) {
        when (state) {
            S.TEXT -> {
                when (cp) {
                    0x07 -> onBell()
                    0x08 -> { // BS
                        if (cx > 0) cx--
                        wrapNext = false
                    }
                    0x09 -> tab() // HT
                    0x0A, 0x0B, 0x0C -> lineFeed() // LF, VT, FF
                    0x0D -> { cx = 0; wrapNext = false } // CR
                    0x1B -> { state = S.ESC; params.clear() }
                    else -> {
                        if (cp >= 0x20) printCodepoint(cp)
                    }
                }
            }
            S.ESC -> {
                when (cp.toChar()) {
                    '[' -> { state = S.CSI; params.clear() }
                    ']' -> { state = S.OSC; params.clear(); oscPrev = 0 }
                    '(' -> { state = S.CHARSET; charsetTarget = 0 }
                    ')' -> { state = S.CHARSET; charsetTarget = 1 }
                    '*' -> { state = S.CHARSET; charsetTarget = 2 }
                    '+' -> { state = S.CHARSET; charsetTarget = 3 }
                    'D' -> { lineFeed(); state = S.TEXT }
                    'M' -> { reverseIndex(); state = S.TEXT }
                    'E' -> { cx = 0; lineFeed(); state = S.TEXT }
                    '7' -> { saveCursor(); state = S.TEXT }
                    '8' -> { restoreCursor(); state = S.TEXT }
                    'c' -> { fullReset(); state = S.TEXT }
                    'H' -> { // HTS
                        if (cx < cols) tabs[cx] = true
                        state = S.TEXT
                    }
                    '=' -> { applicationCursorKeys = true; state = S.TEXT }
                    '>' -> { applicationCursorKeys = false; state = S.TEXT }
                    else -> state = S.TEXT
                }
            }
            S.CSI -> {
                val c = cp.toChar()
                if (c in '0'..'9' || c == ';' || c == '?' || c == '!' || c == '>' || c == ' ') {
                    params.append(c)
                } else {
                    csi(c, params.toString())
                    state = S.TEXT
                }
            }
            S.OSC -> {
                if (cp == 0x07 || (cp == 0x5C && oscPrev == 0x1B)) {
                    osc(params.toString())
                    state = S.TEXT
                } else {
                    params.append(cp.toChar())
                    oscPrev = cp
                }
            }
            S.CHARSET -> {
                gsets[charsetTarget] = cp.toChar()
                state = S.TEXT
            }
        }
    }

    // ─── Print ─────────────────────────────────────────────────────

    private fun printCodepoint(cp: Int) {
        val w = charWidth(cp)
        val ch = cp.toChar()

        if (wrapNext && autowrap) {
            cx = 0
            lineFeed()
            wrapNext = false
        }

        if (insertMode) {
            val shift = if (w == 2) 2 else 1
            for (j in cols - 1 downTo cx + shift) {
                screen[cy][j] = screen[cy][j - shift].copy()
            }
        }

        if (cx >= cols) cx = cols - 1

        val cell = screen[cy][cx]
        cell.ch = ch
        cell.fg = fg
        cell.bg = bg
        cell.flags = flags
        cell.wide = (w == 2)

        if (w == 2 && cx + 1 < cols) {
            val next = screen[cy][cx + 1]
            next.ch = WIDE_FILLER
            next.fg = fg
            next.bg = bg
            next.flags = flags
            next.wide = false
        }

        lastPrinted = ch
        cx += w
        if (cx >= cols) {
            cx = cols - 1
            wrapNext = true
        }
    }

    // ─── Cursor movement / scrolling ───────────────────────────────

    private fun tab() {
        wrapNext = false
        var x = cx + 1
        while (x < cols && !tabs[x]) x++
        cx = x.coerceAtMost(cols - 1)
    }

    private fun backTab() {
        wrapNext = false
        var x = cx - 1
        while (x > 0 && !tabs[x]) x--
        cx = x.coerceAtLeast(0)
    }

    private fun lineFeed() {
        wrapNext = false
        if (cy == bottom) {
            scrollUp(1)
        } else if (cy < rows - 1) {
            cy++
        }
    }

    private fun reverseIndex() {
        if (cy == top) {
            scrollDown(1)
        } else if (cy > 0) {
            cy--
        }
    }

    private fun scrollUp(n: Int) {
        for (i in 0 until n) {
            if (top == 0 && !altActive) {
                pushScrollback(screen[top])
            }
            for (r in top until bottom) {
                screen[r] = screen[r + 1]
            }
            screen[bottom] = Array(cols) { Cell() }
        }
    }

    private fun scrollDown(n: Int) {
        for (i in 0 until n) {
            for (r in bottom downTo top + 1) {
                screen[r] = screen[r - 1]
            }
            screen[top] = Array(cols) { Cell() }
        }
    }

    private fun pushScrollback(row: Array<Cell>) {
        scrollback.addLast(buildLine(row, -1))
        while (scrollback.size > maxScrollback) {
            scrollback.removeFirst()
        }
    }

    // ─── CSI sequences ─────────────────────────────────────────────

    private fun csi(final: Char, rawIn: String) {
        val priv = rawIn.startsWith("?")
        val raw = if (priv) rawIn.substring(1) else rawIn
        val nums = if (raw.isBlank()) intArrayOf() else {
            try {
                raw.split(';').map { it.ifEmpty { "0" }.toInt() }.toIntArray()
            } catch (_: Exception) {
                intArrayOf()
            }
        }
        val n = nums.getOrElse(0) { 0 }
        val m = nums.getOrElse(1) { 0 }

        wrapNext = false

        when (final) {
            'A' -> cy = (cy - n.coerceAtLeast(1)).coerceAtLeast(top) // CUU
            'B', 'e' -> cy = (cy + n.coerceAtLeast(1)).coerceAtMost(bottom) // CUD
            'C', 'a' -> cx = (cx + n.coerceAtLeast(1)).coerceAtMost(cols - 1) // CUF
            'D' -> cx = (cx - n.coerceAtLeast(1)).coerceAtLeast(0) // CUB
            'E' -> { cy = (cy + n.coerceAtLeast(1)).coerceAtMost(bottom); cx = 0 } // CNL
            'F' -> { cy = (cy - n.coerceAtLeast(1)).coerceAtLeast(top); cx = 0 } // CPL
            'G', '`' -> cx = (n.coerceAtLeast(1) - 1).coerceIn(0, cols - 1) // CHA
            'H', 'f' -> { // CUP
                cy = ((if (nums.isNotEmpty()) nums[0] else 1).coerceAtLeast(1) - 1)
                    .coerceIn(0, rows - 1)
                cx = ((if (nums.size > 1) nums[1] else 1).coerceAtLeast(1) - 1)
                    .coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(n) // ED
            'K' -> eraseLine(n) // EL
            'L' -> insertLines(n.coerceAtLeast(1)) // IL
            'M' -> deleteLines(n.coerceAtLeast(1)) // DL
            'P' -> deleteChars(n.coerceAtLeast(1)) // DCH
            '@' -> insertChars(n.coerceAtLeast(1)) // ICH
            'X' -> eraseChars(n.coerceAtLeast(1)) // ECH
            'S' -> scrollUp(n.coerceAtLeast(1))
            'T' -> scrollDown(n.coerceAtLeast(1))
            'Z' -> repeat(n.coerceAtLeast(1)) { backTab() } // CBT
            'd' -> cy = (n.coerceAtLeast(1) - 1).coerceIn(0, rows - 1) // VPA
            'g' -> { // TBC
                when (n) {
                    0 -> if (cx < cols) tabs[cx] = false
                    3 -> tabs.fill(false)
                }
            }
            'r' -> { // DECSTBM
                top = ((if (nums.isNotEmpty()) nums[0] else 1).coerceAtLeast(1) - 1)
                    .coerceIn(0, rows - 1)
                bottom = ((if (nums.size > 1) nums[1] else rows).coerceAtLeast(1) - 1)
                    .coerceIn(0, rows - 1)
                if (top >= bottom) { top = 0; bottom = rows - 1 }
                cx = 0
                cy = if (originMode) top else 0
            }
            'm' -> sgr(raw) // SGR
            'n' -> { // DSR
                when (n) {
                    5 -> respond("\u001b[0n".toByteArray())
                    6 -> respond("\u001b[${cy + 1};${cx + 1}R".toByteArray())
                }
            }
            'c' -> { // DA
                respond("\u001b[?62;22c".toByteArray())
            }
            'h' -> setMode(nums, priv, true)
            'l' -> setMode(nums, priv, false)
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> { /* XTWINOPS: ignore */ }
            'b' -> { // REP
                val count = n.coerceAtLeast(1).coerceAtMost(cols)
                repeat(count) { printCodepoint(lastPrinted.code) }
            }
        }
    }

    // ─── SGR (Select Graphic Rendition) ────────────────────────────

    private fun sgr(raw: String) {
        val parts = if (raw.isBlank()) listOf("0") else raw.split(';')
        var i = 0
        while (i < parts.size) {
            val n = parts[i].toIntOrNull() ?: 0
            when (n) {
                0 -> { fg = DEF_FG; bg = DEF_BG; flags = 0 }
                1 -> flags = flags or 1 // bold
                2 -> flags = flags or 16 // dim
                3 -> flags = flags or 2 // italic
                4 -> flags = flags or 4 // underline
                7 -> flags = flags or 8 // inverse
                9 -> flags = flags or 32 // strikethrough
                21 -> flags = flags and 1.inv() // no bold
                22 -> flags = flags and (1 or 16).inv() // normal intensity
                23 -> flags = flags and 2.inv() // no italic
                24 -> flags = flags and 4.inv() // no underline
                27 -> flags = flags and 8.inv() // no inverse
                29 -> flags = flags and 32.inv() // no strikethrough
                in 30..37 -> fg = PALETTE[n - 30]
                38 -> {
                    i++
                    val sub = parts.getOrNull(i)?.toIntOrNull() ?: 0
                    if (sub == 5) {
                        i++
                        val idx = parts.getOrNull(i)?.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        fg = PALETTE[idx]
                    } else if (sub == 2) {
                        val r = parts.getOrNull(++i)?.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        val g = parts.getOrNull(++i)?.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        val b = parts.getOrNull(++i)?.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        fg = argb(r, g, b)
                    }
                }
                39 -> fg = DEF_FG
                in 40..47 -> bg = PALETTE[n - 40]
                48 -> {
                    i++
                    val sub = parts.getOrNull(i)?.toIntOrNull() ?: 0
                    if (sub == 5) {
                        i++
                        val idx = parts.getOrNull(i)?.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        bg = PALETTE[idx]
                    } else if (sub == 2) {
                        val r = parts.getOrNull(++i)?.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        val g = parts.getOrNull(++i)?.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        val b = parts.getOrNull(++i)?.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        bg = argb(r, g, b)
                    }
                }
                49 -> bg = DEF_BG
                in 90..97 -> fg = PALETTE[n - 90 + 8]
                in 100..107 -> bg = PALETTE[n - 100 + 8]
            }
            i++
        }
    }

    // ─── Mode set/reset ────────────────────────────────────────────

    private fun setMode(nums: IntArray, priv: Boolean, on: Boolean) {
        for (n in nums) {
            if (priv) {
                when (n) {
                    1 -> applicationCursorKeys = on
                    7 -> autowrap = on
                    12 -> { /* blinking cursor, ignore */ }
                    25 -> cursorVisible = on
                    1000 -> mouseMode = if (on) 1000 else 0
                    1002 -> mouseMode = if (on) 1002 else 0
                    1003 -> mouseMode = if (on) 1003 else 0
                    1006 -> mouseSgr = on
                    1049 -> switchAlt(on)
                    2004 -> bracketedPaste = on
                    6 -> {
                        originMode = on
                        cx = 0
                        cy = if (on) top else 0
                    }
                }
            } else {
                when (n) {
                    4 -> insertMode = on
                    20 -> { /* LNM, ignore */ }
                }
            }
        }
    }

    // ─── Alt screen ────────────────────────────────────────────────

    private fun switchAlt(on: Boolean) {
        if (on && !altActive) {
            saveCursor()
            altActive = true
            altScreenActive = true
            alt = grid(rows, cols)
            screen = alt
        } else if (!on && altActive) {
            altActive = false
            altScreenActive = false
            screen = normal
            restoreCursor()
        }
    }

    // ─── Erase operations ──────────────────────────────────────────

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { // below
                for (c in cx until cols) eraseCell(screen[cy][c])
                for (r in cy + 1 until rows)
                    for (c in 0 until cols) eraseCell(screen[r][c])
            }
            1 -> { // above
                for (r in 0 until cy)
                    for (c in 0 until cols) eraseCell(screen[r][c])
                for (c in 0..cx.coerceAtMost(cols - 1)) eraseCell(screen[cy][c])
            }
            2, 3 -> { // entire screen
                for (r in 0 until rows)
                    for (c in 0 until cols) eraseCell(screen[r][c])
                if (mode == 3) scrollback.clear()
            }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (c in cx until cols) eraseCell(screen[cy][c])
            1 -> for (c in 0..cx.coerceAtMost(cols - 1)) eraseCell(screen[cy][c])
            2 -> for (c in 0 until cols) eraseCell(screen[cy][c])
        }
    }

    private fun eraseChars(n: Int) {
        for (c in cx until (cx + n).coerceAtMost(cols)) {
            eraseCell(screen[cy][c])
        }
    }

    private fun eraseCell(cell: Cell) {
        cell.ch = ' '
        cell.fg = fg
        cell.bg = bg
        cell.flags = 0
        cell.wide = false
    }

    private fun insertLines(n: Int) {
        if (cy < top || cy > bottom) return
        for (i in 0 until n) {
            for (r in bottom downTo cy + 1) {
                screen[r] = screen[r - 1]
            }
            screen[cy] = Array(cols) { Cell() }
        }
    }

    private fun deleteLines(n: Int) {
        if (cy < top || cy > bottom) return
        for (i in 0 until n) {
            for (r in cy until bottom) {
                screen[r] = screen[r + 1]
            }
            screen[bottom] = Array(cols) { Cell() }
        }
    }

    private fun insertChars(n: Int) {
        for (j in cols - 1 downTo cx + n) {
            screen[cy][j] = screen[cy][j - n].copy()
        }
        for (j in cx until (cx + n).coerceAtMost(cols)) {
            eraseCell(screen[cy][j])
        }
    }

    private fun deleteChars(n: Int) {
        for (j in cx until cols - n) {
            screen[cy][j] = screen[cy][j + n].copy()
        }
        for (j in (cols - n).coerceAtLeast(cx) until cols) {
            eraseCell(screen[cy][j])
        }
    }

    // ─── OSC ───────────────────────────────────────────────────────

    private fun osc(payload: String) {
        val sep = payload.indexOf(';')
        if (sep < 0) return
        val code = payload.substring(0, sep).toIntOrNull() ?: return
        val text = payload.substring(sep + 1)
        when (code) {
            0, 2 -> onTitle(text) // window title
            1 -> { /* icon name, ignore */ }
        }
    }

    // ─── Save / Restore cursor ─────────────────────────────────────

    private fun saveCursor() {
        savedCx = cx
        savedCy = cy
        savedFg = fg
        savedBg = bg
        savedFlags = flags
    }

    private fun restoreCursor() {
        cx = savedCx.coerceIn(0, cols - 1)
        cy = savedCy.coerceIn(0, rows - 1)
        fg = savedFg
        bg = savedBg
        flags = savedFlags
    }

    private fun fullReset() {
        cx = 0; cy = 0
        fg = DEF_FG; bg = DEF_BG; flags = 0
        top = 0; bottom = rows - 1
        autowrap = true; wrapNext = false
        cursorVisible = true; originMode = false; insertMode = false
        applicationCursorKeys = false
        bracketedPaste = false
        mouseMode = 0; mouseSgr = false
        tabs = defaultTabs(cols)
        state = S.TEXT
        params.clear()
        u8rem = 0; u8acc = 0
        scrollback.clear()
        for (r in 0 until rows)
            for (c in 0 until cols) eraseCell(screen[r][c])
    }

    // ─── Mouse report ──────────────────────────────────────────────

    fun mouseReport(button: Int, col: Int, row: Int, press: Boolean, motion: Boolean = false): ByteArray? {
        if (mouseMode == 0) return null
        if (motion && mouseMode < 1002) return null
        if (mouseSgr) {
            return "\u001b[<${button};${col + 1};${row + 1}${if (press) 'M' else 'm'}".toByteArray()
        }
        val btn = button + 32 + (if (!press) 3 else 0)
        return byteArrayOf(27, '['.code.toByte(), 'M'.code.toByte(),
            (btn).coerceIn(32, 255).toByte(),
            (col + 33).coerceIn(33, 255).toByte(),
            (row + 33).coerceIn(33, 255).toByte())
    }

    // ─── Snapshot for UI ───────────────────────────────────────────

    fun snapshot(): Snapshot {
        val sb = ArrayList<AnnotatedString>(scrollback.size)
        for (line in scrollback) sb.add(line)
        val sc = ArrayList<AnnotatedString>(rows)
        for (r in 0 until rows) {
            sc.add(buildLine(screen[r], if (r == cy) cx else -1))
        }
        return Snapshot(
            scrollback = sb,
            screen = sc,
            cursorRow = cy,
            cursorCol = cx,
            cursorVisible = cursorVisible,
            altScreen = altActive
        )
    }

    private fun buildLine(row: Array<Cell>, cursorCol: Int): AnnotatedString {
        return buildAnnotatedString {
            var lastFg = -1; var lastBg = -1; var lastFlags = -1
            var spanStart = 0
            val sb = StringBuilder(row.size)
            for (c in row.indices) {
                val cell = row[c]
                if (cell.ch == WIDE_FILLER) continue
                val ch = if (cell.ch.code < 0x20) ' ' else cell.ch

                var cellFg = cell.fg
                var cellBg = cell.bg
                val cellFlags = cell.flags or (if (c == cursorCol) 0x100 else 0)

                // Inverse
                if (cellFlags and 8 != 0 || cellFlags and 0x100 != 0) {
                    val tmp = cellFg; cellFg = cellBg; cellBg = tmp
                    if (cellBg == DEF_BG) cellBg = DEF_FG
                    if (cellFg == DEF_FG) cellFg = DEF_BG
                }

                if (cellFg != lastFg || cellBg != lastBg || cellFlags != lastFlags) {
                    if (sb.isNotEmpty()) {
                        val spanText = sb.toString()
                        append(spanText)
                        if (lastFg != -1) {
                            addStyle(styleOf(lastFg, lastBg, lastFlags), spanStart, spanStart + spanText.length)
                        }
                        spanStart += spanText.length
                        sb.clear()
                    }
                    lastFg = cellFg
                    lastBg = cellBg
                    lastFlags = cellFlags
                }
                sb.append(ch)
            }
            // Flush remaining
            if (sb.isNotEmpty()) {
                val spanText = sb.toString()
                append(spanText)
                if (lastFg != -1) {
                    addStyle(styleOf(lastFg, lastBg, lastFlags), spanStart, spanStart + spanText.length)
                }
            }
            // Ensure at least one space so the line takes up visual space
            if (length == 0) append(" ")
        }
    }

    private fun styleOf(fg: Int, bg: Int, flags: Int): SpanStyle {
        val bold = flags and 1 != 0
        val italic = flags and 2 != 0
        val underline = flags and 4 != 0
        val dim = flags and 16 != 0
        val strikethrough = flags and 32 != 0

        var fgColor = Color(fg)
        if (dim) fgColor = fgColor.copy(alpha = 0.6f)

        val bgColor = if (bg != DEF_BG) Color(bg) else Color.Unspecified

        return SpanStyle(
            color = fgColor,
            background = bgColor,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = when {
                underline && strikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                underline -> TextDecoration.Underline
                strikethrough -> TextDecoration.LineThrough
                else -> TextDecoration.None
            }
        )
    }
}
