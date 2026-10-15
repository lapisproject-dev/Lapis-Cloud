package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.132 "Reisekosten-Feinschliff" -- i18n guard for the msgids that wave added (locale date fields, per-meal amounts, day notes, live
 * breakdown): present in `messages.pot` and all seven language catalogs, translated (non-blank, not the German source for the sentences),
 * with the `%N` placeholders of the msgid intact, and the date-pattern words in the three languages whose pattern letters are NOT the English
 * ones. Targeted instead of the whole screen files, so older untranslated strings of those files cannot fail it. Lives in `lapis-server`
 * because `jsTest` has no file system.
 *
 * The translations were written by the model, not by a native speaker (see CHANGELOG "known limitations").
 */
class TravelExpensePolishI18nCatalogTest :
    FunSpec({
        val languages = CATALOG_LANGUAGES

        /** The msgids of this wave (the ones that are NEW; reused ones like "Summe" or "Vorschuss" are guarded by the whole-client scan). */
        val msgids =
            listOf(
                "TT",
                "MM",
                "JJJJ",
                "Format: %1",
                "Bitte als %1 eingeben.",
                "Bitte das Jahr vierstellig eingeben (%1).",
                "Dieses Datum gibt es nicht.",
                "Bitte ein Jahr zwischen %1 und %2 eingeben.",
                "Erste Nacht",
                "Ausführungsdatum",
                "Frühstück gestellt (%1 % = −%2)",
                "Mittagessen gestellt (%1 % = −%2)",
                "Abendessen gestellt (%1 % = −%2)",
                "Kein Tagegeld an diesem Tag, daher kein Abzug.",
                "Abzug auf den Tagessatz von %1 begrenzt.",
                "Tagegeld gesamt (brutto)",
                "Abzüge für gestellte Mahlzeiten",
                "Verzicht zugunsten einer Spende",
                "Fahrtkosten: %1 km × %2",
                "Mitfahrerzuschlag: %1 km × %2 × %3",
                "Tagegeld (alte Erfassung): %1 Tage × %2",
                "Tagegeld (alte Erfassung)",
                "Frühstück gestellt: %1 % von %2",
                "Mittagessen gestellt: %1 % von %2",
                "Abendessen gestellt: %1 % von %2",
                "Tagegeld bei %1 Std. Abwesenheit",
                "Tagesbetrag",
                "So setzt sich der Betrag zusammen",
                "Für diese Abrechnung sind keine gespeicherten Sätze vorhanden; die Aufschlüsselung wird nicht berechnet.",
                "%1: netto %2",
                "%1: noch nicht vollständig",
                "Tagesrechnung %1",
                "Für diesen Tag fehlen noch Angaben.",
            )
        val placeholder = Regex("""%\d""")

        test("messages.pot carries every msgid of the wave") {
            val pot = parseCatalog(File(CATALOG_DIR, "messages.pot"))
            msgids.filterNot { pot.containsKey(it) } shouldBe emptyList()
        }

        languages.forEach { lang ->
            test("messages-$lang.po translates every msgid, keeps the placeholders and does not leave the German source") {
                val catalog = parseCatalog(File(CATALOG_DIR, "messages-$lang.po"))
                msgids.filterNot { catalog.containsKey(it) } shouldBe emptyList()
                msgids.filter { catalog.getValue(it).isBlank() } shouldBe emptyList()

                fun placeholdersOf(text: String) =
                    placeholder
                        .findAll(text)
                        .map { it.value }
                        .sorted()
                        .toList()
                msgids.filter { placeholdersOf(it) != placeholdersOf(catalog.getValue(it)) } shouldBe emptyList()
                // A sentence must differ from its German source (the pattern letters and "MM" legitimately may not).
                val sentences = msgids.filter { it.length > 4 && !it.startsWith("%1:") && it != "Format: %1" && it != "Tagesbetrag" }
                sentences.filter { catalog.getValue(it) == it } shouldBe emptyList()
            }
        }

        test("the pattern words follow the language (day, month, year) and each is a different word") {
            val expected =
                mapOf(
                    "en" to Triple("DD", "MM", "YYYY"),
                    "es" to Triple("DD", "MM", "AAAA"),
                    "fr" to Triple("JJ", "MM", "AAAA"),
                    "it" to Triple("GG", "MM", "AAAA"),
                    "nl" to Triple("DD", "MM", "JJJJ"),
                    "pl" to Triple("DD", "MM", "RRRR"),
                    "ru" to Triple("ДД", "ММ", "ГГГГ"),
                )
            expected.forEach { (lang, words) ->
                val catalog = parseCatalog(File(CATALOG_DIR, "messages-$lang.po"))
                Triple(catalog.getValue("TT"), catalog.getValue("MM"), catalog.getValue("JJJJ")) shouldBe words
            }
        }

        test("a meal label and the amount placeholder keep the minus sign, the percent sign and both placeholders in every language") {
            languages.forEach { lang ->
                val catalog = parseCatalog(File(CATALOG_DIR, "messages-$lang.po"))
                listOf("Frühstück gestellt (%1 % = −%2)", "Mittagessen gestellt (%1 % = −%2)", "Abendessen gestellt (%1 % = −%2)").forEach {
                    val text = catalog.getValue(it)
                    (text.contains("%1 % = −%2")) shouldBe true
                }
            }
        }
    })
