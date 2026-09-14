package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- i18n-Waechter fuer [BankAccountsScreen],
 * analog [BankStatementI18nCatalogTest] (siehe dessen KDoc fuer die vollstaendige Begruendung
 * der Extraktions-/Parsing-Logik, hier unveraendert uebernommen). Review fix (MEDIUM): dieser
 * Screen hatte in Wave 2 33 der 45 `tr(...)`/`gettext(...)`-Strings in KEINEM der sieben
 * Sprachkataloge -- u.a. die komplette FinTS-Rechtshinweis-Bedienung (siehe
 * `BankAccountsScreen.kt`s eigenes "Ich habe den Hinweistext vollstaendig gelesen ..." und den
 * neuen Vorab-Warnhinweis zur Unloeschbarkeit). Dieser Test macht die Luecke strukturell
 * unmoeglich statt disziplinabhaengig -- exakt das Muster, das [BankStatementI18nCatalogTest]
 * schon fuer die Kontoauszugs-Screens etabliert.
 *
 * Liegt in `lapis-server` aus demselben Grund wie [BankStatementI18nCatalogTest]: `jsTest` unter
 * Karma/ChromeHeadless hat kein Dateisystem.
 */
class BankAccountI18nCatalogTest :
    FunSpec({
        val clientSrc: File =
            File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }

        val waveSourceFiles = listOf("BankAccountsScreen.kt")

        val languages = listOf("en", "es", "fr", "it", "nl", "pl", "ru")

        data class WaveMessage(
            val file: String,
            val line: Int,
            val msgid: String,
        )

        fun unescapeKotlinString(raw: String): String =
            raw
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\$", "$")
                .replace("\\\\", "\\")

        fun parseStringLiteral(
            text: String,
            pos: Int,
        ): Pair<String, Int> {
            var i = pos + 1
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

        fun extractWaveMessages(file: File): List<WaveMessage> {
            val text = file.readText()
            val messages = mutableListOf<WaveMessage>()
            val callStart = Regex("""\b(?:tr|gettext)\(""")
            for (match in callStart.findAll(text)) {
                var pos = match.range.last + 1
                while (pos < text.length && text[pos].isWhitespace()) pos++
                if (pos >= text.length || text[pos] != '"') continue
                val lineNumber = text.substring(0, pos).count { it == '\n' } + 1

                val combined = StringBuilder()
                while (true) {
                    val (segment, nextPos) = parseStringLiteral(text, pos)
                    combined.append(segment)
                    pos = nextPos
                    var lookahead = pos
                    while (lookahead < text.length && text[lookahead].isWhitespace()) lookahead++
                    if (lookahead < text.length && text[lookahead] == '+') {
                        var afterPlus = lookahead + 1
                        while (afterPlus < text.length && text[afterPlus].isWhitespace()) afterPlus++
                        if (afterPlus < text.length && text[afterPlus] == '"') {
                            pos = afterPlus
                            continue
                        }
                    }
                    break
                }
                messages += WaveMessage(file.name, lineNumber, unescapeKotlinString(combined.toString()))
            }
            return messages
        }

        fun parsePoEntries(file: File): Map<String, String> {
            val entries = mutableMapOf<String, String>()
            var currentId: StringBuilder? = null
            var currentStr: StringBuilder? = null
            var mode = 0

            fun unescape(s: String) = s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")

            fun flush() {
                val id = currentId
                val str = currentStr
                if (id != null && str != null) {
                    entries[unescape(id.toString())] = unescape(str.toString())
                }
            }
            file.forEachLine { rawLine ->
                val line = rawLine.trim()
                when {
                    line.startsWith("msgid \"") -> {
                        flush()
                        currentId = StringBuilder(line.removePrefix("msgid \"").removeSuffix("\""))
                        currentStr = null
                        mode = 1
                    }
                    line.startsWith("msgstr \"") -> {
                        currentStr = StringBuilder(line.removePrefix("msgstr \"").removeSuffix("\""))
                        mode = 2
                    }
                    line.startsWith("\"") && line.endsWith("\"") -> {
                        when (mode) {
                            1 -> currentId?.append(line.removePrefix("\"").removeSuffix("\""))
                            2 -> currentStr?.append(line.removePrefix("\"").removeSuffix("\""))
                        }
                    }
                    else -> mode = 0
                }
            }
            flush()
            return entries
        }

        val waveMessages: List<WaveMessage> by lazy { waveSourceFiles.flatMap { extractWaveMessages(File(clientSrc, it)) } }
        val uniqueMsgids: List<String> by lazy { waveMessages.map { it.msgid }.distinct() }
        val catalogs: Map<String, Map<String, String>> by lazy {
            languages.associateWith { lang -> parsePoEntries(File(i18nDir, "messages-$lang.po")) }
        }

        test("kein extrahierter Textstring dieses Screens ist leer (Konkatenations-Zusammenfuehrung funktioniert)") {
            val empty = waveMessages.filter { it.msgid.isBlank() }.map { "${it.file}:${it.line}" }
            empty.shouldBeEmpty()
        }

        test("jeder Textstring dieses Screens hat in allen sieben Katalogen einen Eintrag") {
            val missing =
                languages.flatMap { lang ->
                    val catalog = catalogs.getValue(lang)
                    uniqueMsgids.filterNot { catalog.containsKey(it) }.map { "$lang: \"$it\"" }
                }
            missing.shouldBeEmpty()
        }

        test("jeder Textstring dieses Screens ist in allen sieben Katalogen nicht-leer uebersetzt") {
            val untranslated =
                languages.flatMap { lang ->
                    val catalog = catalogs.getValue(lang)
                    uniqueMsgids.filter { catalog[it]?.isBlank() != false }.map { "$lang: \"$it\"" }
                }
            untranslated.shouldBeEmpty()
        }

        test("sanity: der Screen hat ueberhaupt Textstrings gefunden (Extraktion ist nicht kaputt)") {
            (uniqueMsgids.size > 30) shouldBe true
        }
    })
