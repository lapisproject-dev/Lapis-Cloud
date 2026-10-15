package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Welle V1.9.138 "Rederecht im Begegnungsraum" -- i18n guard: every new msgid is in `messages.pot` and in all seven language catalogs,
 * translated (not blank), with every `%N` placeholder of the German text. Lives in `lapis-server` because `jsTest` has no file system.
 */
class EncounterFloorI18nCatalogTest :
    FunSpec({
        val newMsgids =
            listOf(
                "Wort erteilen",
                "Wort entziehen",
                "Wort zurückgeben",
                "+1 Min",
                "+2 Min",
                "Sie haben das Wort · noch %1",
                "%1 spricht · noch %2",
                "Gerade spricht jemand aus der Gemeinde · noch %1",
                "Gerade spricht jemand aus der Versammlung · noch %1",
                "Gleich zu Ende",
                "Es spricht gerade jemand.",
                "Höchstdauer 10 Min erreicht",
                "Wort erteilt",
                "Sie haben das Wort",
                "Wort beendet",
                "Das Wort konnte nicht erteilt werden. Bitte versuchen Sie es erneut.",
            )
        // reused from earlier waves; they must still be there
        val reused = listOf("Wortmeldungen", "Mikrofon einschalten", "Mikrofon an", "Mikrofon aus", "Noch 10 Sekunden")

        test("messages.pot carries every msgid of the floor") {
            val pot = parseCatalog(CATALOG_DIR.resolve("messages.pot"))
            (newMsgids + reused).forEach { (pot.containsKey(it)) shouldBe true }
        }

        CATALOG_LANGUAGES.forEach { lang ->
            test("messages-$lang.po translates every msgid of the floor and keeps its placeholders") {
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
    })
