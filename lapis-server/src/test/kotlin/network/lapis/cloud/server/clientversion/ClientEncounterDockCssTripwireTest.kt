package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.165 -- the stylesheet half of the sticky dock of the encounter room as a source scan (the DOM test measures the real boxes):
 * the dock is `position: sticky; bottom: 0` on layer 6, carries no `backdrop-filter`, `filter` or `transform` (a containing block for
 * `fixed` descendants and a stacking trap), and the two sheets hang from it with `bottom: calc(100% + 4px)`.
 */
private val THEME_CSS =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

private fun rule(
    css: String,
    selector: String,
): String = Regex("""(?m)^${Regex.escape(selector)}\s*\{([^}]*)}""").find(css)?.groupValues?.get(1) ?: ""

class ClientEncounterDockCssTripwireTest :
    FunSpec({
        val css = THEME_CSS.readText()

        test("the dock is sticky at the bottom on layer 6") {
            val dock = rule(css = css, selector = ".lapis-encounter-dock")
            withClue(dock) {
                dock.contains("position: sticky") shouldBe true
                dock.contains("bottom: 0;") shouldBe true
                dock.contains("z-index: 6;") shouldBe true
            }
        }

        test("the dock has no backdrop-filter, filter or transform") {
            val dock = rule(css = css, selector = ".lapis-encounter-dock")
            withClue(dock) {
                listOf("backdrop-filter", "filter:", "transform").forEach { dock.contains(it) shouldBe false }
            }
        }

        test("the sheets hang from the dock") {
            listOf(".lapis-encounter-more-sheet", ".lapis-encounter-device-panel").forEach { selector ->
                withClue(selector) { rule(css = css, selector = selector).contains("bottom: calc(100% + 4px)") shouldBe true }
            }
        }

        // ── V1.9.167: the map layer ends above the dock and never becomes a containing block ──

        test("the map layer is fixed on layer 5, ends above the dock (bar height token) and has no transform, filter or backdrop-filter") {
            val overlay = rule(css = css, selector = ".lapis-encounter-map-overlay")
            withClue(overlay) {
                overlay.contains("position: fixed") shouldBe true
                overlay.contains("z-index: 5;") shouldBe true
                overlay.contains("bottom: calc(var(--lapis-encounter-bar-h)") shouldBe true
                overlay.contains("margin: 0 !important") shouldBe true
            }
            listOf(
                ".lapis-encounter-map-overlay",
                ".lapis-encounter-map-overlay__panel",
                ".lapis-encounter-map-overlay__scrim",
            ).forEach { selector ->
                val body = rule(css = css, selector = selector)
                withClue("$selector: $body") {
                    listOf("backdrop-filter", "filter:", "transform", "container-type").forEach { body.contains(it) shouldBe false }
                }
            }
            css.contains(".lapis-encounter-map-overlay[hidden]") shouldBe true
        }

        test("the theatre hall, the parterre and the loges carry no transform, filter or container-type") {
            listOf(".lapis-encounter-parterre", ".lapis-encounter-loge").forEach { selector ->
                val body = rule(css = css, selector = selector)
                withClue("$selector: $body") {
                    listOf("transform", "filter:", "container-type").forEach { body.contains(it) shouldBe false }
                }
            }
            val hall = rule(css = css, selector = ".lapis-encounter--theatre .lapis-encounter-hall")
            withClue(hall) {
                hall.contains("display: grid") shouldBe true
                listOf("transform", "filter:", "container-type").forEach { hall.contains(it) shouldBe false }
            }
        }

        test("the theatre stacks the loges below the parterre under 768 px and one per row under 420 px") {
            css.contains("@media (max-width: 767.98px) {\n  .lapis-encounter--theatre .lapis-encounter-hall {") shouldBe true
            css.contains("@media (max-width: 419.98px) {\n  .lapis-encounter--theatre .lapis-encounter-hall {") shouldBe true
        }

        test("the table cards stand in a centred flex row") {
            val grid = rule(css = css, selector = ".lapis-encounter-tables-grid")
            withClue(grid) {
                grid.contains("display: flex") shouldBe true
                grid.contains("flex-wrap: wrap") shouldBe true
                grid.contains("justify-content: center") shouldBe true
            }
        }
    })
