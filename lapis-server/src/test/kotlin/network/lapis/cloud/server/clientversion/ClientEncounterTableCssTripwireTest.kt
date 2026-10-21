package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.80 -- the stylesheet half of the tables as a source scan (the DOM tests measure the real boxes; this keeps the next change from
 * quietly undoing the numbers they cannot see, because the Karma window is wider than a phone):
 *
 * - a table seat is a real 44 x 44 px target with a pointer cursor, placed on a ring around the table top (V1.9.159: absolute, square ring box);
 * - a card is at least 172 px wide, so that eight seats do not touch on the ring (V1.9.159); a 320 px phone shows one table per row;
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

        test("a table seat is a 44 pixel target and stands on a ring in a square box") {
            val seat = rule(css = css, selector = ".lapis-encounter-table-seat")
            withClue(seat) {
                seat.contains("min-width: 44px") shouldBe true
                seat.contains("min-height: 44px") shouldBe true
                seat.contains("cursor: pointer") shouldBe true
            }
            seat.contains("position: absolute") shouldBe true
            val ring = rule(css = css, selector = ".lapis-encounter-table-seats")
            ring.contains("aspect-ratio: 1") shouldBe true
            ring.contains("position: relative") shouldBe true
            ring.contains("isolation: isolate") shouldBe true // the table top (z-index -1) lies above the card background, not behind it
        }

        test("the table list buttons of the people tab are 44 pixel targets") {
            rule(css = css, selector = ".lapis-encounter-table-list-button").contains("min-height: 44px") shouldBe true
            rule(css = css, selector = ".lapis-encounter-table-list summary").contains("min-height: 44px") shouldBe true
        }

        test("a card is wide enough for the ring of eight seats, and the old phone breakout is gone") {
            // Ring box = card 172 px - 2 x 2 px border = 168 px (the padding is given back by a negative margin); radius to the seat centres
            // = (168 - 44) / 2 = 62 px; the chord between two neighbours of eight seats is 2 x 62 x sin(22.5 degrees) = 47.4 px >= 44 px.
            val radius = (172 - 2 * 2 - 44) / 2.0
            (2 * radius * kotlin.math.sin(Math.PI / 8) >= 44.0) shouldBe true
            // V1.9.167: the cards stand in a centred flex row of 172 px cards (before: a grid of equal columns, which left a short last row at the left edge)
            val grid = rule(css = css, selector = ".lapis-encounter-tables-grid")
            grid.contains("minmax(172px, 1fr)") shouldBe false
            grid.contains("display: flex") shouldBe true
            grid.contains("justify-content: center") shouldBe true
            val card = rule(css = css, selector = ".lapis-encounter-tables-grid > .lapis-encounter-table")
            card.contains("flex: 0 1 172px") shouldBe true
            card.contains("min-width: 172px") shouldBe true
            rule(css = css, selector = ".lapis-encounter-table-seats").contains("margin: 0.5rem -0.4rem") shouldBe true
            Regex("""@media \(max-width: 767\.98px\) \{\s*\.lapis-encounter-tables-host \{""").containsMatchIn(css) shouldBe false
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
