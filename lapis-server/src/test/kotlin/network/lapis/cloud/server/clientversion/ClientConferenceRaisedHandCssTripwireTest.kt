package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * V1.9.253 (raised hand made conspicuous): the style rules that cannot be checked with `prefers-reduced-motion` in Karma are guarded as text.
 * The bobbing exists ONLY inside `prefers-reduced-motion: no-preference`; the reduction block at the end of the file says nothing about the
 * hand (R54); the rules use tokens only (no hex, no rgb).
 */
private val THEME_CSS: File =
    File("../lapis-client/src/jsMain/resources/theme.css").let {
        if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css")
    }

private fun cssWithoutComments(): String = THEME_CSS.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

/** The body (without the outer braces) of the first block that starts with [header], by brace matching. */
private fun blockBody(
    css: String,
    header: String,
): String {
    val start = css.indexOf(header)
    check(start >= 0) { "block not found: $header" }
    val open = css.indexOf('{', start)
    var depth = 0
    for (i in open until css.length) {
        when (css[i]) {
            '{' -> depth++
            '}' -> if (--depth == 0) return css.substring(open + 1, i)
        }
    }
    error("unbalanced block: $header")
}

private fun isHandSelector(selector: String): Boolean =
    selector.contains("lapis-conference-tile--hand") ||
        selector.contains("lapis-conference-hand-badge.is-raised") ||
        selector.contains("lapis-conference-hand-bob")

private val BANNED =
    listOf(
        "lapis-conference-grid",
        "lapis-conference-tiles",
        "lapis-conference-tile--small",
        "lapis-conference-root",
        "lapis-conference-dock-host",
    )

class ClientConferenceRaisedHandCssTripwireTest :
    FunSpec({
        test("the bobbing animation is used only inside the no-preference block, outside there is just the keyframes definition") {
            val css = cssWithoutComments()
            val noPreference = blockBody(css = css, header = "@media (prefers-reduced-motion: no-preference)")
            noPreference shouldContain "animation: lapis-conference-hand-bob"
            val outside = css.replace(noPreference, "")
            withClue("outside the no-preference block") {
                Regex("animation[^;]*lapis-conference-hand-bob").containsMatchIn(outside) shouldBe false
            }
            outside shouldContain "@keyframes lapis-conference-hand-bob"
        }

        test("no hand rule outside the no-preference block mentions animation or transition") {
            val css = cssWithoutComments()
            val outside = css.replace(blockBody(css = css, header = "@media (prefers-reduced-motion: no-preference)"), "")
            val rules = Regex("([^{}]+)\\{([^{}]*)}").findAll(outside).filter { isHandSelector(it.groupValues[1]) }.toList()
            (rules.size >= 4) shouldBe true
            for (rule in rules) {
                withClue(rule.groupValues[1].trim()) {
                    rule.groupValues[2] shouldNotContain "animation"
                    rule.groupValues[2] shouldNotContain "transition"
                }
            }
        }

        test("the hand rules use tokens only: gold and media background, no hex literal, no rgb") {
            val css = cssWithoutComments()
            val rules = Regex("([^{}]+)\\{([^{}]*)}").findAll(css).filter { isHandSelector(it.groupValues[1]) }.toList()
            val body = rules.joinToString("\n") { it.groupValues[2] }
            Regex("#[0-9a-fA-F]{3,8}\\b").containsMatchIn(body) shouldBe false
            body shouldNotContain "rgb("
            body shouldContain "var(--lapis-fleck)"
            body shouldContain "var(--lapis-media-bg)"
        }

        test("the reduction block at the end of the file says nothing about the hand (R54)") {
            val css = cssWithoutComments()
            val reduce = blockBody(css = css, header = "@media (prefers-reduced-motion: reduce)")
            reduce shouldNotContain "lapis-conference-hand"
            reduce shouldNotContain "tile--hand"
        }

        test("forced colors: the ring turns into Highlight and the raised badge keeps the system colours") {
            val css = cssWithoutComments()
            val joined =
                Regex("@media \\(forced-colors: active\\)\\s*\\{")
                    .findAll(css)
                    .joinToString("\n") { blockBody(css = css.substring(it.range.first), header = "@media") }
            joined shouldContain ".lapis-conference-tile--hand::before"
            joined shouldContain "Highlight"
            joined shouldContain ".lapis-conference-hand-badge.is-raised"
        }

        test("the hand selectors avoid the substrings of the grid-animation ban of the UI guideline tripwire") {
            val css = cssWithoutComments()
            val selectors =
                Regex("([^{}]+)\\{")
                    .findAll(css)
                    .map { it.groupValues[1] }
                    .filter { isHandSelector(it) }
                    .toList()
            (selectors.isNotEmpty()) shouldBe true
            for (selector in selectors) {
                for (banned in BANNED) {
                    withClue(selector.trim()) { selector shouldNotContain banned }
                }
            }
        }
    })
