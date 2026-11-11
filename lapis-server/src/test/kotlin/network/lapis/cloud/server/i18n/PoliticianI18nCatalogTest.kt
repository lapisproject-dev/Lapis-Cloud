package network.lapis.cloud.server.i18n

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.301 "Politiker-Ansicht umgestalten" -- i18n guard for `PoliticianScreen.kt`: every message is in the template and in all
 * seven catalogs with a non-empty translation that keeps the `%N` placeholders, no gendering, and the screen shows no role abbreviation
 * or Like/Dislike wording to the user (UI strings only; KDoc may name the roles).
 */
class PoliticianI18nCatalogTest :
    FunSpec({
        val screen = File(CLIENT_KOTLIN_DIR, "PoliticianScreen.kt")
        val messages by lazy { extractMessages(fileName = screen.name, text = screen.readText(), constants = clientStringConstants()) }
        val msgids by lazy { messages.map { it.msgid }.distinct() }
        val template by lazy { parseCatalog(File(CATALOG_DIR, "messages.pot")) }
        val catalogs by lazy { CATALOG_LANGUAGES.associateWith { parseCatalog(File(CATALOG_DIR, "messages-$it.po")) } }
        val placeholder = Regex("""%\d""")

        test("the scan sees the screen (not vacuous)") {
            msgids.size shouldBeGreaterThan 40
        }

        test("every message is in the template and in all seven catalogs") {
            withClue("template") { msgids.filterNot { template.containsKey(it) }.shouldBeEmpty() }
            CATALOG_LANGUAGES.forEach { lang ->
                withClue(lang) { msgids.filterNot { catalogs.getValue(lang).containsKey(it) }.shouldBeEmpty() }
            }
        }

        test("every message is translated and keeps its placeholders in all seven catalogs") {
            CATALOG_LANGUAGES.forEach { lang ->
                val catalog = catalogs.getValue(lang)
                withClue("$lang untranslated") { msgids.filter { catalog[it].isNullOrBlank() }.shouldBeEmpty() }
                withClue("$lang placeholders") {
                    msgids
                        .filter { id ->
                            placeholder.findAll(id).map { it.value }.toSortedSet() !=
                                placeholder.findAll(catalog[id].orEmpty()).map { it.value }.toSortedSet()
                        }.shouldBeEmpty()
                }
            }
        }

        test("no gender markers in any message of the screen or its translations of the new strings") {
            val marker = Regex("""(?i)(\*|:|_|/)innen\b|\w\*in\b|\w:in\b""")
            withClue("source") { msgids.filter { marker.containsMatchIn(it) }.shouldBeEmpty() }
        }

        test("the screen shows no Like/Dislike wording and no role abbreviation (BOARD/ADMIN/TREASURER) as UI text") {
            withClue("Like/Dislike msgids") { msgids.filter { it == "Like" || it == "Dislike" || it.contains("Like ") }.shouldBeEmpty() }
            withClue("role abbreviations") {
                msgids.filter { Regex("""\b(BOARD|ADMIN|TREASURER)\b""").containsMatchIn(it) }.shouldBeEmpty()
            }
            (msgids.contains("Positiv") && msgids.contains("Negativ")) shouldBe true
        }
    })
