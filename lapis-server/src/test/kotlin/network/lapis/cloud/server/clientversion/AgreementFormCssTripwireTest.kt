package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.134 -- the CSS text of the registration layout, pinned (Karma cannot switch a media query, so the two-column assertions of
 * `RegistrationLayoutDomTest` only run at the width of the Karma window): the grid is ONE column by default and two from 768 px; a "full" cell
 * spans both columns only inside that media query; the reading frame has no height or overflow outside it; and every colour is a theme token (so the
 * dark theme follows without a second rule set).
 */
private val THEME_CSS_FILE: File =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

private val BREAKPOINT = "@media (min-width: 768px)"

class AgreementFormCssTripwireTest :
    FunSpec({
        val rules = scrollCssRules(THEME_CSS_FILE.readText())

        fun rulesOf(selector: String) = rules.filter { it.selector == selector }

        test("the grid is one column by default and two columns from 768 px") {
            val base = rulesOf(".lapis-form-grid").single { it.atRules.isEmpty() }
            Regex("""grid-template-columns\s*:\s*minmax\(0,\s*1fr\)\s*;?""").containsMatchIn(base.body) shouldBe true
            Regex("""repeat\(2""").containsMatchIn(base.body) shouldBe false
            val wide = rulesOf(".lapis-form-grid").single { it.atRules == listOf(BREAKPOINT) }
            Regex("""grid-template-columns\s*:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)""").containsMatchIn(wide.body) shouldBe true
            Regex("""align-items\s*:\s*start""").containsMatchIn(wide.body) shouldBe true
            Regex("""column-gap""").containsMatchIn(wide.body) shouldBe true
        }

        test("a full-width cell spans both columns only inside the 768 px media query") {
            val full = rulesOf(".lapis-form-cell--full")
            full.size shouldBe 1
            full.single().atRules shouldBe listOf(BREAKPOINT)
            Regex("""grid-column\s*:\s*1\s*/\s*-1""").containsMatchIn(full.single().body) shouldBe true
        }

        test("the reading frame has no height and no overflow outside the 768 px media query") {
            val frame = rulesOf(".lapis-agreement-frame")
            val base = frame.filter { it.atRules.isEmpty() }
            base.isNotEmpty() shouldBe true
            base.forEach { rule ->
                withClue("base rule must not cap or scroll: ${rule.body}") {
                    Regex("""(?:^|[;\s])(?:max-)?height\s*:""").containsMatchIn(rule.body) shouldBe false
                    Regex("""overflow""").containsMatchIn(rule.body) shouldBe false
                }
            }
            val wide = frame.single { it.atRules == listOf(BREAKPOINT) }
            Regex("""(?:^|[;\s])height\s*:\s*20rem""").containsMatchIn(wide.body) shouldBe true
            Regex("""overflow-y\s*:\s*auto""").containsMatchIn(wide.body) shouldBe true
            Regex("""overscroll-behavior\s*:\s*contain""").containsMatchIn(wide.body) shouldBe true
        }

        test("the frame's line length is 72ch and the frame is built from theme tokens only (no literal colour)") {
            Regex("""max-width\s*:\s*72ch""").containsMatchIn(rulesOf(".lapis-agreement-frame > *").single().body) shouldBe true
            val colourLiteral = Regex("""#[0-9a-fA-F]{3,8}\b|\brgba?\(|\bhsla?\(""")
            (rulesOf(".lapis-agreement-frame") + rulesOf(".lapis-form-grid") + rulesOf(".lapis-form-cell")).forEach { rule ->
                withClue("colour literal in ${rule.selector}: ${rule.body}") { colourLiteral.containsMatchIn(rule.body) shouldBe false }
            }
            Regex("""var\(--lapis-surface\)""").containsMatchIn(rulesOf(".lapis-agreement-frame").first().body) shouldBe true
        }

        test("the scroll shadow needs no script: the frame rules use only CSS (no Kotlin insert hook knows the class)") {
            val clientDir =
                File("../lapis-client/src/jsMain/kotlin").let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }
            val users =
                clientDir
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { "AGREEMENT_FRAME_CLASS" in it.readText() || "lapis-agreement-frame" in it.readText() }
                    .map { it.name }
                    .toSet()
            users shouldBe setOf("RegistrationFormLayout.kt")
        }
    })
