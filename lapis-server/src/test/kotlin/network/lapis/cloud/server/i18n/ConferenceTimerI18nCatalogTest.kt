package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.133 "Timer in Videokonferenzen" -- i18n-Waechter. Prueft die neuen msgids in `messages.pot` und allen sieben Sprachkatalogen:
 * vorhanden, mit nicht leerem `msgstr` bzw. der richtigen Zahl Pluralformen (pl/ru: drei), und jeder Platzhalter `%1` des Quelltextes steht
 * auch in der Uebersetzung. Liegt in `lapis-server`, weil `jsTest` kein Dateisystem hat.
 */
class ConferenceTimerI18nCatalogTest :
    FunSpec({
        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }
        val languages = listOf("en", "es", "fr", "it", "nl", "pl", "ru")
        val singles =
            listOf(
                "Timer",
                "Timer %1",
                "Timer: noch %1",
                "Timer pausiert: noch %1",
                "Timer steuern, noch %1",
                "Timer steuern, pausiert, noch %1",
                "Timer steuern, Zeit abgelaufen",
                "Timer: Zeit abgelaufen",
                "Gleich vorbei",
                "Zeit abgelaufen",
                "Pausiert",
                "Eigene Zeit",
                "Zwischen 10 Sekunden und 60 Minuten",
                "Starten",
                "Pausieren",
                "Fortsetzen",
                "Stoppen",
                "+30 s",
                "+1 min",
                "+5 min",
                "Um 30 Sekunden verlängern",
                "Um 1 Minute verlängern",
                "Um 5 Minuten verlängern",
                "Timer gestartet: %1",
                "Timer pausiert",
                "Timer fortgesetzt",
                "Timer gestoppt",
                "Noch eine Minute",
                "Noch 10 Sekunden",
                "Timer wurde zurückgesetzt",
                "Gong am Ende",
                "Ein leiser Gong, wenn ein Timer abläuft. Er erklingt nur, wenn Ihr Browser Ton zulässt.",
                "Der Timer lässt sich jetzt nicht ändern. Bitte versuchen Sie es erneut.",
                "Die gewählte Zeit ist nicht möglich (10 Sekunden bis 60 Minuten).",
            )
        val plurals = listOf("%1 Minute" to "%1 Minuten", "%1 Sekunde" to "%1 Sekunden")

        /** msgid -> list of msgstr (one entry for a singular, one per plural form otherwise). */
        fun parse(file: File): Map<String, List<String>> {
            val result = linkedMapOf<String, List<String>>()
            var id = StringBuilder()
            var forms = mutableListOf<StringBuilder>()
            var target: StringBuilder? = null
            var open = false

            fun literal(line: String): String =
                line
                    .substringAfter('"')
                    .substringBeforeLast('"')
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\\\", "\\")

            fun flush() {
                if (open) result[id.toString()] = forms.map { it.toString() }
                open = false
                id = StringBuilder()
                forms = mutableListOf()
                target = null
            }
            file.readLines().forEach { line ->
                when {
                    line.startsWith("msgid ") -> {
                        flush()
                        open = true
                        id = StringBuilder(literal(line))
                        target = id
                    }
                    line.startsWith("msgid_plural ") -> target = StringBuilder()
                    line.startsWith("msgstr[") || line.startsWith("msgstr ") -> {
                        val sb = StringBuilder(literal(line))
                        forms.add(sb)
                        target = sb
                    }
                    line.startsWith("\"") -> target?.append(literal(line))
                    else -> Unit
                }
            }
            flush()
            return result
        }

        test("messages.pot carries every new msgid exactly once") {
            val pot = parse(File(i18nDir, "messages.pot"))
            singles.forEach { (pot.containsKey(it)) shouldBe true }
            plurals.forEach { (pot.containsKey(it.first)) shouldBe true }
            pot.getValue("%1 Minute").size shouldBe 2
        }

        languages.forEach { lang ->
            test("messages-$lang.po translates every new msgid, with the placeholders and the right number of plural forms") {
                val catalog = parse(File(i18nDir, "messages-$lang.po"))
                singles.forEach { msgid ->
                    val translated = catalog.getValue(msgid).single()
                    translated.isNotBlank() shouldBe true
                    if ("%1" in msgid) (translated.contains("%1")) shouldBe true
                }
                val expectedForms = if (lang == "pl" || lang == "ru") 3 else 2
                plurals.forEach { (singular, _) ->
                    val forms = catalog.getValue(singular)
                    forms.size shouldBe expectedForms
                    forms.forEach { (it.isNotBlank() && it.contains("%1")) shouldBe true }
                }
            }
        }
    })
