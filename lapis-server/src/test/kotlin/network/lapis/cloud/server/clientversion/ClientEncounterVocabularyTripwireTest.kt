package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.67 "Begegnungsraum Stufe 1" -- the vocabulary of the room profiles lives in ONE file ([ONLY_FILE], `EncounterVocabulary.kt`).
 * A source scan over the `.kt` files of `client/encounter/` (the DOM tests prove the behaviour; this keeps the next change from quietly hard-coding a
 * word again):
 *
 * - **No profile word as a string literal** outside the vocabulary file: "Kanzel", "Ordner", "Gemeinde", "Amen", "Gottesdienst", "Segen" (V1.9.95). The scope is
 *   `client/encounter/` only -- elsewhere "Ordner" means "folder" (the documents screens). Comment lines are exempt.
 * - **No branching on the profile** outside the vocabulary file and the scene layout: no `if`/`when` over `EncounterProfile` or
 *   `.profile`. Everything else asks `EncounterTerms`.
 * - **CSS**: no `max-width: 720px` in any `.lapis-encounter*` rule (the stage mode of V1.9.67 removed that cap; it must not creep back).
 *
 * Every detector proves itself against a positive and a negative example. Gradle runs server tests with `lapis-server` as the working directory.
 */
private val ENCOUNTER_DIR =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/encounter")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client/encounter") }

private val THEME_CSS =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

private const val ONLY_FILE = "EncounterVocabulary.kt"

/** The words of the church profile; a string literal containing one of them (as a word start) is a hard-coded profile word. */
private val PROFILE_WORD_LITERAL = Regex(""""[^"\n]*\b(?:Kanzel|Ordner|Gemeinde|Gottesdienst|Segen|Amen\b)[^"\n]*"""")

/** `if (... profile ...)` / `when (... profile ...)` / `when (...EncounterProfile...)` on one line. */
private val PROFILE_BRANCH = Regex("""\b(?:if|when)\s*\([^)]*(?:\.profile\b|\bprofile\b|EncounterProfile)""")

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

private fun encounterFiles(): List<File> = ENCOUNTER_DIR.listFiles { f -> f.isFile && f.extension == "kt" }.orEmpty().sortedBy { it.name }

private fun codeLines(file: File): List<String> = file.readLines().filterNot { isCommentLine(it) }

/** The `.lapis-encounter*` rules of [css] that declare `max-width: 720px`. */
internal fun encounterRulesCappedAt720(css: String): List<String> =
    Regex("""([^{}]*\.lapis-encounter[^{}]*)\{([^{}]*)}""")
        .findAll(css)
        .filter { Regex("""max-width:\s*720px""").containsMatchIn(it.groupValues[2]) }
        .map { it.groupValues[1].trim() }
        .toList()

class ClientEncounterVocabularyTripwireTest :
    FunSpec({
        test("the scan sees the encounter client and the vocabulary file (not vacuous)") {
            encounterFiles().size shouldBeGreaterThanOrEqual 14
            encounterFiles().map { it.name }.contains(ONLY_FILE) shouldBe true
            // the vocabulary file really contains the words, otherwise the scan below would prove nothing
            codeLines(File(ENCOUNTER_DIR, ONLY_FILE)).any { PROFILE_WORD_LITERAL.containsMatchIn(it) } shouldBe true
        }

        test("no profile word is a string literal outside EncounterVocabulary.kt") {
            val found =
                encounterFiles()
                    .filter { it.name != ONLY_FILE }
                    .flatMap { file ->
                        codeLines(file).filter { PROFILE_WORD_LITERAL.containsMatchIn(it) }.map { "${file.name}: ${it.trim()}" }
                    }
            withClue(found.joinToString("\n")) { found.shouldBeEmpty() }
        }

        test("nothing branches on the profile outside the vocabulary and the scene files") {
            val found =
                encounterFiles()
                    .filter { it.name != ONLY_FILE }
                    .flatMap { file -> codeLines(file).filter { PROFILE_BRANCH.containsMatchIn(it) }.map { "${file.name}: ${it.trim()}" } }
            withClue(found.joinToString("\n")) { found.shouldBeEmpty() }
        }

        test("no .lapis-encounter rule is capped at max-width: 720px") {
            withClue(encounterRulesCappedAt720(THEME_CSS.readText()).joinToString("\n")) {
                encounterRulesCappedAt720(THEME_CSS.readText()).shouldBeEmpty()
            }
        }

        test("the detectors recognise what they are for, and leave the rest alone") {
            listOf(
                "val x = tr(\"Die Kanzel ist leer\")",
                "gettext(\"Ordner im Gottesdienst\")",
                "label = \"Amen\"",
                "text(\"Gemeinde\")",
                "tr(\"Der Segen wird gesprochen\")",
            ).forEach { PROFILE_WORD_LITERAL.containsMatchIn(it) shouldBe true }
            listOf(
                "EncounterReaction.AMEN -> showEvent(seat)",
                "class EncounterAmenThing",
                "val name = \"Amenhotep\"",
                "tr(\"Dokumentenordner\")",
                "gettext(\"Teilnehmende\")",
                "val x = Segensreich",
            ).forEach { PROFILE_WORD_LITERAL.containsMatchIn(it) shouldBe false }
            listOf(
                "if (space.profile == EncounterProfile.ASSEMBLY) 1 else 2",
                "when (profile) {",
                "if (church && x) a else b".replace("church", "profile"),
                "val v = when (space.profile) {",
            ).forEach { PROFILE_BRANCH.containsMatchIn(it) shouldBe true }
            listOf(
                "val terms = termsFor(space.profile)",
                "if (viewer.canModerate) add(tab)",
                "private val church: Boolean get() = profile == EncounterProfile.CHURCH_SERVICE",
            ).forEach { PROFILE_BRANCH.containsMatchIn(it) shouldBe false }
            encounterRulesCappedAt720(".lapis-encounter-pulpit { width: 100%; max-width: 720px; }") shouldBe
                listOf(".lapis-encounter-pulpit")
            encounterRulesCappedAt720(".lapis-encounter-pulpit { max-width: 1600px; }").shouldBeEmpty()
            encounterRulesCappedAt720(".other { max-width: 720px; }").shouldBeEmpty()
        }
    })
