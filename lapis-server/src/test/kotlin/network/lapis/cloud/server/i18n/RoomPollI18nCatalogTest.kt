package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Welle V1.9.304 "Raum-Umfragen" -- i18n guard: every new msgid is in `messages.pot` and in all seven language catalogs, translated (not blank),
 * with every `%N` placeholder of the German text. Lives in `lapis-server` because `jsTest` has no file system.
 */
class RoomPollI18nCatalogTest :
    FunSpec({
        val newMsgids =
            listOf(
                "Umfrage im Videoraum",
                "Neue Umfrage: %1",
                "Umfrage beendet. Das Ergebnis ist da.",
                "Bitte geben Sie eine Frage mit %1 bis %2 Zeichen ein.",
                "Bitte geben Sie %1 bis %2 verschiedene Antworten ein (je höchstens %3 Zeichen).",
                "Frage und Antworten dürfen kein < oder > und keine Steuerzeichen enthalten.",
                "Antwort %1",
                "Antwort entfernen",
                "Ja / Nein / Enthaltung",
                "Ja / Nein",
                "Eigene Antworten",
                "Antwort hinzufügen",
                "Zählweise",
                "Köpfe (demokratisch)",
                "LTR-gewichtet (meritokratisch)",
                "Beides nebeneinander",
                "Es läuft bereits eine Umfrage.",
                "Die Umfrage konnte nicht gestartet werden. Bitte versuchen Sie es erneut.",
                "Unverbindlich",
                "Die Antwort konnte nicht gesendet werden. Bitte versuchen Sie es erneut.",
                "Eine Antwort, nicht änderbar.",
                "Ihre Stimme wurde gezählt.",
                "Umfrage beenden",
                "Umfrage beenden?",
                "Beenden",
                "Die Umfrage konnte nicht beendet werden. Bitte versuchen Sie es erneut.",
                "Ergebnis der Umfrage",
                "Die Umfrage endete mit dem Raum.",
                "Die Umfrage endete nach %1 Minuten.",
                "Die Umfrage wurde beendet.",
                "Umfrage – offen, noch nicht beantwortet",
                "Ergebnis erst ab %1 Antworten (bisher %2)",
                "Gäste und Freunde",
                "Alle zusammen",
                "Aufteilung erst ab %1 Antworten je Gruppe",
                "LTR-Gewichtung zählt nur Mitglieder",
                "n = %1",
                "Köpfe",
                "Gäste haben kein LTR – nur Kopfzahl",
            )
        // reused from earlier waves; they must still be there
        val reused =
            listOf(
                "Umfrage",
                "Umfrage schließen",
                "Neue Umfrage",
                "Starten",
                "Ja",
                "Nein",
                "Enthaltung",
                "Mitglieder",
                "Abbrechen",
                "Frage",
                "Antworten",
                "Status wird aktualisiert …",
                "Ausblenden",
                "1 Antwort",
                "%1 Antworten",
            )

        test("messages.pot carries every msgid of the room polls") {
            val pot = parseCatalog(CATALOG_DIR.resolve("messages.pot"))
            (newMsgids + reused).forEach { (pot.containsKey(it)) shouldBe true }
        }

        CATALOG_LANGUAGES.forEach { lang ->
            test("messages-$lang.po translates every msgid of the room polls and keeps its placeholders") {
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
