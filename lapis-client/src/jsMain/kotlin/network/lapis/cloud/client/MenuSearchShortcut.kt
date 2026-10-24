package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.browser.window

/**
 * Pure: does this key event open (or close) the command palette?
 *
 * Only Ctrl+K / Cmd+K without Alt and Shift. It is ignored when another handler already took the key ([defaultPrevented] -- the mail
 * editor uses Ctrl+K for "insert link" and says so with `preventDefault()`), while an input method composes a character, and while the
 * focus is in a field that edits running text ([inTextEditor]: a `contenteditable` area or a `textarea`), where the person means the
 * field's own shortcut. A plain one-line `input` does open the palette.
 */
internal fun isMenuSearchShortcut(
    key: String,
    ctrl: Boolean,
    meta: Boolean,
    alt: Boolean,
    shift: Boolean,
    defaultPrevented: Boolean,
    composing: Boolean,
    inTextEditor: Boolean,
): Boolean =
    key.equals("k", ignoreCase = true) &&
        (ctrl || meta) &&
        !alt &&
        !shift &&
        !defaultPrevented &&
        !composing &&
        !inTextEditor

/** Pure: does this platform name belong to an Apple device (shortcut hint "⌘K" instead of "Strg K")? */
internal fun isApplePlatformName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.contains("mac") || lower.contains("iphone") || lower.contains("ipad") || lower.contains("ipod")
}

/**
 * The shortcut hint for the field: "⌘K" on an Apple device, "Strg K" elsewhere. `navigator.platform` is deprecated, so the newer
 * `userAgentData.platform` comes first, then `platform`, then the user agent; every read is guarded and a failed detection means "Strg K".
 */
internal fun menuSearchShortcutHint(): String {
    val name =
        runCatching {
            window.navigator
                .asDynamic()
                .userAgentData
                ?.platform as? String
        }.getOrNull()
            ?: runCatching { window.navigator.platform }.getOrNull()
            ?: runCatching { window.navigator.userAgent }.getOrNull()
    if (name != null && isApplePlatformName(name)) return "⌘K"
    return gettext("Strg K")
}

/** The value for `aria-keyshortcuts` that matches [menuSearchShortcutHint]. */
internal fun menuSearchAriaShortcut(): String {
    // two returns, not `if .. else "Control+K"`: the catalog scan reads an upper-case literal after `else` as a raw German fallback
    if (menuSearchShortcutHint() == "⌘K") return "Meta+K"
    return "Control+K"
}
