package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Room polls, wave B (viewer page) -- i18n guard: every new msgid is in `messages.pot` and in all seven language catalogs, translated (not
 * blank), with every `%N` placeholder of the German text. Lives in `lapis-server` because `jsTest` has no file system.
 */
class RoomPollViewerI18nCatalogTest :
    FunSpec({
        val newMsgids =
            listOf(
                "Umfrage im Stream",
                "Gerade läuft keine Umfrage. Diese Seite aktualisiert sich selbst.",
                "Das Ergebnis erscheint hier, sobald die Umfrage beendet ist.",
                "Ihre Stimme wurde bereits gezählt.",
                "Die Umfrage wurde gerade beendet.",
                "Zu diesem Raum läuft kein Stream.",
                "Bitte melden Sie sich an, um an der Umfrage teilzunehmen.",
                "Eine Umfrage hat begonnen.",
                "Die Umfrage ist beendet. Das Ergebnis wird angezeigt.",
                "Umfrage läuft",
                "Zuschauer-Link",
                "Link kopieren",
                "QR groß anzeigen",
                "Zur Umfrage: QR-Code scannen",
                "Der Link funktioniert, sobald dieser Raum streamt.",
                "QR-Code zur Umfrage-Seite",
            )
        // reused from earlier waves or screens; they must still be there
        val reused =
            listOf(
                "Anmelden",
                "Schließen",
                "Kopiert",
                "Wird geladen …",
                "Erneut prüfen",
                "Ihre Stimme wurde gezählt.",
                "Die Antwort konnte nicht gesendet werden. Bitte versuchen Sie es erneut.",
            )

        test("messages.pot carries every msgid of the viewer page") {
            val pot = parseCatalog(CATALOG_DIR.resolve("messages.pot"))
            (newMsgids + reused).forEach { (pot.containsKey(it)) shouldBe true }
        }

        CATALOG_LANGUAGES.forEach { lang ->
            test("messages-$lang.po translates every msgid of the viewer page and keeps its placeholders") {
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
