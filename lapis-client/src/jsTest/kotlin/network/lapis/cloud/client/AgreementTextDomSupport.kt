package network.lapis.cloud.client

import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/** `true` when the Karma window is at least as wide as the registration grid's breakpoint (the media query follows the REAL window). */
internal fun windowIsWide(): Boolean = window.innerWidth >= WIDE_BREAKPOINT_PX

internal const val WIDE_BREAKPOINT_PX = 768

internal fun loadThemeStyles(): Boolean {
    js("require('bootstrap/dist/css/bootstrap.min.css')")
    js("require('zzz-kvision-assets/css/kv-style.css')")
    js("require('./theme.css')")
    return true
}

/** The computed track count of a grid element (`grid-template-columns` resolves to one length per track). */
internal fun HTMLElement.gridTrackCount(): Int =
    window
        .getComputedStyle(this)
        .getPropertyValue("grid-template-columns")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() && it != "none" }
        .size
