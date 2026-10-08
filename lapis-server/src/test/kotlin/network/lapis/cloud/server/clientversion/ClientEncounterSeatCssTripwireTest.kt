package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.79 -- the stylesheet half of the seat plan as a source scan (the DOM tests measure the real boxes; this keeps the next
 * change from quietly undoing the numbers they cannot see, because the Karma window is wider than a phone):
 *
 * - a seat is at least 44 x 44 px, also on the phone (the old phone value was 36 px);
 * - the phone values leave 6 seats + the aisle inside 360 px: 6 x 44 + 4 x 4 + 16 = 296 px;
 * - every transition of a seat is in the one `prefers-reduced-motion` block (R54), none is longer than 200 ms;
 * - forced colours keep a visible outline and the word "Sie".
 */
private val THEME_CSS =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

private fun rule(
    css: String,
    selector: String,
): String = Regex("""(?m)^${Regex.escape(selector)}\s*\{([^}]*)}""").find(css)?.groupValues?.get(1) ?: ""

class ClientEncounterSeatCssTripwireTest :
    FunSpec({
        val css = THEME_CSS.readText()

        test("a seat is a 44 pixel target: min-width and min-height 44px, a real button reset") {
            val seat = rule(css = css, selector = ".lapis-encounter-seat")
            withClue(seat) {
                seat.contains("min-width: 44px") shouldBe true
                seat.contains("min-height: 44px") shouldBe true
                seat.contains("cursor: pointer") shouldBe true
            }
        }

        test("the phone values: seat 44px, gap 4px, aisle 16px -- six seats and the aisle fit into 360 px") {
            // the phone rule is the `.lapis-encounter { ... }` block inside the max-width media query that also holds `.lapis-encounter-main`
            val phone = css.substring(css.indexOf("  .lapis-encounter-main {\n    flex-direction: column;"))
            val block = Regex("""\.lapis-encounter \{([^}]*)}""").find(phone)!!.groupValues[1]
            withClue(block) {
                block.contains("--lapis-enc-seat: 44px") shouldBe true
                block.contains("--lapis-enc-seat-gap: 4px") shouldBe true
                block.contains("--lapis-enc-aisle: 16px") shouldBe true
            }
            (6 * 44 + 4 * 4 + 16 <= 360) shouldBe true
        }

        test("the seat transition is in the reduced-motion block and not longer than 200 ms") {
            val seat = rule(css = css, selector = ".lapis-encounter-seat")
            Regex("""transition:[^;]*?(\d+)ms""").findAll(seat).forEach { (it.groupValues[1].toInt() <= 200) shouldBe true }
            val reduced = css.substring(css.indexOf("@media (prefers-reduced-motion: reduce)"))
            reduced.contains(".lapis-encounter-seat,") shouldBe true
        }

        test("forced colours: the seat keeps a Highlight outline, and the own-seat word and the glyphs stay") {
            val forced = css.substring(css.indexOf("@media (forced-colors: active) {"))
            forced.contains("outline: 3px solid Highlight") shouldBe true
            forced.contains(".lapis-encounter-seat-self") shouldBe true
            forced.contains(".lapis-encounter-seat-hand") shouldBe true
            // no rule hides the glyphs or the word in forced colours
            Regex("""\.lapis-encounter-seat-(self|hand|event)[^{]*\{[^}]*display:\s*none""").containsMatchIn(forced) shouldBe false
        }

        test("a free seat has a dashed outline in the text colour (a boundary against the surface of at least 3:1)") {
            val free = rule(css = css, selector = ".lapis-encounter-seat--free")
            withClue(free) {
                free.contains("dashed var(--lapis-encounter-seat-text)") shouldBe true
            }
        }
    })
