package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Welle V1.9.295 "Laufende Veranstaltungskonferenzen in der Übersicht" -- i18n guard: every new msgid is in `messages.pot` and in all seven
 * language catalogs, translated (not blank), with every `%N` placeholder of the German text. Lives in `lapis-server` because `jsTest` has no
 * file system.
 */
class EventConferenceLobbyI18nCatalogTest :
    FunSpec({
        val newMsgids =
            listOf(
                "Zur Veranstaltung",
                "Zur Veranstaltung „%1“",
                "Die Videokonferenz läuft. Sie nehmen über die Veranstaltungsseite teil.",
                "Die Videokonferenz läuft. Teilnehmen kann nur, wer zur Veranstaltung angemeldet und bestätigt ist.",
                "Die Videokonferenz wird vorbereitet. Die Teilnahme öffnet auf der Veranstaltungsseite.",
            )
        // reused from earlier waves; it must still be there
        val reused = listOf("Veranstaltung", "Derzeit keine aktive Besprechung.")

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
