package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V154 -- i18n guard of the founding-member client. Every message of the two files of this wave is in the template and in all seven catalogs with a
 * non-empty translation that keeps its `%N` placeholders, and no German message uses a gender form. (The consent wording itself is shown verbatim
 * from the shared rules and is deliberately not a catalog message.)
 */
class FoundingMemberI18nCatalogTest :
    FunSpec({
        val waveFiles = listOf("MemberFoundingMemberCard.kt", "MemberAdministrationScreen.kt")
        val messages by lazy {
            val constants = clientStringConstants()
            waveFiles.flatMap { name ->
                extractMessages(fileName = name, text = File(CLIENT_KOTLIN_DIR, name).readText(), constants = constants)
            }
        }
        val msgids by lazy { messages.map { it.msgid }.filter { "ründungsmitglied" in it || "Einwilligung" in it }.distinct() }
        val template by lazy { parseCatalog(File(CATALOG_DIR, "messages.pot")) }
        val catalogs by lazy { CATALOG_LANGUAGES.associateWith { parseCatalog(File(CATALOG_DIR, "messages-$it.po")) } }

        test("the scan sees the founding-member messages (not vacuous)") {
            msgids.size shouldBeGreaterThan 8
            ("Gründungsmitglied" in msgids) shouldBe true
        }

        test("every founding-member message is in the template and in all seven catalogs, translated") {
            val problems =
                msgids.flatMap { id ->
                    buildList {
                        if (!template.containsKey(id)) add("pot: \"$id\"")
                        CATALOG_LANGUAGES.forEach { lang ->
                            val translation = catalogs.getValue(lang)[id]
                            if (translation ==
                                null
                            ) {
                                add("$lang: missing \"$id\"")
                            } else if (translation.isBlank()) {
                                add("$lang: blank \"$id\"")
                            }
                        }
                    }
                }
            problems.shouldBeEmpty()
        }

        test("every translation keeps the %N placeholders of its message") {
            val placeholder = Regex("""%\d""")
            val broken =
                CATALOG_LANGUAGES.flatMap { lang ->
                    msgids
                        .filter { id ->
                            val translated = catalogs.getValue(lang)[id] ?: return@filter false
                            placeholder.findAll(id).map { it.value }.toSet() != placeholder.findAll(translated).map { it.value }.toSet()
                        }.map { "$lang: \"$it\"" }
                }
            broken.shouldBeEmpty()
        }

        test("no German message uses a gender form") {
            val forms = listOf("*innen", ":innen", "_innen", "/innen")
            msgids.filter { id -> forms.any { it in id } }.shouldBeEmpty()
        }
    })
