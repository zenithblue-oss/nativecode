package com.zenithblue.nativecode.terminal

import com.zenithblue.nativecode.terminal.keys.Key
import com.zenithblue.nativecode.terminal.keys.KeyKind
import com.zenithblue.nativecode.terminal.keys.KeyLayouts
import com.zenithblue.nativecode.terminal.keys.KeyPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLayoutsTest {
    private fun keys(preset: KeyPreset) = KeyLayouts.pages(preset).flatMap { it.rows.flatten() }

    @Test fun everyPageIsARectangleWithOnePageKey() {
        KeyPreset.values().forEach { preset ->
            KeyLayouts.pages(preset).forEach { page ->
                assertEquals(1, page.rows.map { it.size }.distinct().size)
                assertEquals(9, page.rows.first().size)
                assertEquals(1, page.rows.flatten().count { it.kind == KeyKind.PAGE })
                assertTrue(page.gapsAfter.all { it in 0 until 8 })
            }
        }
        assertEquals(2, KeyLayouts.pages(KeyPreset.STANDARD).first().rows.size)
        assertEquals(1, KeyLayouts.pages(KeyPreset.COMPACT).first().rows.size)
    }

    /** Owner: ENT and BKSP stay. Both presets also keep ESC/TAB/modifiers/arrows. */
    @Test fun essentialKeysPresentInEveryPreset() {
        KeyPreset.values().forEach { preset ->
            val sends = keys(preset).filter { it.kind == KeyKind.SEND }.map { it.send }.toSet()
            listOf("ENTER", "BKSP", "ESC", "TAB", "UP", "DOWN", "LEFT", "RIGHT", "-", "/", "|", "~").forEach {
                assertTrue("$preset misses $it", it in sends)
            }
            val kinds = keys(preset).map { it.kind }.toSet()
            assertTrue(kinds.containsAll(listOf(KeyKind.CTRL, KeyKind.ALT, KeyKind.SHIFT)))
            val actions = keys(preset).filter { it.kind == KeyKind.ACTION }.map { it.send }.toSet()
            assertEquals(setOf(KeyLayouts.ACTION_COPY, KeyLayouts.ACTION_PASTE, KeyLayouts.ACTION_TRANSCRIPT, KeyLayouts.ACTION_LIVE), actions)
        }
    }

    @Test fun firstStandardPageHasEnterAndBackspaceAndRepeatingArrows() {
        val first = KeyLayouts.pages(KeyPreset.STANDARD).first().rows.flatten()
        assertTrue(first.any { it.send == "ENTER" })
        val bksp = first.first { it.send == "BKSP" }
        assertTrue(bksp.repeat)
        assertEquals("DEL", bksp.alt?.send)
        listOf("UP", "DOWN", "LEFT", "RIGHT").forEach { a -> assertTrue(first.first { it.send == a }.repeat) }
        assertFalse(first.first { it.send == "ESC" }.repeat)
        val tabAlt = first.first { it.send == "TAB" && !it.shift }.alt!!
        assertTrue(tabAlt.shift); assertEquals("TAB", tabAlt.send)
    }

    @Test fun ctrlChordsSendLowercaseWithCtrl() {
        val c = keys(KeyPreset.STANDARD).first { it.label == "^C" }
        assertEquals("c", c.send); assertTrue(c.ctrl)
    }

    @Test fun stickyModifierCycle() {
        assertEquals(true to false, KeyLayouts.nextModifier(active = false, locked = false)) // off → one-shot
        assertEquals(true to true, KeyLayouts.nextModifier(active = true, locked = false))   // → locked
        assertEquals(false to false, KeyLayouts.nextModifier(active = true, locked = true))  // → off
    }

    @Test fun swipeUpPicksAlternateOnlyPastThreshold() {
        val k = Key("-", alt = Key("_"))
        assertTrue(KeyLayouts.pickAlt(k, dy = -30f, threshold = 20f))
        assertFalse(KeyLayouts.pickAlt(k, dy = -10f, threshold = 20f))
        assertFalse(KeyLayouts.pickAlt(k, dy = 40f, threshold = 20f))
        assertFalse(KeyLayouts.pickAlt(Key("ESC"), dy = -99f, threshold = 20f))
    }

    @Test fun presetAndDots() {
        assertEquals(KeyPreset.STANDARD, KeyPreset.byId(null))
        assertEquals(KeyPreset.STANDARD, KeyPreset.byId("nope"))
        assertEquals(KeyPreset.COMPACT, KeyPreset.byId("compact"))
        assertEquals("○●○", KeyLayouts.pageDots(1, 3))
    }
}
