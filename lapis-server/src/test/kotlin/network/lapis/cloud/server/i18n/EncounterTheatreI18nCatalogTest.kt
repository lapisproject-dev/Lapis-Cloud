package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Welle V1.9.167 "Theatersaal und Kartenebene" -- i18n guard: every new msgid is in `messages.pot` and in all seven language catalogs,
 * translated (not blank), with every `%N` placeholder of the German text, and none of the German texts is gendered. Lives in `lapis-server` because
 * `jsTest` has no file system.
 */
class EncounterTheatreI18nCatalogTest :
    FunSpec({
        val newMsgids =
            listOf(
                "Karte öffnen",
                "Karte öffnen, Sie sind auf der Karte",
                "Karte schließen",
                "Ihre Position sehen nur anwesende Mitglieder. Nichts wird gespeichert.",
                "Linke Loge",
                "Rechte Loge",
            )
        // reused: the summary of the privacy disclosure and the heading of the layer
        val reused = listOf("Mehr", "Karte")

        test("messages.pot carries every msgid of the theatre hall and the map layer") {
            val pot = parseCatalog(CATALOG_DIR.resolve("messages.pot"))
            (newMsgids + reused).forEach { (pot.containsKey(it)) shouldBe true }
        }

        CATALOG_LANGUAGES.forEach { lang ->
            test("messages-$lang.po translates every msgid of the theatre hall and the map layer and keeps its placeholders") {
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

        test("none of the German texts of the theatre hall and the map layer is gendered") {
            val gendered = Regex("""\p{L}[*:_/]innen|\p{L}[*:_/]in\b|\*innen|:innen|_innen|/innen""")
            newMsgids.filter { gendered.containsMatchIn(it) } shouldBe emptyList()
        }
    })
