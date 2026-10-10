package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.96 -- the stylesheet half of the bell as a source scan (Karma cannot emulate `prefers-reduced-motion` reliably): the sign is
 * quiet -- no animation, no scaling, no accent colour, no literal colour -- fades in an opacity transition of at most 200 ms that reduced
 * motion switches off, and never takes a click. Gradle runs server tests with `lapis-server` as the working directory.
 */
private val THEME_CSS =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

private fun bellRules(css: String): List<String> =
    Regex("""(?m)^\s*([^{}@/]*\.lapis-encounter-bell[^{}]*)\{([^{}]*)}""")
        .findAll(css)
        .map { "${it.groupValues[1].trim()} { ${it.groupValues[2]} }" }
        .toList()

class ClientEncounterBellCssTripwireTest :
    FunSpec({
        val css = THEME_CSS.readText()

        test("the scan sees the bell rules (not vacuous)") {
            (bellRules(css).size >= 5) shouldBe true
        }

        test("the sign never takes a click and has no keyframes, scaling, animation, accent colour or shadow") {
            val rules = bellRules(css).joinToString("\n")
            val base = Regex("""(?m)^\.lapis-encounter-bell\s*\{([^}]*)}""").find(css)!!.groupValues[1]
            withClue(base) { base.contains("pointer-events: none") shouldBe true }
            listOf("animation", "@keyframes", "scale(", "scale:", "--lapis-accent", "box-shadow").forEach { forbidden ->
                withClue("the bell rules must not contain '$forbidden':\n$rules") { rules.contains(forbidden) shouldBe false }
            }
            css.contains("@keyframes lapis-encounter-bell") shouldBe false
        }

        test("the fade is an opacity transition of at most 200 ms and reduced motion switches it off in the one block") {
            val base = Regex("""(?m)^\.lapis-encounter-bell\s*\{([^}]*)}""").find(css)!!.groupValues[1]
            val ms =
                Regex("""transition:\s*opacity\s+(\d+)ms""")
                    .find(base)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            withClue(base) { ((ms ?: Int.MAX_VALUE) <= 200) shouldBe true }
            val reduced = css.substring(css.indexOf("@media (prefers-reduced-motion: reduce)"))
            reduced.contains(".lapis-encounter-bell,") shouldBe true
            reduced.contains("transition: none") shouldBe true
        }

        test("the surface is the theme's own (tokens only, no literal colour anywhere in the bell rules)") {
            val base = Regex("""(?m)^\.lapis-encounter-bell\s*\{([^}]*)}""").find(css)!!.groupValues[1]
            base.contains("var(--lapis-encounter-surface)") shouldBe true
            base.contains("color: var(--lapis-text)") shouldBe true
            Regex("""#[0-9A-Fa-f]{3,8}\b|rgba?\(""").containsMatchIn(bellRules(css).joinToString("\n")) shouldBe false
        }

        test("the bell never lies on top of the blessing: both sit in the common holder") {
            val signs = Regex("""(?m)^\.lapis-encounter-signs\s*\{([^}]*)}""").find(css)!!.groupValues[1]
            signs.contains("flex-wrap: wrap") shouldBe true
            val base = Regex("""(?m)^\.lapis-encounter-bell\s*\{([^}]*)}""").find(css)!!.groupValues[1]
            base.contains("position:") shouldBe false
        }
    })
