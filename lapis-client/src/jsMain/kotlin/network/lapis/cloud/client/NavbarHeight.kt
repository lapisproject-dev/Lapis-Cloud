package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.client.maplibre.ResizeObserver
import org.w3c.dom.HTMLElement

/** CSS custom property that holds the measured height of the sticky navbar (V1.9.209). The CSS default is 56px. */
internal const val NAVBAR_HEIGHT_VAR = "--lapis-navbar-height"

private const val NAVBAR_SELECTOR = ".navbar.lapis-navbar"
private const val MAX_ATTEMPTS = 120

private var navbarObserver: ResizeObserver? = null
private var observedNavbar: HTMLElement? = null

/** Writes the current offset height of [navbar] into [NAVBAR_HEIGHT_VAR] on `:root`. */
internal fun applyNavbarHeight(navbar: HTMLElement) {
    val height = navbar.offsetHeight
    if (height > 0) {
        document.documentElement
            ?.let { it as? HTMLElement }
            ?.style
            ?.setProperty(NAVBAR_HEIGHT_VAR, "${height}px")
    }
}

/**
 * Keeps [NAVBAR_HEIGHT_VAR] equal to the real height of the sticky navbar (text zoom, wrapping brand, language switch), so that
 * everything that sticks or scrolls below it (banner, member map, totals bar, rating booth head, `scroll-padding-top`) follows.
 *
 * Idempotent: the observer is installed once per navbar element; a different element (after a re-render) replaces the previous
 * observer, which is disconnected first, so observers never stack. While the navbar is not in the document yet it retries on the
 * next animation frames (bounded).
 */
internal fun installNavbarHeightObserver(attempt: Int = 0) {
    val navbar = document.querySelector(NAVBAR_SELECTOR) as? HTMLElement
    if (navbar == null) {
        if (attempt < MAX_ATTEMPTS) window.requestAnimationFrame { installNavbarHeightObserver(attempt + 1) }
        return
    }
    if (navbar === observedNavbar && navbar.isConnected) return
    navbarObserver?.disconnect()
    applyNavbarHeight(navbar)
    navbarObserver = ResizeObserver { _, _ -> applyNavbarHeight(navbar) }.also { it.observe(navbar) }
    observedNavbar = navbar
}

/** Stops the observer (tests). */
internal fun disposeNavbarHeightObserver() {
    navbarObserver?.disconnect()
    navbarObserver = null
    observedNavbar = null
}
