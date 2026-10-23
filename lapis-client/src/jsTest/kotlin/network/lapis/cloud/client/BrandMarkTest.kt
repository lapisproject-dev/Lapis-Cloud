package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLScriptElement
import org.w3c.dom.svg.SVGElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.195 -- the default Lapis Cloud mark: two inline SVGs (light + dark) in one span, an operator logo always wins, a custom title
 * without a logo shows no mark, the footer variant is 12 px and has no sparkle, and exactly one of the two SVGs is visible per theme
 * (the rule lives in theme.css; a copy of it is injected here, see [RULES]).
 */
class BrandMarkTest {
    private companion object {
        const val BRAND_ID = "lapis-brand"
        const val STYLE_ID = "brand-mark-test-style"

        /**
         * The two visibility rules, copied from `theme.css` (ClientFaviconTest pins them there). The whole stylesheet is NOT loaded
         * here: a global `theme.css` mounted this early changes the layout of later DOM tests (found live: EncounterBellDomTest).
         */
        const val RULES = """
            html[data-theme="dark"] .lapis-brand-mark--light,
            html:not([data-theme="dark"]) .lapis-brand-mark--dark { display: none; }
        """
    }

    private val themeBefore: String? = document.documentElement?.getAttribute("data-theme")

    init {
        val style = document.createElement("style")
        style.id = STYLE_ID
        style.textContent = RULES
        document.head?.appendChild(style)
    }

    @AfterTest
    fun cleanup() {
        document.getElementById(BRAND_ID)?.remove()
        document.getElementById(STYLE_ID)?.remove()
        if (themeBefore == null) {
            document.documentElement?.removeAttribute("data-theme")
        } else {
            document.documentElement?.setAttribute("data-theme", themeBefore)
        }
    }

    private fun setBrand(json: String) {
        document.getElementById(BRAND_ID)?.remove()
        val script = document.createElement("script") as HTMLScriptElement
        script.type = "application/json"
        script.id = BRAND_ID
        script.textContent = json
        document.head?.appendChild(script)
    }

    private fun HTMLElement.svgs() = querySelectorAll("svg.lapis-brand-mark-svg")

    private fun SVGElement.displayed(): Boolean = window.getComputedStyle(this).display != "none"

    @Test
    fun defaultBrand_lockupHoldsBothVariants_decorative22px() {
        setBrand("""{"title":"Lapis Cloud","logoUrl":null}""")
        withMountedRoot("brand-mark-default") { root, element ->
            root.brandLockup()
            val el = element()
            assertEquals(1, el.querySelectorAll(".lapis-brand-mark--light").length)
            assertEquals(1, el.querySelectorAll(".lapis-brand-mark--dark").length)
            for (i in 0 until el.svgs().length) {
                val svg = el.svgs().item(i) as SVGElement
                assertEquals("22", svg.getAttribute("width"))
                assertEquals("22", svg.getAttribute("height"))
                assertEquals("true", svg.getAttribute("aria-hidden"))
                assertEquals("false", svg.getAttribute("focusable"))
            }
        }
    }

    @Test
    fun exactlyOneVariantIsVisible_perTheme() {
        setBrand("""{"title":"Lapis Cloud","logoUrl":null}""")
        withMountedRoot("brand-mark-theme") { root, element ->
            root.brandLockup()
            val light = element().querySelector(".lapis-brand-mark--light") as SVGElement
            val dark = element().querySelector(".lapis-brand-mark--dark") as SVGElement
            document.documentElement?.setAttribute("data-theme", "light")
            assertTrue(light.displayed())
            assertFalse(dark.displayed())
            document.documentElement?.setAttribute("data-theme", "dark")
            assertFalse(light.displayed())
            assertTrue(dark.displayed())
        }
    }

    @Test
    fun operatorLogo_winsAndNoDefaultMarkIsRendered() {
        setBrand("""{"title":"Lapis Cloud","logoUrl":"/brand/logo.svg"}""")
        withMountedRoot("brand-mark-logo") { root, element ->
            root.brandLockup()
            assertTrue(element().querySelector(".lapis-brand-logo img") != null)
            assertEquals(0, element().svgs().length)
        }
    }

    @Test
    fun customTitleWithoutLogo_showsNoMark() {
        setBrand("""{"title":"Mein Verein","logoUrl":null}""")
        withMountedRoot("brand-mark-custom") { root, element ->
            root.brandLockup()
            assertEquals(0, element().svgs().length)
        }
    }

    @Test
    fun footerAttribution_is12px_andHasNoSparkle() {
        withMountedRoot("brand-mark-footer") { root, element ->
            root.lapisAttribution()
            val el = element()
            assertEquals(2, el.svgs().length)
            for (i in 0 until el.svgs().length) {
                val svg = el.svgs().item(i) as SVGElement
                val box = svg.getBoundingClientRect()
                if (svg.displayed()) {
                    assertEquals(12.0, box.width)
                    assertEquals(12.0, box.height)
                }
            }
            val html = el.innerHTML
            assertFalse(html.contains("bm-spark"))
        }
    }

    @Test
    fun markConstants_haveNoIdsNoCurrentColorNoDefs() {
        for (svg in listOf(LAPIS_CLOUD_MARK_SVG, LAPIS_CLOUD_MARK_SMALL_SVG)) {
            assertFalse(svg.contains(" id="))
            assertFalse(svg.contains("currentColor"))
            assertFalse(svg.contains("<defs"))
            assertFalse(svg.contains("<script"))
            assertFalse(svg.contains("#"), "no colour literal in the Kotlin markup")
            assertEquals(2, Regex("<svg ").findAll(svg).count())
        }
        assertEquals(2, Regex("bm-spark").findAll(LAPIS_CLOUD_MARK_SVG).count())
        assertFalse(LAPIS_CLOUD_MARK_SMALL_SVG.contains("bm-spark"))
    }
}
