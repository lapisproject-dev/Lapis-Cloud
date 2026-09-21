package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.27 (W3): what the report grammar looks like -- with the REAL Bootstrap and `theme.css` loaded into
 * the Karma page (they are not part of the test bundle otherwise, so a style assertion would test nothing).
 *
 * The point of these tests: the sum row must beat Bootstrap's zebra WITHOUT `!important`. The zebra is a
 * `--bs-table-bg-type` + inset `box-shadow`, and its selector (0,2,2) ties with the zebra of `theme.css`; only
 * the (0,2,3) selectors of the report grammar win. A `background-color` alone would be painted over.
 */
class ReportRowStyleDomTest {
    private companion object {
        // The bundler resolves both like `App.kt` does (`BootstrapCssModule` / `require('./theme.css')`); the
        // test package carries the main resources next to its compiled code.
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('./theme.css')")
                true
            }
    }

    private val headers = listOf(TableHeader("Konto"), TableHeader("Kontenklasse"), TableHeader("Betrag", numeric = true))
    private val ledgerHeaders =
        listOf(
            TableHeader("Datum"),
            TableHeader("Beschreibung"),
            TableHeader("Soll", numeric = true),
            TableHeader("Haben", numeric = true),
            TableHeader("Saldo", numeric = true),
        )

    private fun style(element: HTMLElement) = window.getComputedStyle(element)

    private fun cell(
        root: HTMLElement,
        selector: String,
    ) = assertNotNull(root.querySelector(selector) as? HTMLElement, "nothing matches $selector")

    /**
     * Audit fix B1: the warn-red re-interpretation of `text-danger` inside table cells must reach the TEXT utility only, not the
     * inherited `--bs-danger-rgb` -- else `.text-bg-danger` badges in cells get a pale surface in the dark theme (white text on
     * #EA868F = 2.53:1). Measured on the real cascade, both themes, plain / zebra / sum rows.
     */
    @Test
    fun dangerBadgeInATableCellKeepsWhiteOnBootstrapRed_bothThemes() {
        assertTrue(stylesLoaded)
        listOf("light", "dark").forEach { theme ->
            inTheme(theme) {
                withMountedRoot("report-style-badge-$theme") { root, element ->
                    val report = root.reportTable(caption = "GuV", headers = headers)
                    report.reportRows(incomeStatementRows(incomeStatement), headers)
                    val trs = rows(element())
                    val cells =
                        listOf(
                            trs.first { it.className.isBlank() },
                            trs.first { it.classList.contains("lapis-total-row") },
                        ).map { assertNotNull(it.querySelector("td, th") as HTMLElement) }
                    cells.forEach { cell ->
                        listOf("badge text-bg-danger", "badge bg-danger text-white").forEach { classes ->
                            val badge = appendDanger(cell, "span", classes)
                            val surface = colourOf(style(badge).backgroundColor)
                            assertTrue(
                                surface.sameAs(colourOf("rgb(220, 53, 69)")),
                                "$theme: a '$classes' badge in a table cell is not Bootstrap red but ${style(badge).backgroundColor}",
                            )
                            assertTrue(
                                contrast(colourOf(style(badge).color), surface) >= 4.5,
                                "$theme: badge text ${style(badge).color} on ${style(badge).backgroundColor} is below AA",
                            )
                        }
                    }
                }
            }
        }
    }

    /** `text-danger` in a cell stays AA on the plain AND the zebra row in both themes (and on the td itself, not only on a child). */
    @Test
    fun dangerTextInACellIsAaOnPlainAndZebraRows_bothThemes() {
        assertTrue(stylesLoaded)
        listOf("light", "dark").forEach { theme ->
            inTheme(theme) {
                withMountedRoot("report-style-danger-$theme") { root, element ->
                    val report = root.reportTable(caption = "GuV", headers = headers)
                    report.reportRows(incomeStatementRows(incomeStatement), headers)
                    val trs = rows(element()).filter { it.className.isBlank() }
                    val all = rows(element())
                    val zebra = trs.first { all.indexOf(it) % 2 == 0 }
                    val even = trs.first { all.indexOf(it) % 2 == 1 }
                    listOf(zebra, even).forEach { row ->
                        val cell = assertNotNull(row.querySelector("td") as? HTMLElement)
                        val background = visibleBackground(cell)
                        val child = appendDanger(cell, "span", "text-danger")
                        assertTrue(
                            contrast(colourOf(style(child).color), background) >= 4.5,
                            "$theme: text-danger child ${style(child).color}",
                        )
                        cell.classList.add("text-danger")
                        assertTrue(
                            contrast(colourOf(style(cell).color), background) >= 4.5,
                            "$theme: text-danger cell ${style(cell).color}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun captionStandsAboveTheTable() {
        assertTrue(stylesLoaded)
        withMountedRoot("report-style-caption") { root, element ->
            root.reportTable(caption = "Bilanz", headers = headers)
            val table = cell(element(), "table")
            assertEquals(
                "top",
                style(table).getPropertyValue("caption-side"),
                "caption-top did not win over Bootstrap's caption-side: bottom",
            )
        }
    }

    /** A CSS colour as the browser reports it (`rgb(...)`/`rgba(...)`), channels 0..255, [a] 0..1. */
    private class Colour(
        val r: Double,
        val g: Double,
        val b: Double,
        val a: Double,
    ) {
        /** [this] painted over an opaque [under]. */
        fun over(under: Colour) =
            Colour(
                r = r * a + under.r * (1 - a),
                g = g * a + under.g * (1 - a),
                b = b * a + under.b * (1 - a),
                a = 1.0,
            )

        fun luminance(): Double {
            fun channel(value: Double): Double {
                val c = value / 255.0
                return if (c <= 0.03928) c / 12.92 else kotlin.math.exp(2.4 * kotlin.math.ln((c + 0.055) / 1.055))
            }
            return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        }

        fun sameAs(other: Colour) =
            kotlin.math.abs(r - other.r) < 0.5 && kotlin.math.abs(g - other.g) < 0.5 && kotlin.math.abs(b - other.b) < 0.5
    }

    private val colourPattern = Regex("""rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\s/]+([\d.]+))?\s*\)""")

    private fun colourOf(css: String): Colour {
        if (css.startsWith("#") && css.length == 7) {
            fun channel(from: Int) = css.substring(from, from + 2).toInt(16).toDouble()
            return Colour(channel(1), channel(3), channel(5), 1.0)
        }
        val match = assertNotNull(colourPattern.find(css), "not a colour: '$css'")
        val (r, g, b, a) = match.destructured
        return Colour(r.toDouble(), g.toDouble(), b.toDouble(), a.takeIf { it.isNotEmpty() }?.toDouble() ?: 1.0)
    }

    private fun contrast(
        one: Colour,
        other: Colour,
    ): Double {
        val (lighter, darker) = listOf(one.luminance(), other.luminance()).sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * What the eye sees on a cell: its `background-color` painted over the page, then Bootstrap's zebra/hover layer --
     * an INSET `box-shadow` of 9999 px, which is how Bootstrap 5.3 paints `--bs-table-bg-type` -- painted on top.
     * (A `background-color` alone is not what is on screen: that is exactly how the sum row came out LIGHTER than the
     * zebra in the light theme.)
     */
    private fun visibleBackground(cell: HTMLElement): Colour {
        // The page colour from its token, not from `body`'s computed value: `body` has a 0.15 s colour transition, which
        // would still report the OLD theme's colour right after a theme switch.
        val page = colourOf(style(document.documentElement as HTMLElement).getPropertyValue("--lapis-bg").trim())
        var visible = colourOf(style(cell).backgroundColor).over(page)
        val shadow = style(cell).getPropertyValue("box-shadow")
        if (shadow.contains("inset") && shadow.contains("9999px")) visible = colourOf(shadow).over(visible)
        return visible
    }

    private inline fun <T> inTheme(
        theme: String,
        block: () -> T,
    ): T {
        val html = document.documentElement!!
        val previousTheme = html.getAttribute("data-theme")
        val previousBootstrap = html.getAttribute("data-bs-theme")
        html.setAttribute("data-theme", theme)
        html.setAttribute("data-bs-theme", theme)
        try {
            return block()
        } finally {
            previousTheme?.let { html.setAttribute("data-theme", it) } ?: html.removeAttribute("data-theme")
            previousBootstrap?.let { html.setAttribute("data-bs-theme", it) } ?: html.removeAttribute("data-bs-theme")
        }
    }

    /** A `<span class=[classes]>` appended to [cell] (a stand-in for `text-danger` amounts and status badges in table cells). */
    private fun appendDanger(
        cell: HTMLElement,
        tag: String,
        classes: String,
    ): HTMLElement {
        val element = document.createElement(tag) as HTMLElement
        element.className = classes
        element.textContent = "x"
        cell.appendChild(element)
        return element
    }

    private fun rows(root: HTMLElement): List<HTMLElement> {
        val all = root.querySelectorAll("tbody > tr")
        return (0 until all.length).map { all.item(it) as HTMLElement }
    }

    /** The sum row must look different from a zebra row AND from a plain row, whichever theme is on. */
    private fun assertSumRowIsToldApart(
        theme: String,
        lighterThanZebra: Boolean,
    ) = inTheme(theme) {
        withMountedRoot("report-style-total-$theme") { root, element ->
            val report = root.reportTable(caption = "GuV", headers = headers)
            report.reportRows(incomeStatementRows(incomeStatement), headers)
            val trs = rows(element())
            val sum = trs.first { it.classList.contains("lapis-total-row") }
            val sumCell = assertNotNull(sum.querySelector("td") as? HTMLElement)
            val plain = trs.filter { it.className.isBlank() }
            // Bootstrap's `nth-of-type(odd)` is 1-based: the first row is odd = zebra.
            val zebra = assertNotNull(plain.firstOrNull { trs.indexOf(it) % 2 == 0 }, "no zebra data row")
            val even = assertNotNull(plain.firstOrNull { trs.indexOf(it) % 2 == 1 }, "no non-zebra data row")
            val zebraBackground = visibleBackground(assertNotNull(zebra.querySelector("td") as? HTMLElement))
            val evenBackground = visibleBackground(assertNotNull(even.querySelector("td") as? HTMLElement))
            val sumBackground = visibleBackground(sumCell)

            assertTrue(!sumBackground.sameAs(zebraBackground), "$theme: the sum row looks like a zebra row")
            assertTrue(!sumBackground.sameAs(evenBackground), "$theme: the sum row looks like a plain row")
            if (lighterThanZebra) {
                assertTrue(sumBackground.luminance() < zebraBackground.luminance(), "$theme: the sum row is not darker than the zebra")
                assertTrue(sumBackground.luminance() < evenBackground.luminance(), "$theme: the sum row is not darker than a plain row")
            }

            // WCAG 1.4.3: the label and a warn-red amount stay readable on the sum row's own surface ...
            assertTrue(contrast(colourOf(style(sumCell).getPropertyValue("color")), sumBackground) >= 4.5, "$theme: text on the sum row")
            val danger = appendDanger(sumCell, "span", "text-danger")
            assertTrue(
                contrast(colourOf(style(danger).color), sumBackground) >= 4.5,
                "$theme: warn-red amount on the sum row (${style(danger).color})",
            )
            // ... and WCAG 1.4.11: the top rule is a non-text contrast of 3:1 against BOTH neighbours (the row above and its own surface)
            val rule = colourOf(style(sumCell).borderTopColor)
            assertEquals("2px", style(sumCell).borderTopWidth, "$theme: the sum row's strong top line is missing")
            assertTrue(contrast(rule, sumBackground) >= 3.0, "$theme: the top rule against the sum row's own surface")
            val above = visibleBackground(assertNotNull(trs[trs.indexOf(sum) - 1].querySelector("td") as? HTMLElement))
            assertTrue(contrast(rule, above) >= 3.0, "$theme: the top rule against the row above")
            assertEquals("600", style(sumCell).fontWeight)
            val table = assertNotNull(element().querySelector("table") as? HTMLElement)
            assertEquals("separate", style(table).getPropertyValue("border-collapse"))
        }
    }

    @Test
    fun sumRowIsToldApartFromTheZebra_light_withoutImportant() {
        assertTrue(stylesLoaded)
        assertSumRowIsToldApart("light", lighterThanZebra = true)
    }

    @Test
    fun sumRowIsToldApartFromTheZebra_dark() {
        assertTrue(stylesLoaded)
        // In the dark theme Bootstrap's zebra LIGHTENS (white at 5 %) and the report surfaces sink: "different" is the criterion.
        assertSumRowIsToldApart("dark", lighterThanZebra = false)
    }

    @Test
    fun balanceRowGetsTheSameSurfaceAndARuleThatIsActuallyVisible() {
        assertTrue(stylesLoaded)
        listOf("light", "dark").forEach { theme ->
            inTheme(theme) {
                withMountedRoot("report-style-balance-$theme") { root, element ->
                    val report = root.reportTable(caption = "Hauptbuch", headers = headers)
                    report.reportRows(generalLedgerRows(generalLedger), ledgerHeaders)
                    val trs = rows(element())
                    val balance = trs.first { it.classList.contains("lapis-balance-row") }
                    val cell = assertNotNull(balance.querySelector("td") as? HTMLElement)
                    val total = visibleBackground(cell)
                    val below = visibleBackground(assertNotNull(trs[trs.indexOf(balance) + 1].querySelector("td") as? HTMLElement))
                    assertTrue(!total.sameAs(below), "$theme: the balance row looks like the plain row below it")
                    assertEquals("1px", style(cell).borderTopWidth)
                    assertTrue(contrast(colourOf(style(cell).borderTopColor), total) >= 3.0, "$theme: the balance rule against its surface")
                    // the 1-px rule of a balance row only shows in the SEPARATE border model (collapsed borders lose it
                    // against the 1-px bottom line of the row above)
                    assertEquals(
                        "separate",
                        style(assertNotNull(element().querySelector("table") as? HTMLElement)).getPropertyValue("border-collapse"),
                    )
                }
            }
        }
    }

    @Test
    fun closingResultRowIsHeavierThanTheSectionSums() {
        assertTrue(stylesLoaded)
        withMountedRoot("report-style-strong") { root, element ->
            val report = root.reportTable(caption = "GuV", headers = headers)
            report.reportRows(incomeStatementRows(incomeStatement), headers)
            val sums = rows(element()).filter { it.classList.contains("lapis-total-row") }
            val sectionSum = assertNotNull(sums.first().querySelector("td") as? HTMLElement)
            val result = assertNotNull(sums.last().querySelector("td") as? HTMLElement)
            assertTrue(sums.last().classList.contains("lapis-total-row-strong"))
            assertTrue(sums.dropLast(1).none { it.classList.contains("lapis-total-row-strong") })
            assertEquals("3px", style(result).borderTopWidth)
            assertEquals("2px", style(sectionSum).borderTopWidth)
            assertTrue(style(result).fontWeight.toInt() > style(sectionSum).fontWeight.toInt(), "the result is not heavier")
        }
    }

    @Test
    fun hiddenCaptionStaysInTheDocumentForAssistiveTechnology() {
        assertTrue(stylesLoaded)
        withMountedRoot("report-style-caption-hidden") { root, element ->
            root.reportTable(caption = "GuV", headers = headers, captionVisible = false)
            val caption = assertNotNull(element().querySelector("table > caption") as? HTMLElement, "the caption was removed")
            assertEquals("GuV", caption.textContent?.trim())
            // visually hidden = clipped to 1 px, not display:none (which would take it out of the accessibility tree)
            assertEquals("1px", style(caption).getPropertyValue("width"))
            assertEquals("absolute", style(caption).getPropertyValue("position"))
            assertTrue(style(caption).getPropertyValue("display") != "none")
        }
        withMountedRoot("report-style-caption-shown") { root, element ->
            root.reportTable(caption = "GuV", headers = headers)
            val caption = assertNotNull(element().querySelector("table > caption") as? HTMLElement)
            assertTrue(style(caption).getPropertyValue("position") != "absolute", "a visible caption was hidden")
        }
    }

    @Test
    fun sectionHeadingAndBalanceRowKeepTheirOwnWeightAndLine() {
        assertTrue(stylesLoaded)
        withMountedRoot("report-style-section") { root, element ->
            val report = root.reportTable(caption = "Kassenbuch", headers = headers)
            report.reportRows(balanceSheetRows(balanceSheet), headers)
            val section = cell(element(), "tbody tr.lapis-section-row > th")
            assertEquals("600", style(section).fontWeight)
            assertTrue(style(section).getPropertyValue("--bs-table-bg-type").isNotBlank())
            val balance = cell(element(), "tbody tr.lapis-balance-row > td")
            assertEquals("1px", style(balance).borderTopWidth)
            assertEquals("400", style(balance).fontWeight)
            val total = cell(element(), "tbody tr.lapis-total-row > td")
            assertNotEquals(style(balance).fontWeight, style(total).fontWeight, "balance rows are not bold, sum rows are")
        }
    }
}
