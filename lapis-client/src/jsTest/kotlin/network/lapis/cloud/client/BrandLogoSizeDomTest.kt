package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.css.CSSStyleRule
import org.w3c.dom.css.CSSStyleSheet
import kotlin.coroutines.resume
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.9.210 "Logo in der Kopfleiste": the operator logo and the default mark are 32px (2rem) tall in the navbar and 40px in the login
 * lockup, with a real 0.5rem gap to the title, WITHOUT making the navbar taller. Measured on the real cascade (Bootstrap + KVision +
 * `theme.css`) in Karma's Chrome. Limits: the whole app shell cannot be mounted, so the navbar is a probe carrying the production
 * classes; a narrow viewport is emulated by a narrow host element.
 */
class BrandLogoSizeDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }

        const val SQUARE_LOGO =
            "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='200' height='200'%3E" +
                "%3Crect width='200' height='200'/%3E%3C/svg%3E"
        const val WIDE_LOGO =
            "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='800' height='100'%3E" +
                "%3Crect width='800' height='100'/%3E%3C/svg%3E"
    }

    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

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

    private suspend fun HTMLImageElement.loaded() {
        if (complete && naturalWidth > 0) return
        suspendCancellableCoroutine<Unit> { cont ->
            onload = { cont.resume(Unit) }
            onerror = { _, _, _, _, _ -> cont.resume(Unit) }
        }
    }

    private class Probe(
        val host: HTMLElement,
        val navbar: HTMLElement,
        val brand: HTMLElement,
        val logo: HTMLElement?,
        val trigger: HTMLElement,
    ) {
        val img: HTMLImageElement? get() = logo?.querySelector("img") as? HTMLImageElement
    }

    /** A navbar probe: brand (optional mark/logo + title text node) and a right-hand toolbar trigger. */
    private suspend fun withNavbar(
        hostWidth: Int,
        logoSrc: String? = null,
        defaultMark: Boolean = false,
        legacyBrandPadding: Boolean = false,
        block: suspend (Probe) -> Unit,
    ) {
        assertTrue(stylesLoaded)
        val host = el(document.body!!, "")
        host.style.width = "${hostWidth}px"
        try {
            val navbar = el(host, "navbar lapis-navbar")
            val brand = el(navbar, "navbar-brand", "a")
            var logo: HTMLElement? = null
            if (logoSrc != null) {
                logo = el(brand, "lapis-brand-logo", "span")
                logo.innerHTML = brandLogoImgHtml(src = logoSrc, alt = "Logo")
            } else if (defaultMark) {
                logo = el(brand, "lapis-brand-mark", "span")
                logo.innerHTML = LAPIS_CLOUD_MARK_SVG
            }
            brand.appendChild(document.createTextNode("Lapis Cloud"))
            if (legacyBrandPadding) {
                // The pre-V1.9.210 geometry: Bootstrap's own 5px brand padding, no flex row.
                brand.style.paddingTop = "5px"
                brand.style.paddingBottom = "5px"
            }
            val trigger = el(navbar, "nav-link", "button")
            trigger.style.width = "44px"
            trigger.style.flexShrink = "0"
            trigger.textContent = "x"
            val probe = Probe(host, navbar, brand, logo, trigger)
            probe.img?.loaded()
            block(probe)
        } finally {
            host.remove()
        }
    }

    /** The navbar height of the old geometry (title only, 5px brand padding) at the given width. */
    private suspend fun legacyNavbarHeight(width: Int): Double {
        var height = 0.0
        withNavbar(width, legacyBrandPadding = true) { height = it.navbar.getBoundingClientRect().height }
        return height
    }

    @Test
    fun operatorLogo_square_is32pxTall_notTheOld22() =
        test {
            withNavbar(1280, SQUARE_LOGO) { p ->
                val h = p.img!!.getBoundingClientRect().height
                assertEquals(32.0, h, 0.5, "logo height is 2rem")
                assertTrue(h > 22.0)
            }
        }

    @Test
    fun navbarWithLogo_isNotTallerThanTheOldNavbar() =
        test {
            val old = legacyNavbarHeight(1280)
            withNavbar(1280, SQUARE_LOGO) { p ->
                assertEquals(old, p.navbar.getBoundingClientRect().height, 0.5, "the logo does not grow the bar")
            }
        }

    /** The declared value of [property] in the top-level (not media-wrapped) rule with exactly this [selector]. */
    private fun declared(
        selector: String,
        property: String,
    ): String {
        var found = ""
        for (i in 0 until document.styleSheets.length) {
            val sheet = document.styleSheets.item(i) as? CSSStyleSheet ?: continue
            val rules =
                try {
                    sheet.cssRules
                } catch (_: dynamic) {
                    continue
                }
            for (j in 0 until rules.length) {
                val rule = rules.item(j) as? CSSStyleRule ?: continue
                if (rule.selectorText == selector) {
                    val value = rule.style.getPropertyValue(property)
                    if (value.isNotEmpty()) found = value
                }
            }
        }
        return found
    }

    @Test
    fun gapBetweenLogoAndTitle_isHalfARemOnDesktop_andNoneOnPhones() =
        test {
            // Karma's window is ~765px wide, i.e. inside the phone media block, so the desktop value is also checked on the rule itself.
            assertEquals("0.5rem", declared(".lapis-navbar .navbar-brand", "column-gap"))
            withNavbar(1280, SQUARE_LOGO) { p ->
                val range = document.createRange()
                val title = p.brand.lastChild!!
                range.setStart(title, 0)
                range.setEnd(title, title.textContent!!.length)
                val gap = range.getBoundingClientRect().left - p.img!!.getBoundingClientRect().right
                if (window.innerWidth >= 768) {
                    assertTrue(gap >= 4.0, "gap between logo and title: $gap")
                    assertEquals(8.0, gap, 0.5)
                } else {
                    assertEquals(0.0, gap, 0.5, "phones: no gap next to the hidden title")
                }
            }
        }

    @Test
    fun wideLogo_onPhone_isCappedTo120px_and_nothingOverflows() =
        test {
            val old = legacyNavbarHeight(390)
            withNavbar(390, WIDE_LOGO) { p ->
                val img = p.img!!.getBoundingClientRect()
                assertTrue(img.width <= 120.5, "wide logo width on a phone: ${img.width}")
                assertTrue(p.navbar.scrollWidth <= p.navbar.clientWidth, "no horizontal overflow of the navbar")
                assertTrue(p.navbar.getBoundingClientRect().height <= old + 0.5, "navbar height on a phone")
                val host = p.host.getBoundingClientRect()
                val trigger = p.trigger.getBoundingClientRect()
                assertTrue(trigger.right <= host.right + 0.5 && trigger.left >= host.left, "toolbar trigger stays inside the viewport")
            }
        }

    @Test
    fun wideLogo_onDesktop_isCappedTo180px() =
        test {
            withNavbar(1280, WIDE_LOGO) { p ->
                val img = p.img!!.getBoundingClientRect()
                assertTrue(img.width <= 180.5, "wide logo width on desktop: ${img.width}")
                assertEquals(32.0, img.height, 0.5)
            }
            assertEquals("180px", declared(".lapis-brand-logo img", "max-width"))
        }

    @Test
    fun defaultMark_is32px_and_barKeepsItsHeight() =
        test {
            val old = legacyNavbarHeight(1280)
            withNavbar(1280, defaultMark = true) { p ->
                val visible =
                    (0 until p.logo!!.querySelectorAll("svg").length)
                        .map { p.logo.querySelectorAll("svg").item(it) as Element }
                        .single { it.getBoundingClientRect().height > 0.0 }
                assertEquals(32.0, visible.getBoundingClientRect().height, 0.5)
                assertEquals(32.0, visible.getBoundingClientRect().width, 0.5)
                assertEquals(old, p.navbar.getBoundingClientRect().height, 0.5)
            }
        }

    @Test
    fun loginLockup_logoIs40pxTall_withAGap() =
        test {
            assertTrue(stylesLoaded)
            val host = el(document.body!!, "")
            try {
                val lockup = el(host, "lapis-lockup", "a")
                val wrapper = el(lockup, "lapis-brand-logo", "span")
                wrapper.innerHTML = brandLogoImgHtml(src = SQUARE_LOGO, alt = "Logo")
                val img = wrapper.querySelector("img") as HTMLImageElement
                lockup.appendChild(document.createTextNode("Lapis Cloud"))
                img.loaded()
                assertEquals(40.0, img.getBoundingClientRect().height, 0.5)
                val range = document.createRange()
                val title = lockup.lastChild!!
                range.setStart(title, 0)
                range.setEnd(title, title.textContent!!.length)
                val gap = range.getBoundingClientRect().left - img.getBoundingClientRect().right
                assertTrue(gap >= 4.0, "lockup gap: $gap")

                val markLockup = el(host, "lapis-lockup", "a")
                el(markLockup, "lapis-brand-mark", "span").innerHTML = LAPIS_CLOUD_MARK_SVG
                val svg =
                    (0 until markLockup.querySelectorAll("svg").length)
                        .map { markLockup.querySelectorAll("svg").item(it) as Element }
                        .single { it.getBoundingClientRect().height > 0.0 }
                assertEquals(40.0, svg.getBoundingClientRect().height, 0.5)
            } finally {
                host.remove()
            }
        }
}
