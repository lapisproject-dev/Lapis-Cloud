package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.308 -- i18n guard of the tender payout client. Every message of the wave is used in the client source, is in the template and in all seven
 * catalogs with a non-empty translation that keeps its `%N` placeholders, and no German message uses a gender form.
 */
class TenderPayoutI18nCatalogTest :
    FunSpec({
        val waveFiles = listOf("TenderDetailScreen.kt", "TendersScreen.kt", "TenderErrors.kt", "LtrLedgerScreen.kt")
        val expected =
            listOf(
                "Ausgezahlt",
                "Auszahlung gescheitert",
                "Der Gewinner ist Gast und führt sein LTR-Konto auf seinem Heimatserver.",
                "Der Gewinner kann derzeit kein LTR empfangen.",
                "Auszahlung",
                "Ausgezahlt am %1 von %2",
                "Ausgezahlt am %1",
                "Auszahlung gescheitert am %1: %2",
                "Auszahlung steht aus.",
                "%1 an %2",
                "Erneut auszahlen",
                "LTR auszahlen",
                "LTR auszahlen?",
                "%1 an %2 auszahlen?",
                "An %1 auszahlen?",
                "Die Buchung lässt sich nicht rückgängig machen.",
                "Auszahlen",
                "LTR ausgezahlt.",
                "Die Auszahlung ist gescheitert.",
                "Die Abnahme hat %1 erfasst. Auszahlen muss eine andere Person (Vier-Augen-Prinzip).",
                "Sie haben auf diese Ausschreibung geboten und dürfen nicht auszahlen.",
                "Auszahlung (Ausschreibung)",
                "Der Auszahlungsbetrag ist ungültig.",
                "Ausgezahlt werden kann nur LTR.",
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
                            if (translation == null) {
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
