package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import kotlin.coroutines.resume
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LIGHT_TOKEN = "--lapis-nav-logo-light-display"
private const val DARK_TOKEN = "--lapis-nav-logo-dark-display"

/** Simulates what the server `/theme.css` writes: the logo variant chosen by the lightness of `nav` (true = dark nav). */
internal fun setNavLogoTokens(dark: Boolean) {
    val style = document.documentElement.unsafeCast<HTMLElement>().style
    style.setProperty(LIGHT_TOKEN, if (dark) "none" else "block")
    style.setProperty(DARK_TOKEN, if (dark) "block" else "none")
}

internal fun clearNavLogoTokens() {
    val style = document.documentElement.unsafeCast<HTMLElement>().style
    style.removeProperty(LIGHT_TOKEN)
    style.removeProperty(DARK_TOKEN)
}

/**
 * V1.9.279 -- the navbar logo variant follows the lightness of `nav` (server-written tokens), not `data-theme`. Probes carry the
 * production classes and [brandSlot] markup; the computed style proves the raised selector wins in both `data-theme` states.
 */
class BrandLogoNavContrastDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }

        private fun svg(
            w: Int,
            h: Int,
            fill: String,
        ) =
            "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='$w' height='$h'%3E%3Crect width='$w' height='$h' fill='%23$fill'/%3E%3C/svg%3E"

        val LIGHT_URL = svg(200, 200, "ffffff")
        val DARK_URL = svg(300, 100, "000000")
    }

    private val themeBefore: String? = document.documentElement?.getAttribute("data-theme")

    @AfterTest
    fun cleanup() {
        clearNavLogoTokens()
        if (themeBefore == null) {
            document.documentElement?.removeAttribute("data-theme")
        } else {
            document.documentElement?.setAttribute("data-theme", themeBefore)
        }
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

    private fun HTMLElement.visibleParts(): List<Element> =
        (0 until querySelectorAll("img, svg").length)
            .map { querySelectorAll("img, svg").item(it) as Element }
            .filter {
                kotlinx.browser.window
                    .getComputedStyle(it)
                    .display != "none"
            }

    private suspend fun withProbe(
        navbar: Boolean,
        pair: LogoPair = LogoPair(light = LIGHT_URL, dark = DARK_URL),
        block: suspend (wrapper: HTMLElement) -> Unit,
    ) {
        assertTrue(stylesLoaded)
        val host = el(document.body!!, "")
        host.style.width = "1280px"
        try {
            val container =
                if (navbar) el(el(host, "navbar lapis-navbar"), "navbar-brand", "a") else el(host, "lapis-lockup", "a")
            val slot = brandSlot(pair = pair, alt = "Verein")
            val wrapper = el(container, slot.className, "span")
            wrapper.innerHTML = slot.html
            val images = wrapper.querySelectorAll("img")
            (0 until images.length).forEach { (images.item(it) as HTMLImageElement).loaded() }
            block(wrapper)
        } finally {
            host.remove()
        }
    }

    @Test
    fun darkNav_showsDarkImage_inBothDataThemeStates() =
        test {
            withProbe(navbar = true) { wrapper ->
                setNavLogoTokens(dark = true)
                for (theme in listOf("light", "dark")) {
                    document.documentElement?.setAttribute("data-theme", theme)
                    val shown = wrapper.visibleParts()
                    assertEquals(1, shown.size, "theme $theme")
                    assertTrue(shown.single().classList.contains("lapis-brand-logo--dark"), "theme $theme")
                    assertEquals(32.0, shown.single().getBoundingClientRect().height, 0.5)
                }
            }
        }

    @Test
    fun lightNav_showsLightImage_inBothDataThemeStates() =
        test {
            withProbe(navbar = true) { wrapper ->
                setNavLogoTokens(dark = false)
                for (theme in listOf("light", "dark")) {
                    document.documentElement?.setAttribute("data-theme", theme)
                    val shown = wrapper.visibleParts()
                    assertEquals(1, shown.size, "theme $theme")
                    assertTrue(shown.single().classList.contains("lapis-brand-logo--light"), "theme $theme")
                }
            }
        }

    @Test
    fun defaultFallbackTokens_meanDarkNav() =
        test {
            withProbe(navbar = true) { wrapper ->
                document.documentElement?.setAttribute("data-theme", "light")
                val shown = wrapper.visibleParts()
                assertEquals(1, shown.size)
                assertTrue(shown.single().classList.contains("lapis-brand-logo--dark"))
            }
        }

    @Test
    fun mixedSlot_withDarkNav_showsOnlyTheBuiltInDarkHalf() =
        test {
            withProbe(navbar = true, pair = LogoPair(light = LIGHT_URL, dark = null)) { wrapper ->
                setNavLogoTokens(dark = true)
                for (theme in listOf("light", "dark")) {
                    document.documentElement?.setAttribute("data-theme", theme)
                    val shown = wrapper.visibleParts()
                    assertEquals(1, shown.size, "theme $theme")
                    assertTrue(shown.single().classList.contains("lapis-brand-mark--dark"), "theme $theme")
                }
                setNavLogoTokens(dark = false)
                val shown = wrapper.visibleParts()
                assertEquals(1, shown.size)
                assertTrue(shown.single().classList.contains("lapis-brand-logo--light"))
            }
        }

    @Test
    fun lockup_stillFollowsDataTheme_regardlessOfNavTokens() =
        test {
            withProbe(navbar = false) { wrapper ->
                for (dark in listOf(true, false)) {
                    setNavLogoTokens(dark = dark)
                    for (theme in listOf("light", "dark")) {
                        document.documentElement?.setAttribute("data-theme", theme)
                        val shown = wrapper.visibleParts()
                        assertEquals(1, shown.size, "theme $theme")
                        assertTrue(shown.single().classList.contains("lapis-brand-logo--$theme"))
                        assertEquals(40.0, shown.single().getBoundingClientRect().height, 0.5)
                    }
                }
            }
        }
}
