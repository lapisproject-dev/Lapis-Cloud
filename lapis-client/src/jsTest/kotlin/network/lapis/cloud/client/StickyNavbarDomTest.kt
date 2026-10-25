package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.209 "Feste Kopfleiste": the navbar sticks to the top edge on every viewport, and everything below it follows. Measured on the real
 * cascade (Bootstrap + KVision + `theme.css`) in Karma's Chrome. Limits: the whole app shell cannot be mounted here, so the navbar, banner
 * and booth head are probes carrying the production classes; Karma has ONE fixed window, so a narrow width is set through the host
 * element; the page is scrolled with `window.scrollTo`.
 */
class StickyNavbarDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private class Probe(
        val host: HTMLElement,
        val navbar: HTMLElement,
        val banner: HTMLElement?,
        val head: HTMLElement?,
        val focusTarget: HTMLElement,
    )

    private fun el(
        parent: HTMLElement,
        className: String,
        tag: String = "div",
    ): HTMLElement {
        val element = document.createElement(tag) as HTMLElement
        element.className = className
        parent.appendChild(element)
        return element
    }

    private fun withProbe(
        hostWidth: Int,
        withBanner: Boolean = false,
        withHead: Boolean = false,
        block: (Probe) -> Unit,
    ) {
        assertTrue(stylesLoaded)
        val host = el(document.body!!, "")
        host.style.width = "${hostWidth}px"
        try {
            val navbar = el(host, "navbar lapis-navbar")
            el(navbar, "navbar-brand", "span").textContent = "Lapis Cloud"
            val banner =
                if (withBanner) {
                    el(host, "lapis-conference-transparency-banner").also { it.textContent = "Transparenz" }
                } else {
                    null
                }
            val head =
                if (withHead) {
                    el(
                        host,
                        "lapis-rating-booth-head bg-body py-2 border-bottom",
                    ).also { it.textContent = "0/3" }
                } else {
                    null
                }
            el(host, "").style.height = "300px"
            val focusTarget = el(host, "", "button").also { it.textContent = "Fokus" }
            el(host, "").style.height = "3000px"
            val probe = Probe(host, navbar, banner, head, focusTarget)
            installNavbarHeightObserver()
            try {
                block(probe)
            } finally {
                disposeNavbarHeightObserver()
                scrollTo(0.0)
            }
        } finally {
            host.remove()
            document.documentElement?.let { (it as HTMLElement).style.removeProperty(NAVBAR_HEIGHT_VAR) }
        }
    }

    /** Instant scroll: the stylesheet may set `scroll-behavior: smooth`, which would make `scrollTo` asynchronous. */
    private fun scrollTo(y: Double) {
        val html = document.documentElement as HTMLElement
        html.style.setProperty("scroll-behavior", "auto")
        window.scrollTo(0.0, y)
    }

    private fun top(element: HTMLElement) = element.getBoundingClientRect().top

    private fun bottom(element: HTMLElement) = element.getBoundingClientRect().bottom

    private fun checkSticky(width: Int) =
        withProbe(width) { p ->
            scrollTo(600.0)
            assertTrue(window.scrollY >= 500.0, "the page must really scroll for this test to mean anything")
            assertEquals("sticky", window.getComputedStyle(p.navbar).position)
            assertEquals(0.0, top(p.navbar), 0.5, "the navbar stays at the top edge after scrolling")
            val centre = p.navbar.getBoundingClientRect()
            val hit = document.elementFromPoint(centre.left + 2.0, centre.top + centre.height / 2)
            assertNotNull(hit)
            assertTrue(p.navbar.contains(hit), "the navbar is the topmost element at its own centre")
        }

    @Test
    fun navbar_sticksAfterScrolling_at390() = checkSticky(390)

    @Test
    fun navbar_sticksAfterScrolling_at1280() = checkSticky(1280)

    @Test
    fun navbar_heightIsTheMinHeightToken_and_variableFollowsOffsetHeight() =
        withProbe(1280) { p ->
            // Pre-measure: the token's default is 56px at 100% zoom; the probe has no taller content.
            // (The probe carries Bootstrap's own navbar padding + brand, so it is a little taller than the real bar; the real bar is measured
            // manually, see the wave notes.)
            assertTrue(p.navbar.offsetHeight >= 56, "the navbar is never below the 56px token: ${p.navbar.offsetHeight}")
            p.navbar.style.paddingTop = "40px"
            assertTrue(p.navbar.offsetHeight > 56, "min-height must let the navbar grow")
            p.navbar.style.paddingTop = ""
            applyNavbarHeight(p.navbar)
            assertTrue(p.navbar.offsetHeight < 80, "no ratchet: the navbar shrinks back (min-height is a literal, not the variable)")
            val value = document.documentElement!!.let { (it as HTMLElement).style.getPropertyValue(NAVBAR_HEIGHT_VAR) }
            assertEquals("${p.navbar.offsetHeight}px", value)
            // The observer follows a taller navbar (text zoom / wrapping brand) -- min-height, not height.
            p.navbar.style.paddingTop = "40px"
            applyNavbarHeight(p.navbar)
            assertTrue(p.navbar.offsetHeight > 56, "min-height must let the navbar grow")
            val grown = (document.documentElement as HTMLElement).style.getPropertyValue(NAVBAR_HEIGHT_VAR)
            assertEquals("${p.navbar.offsetHeight}px", grown)
        }

    @Test
    fun observer_isIdempotent_forTheSameElement() =
        withProbe(1280) { p ->
            installNavbarHeightObserver()
            installNavbarHeightObserver()
            assertEquals("${p.navbar.offsetHeight}px", (document.documentElement as HTMLElement).style.getPropertyValue(NAVBAR_HEIGHT_VAR))
        }

    @Test
    fun banner_restsDirectlyBelowTheNavbar() =
        withProbe(390, withBanner = true) { p ->
            scrollTo(600.0)
            assertEquals(bottom(p.navbar), top(p.banner!!), 0.5)
        }

    @Test
    fun focus_landsBelowNavbar_and_banner() {
        withProbe(390) { p ->
            scrollTo(0.0)
            p.focusTarget.focus()
            p.focusTarget.scrollIntoView()
            assertTrue(
                top(p.focusTarget) >= bottom(p.navbar) - 0.5,
                "focus target below the navbar: ${top(p.focusTarget)} vs ${bottom(p.navbar)}",
            )
        }
        withProbe(390, withBanner = true) { p ->
            assertTrue(document.documentElement!!.matches("html:has(.lapis-conference-transparency-banner)"), ":has() must be active")
            p.focusTarget.scrollIntoView()
            assertTrue(top(p.focusTarget) >= bottom(p.banner!!) - 0.5, "focus target below the banner")
        }
    }

    @Test
    fun ratingBoothHead_restsBelowNavbar_and_navbarStaysOnTop() =
        withProbe(390, withHead = true) { p ->
            scrollTo(600.0)
            assertEquals(bottom(p.navbar), top(p.head!!), 0.5)
            val r = p.navbar.getBoundingClientRect()
            val hit = document.elementFromPoint(r.left + 2.0, r.top + r.height / 2)
            assertTrue(hit != null && p.navbar.contains(hit))
        }

    @Test
    fun print_dropsTheStickyPositions() {
        assertTrue(stylesLoaded)
        val css =
            (0 until document.styleSheets.length).joinToString("\n") { i ->
                val sheet = document.styleSheets.item(i) as? org.w3c.dom.css.CSSStyleSheet
                try {
                    val rules = sheet?.cssRules
                    (0 until (rules?.length ?: 0)).joinToString("\n") { j -> rules!!.item(j)?.cssText ?: "" }
                } catch (_: dynamic) {
                    ""
                }
            }
        val printRule = Regex("""@media print \{[^@]*?\.navbar\.lapis-navbar[^{]*\{[^}]*position:\s*static""").containsMatchIn(css)
        assertTrue(printRule, "the print block must make the navbar static")
    }
}
