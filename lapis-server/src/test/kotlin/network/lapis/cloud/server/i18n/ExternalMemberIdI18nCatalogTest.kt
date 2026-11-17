package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V156 -- i18n guard of the external-member-id client. Every message of the wave is used in the client source, is in the template and in all seven
 * catalogs with a non-empty translation that keeps its `%N` placeholders, and no German message uses a gender form.
 */
class ExternalMemberIdI18nCatalogTest :
    FunSpec({
        val waveFiles = listOf("MemberExternalIdCard.kt", "NoVotingRightNotice.kt", "AppState.kt", "MemberAdministrationScreen.kt")
        val expected =
            listOf(
                "Die ID darf nur Buchstaben, Ziffern und . _ : / - enthalten (höchstens 64 Zeichen).",
                "ID wirklich entfernen?",
                "ID wirklich entfernen? Das Mitglied verliert damit sein Stimmrecht.",
                "Diese ID ist bereits einem anderen Mitglied zugeordnet.",
                "Externe Mitglieds-ID entfernen",
                "Externe Mitglieds-ID",
                "Quelle: %1",
                "Externe Mitglieds-ID gespeichert.",
                "Externe Mitglieds-ID entfernt.",
                "Externe Mitglieds-ID (%1): %2",
                "Stimmrecht ruht: externe ID fehlt",
                "Ihr Stimmrecht ruht, weil keine externe Mitglieds-ID hinterlegt ist. Bitte wenden Sie sich an den Vorstand.",
                "Für diese Instanz ist keine externe Mitglieds-ID-Quelle eingerichtet.",
            )
        val used by lazy {
            val constants = clientStringConstants()
            waveFiles
                .flatMap { name ->
                    extractMessages(fileName = name, text = File(CLIENT_KOTLIN_DIR, name).readText(), constants = constants)
                }.map { it.msgid }
                .toSet()
        }
        val template by lazy { parseCatalog(File(CATALOG_DIR, "messages.pot")) }
        val catalogs by lazy { CATALOG_LANGUAGES.associateWith { parseCatalog(File(CATALOG_DIR, "messages-$it.po")) } }

        test("the client source uses every message of the wave (not vacuous)") {
            expected.filter { it !in used }.shouldBeEmpty()
        }

        test("every message is in the template and in all seven catalogs, translated") {
            val problems =
                expected.flatMap { id ->
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
                    expected
                        .filter { id ->
                            val translated = catalogs.getValue(lang)[id] ?: return@filter false
                            placeholder.findAll(id).map { it.value }.toSet() != placeholder.findAll(translated).map { it.value }.toSet()
                        }.map { "$lang: \"$it\"" }
                }
            broken.shouldBeEmpty()
        }

        test("no German message uses a gender form") {
            val forms = listOf("*innen", ":innen", "_innen", "/innen")
            expected.filter { id -> forms.any { it in id } }.size shouldBe 0
        }
    })
