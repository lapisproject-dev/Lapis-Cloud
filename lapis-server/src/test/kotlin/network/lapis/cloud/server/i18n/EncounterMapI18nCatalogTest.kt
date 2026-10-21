package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Welle V1.9.166 "Begegnungsraum Stufe 3, Rasterkarte" -- i18n guard: every new msgid is in `messages.pot` and in all seven language catalogs,
 * translated (not blank), with every `%N` placeholder of the German text, and none of the German texts is gendered. Lives in `lapis-server` because
 * `jsTest` has no file system.
 */
class EncounterMapI18nCatalogTest :
    FunSpec({
        val newMsgids =
            listOf(
                "Karte",
                "Auf die Karte",
                "Karte verlassen",
                "Zum Eingang",
                "Zur Mitte",
                "Zum Podium",
                "Zur Kanzel",
                "Reihe %1, Spalte %2",
                "Eine Person in Ihrer Nähe",
                "%1 Personen in Ihrer Nähe",
                "In Ihrer Nähe",
                "und %1 weitere",
                "Weg blockiert",
                "Die Karte ist voll oder Sie waren zu schnell.",
                "Nur bei geschlossenem Raum einschaltbar",
                "Karte (Positionen der Mitglieder)",
                "Sie stehen nicht auf der Karte.",
                "Richtungstasten",
                "Nach links",
                "Nach rechts",
                "Nur in der Raumart Versammlung. Mitglieder bewegen sich auf einer Karte; Gäste erscheinen nicht darauf.",
            )
        // reused from earlier waves (the direction pad); they must still be there
        val reused = listOf("Nach oben", "Nach unten")

        test("messages.pot carries every msgid of the map") {
            val pot = parseCatalog(CATALOG_DIR.resolve("messages.pot"))
            (newMsgids + reused).forEach { (pot.containsKey(it)) shouldBe true }
        }

        CATALOG_LANGUAGES.forEach { lang ->
            test("messages-$lang.po translates every msgid of the map and keeps its placeholders") {
                val catalog = parseCatalog(CATALOG_DIR.resolve("messages-$lang.po"))
                (newMsgids + reused).forEach { msgid ->
                    val translated = catalog.getValue(msgid)
                    translated.isNotBlank() shouldBe true
                    Regex("""%\d""").findAll(msgid).map { it.value }.toSet().forEach { placeholder ->
                        (translated.contains(placeholder)) shouldBe true
                    }
                }
            }
        }

        test("none of the German texts of the map is gendered") {
            val gendered = Regex("""\p{L}[*:_/]innen|\p{L}[*:_/]in\b|\*innen|:innen|_innen|/innen""")
            newMsgids.filter { gendered.containsMatchIn(it) } shouldBe emptyList()
        }
    })
