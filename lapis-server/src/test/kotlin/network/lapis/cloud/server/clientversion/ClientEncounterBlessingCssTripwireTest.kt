package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.95 -- the stylesheet half of the blessing as a source scan (Karma cannot emulate `prefers-reduced-motion` reliably):
 * the cross is quiet -- no animation, no scaling, no accent colour -- fades in an opacity transition of at most 200 ms that reduced motion
 * switches off, never takes a click, and sits in the pulpit area. Gradle runs server tests with `lapis-server` as the working directory.
 */
private val THEME_CSS =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

private fun blessingRules(css: String): List<String> =
    Regex("""(?m)^\s*([^{}@/]*\.lapis-encounter-blessing[^{}]*)\{([^{}]*)}""")
        .findAll(css)
        .map {
            "${it.groupValues[1].trim()} { ${it.groupValues[2]} }"
        }.toList()

class ClientEncounterBlessingCssTripwireTest :
    FunSpec({
        val css = THEME_CSS.readText()

        test("the scan sees the blessing rules (not vacuous)") {
            (blessingRules(css).size >= 4) shouldBe true
        }

        test("the pulpit area is the positioning context of the display") {
            val pulpit = Regex("""(?m)^\.lapis-encounter-pulpit\s*\{([^}]*)}""").find(css)!!.groupValues[1]
            withClue(pulpit) { pulpit.contains("position: relative") shouldBe true }
        }

        test("the display never takes a click and has no keyframes, scaling, animation or accent colour") {
            val rules = blessingRules(css).joinToString("\n")
            val base = Regex("""(?m)^\.lapis-encounter-blessing\s*\{([^}]*)}""").find(css)!!.groupValues[1]
            withClue(base) { base.contains("pointer-events: none") shouldBe true }
            listOf("animation", "@keyframes", "scale(", "scale:", "--lapis-accent", "box-shadow").forEach { forbidden ->
                withClue("the blessing rules must not contain '$forbidden':\n$rules") { rules.contains(forbidden) shouldBe false }
            }
            css.contains("@keyframes lapis-encounter-blessing") shouldBe false
        }

        test("the fade is an opacity transition of at most 200 ms and reduced motion switches it off in the one block") {
            val base = Regex("""(?m)^\.lapis-encounter-blessing\s*\{([^}]*)}""").find(css)!!.groupValues[1]
            val ms =
                Regex("""transition:\s*opacity\s+(\d+)ms""")
                    .find(base)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            withClue(base) { ((ms ?: Int.MAX_VALUE) <= 200) shouldBe true }
            val reduced = css.substring(css.indexOf("@media (prefers-reduced-motion: reduce)"))
            reduced.contains(".lapis-encounter-blessing") shouldBe true
            reduced.contains("transition: none") shouldBe true
        }

        test("the panel is the theme's own surface over the picture (contrast comes from tokens, no literal colour)") {
            val base = Regex("""(?m)^\.lapis-encounter-blessing\s*\{([^}]*)}""").find(css)!!.groupValues[1]
            base.contains("var(--lapis-encounter-surface)") shouldBe true
            base.contains("color: var(--lapis-text)") shouldBe true
            Regex("""#[0-9A-Fa-f]{3,8}\b""").containsMatchIn(base) shouldBe false
        }

        test("V1.9.96: the display sits in the common holder of the signs, which is positioned in the pulpit area and takes no click") {
            val holder = Regex("""(?m)^\.lapis-encounter-signs\s*\{([^}]*)}""").find(css)!!.groupValues[1]
            withClue(holder) {
                holder.contains("position: absolute") shouldBe true
                holder.contains("pointer-events: none") shouldBe true
            }
            // the display itself no longer positions itself
            val base = Regex("""(?m)^\.lapis-encounter-blessing\s*\{([^}]*)}""").find(css)!!.groupValues[1]
            base.contains("position:") shouldBe false
            base.contains("transform:") shouldBe false
        }
    })
