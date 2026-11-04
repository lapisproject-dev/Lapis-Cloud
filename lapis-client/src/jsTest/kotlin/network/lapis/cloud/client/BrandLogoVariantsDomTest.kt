package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import kotlin.coroutines.resume
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.265 -- the light/dark logo pair on the real cascade (Bootstrap + KVision + `theme.css`) in Karma's Chrome: exactly one of the two
 * images is displayed per `data-theme`, at the usual size (32px navbar, 40px lockup), and flips live without a reload.
 * Limits: the whole app shell cannot be mounted, so the navbar/lockup are probes carrying the production classes and the markup of
 * [brandSlot]; real artwork and real devices are not covered.
 */
class BrandLogoVariantsDomTest {
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
        if (themeBefore ==
            null
        ) {
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

    private fun HTMLElement.images(): List<HTMLImageElement> =
        (0 until querySelectorAll("img").length).map { querySelectorAll("img").item(it) as HTMLImageElement }

    private fun HTMLImageElement.shown(): Boolean =
        kotlinx.browser.window
            .getComputedStyle(this)
            .display != "none"

    private suspend fun withProbe(
        navbar: Boolean,
        block: suspend (container: HTMLElement, wrapper: HTMLElement) -> Unit,
    ) {
        assertTrue(stylesLoaded)
        val host = el(document.body!!, "")
        host.style.width = "1280px"
        try {
            val container =
                if (navbar) el(el(host, "navbar lapis-navbar"), "navbar-brand", "a") else el(host, "lapis-lockup", "a")
            val slot = brandSlot(pair = LogoPair(light = LIGHT_URL, dark = DARK_URL), alt = "Verein")
            val wrapper = el(container, slot.className, "span")
            wrapper.innerHTML = slot.html
            wrapper.images().forEach { it.loaded() }
            block(container, wrapper)
        } finally {
            host.remove()
        }
    }

    @Test
    fun navbar_showsExactlyOneImage_perTheme_andFlipsLive_at32px() =
        test {
            withProbe(navbar = true) { _, wrapper ->
                document.documentElement?.setAttribute("data-theme", "light")
                setNavLogoTokens(dark = false)
                var shown = wrapper.images().filter { it.shown() }
                assertEquals(1, shown.size)
                assertTrue(shown.single().classList.contains("lapis-brand-logo--light"))
                assertEquals(32.0, shown.single().getBoundingClientRect().height, 0.5)

                document.documentElement?.setAttribute("data-theme", "dark")
                setNavLogoTokens(dark = true)
                shown = wrapper.images().filter { it.shown() }
                assertEquals(1, shown.size)
                assertTrue(shown.single().classList.contains("lapis-brand-logo--dark"))
                assertEquals(32.0, shown.single().getBoundingClientRect().height, 0.5)
                assertTrue(shown.single().getBoundingClientRect().width <= 180.5, "wide dark logo stays inside the cap")

                document.documentElement?.setAttribute("data-theme", "light")
                setNavLogoTokens(dark = false)
                shown = wrapper.images().filter { it.shown() }
                assertEquals(1, shown.size)
                assertTrue(shown.single().classList.contains("lapis-brand-logo--light"))
            }
        }

    @Test
    fun noDataTheme_behavesAsLight() =
        test {
            withProbe(navbar = true) { _, wrapper ->
                document.documentElement?.removeAttribute("data-theme")
                setNavLogoTokens(dark = false)
                val shown = wrapper.images().filter { it.shown() }
                assertEquals(1, shown.size)
                assertTrue(shown.single().classList.contains("lapis-brand-logo--light"))
            }
        }

    @Test
    fun lockup_showsExactlyOneImage_perTheme_at40px() =
        test {
            withProbe(navbar = false) { _, wrapper ->
                for (theme in listOf("light", "dark")) {
                    document.documentElement?.setAttribute("data-theme", theme)
                    val shown = wrapper.images().filter { it.shown() }
                    assertEquals(1, shown.size, "theme $theme")
                    assertTrue(shown.single().classList.contains("lapis-brand-logo--$theme"))
                    assertEquals(40.0, shown.single().getBoundingClientRect().height, 0.5)
                }
            }
        }

    @Test
    fun brandLinkWidth_doesNotJumpOverTheCap_whenSwitchingTheme() =
        test {
            withProbe(navbar = true) { container, _ ->
                document.documentElement?.setAttribute("data-theme", "light")
                setNavLogoTokens(dark = false)
                val light = container.getBoundingClientRect().width
                document.documentElement?.setAttribute("data-theme", "dark")
                setNavLogoTokens(dark = true)
                val dark = container.getBoundingClientRect().width
                assertTrue(light <= 180.5 + 16.0 && dark <= 180.5 + 16.0, "brand link widths: $light / $dark")
            }
        }

    @Test
    fun noImageCarriesALoadingAttribute() =
        test {
            withProbe(navbar = true) { _, wrapper ->
                wrapper.images().forEach { assertNull(it.getAttribute("loading")) }
                assertFalse(wrapper.innerHTML.contains("loading="))
            }
        }

    @Test
    fun hiddenImage_isNotInTheAccessibilityTree_theShownOneNamesTheLink() =
        test {
            withProbe(navbar = true) { _, wrapper ->
                document.documentElement?.setAttribute("data-theme", "dark")
                setNavLogoTokens(dark = true)
                val hidden = wrapper.images().filter { !it.shown() }
                assertEquals(1, hidden.size)
                assertEquals("Verein", wrapper.images().single { it.shown() }.alt)
            }
        }
}
