package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.203: which key events open the command palette (pure), and which platforms show the Apple hint. */
class MenuSearchShortcutTest {
    private fun shortcut(
        key: String = "k",
        ctrl: Boolean = false,
        meta: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        defaultPrevented: Boolean = false,
        composing: Boolean = false,
        inTextEditor: Boolean = false,
    ) = isMenuSearchShortcut(key, ctrl, meta, alt, shift, defaultPrevented, composing, inTextEditor)

    @Test
    fun ctrlK_andCmdK_open() {
        assertTrue(shortcut(ctrl = true))
        assertTrue(shortcut(meta = true))
        assertTrue(shortcut(key = "K", ctrl = true))
    }

    @Test
    fun withoutAModifier_orWithAltOrShift_itDoesNot() {
        assertFalse(shortcut())
        assertFalse(shortcut(ctrl = true, alt = true))
        assertFalse(shortcut(meta = true, shift = true))
        assertFalse(shortcut(key = "j", ctrl = true))
    }

    @Test
    fun aTakenKey_aComposition_andATextEditor_areIgnored() {
        assertFalse(shortcut(ctrl = true, defaultPrevented = true))
        assertFalse(shortcut(ctrl = true, composing = true))
        assertFalse(shortcut(ctrl = true, inTextEditor = true))
    }

    @Test
    fun applePlatforms_getTheCommandHint() {
        assertTrue(isApplePlatformName("MacIntel"))
        assertTrue(isApplePlatformName("macOS"))
        assertTrue(isApplePlatformName("iPhone"))
        assertTrue(isApplePlatformName("iPad"))
        assertFalse(isApplePlatformName("Win32"))
        assertFalse(isApplePlatformName("Linux x86_64"))
        assertFalse(isApplePlatformName(""))
    }

    @Test
    fun theHint_isAlwaysOneOfTheTwoSpellings() {
        val hint = menuSearchShortcutHint()
        assertTrue(hint == "⌘K" || hint == "Strg K", hint)
        assertEquals(if (hint == "⌘K") "Meta+K" else "Control+K", menuSearchAriaShortcut())
    }
}
