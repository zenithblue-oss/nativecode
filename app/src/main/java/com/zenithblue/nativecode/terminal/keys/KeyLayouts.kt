package com.zenithblue.nativecode.terminal.keys

/** What a key does when it fires. */
enum class KeyKind { SEND, CTRL, ALT, SHIFT, PAGE, ACTION }

/**
 * One extra key. [send] is a terminal key name (ESC, TAB, ENTER, BKSP, DEL, UP…, HOME, F1…)
 * or literal text; for [KeyKind.ACTION] it is the action id.
 */
data class Key(
    val label: String,
    val send: String = label,
    val kind: KeyKind = KeyKind.SEND,
    /** Swipe-up (and long-press, when the key does not repeat) alternate. */
    val alt: Key? = null,
    /** Auto-repeat while held. */
    val repeat: Boolean = false,
    /** Chord baked into the key (^C, Shift+Tab). */
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    /** Spoken name / tooltip. */
    val name: String = label,
)

/** A page is a list of equally long rows. [gapsAfter]: column indexes followed by a group gap. */
data class KeyPage(val rows: List<List<Key>>, val gapsAfter: Set<Int> = emptySet())

enum class KeyPreset(val id: String, val title: String) {
    STANDARD("standard", "Standard · 2 rows"),
    COMPACT("compact", "Compact · 1 row");

    companion object {
        fun byId(id: String?): KeyPreset = values().firstOrNull { it.id == id } ?: STANDARD
    }
}

/** NativeCode's own extra-key layouts (pure data + state math, unit-tested). */
object KeyLayouts {
    const val PREF_PRESET = "pref_extra_keys_preset"
    const val ACTION_COPY = "copy"
    const val ACTION_PASTE = "paste"
    const val ACTION_TRANSCRIPT = "transcript"
    const val ACTION_LIVE = "live"

    const val REPEAT_DELAY_MS = 400L
    const val REPEAT_INTERVAL_MS = 60L

    private fun sym(c: String, alt: String) = Key(c, alt = Key(alt))
    private fun ctrl(c: Char) = Key("^${c.uppercaseChar()}", c.lowercaseChar().toString(), ctrl = true, name = "Control ${c.uppercaseChar()}")
    private fun fn(n: Int) = Key("F$n")

    private val ESC = Key("ESC", name = "Escape")
    private val SHIFT_TAB = Key("⇧⇥", "TAB", shift = true, name = "Shift Tab")
    private val TAB = Key("TAB", alt = SHIFT_TAB, name = "Tab")
    private val CTRL = Key("CTRL", kind = KeyKind.CTRL, name = "Control")
    private val ALT = Key("ALT", kind = KeyKind.ALT, name = "Alt")
    private val SHIFT = Key("SHFT", kind = KeyKind.SHIFT, name = "Shift")
    private val PGUP = Key("PGUP", repeat = true, name = "Page up")
    private val PGDN = Key("PGDN", repeat = true, name = "Page down")
    private val HOME = Key("HOME", alt = PGUP.copy(repeat = false), name = "Home")
    private val END = Key("END", alt = PGDN.copy(repeat = false), name = "End")
    private val UP = Key("↑", "UP", repeat = true, name = "Up")
    private val DOWN = Key("↓", "DOWN", repeat = true, name = "Down")
    private val LEFT = Key("←", "LEFT", repeat = true, name = "Left")
    private val RIGHT = Key("→", "RIGHT", repeat = true, name = "Right")
    private val DEL = Key("DEL", repeat = true, name = "Delete")
    private val BKSP = Key("⌫", "BKSP", repeat = true, alt = DEL.copy(repeat = false), name = "Backspace")
    private val ENTER = Key("⏎", "ENTER", name = "Enter")
    private val INS = Key("INS", name = "Insert")
    private val PAGE = Key("•", kind = KeyKind.PAGE, name = "Next key page")

    private val COPY = Key("COPY", ACTION_COPY, KeyKind.ACTION, name = "Copy all terminal text")
    private val PASTE = Key("PASTE", ACTION_PASTE, KeyKind.ACTION, name = "Paste")
    private val LOG = Key("LOG", ACTION_TRANSCRIPT, KeyKind.ACTION, name = "Scrollable transcript")
    private val LIVE = Key("LIVE", ACTION_LIVE, KeyKind.ACTION, name = "Jump to live output")

    private val DASH = sym("-", "_")
    private val SLASH = sym("/", "\\")
    private val PIPE = sym("|", "&")
    private val TILDE = sym("~", "`")

    /**
     * Standard: modifiers left, arrows grouped in an inverted T, BKSP/ENT on the right edge.
     * The page key sits in the same cell on every page.
     */
    private val standard = listOf(
        KeyPage(
            listOf(
                listOf(ESC, TAB, CTRL, ALT, SHIFT, HOME, UP, END, BKSP),
                listOf(DASH, SLASH, PIPE, TILDE, PAGE, LEFT, DOWN, RIGHT, ENTER),
            ),
            gapsAfter = setOf(4, 7),
        ),
        KeyPage(
            listOf(
                listOf(ctrl('c'), ctrl('d'), ctrl('z'), ctrl('l'), ctrl('r'), SHIFT_TAB, PGUP, PGDN, DEL),
                listOf(sym("$", "#"), sym("\"", "'"), sym("(", "<"), sym(")", ">"), PAGE, sym("{", "["), sym("}", "]"), sym(";", ":"), sym("=", "+")),
            ),
            gapsAfter = setOf(4),
        ),
        KeyPage(
            listOf(
                (1..9).map(::fn),
                listOf(fn(10), fn(11), fn(12), INS, PAGE, COPY, PASTE, LOG, LIVE),
            ),
            gapsAfter = setOf(4),
        ),
    )

    // ponytail: no F-keys in Compact; add a 6th page if someone asks.
    private val compact = listOf(
        KeyPage(listOf(listOf(ESC, TAB, CTRL, ALT, LEFT, DOWN, UP, RIGHT, PAGE)), setOf(3, 7)),
        KeyPage(listOf(listOf(SHIFT, DASH, SLASH, PIPE, TILDE, HOME, BKSP, ENTER, PAGE)), setOf(4, 7)),
        KeyPage(listOf(listOf(ctrl('c'), ctrl('d'), ctrl('z'), ctrl('l'), ctrl('r'), SHIFT_TAB, PGUP, PGDN, PAGE)), setOf(4, 7)),
        KeyPage(listOf(listOf(sym("$", "#"), sym("\"", "'"), sym("(", "<"), sym(")", ">"), sym("{", "["), sym("}", "]"), sym(";", ":"), sym("=", "+"), PAGE)), setOf(7)),
        KeyPage(listOf(listOf(COPY, PASTE, LOG, LIVE, INS, DEL, END, fn(1).copy(alt = fn(2)), PAGE)), setOf(3, 7)),
    )

    fun pages(preset: KeyPreset): List<KeyPage> = if (preset == KeyPreset.COMPACT) compact else standard

    /** Sticky modifier cycle on tap: off → one-shot → locked → off. Returns (active, locked). */
    fun nextModifier(active: Boolean, locked: Boolean): Pair<Boolean, Boolean> = when {
        !active -> true to false
        !locked -> true to true
        else -> false to false
    }

    /** A finger that moved up by more than [threshold] px selects the alternate. */
    fun pickAlt(key: Key, dy: Float, threshold: Float): Boolean = key.alt != null && dy < -threshold

    /** Dots under the page key: ●○○. */
    fun pageDots(page: Int, count: Int): String = (0 until count).joinToString("") { if (it == page) "●" else "○" }
}
