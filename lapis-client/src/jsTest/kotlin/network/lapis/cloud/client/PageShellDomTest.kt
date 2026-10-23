package network.lapis.cloud.client

import io.kvision.html.div
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.189: the one page shell (`pageShell`, `theme.css` `.lapis-page`) measured on the real cascade (Bootstrap + KVision + `theme.css` in Karma).
 *
 * Mechanism and limit: Karma runs ONE fixed window, so the `@media` rules (padding 16/24/32 px) follow the real viewport and cannot be
 * varied here. Widths are varied through the HOST element instead (the shell's `max-width` and the centring margins depend on the width of its
 * containing block, not on the viewport); the padding is asserted against the viewport that Karma really has, and the media rules themselves are
 * pinned by scanning the loaded stylesheet text.
 */
class PageShellDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private fun style(element: HTMLElement) = window.getComputedStyle(element)

    private fun HTMLElement.q(selector: String) = querySelector(selector) as HTMLElement

    /** Border-box width of the shell, its computed left margin, for a host that is [hostWidth] px wide. */
    private fun measure(
        width: PageWidth,
        hostWidth: Int,
    ): Pair<Double, String> {
        var result: Pair<Double, String>? = null
        withMountedRoot("page-shell-${width.name}-$hostWidth") { root, element ->
            root.pageShell(width) { div("content") }
            element().style.width = "${hostWidth}px"
            val shell = element().q(".lapis-page")
            result = shell.getBoundingClientRect().width to style(shell).marginLeft
        }
        return result!!
    }

    private fun expectedPaddingPx(): Int =
        when {
            window.innerWidth >= 1400 -> 32
            window.innerWidth >= 768 -> 24
            else -> 16
        }

    @Test
    fun wide_at2000_isTheCentred100remFrame() {
        assertTrue(stylesLoaded)
        val (outer, marginLeft) = measure(PageWidth.WIDE, 2000)
        assertEquals(1600.0, outer, 0.5)
        assertEquals("200px", marginLeft)
    }

    @Test
    fun form_at2000_sitsLeftInsideTheSame100remFrame() {
        assertTrue(stylesLoaded)
        val (outer, marginLeft) = measure(PageWidth.FORM, 2000)
        assertEquals(960.0, outer, 0.5)
        assertEquals("200px", marginLeft, "the left edge is the same as the wide shell's")
    }

    @Test
    fun below100rem_wideFillsTheHost_formKeepsItsMeasure() {
        assertTrue(stylesLoaded)
        val (wide, wideMargin) = measure(PageWidth.WIDE, 1000)
        assertEquals(1000.0, wide, 0.5)
        assertEquals("0px", wideMargin)
        val (form, formMargin) = measure(PageWidth.FORM, 1000)
        assertEquals(960.0, form, 0.5)
        assertEquals("0px", formMargin)
    }

    @Test
    fun narrowHost_shellNeverOverflows() {
        assertTrue(stylesLoaded)
        listOf(375, 768, 1024, 1440, 1920).forEach { host ->
            listOf(PageWidth.WIDE, PageWidth.FORM).forEach { width ->
                var overflow = true
                var outer = 0.0
                withMountedRoot("page-shell-fit-${width.name}-$host") { root, element ->
                    root.pageShell(width) { div("content") }
                    element().style.width = "${host}px"
                    val shell = element().q(".lapis-page")
                    outer = shell.getBoundingClientRect().width
                    overflow = shell.scrollWidth > shell.clientWidth
                }
                assertFalse(overflow, "$width at host width $host overflows")
                val cap = if (width == PageWidth.WIDE) 1600.0 else 960.0
                assertEquals(minOf(host.toDouble(), cap), outer, 0.5, "$width at host width $host")
            }
        }
    }

    @Test
    fun padding_followsTheRealViewport() {
        assertTrue(stylesLoaded)
        withMountedRoot("page-shell-padding") { root, element ->
            root.pageShell(PageWidth.WIDE) { div("content") }
            val shell = element().q(".lapis-page")
            assertEquals("${expectedPaddingPx()}px", style(shell).paddingLeft)
            assertEquals("${expectedPaddingPx()}px", style(shell).paddingRight)
            assertEquals("24px", style(shell).marginTop)
        }
    }

    @Test
    fun mediaRules_arePinnedInTheStylesheet() {
        assertTrue(stylesLoaded)
        val css = StringBuilder()
        val sheets = window.document.styleSheets
        for (i in 0 until sheets.length) {
            val sheet = sheets.asDynamic()[i]
            try {
                val rules = sheet.cssRules
                for (j in 0 until (rules.length as Int)) css.append(rules[j].cssText as String)
            } catch (_: Throwable) {
                // cross-origin sheet: not ours
            }
        }
        val text = css.toString()
        assertTrue(text.contains(".lapis-page--wide"), "wide variant present")
        assertTrue(text.contains(".lapis-page--form"), "form variant present")
        assertTrue(
            Regex("""@media \(min-width: 768px\)\s*\{[^}]*\.lapis-page\b[^}]*padding-inline""").containsMatchIn(text),
            "768 px padding rule",
        )
        assertTrue(
            Regex("""@media \(min-width: 1400px\)\s*\{[^}]*\.lapis-page\b[^}]*padding-inline""").containsMatchIn(text),
            "1400 px padding rule",
        )
    }

    @Test
    fun classes_dataScreenRootIsTheWideShell_formSectionIsTheFormMeasure() {
        assertTrue(stylesLoaded)
        withMountedRoot("page-shell-classes") { root, element ->
            root.dataScreenRoot { formSection { div("form") } }
            val shell = element().q(".lapis-page")
            assertTrue(shell.classList.contains("lapis-page--wide"))
            assertFalse(shell.classList.contains("lapis-page--form"))
            assertEquals("960px", style(element().q(".lapis-page-section--form")).maxWidth)
            assertEquals("", shell.style.maxWidth, "no inline maxWidth: it would beat the stylesheet")
        }
    }

    @Test
    fun rowHelpers_buttonRowFilterRowAndToggle() {
        assertTrue(stylesLoaded)
        withMountedRoot("page-shell-rows") { root, element ->
            val buttons = root.buttonRow()
            val filters = root.filterRow()
            val toggle = filters.div("switch")
            toggle.asFilterToggle()
            assertTrue(buttons.getElement()!!.classList.contains("lapis-button-row"))
            assertEquals("flex", style(element().q(".lapis-button-row")).display)
            assertEquals("wrap", style(element().q(".lapis-button-row")).flexWrap)
            assertTrue(filters.getElement()!!.classList.contains("lapis-filter-row"))
            assertEquals("flex-end", style(element().q(".lapis-filter-row")).alignItems)
            assertTrue(element().q(".lapis-filter-toggle").textContent == "switch")
        }
    }
}
