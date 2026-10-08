package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.80 -- the stylesheet half of the tables as a source scan (the DOM tests measure the real boxes; this keeps the next change from
 * quietly undoing the numbers they cannot see, because the Karma window is wider than a phone):
 *
 * - a table seat is a real 44 x 44 px target with a pointer cursor, three per row;
 * - on the phone the tables break out of the room's padding by 12 px on each side, so two cards (2 x 154 + 8 px) fit into 320 px;
 * - no table rule animates (no `transition`, `animation`), so none belongs into the single reduced-motion block (R54);
 * - forced colours keep a visible outline for the own and the speaking seat, and the words "Sie" / "spricht" stay (no `display: none`).
 */
private val THEME_CSS =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

private fun rule(
    css: String,
    selector: String,
): String = Regex("""(?m)^${Regex.escape(selector)}\s*\{([^}]*)}""").find(css)?.groupValues?.get(1) ?: ""

class ClientEncounterTableCssTripwireTest :
    FunSpec({
        val css = THEME_CSS.readText()

        test("a table seat is a 44 pixel target and a card holds three of them per row") {
            val seat = rule(css = css, selector = ".lapis-encounter-table-seat")
            withClue(seat) {
                seat.contains("min-width: 44px") shouldBe true
                seat.contains("min-height: 44px") shouldBe true
                seat.contains("cursor: pointer") shouldBe true
            }
            rule(css = css, selector = ".lapis-encounter-table-seats").contains("grid-template-columns: repeat(3, 44px)") shouldBe true
        }

        test("the table list buttons of the people tab are 44 pixel targets") {
            rule(css = css, selector = ".lapis-encounter-table-list-button").contains("min-height: 44px") shouldBe true
            rule(css = css, selector = ".lapis-encounter-table-list summary").contains("min-height: 44px") shouldBe true
        }

        test("on the phone the tables reach 320 px: the host breaks out of the room's padding by 0.75rem on each side") {
            val phone =
                Regex("""@media \(max-width: 767\.98px\) \{\s*\.lapis-encounter-tables-host \{([^}]*)}""").find(css)?.groupValues?.get(1)
            withClue(phone) {
                (phone != null) shouldBe true
                phone!!.contains("width: calc(100% + 1.5rem)") shouldBe true
                phone.contains("margin-inline: -0.75rem") shouldBe true
            }
            // 3 x 44 + 2 x 2 = 136 px of seats + 2 x 0.4rem padding + 2 x 2px border fits into a card of 154 px; 2 cards + 8 px gap = 316 <= 320
            (3 * 44 + 2 * 2 + 2 * 7 + 2 * 2 <= 154) shouldBe true
            (2 * 154 + 8 <= 320) shouldBe true
            rule(css = css, selector = ".lapis-encounter-tables-grid").contains("minmax(154px, 1fr)") shouldBe true
        }

        test("no table rule animates: no transition, no animation") {
            val rules = Regex("""(?m)^(\.lapis-encounter-table[\w-]*[^{]*)\{([^}]*)}""").findAll(css).toList()
            (rules.size >= 15) shouldBe true // the scan is not vacuous
            rules.forEach { r ->
                withClue(r.groupValues[1]) {
                    Regex("""\b(transition|animation)\b""").containsMatchIn(r.groupValues[2]) shouldBe false
                }
            }
        }

        test("forced colours: the own and the speaking seat keep a Highlight outline, the words stay") {
            val forced = css.substring(css.indexOf("@media (forced-colors: active) {"))
            forced.contains(".lapis-encounter-table-seat--own") shouldBe true
            forced.contains(".lapis-encounter-table-seat.is-speaking") shouldBe true
            forced.contains(".lapis-encounter-table-seat-self") shouldBe true
            forced.contains(".lapis-encounter-table-seat-speaking") shouldBe true
            Regex("""\.lapis-encounter-table-seat-(self|speaking)[^{]*\{[^}]*display:\s*none""").containsMatchIn(forced) shouldBe false
        }

        test("a free table seat has a dashed outline in the text colour (a boundary against the surface of at least 3:1)") {
            rule(css = css, selector = ".lapis-encounter-table-seat--free").contains("dashed var(--lapis-encounter-seat-text)") shouldBe
                true
        }
    })
