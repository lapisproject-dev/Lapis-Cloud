package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.275 -- i18n guard of the stream reconnect: every text of `StreamTargetsPanel.kt` (and the four refusal texts of `AppState.kt`) is present
 * and translated in all seven catalogs and the template, every placeholder of the German text is kept, no text gendered.
 */
class StreamTargetReconnectI18nCatalogTest :
    FunSpec({
        val clientSrc: File =
            File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }
        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }
        val languages = listOf("en", "es", "fr", "it", "nl", "pl", "ru")

        fun parseLiteral(
            text: String,
            start: Int,
        ): Pair<String, Int> {
            var i = start + 1
            val raw = StringBuilder()
            while (i < text.length && text[i] != '"') {
                if (text[i] == '\\' && i + 1 < text.length) {
                    raw.append(text[i]).append(text[i + 1])
                    i += 2
                } else {
                    raw.append(text[i])
                    i++
                }
            }
            return raw.toString() to (i + 1)
        }

        fun extractMsgids(file: File): List<String> {
            val text = file.readText()
            val result = mutableListOf<String>()
            for (match in Regex("""\b(?:tr|gettext)\(""").findAll(text)) {
                var pos = match.range.last + 1
                while (pos < text.length && text[pos].isWhitespace()) pos++
                if (pos >= text.length || text[pos] != '"') continue
                val (raw, _) = parseLiteral(text, pos)
                result += raw.replace("\\\"", "\"").replace("\\\\", "\\")
            }
            return result
        }

        fun parsePo(file: File): Map<String, String> {
            val entries = mutableMapOf<String, String>()
            var id: StringBuilder? = null
            var str: StringBuilder? = null
            var mode = 0

            fun unescape(s: String) = s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")

            fun flush() {
                val i = id
                val s = str
                if (i != null && s != null) entries[unescape(i.toString())] = unescape(s.toString())
            }
            file.forEachLine { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("msgid \"") -> {
                        flush()
                        id = StringBuilder(line.removePrefix("msgid \"").removeSuffix("\""))
                        str = null
                        mode = 1
                    }
                    line.startsWith("msgstr \"") -> {
                        str = StringBuilder(line.removePrefix("msgstr \"").removeSuffix("\""))
                        mode = 2
                    }
                    line.startsWith("\"") && line.endsWith("\"") -> {
                        val part = line.removePrefix("\"").removeSuffix("\"")
                        if (mode == 1) {
                            id?.append(part)
                        } else if (mode == 2) {
                            str?.append(part)
                        }
                    }
                    else -> mode = 0
                }
            }
            flush()
            return entries
        }

        val explicit =
            listOf(
                "Ein Einmal-Schlüssel gilt bei Facebook nur für eine Verbindung. " +
                    "Geben Sie einen neuen Schlüssel ein und verbinden Sie neu. " +
                    "Besser: in Facebook einen dauerhaften Schlüssel einrichten.",
                "Ein Einmal-Schlüssel gilt bei X nur für eine Verbindung. Geben Sie einen neuen Schlüssel ein und verbinden Sie neu. " +
                    "Besser: in X Media Studio einen dauerhaften Schlüssel einrichten.",
                "Neu verbinden geht nur während einer laufenden Übertragung.",
                "Dieses Ziel ist nicht fehlgeschlagen.",
                "Für dieses Ziel gilt ein dauerhafter Schlüssel; er wird unter Streaming-Ziele geändert.",
                "Das Ziel konnte nicht neu verbunden werden. Bitte versuchen Sie es in einer Minute erneut.",
            )
        val msgids: List<String> by lazy { (extractMsgids(File(clientSrc, "StreamTargetsPanel.kt")) + explicit).distinct() }
        val catalogs: Map<String, Map<String, String>> by lazy { languages.associateWith { parsePo(File(i18nDir, "messages-$it.po")) } }
        val template: Map<String, String> by lazy { parsePo(File(i18nDir, "messages.pot")) }

        test("sanity: the panel file yields its texts") {
            (msgids.size >= 20) shouldBe true
        }

        test("every text is in the template and in all seven catalogs, translated") {
            msgids.filterNot { template.containsKey(it) }.shouldBeEmpty()
            languages
                .flatMap { lang ->
                    msgids.filter { catalogs.getValue(lang)[it].isNullOrBlank() }.map { "$lang: \"$it\"" }
                }.shouldBeEmpty()
        }

        test("every placeholder %1/%2 of a German text is kept in every translation") {
            val placeholder = Regex("""%\d""")
            languages
                .flatMap { lang ->
                    msgids
                        .filter { id ->
                            val expected = placeholder.findAll(id).map { it.value }.toSet()
                            val actual = placeholder.findAll(catalogs.getValue(lang)[id].orEmpty()).map { it.value }.toSet()
                            !actual.containsAll(expected)
                        }.map { "$lang: \"$it\"" }
                }.shouldBeEmpty()
        }

        test("no German text is gendered") {
            msgids.filter { Regex("""[*:_/]innen|\*in\b""").containsMatchIn(it) }.shouldBeEmpty()
        }
    })
