package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.4.25 "UI foundation" -- i18n guard for the new UI building blocks, same extraction/parsing
 * logic as [BankAccountI18nCatalogTest] (see its KDoc for the full reasoning; `jsTest` has no file system,
 * hence this lives in `lapis-server`).
 *
 * Scans the three new helper files completely. `MemberAdministrationScreen.kt` is deliberately NOT scanned
 * as a whole -- pre-existing gaps in that large file would break this test for the wrong reason -- so its
 * two new roster sentences are checked through an explicit list instead.
 */
class UiFoundationI18nCatalogTest :
    FunSpec({
        val clientSrc: File =
            File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }

        val waveSourceFiles = listOf("DataTableState.kt", "DataTable.kt", "DataSection.kt")

        /** New sentences that live in a screen file that is not scanned as a whole. */
        val explicitMessages =
            listOf(
                "Noch keine Mitglieder vorhanden.",
                "Kein Mitglied passt zu \"%1\".",
                "Alle (%1)",
            )

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
        val uniqueMsgids: List<String> by lazy { (waveMessages.map { it.msgid } + explicitMessages).distinct() }
        val catalogs: Map<String, Map<String, String>> by lazy {
            languages.associateWith { lang -> parsePoEntries(File(i18nDir, "messages-$lang.po")) }
        }
        val template: Map<String, String> by lazy { parsePoEntries(File(i18nDir, "messages.pot")) }

        test("no extracted string of the new helpers is blank") {
            waveMessages.filter { it.msgid.isBlank() }.map { "${it.file}:${it.line}" }.shouldBeEmpty()
        }

        test("every string of the new helpers is in the template and all seven catalogs") {
            val missing =
                languages.flatMap { lang ->
                    uniqueMsgids.filterNot { catalogs.getValue(lang).containsKey(it) }.map { "$lang: \"$it\"" }
                } + uniqueMsgids.filterNot { template.containsKey(it) }.map { "pot: \"$it\"" }
            missing.shouldBeEmpty()
        }

        test("every string of the new helpers is translated (non-blank) in all seven catalogs") {
            val untranslated =
                languages.flatMap { lang ->
                    uniqueMsgids.filter { catalogs.getValue(lang)[it]?.isBlank() != false }.map { "$lang: \"$it\"" }
                }
            untranslated.shouldBeEmpty()
        }

        test("every translation keeps the %N placeholders of its msgid") {
            val placeholder = Regex("""%\d""")
            val broken =
                languages.flatMap { lang ->
                    uniqueMsgids
                        .filter { id ->
                            placeholder.findAll(id).map { it.value }.toSet() !=
                                placeholder.findAll(catalogs.getValue(lang).getValue(id)).map { it.value }.toSet()
                        }.map { "$lang: \"$it\"" }
                }
            broken.shouldBeEmpty()
        }

        test("audit V1.4.25 translation fixes stay fixed (pl meaning of 'noch keine', fr no doubled 'par', nl infinitive)") {
            catalogs.getValue("pl").getValue("Noch keine Mitglieder vorhanden.") shouldBe "Nie ma jeszcze członków."
            catalogs.getValue("fr").getValue("Nach %1 aufsteigend sortieren") shouldBe "Trier par %1 (croissant)"
            catalogs.getValue("fr").getValue("Nach %1 absteigend sortieren") shouldBe "Trier par %1 (décroissant)"
            catalogs.getValue("fr").getValue("Aufsteigend sortiert nach %1") shouldBe "Trié par %1 (croissant)"
            catalogs.getValue("fr").getValue("Absteigend sortiert nach %1") shouldBe "Trié par %1 (décroissant)"
            catalogs.getValue("nl").getValue("Nach %1 aufsteigend sortieren") shouldBe "Sorteren op %1 oplopend"
            catalogs.getValue("nl").getValue("Nach %1 absteigend sortieren") shouldBe "Sorteren op %1 aflopend"
        }

        test("the explicit roster sentences really occur in MemberAdministrationScreen.kt") {
            val screen = File(clientSrc, "MemberAdministrationScreen.kt").readText()
            explicitMessages.forEach { message ->
                (screen.contains(message.replace("\"", "\\\""))) shouldBe true
            }
        }

        test("sanity: the helpers yielded strings at all (extraction is not broken)") {
            (waveMessages.size >= 8) shouldBe true
        }
    })
