package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Tripwires of V1.9.211 (UI/UX guideline R62, responsive tables), scanned as text like the neighbouring client tripwires.
 *
 * - Every `masterTable(` call keeps the default (cards) or names `TableMobileLayout.SCROLL` explicitly; no fixed call count is asserted.
 * - The R62 CSS breakpoint is `CARD_LIST_BREAKPOINT_PX - 0.02`.
 * - The R62 block stands above the last `prefers-reduced-motion` block (R54).
 * - `tableActionGroup` carries `lapis-table-actions` (the hook finds the actions cell by it) and the hook file exists.
 */
private val RESPONSIVE_CLIENT_DIR: File =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val RESPONSIVE_THEME_CSS: File =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

private fun responsiveCode(source: String): String =
    source.split('\n').joinToString("\n") { line ->
        line.trimStart().let { if (it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")) "" else line }
    }

/** The layout argument of each `masterTable(` call in [code] (empty string when the default is used). */
private fun masterTableLayouts(code: String): List<String> {
    val result = mutableListOf<String>()
    var from = code.indexOf("masterTable(")
    while (from >= 0) {
        val lineStart = code.lastIndexOf('\n', from) + 1
        if (code.substring(lineStart, from).contains("fun ")) {
            from = code.indexOf("masterTable(", from + 1)
            continue
        }
        run {
            var depth = 0
            var i = code.indexOf('(', from)
            val start = i
            while (i < code.length) {
                if (code[i] == '(') depth++
                if (code[i] == ')') {
                    depth--
                    if (depth == 0) break
                }
                i++
            }
            val call = code.substring(start, i + 1)
            result += if (call.contains("mobileLayout")) call.substringAfter("mobileLayout") else ""
        }
        from = code.indexOf("masterTable(", from + 1)
    }
    return result
}

class ClientResponsiveTableTripwireTest :
    FunSpec({
        test("every masterTable call uses the default cards layout or names SCROLL explicitly") {
            val layouts =
                RESPONSIVE_CLIENT_DIR
                    .walkTopDown()
                    .filter { it.extension == "kt" }
                    .flatMap { masterTableLayouts(responsiveCode(it.readText())) }
                    .toList()
            withClue("master tables found") { layouts.isNotEmpty() shouldBe true }
            layouts.forEach { layout ->
                withClue("layout argument '$layout'") {
                    (layout.isEmpty() || layout.contains("TableMobileLayout.SCROLL") || layout.contains("TableMobileLayout.CARDS")) shouldBe
                        true
                }
            }
        }

        test("detector proves itself") {
            masterTableLayouts("x.masterTable(a, b)") shouldBe listOf("")
            masterTableLayouts("x.masterTable(a, b, mobileLayout = TableMobileLayout.SCROLL)").single().contains("SCROLL") shouldBe true
            masterTableLayouts("internal fun Container.masterTable(a)") shouldBe emptyList()
        }

        test("the R62 breakpoint is the card-list breakpoint minus 0.02") {
            val layout = File(RESPONSIVE_CLIENT_DIR, "network/lapis/cloud/client/DataScreenLayout.kt").readText()
            val px = Regex("""CARD_LIST_BREAKPOINT_PX\s*=\s*(\d+)""").find(layout)!!.groupValues[1].toInt()
            val css = RESPONSIVE_THEME_CSS.readText()
            val block = css.substring(css.indexOf("R62 / V1.9.211"))
            val query = Regex("""@media \(max-width: ([\d.]+)px\)""").find(block)!!.groupValues[1]
            withClue("R62 media query") { query shouldBe "${px - 1}.98" }
        }

        test("the R62 block stands above the last reduced-motion block") {
            val css = RESPONSIVE_THEME_CSS.readText()
            val reduced = css.lastIndexOf("@media (prefers-reduced-motion: reduce)")
            withClue("R54 ordering") { (css.indexOf("R62 / V1.9.211") in 0 until reduced) shouldBe true }
            withClue("opaque sticky cell") { css.contains(".lapis-table-sticky-first tr > :first-child") shouldBe true }
        }

        test("the action group carries the actions class and the label hook exists") {
            val layout = File(RESPONSIVE_CLIENT_DIR, "network/lapis/cloud/client/DataScreenLayout.kt").readText()
            val group = layout.substringAfter("fun Container.tableActionGroup()").substringBefore("/** CSS classes")
            withClue("tableActionGroup") { group.contains("\$TABLE_ACTIONS_CLASS") shouldBe true }
            withClue("hook file") { File(RESPONSIVE_CLIENT_DIR, "network/lapis/cloud/client/TableCardLabels.kt").exists() shouldBe true }
            val labels = File(RESPONSIVE_CLIENT_DIR, "network/lapis/cloud/client/TableCardLabels.kt").readText()
            withClue("no HTML sink") { (labels.contains("innerHTML") || labels.contains("insertAdjacentHTML")) shouldBe false }
        }
        test("V1.9.212: every row-action cell carries the actions-cell class and the CSS keeps it on one line") {
            val table = File(RESPONSIVE_CLIENT_DIR, "network/lapis/cloud/client/DataTable.kt").readText()
            val calls = Regex("""actions\(this,""").findAll(table).count()
            val classed = Regex("""addCssClass\(TABLE_ACTIONS_CELL_CLASS\)\s*\n\s*actions\(this,""").findAll(table).count()
            withClue("action call sites") { (calls > 0 && classed == calls) shouldBe true }
            val css = RESPONSIVE_THEME_CSS.readText()
            val rule = css.substringAfter(".lapis-table-actions-cell {").substringBefore("}")
            withClue("nowrap rule") { rule.contains("white-space: nowrap") shouldBe true }
            val ruleAt = css.indexOf(".lapis-table-actions-cell {")
            val mediaAt = css.indexOf("@media (max-width: 767.98px)", css.indexOf("R62 / V1.9.211"))
            withClue("before the card media query") { (ruleAt < mediaAt) shouldBe true }
            val cardCss = css.substring(mediaAt)
            val cardCell = cardCss.substringAfter("td[data-cell=\"actions\"] {\n    width: auto;").substringBefore("}")
            withClue(
                "mobile override of the cell width",
            ) { cardCss.contains("td[data-cell=\"actions\"] {\n    width: auto;") shouldBe true }
            withClue("mobile override of nowrap") { cardCell.contains("white-space: normal") shouldBe true }
            withClue("mobile override of the group nowrap") {
                cardCss
                    .substringAfter(
                        "td[data-cell=\"actions\"] > .lapis-table-actions {",
                    ).substringBefore("}")
                    .contains("flex-wrap: wrap") shouldBe
                    true
            }
            val card = css.substringAfter(".lapis-data-card-actions {").substringBefore("}")
            withClue("card actions keep wrapping") { card.contains("flex-wrap: wrap") shouldBe true }
        }
    })
