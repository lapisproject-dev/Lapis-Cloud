package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.File

/**
 * Welle V1.9.131 "X als Streaming-Plattform" -- i18n-Waechter. Prueft genau die drei neuen msgids (Label, Anlege-Hinweis,
 * Bearbeiten-Hinweis) in `messages.pot` und allen sieben Sprachkatalogen: vorhanden, in den Sprachkatalogen mit nicht leerem `msgstr`,
 * und das Label `"X"` bleibt in jeder Sprache genau `X`. Gezielt statt der ganzen Screen-Datei, damit alte, nicht uebersetzte
 * Strings dieser Datei den Test nicht faellen. Liegt in `lapis-server`, weil `jsTest` kein Dateisystem hat.
 */
class ConferenceStreamPlatformXI18nCatalogTest :
    FunSpec({
        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }

        val createHintStart = "X (ehemals Twitter): Server-URL und Stream-Schlüssel finden Sie im Media Studio von X"
        val editHint = "Bei X kann sich der Schlüssel pro Übertragung ändern. Tragen Sie ihn dann hier neu ein."
        val languages = listOf("en", "es", "fr", "it", "nl", "pl", "ru")

        /** msgid -> msgstr, with the multi-line `"..."` continuation lines concatenated and the common escapes resolved. */
        fun parse(file: File): Map<String, String> {
            val result = linkedMapOf<String, String>()
            var id: StringBuilder? = null
            var str: StringBuilder? = null
            var target: StringBuilder? = null

            fun literal(line: String): String =
                line
                    .substringAfter('"')
                    .substringBeforeLast('"')
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\\\", "\\")

            fun flush() {
                if (id != null && str != null) result[id.toString()] = str.toString()
                id = null
                str = null
                target = null
            }
            file.readLines().forEach { line ->
                when {
                    line.startsWith("msgid ") -> {
                        flush()
                        id = StringBuilder(literal(line))
                        target = id
                    }
                    line.startsWith("msgstr ") -> {
                        str = StringBuilder(literal(line))
                        target = str
                    }
                    line.startsWith("\"") -> target?.append(literal(line))
                    else -> Unit
                }
            }
            flush()
            return result
        }

        fun createHintMsgid(catalog: Map<String, String>): String? = catalog.keys.firstOrNull { it.startsWith(createHintStart) }

        test("messages.pot carries the three new msgids") {
            val pot = parse(File(i18nDir, "messages.pot"))
            pot.containsKey("X") shouldBe true
            (createHintMsgid(pot) != null) shouldBe true
            pot.containsKey(editHint) shouldBe true
        }

        test("the create hint is the four-sentence copy and starts with X (ehemals Twitter)") {
            val msgid = createHintMsgid(parse(File(i18nDir, "messages.pot")))!!
            msgid shouldStartWith "X (ehemals Twitter): "
            msgid.contains("Der Schlüssel kann sich pro Übertragung ändern.") shouldBe true
            msgid.contains("Getestet wurde nur das Layout \"Einzelne Person\".") shouldBe true
            msgid.contains("Zugangsbedingungen") shouldBe true
        }

        languages.forEach { lang ->
            test("messages-$lang.po translates the three msgids (non-empty), and \"X\" stays X") {
                val catalog = parse(File(i18nDir, "messages-$lang.po"))
                catalog["X"] shouldBe "X"
                val hint = createHintMsgid(catalog)
                (hint != null) shouldBe true
                catalog.getValue(hint!!).isNotBlank() shouldBe true
                catalog.getValue(editHint).isNotBlank() shouldBe true
                // The tested layout name and the product names Media Studio / Producer are not translated away.
                catalog.getValue(hint).contains("Media Studio") shouldBe true
                catalog.getValue(hint).contains("Producer") shouldBe true
            }
        }
    })
