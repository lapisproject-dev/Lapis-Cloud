package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.4.27 "UI/UX guideline wave W3 -- pseudo tables" -- i18n guard for the sentences this wave added, same
 * extraction/parsing logic as [HotTableI18nCatalogTest] (see there and [BankAccountI18nCatalogTest]'s KDoc for the
 * reasoning; `jsTest` has no file system, hence this lives in `lapis-server`).
 *
 * ## Why an explicit list and not "scan the migrated screens"
 *
 * The screens this wave touched carry hundreds of pre-existing strings with known catalog gaps (the guideline's
 * R51 only pins the EQUALITY of the msgid sets across the eight catalogs, not the coverage of the source).
 * Scanning a whole screen would fail for somebody else's debt and say nothing about this wave. `ReportRows.kt` is
 * scanned completely: it is the wave's own row-model file and every string in it is a cell of a report.
 */
class PseudoTableI18nCatalogTest :
    FunSpec({
        val clientSrc: File =
            File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }

        /** Scanned completely: the row model of the report grammar. */
        val waveSourceFiles = listOf("ReportRows.kt")

        /** The sentences W3 added outside `ReportRows.kt`, with the file each must occur in. */
        val screenMessages: List<Pair<String, String>> =
            listOf(
                "Details %1 ein-/ausblenden" to "DataScreenLayout.kt",
                "Berichtsart" to "FinancialReportsScreen.kt",
                "Kennzahl" to "FinancialReportsScreen.kt",
                "Aktion" to "AuditLogScreen.kt",
                "Seq." to "AuditLogScreen.kt",
                "Akteur" to "AuditLogScreen.kt",
                "Entität" to "AuditLogScreen.kt",
                "Version" to "DocumentsScreen.kt",
                "v%1" to "DocumentsScreen.kt",
                "Datei" to "DocumentsScreen.kt",
                "Größe" to "DocumentsScreen.kt",
                "Hochgeladen von" to "DocumentsScreen.kt",
                "Hochgeladen am" to "DocumentsScreen.kt",
                "Änderungshinweis" to "DocumentsScreen.kt",
                "Downloads" to "DocumentsScreen.kt",
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
        val uniqueMsgids: List<String> by lazy { (waveMessages.map { it.msgid } + screenMessages.map { it.first }).distinct() }
        val catalogs: Map<String, Map<String, String>> by lazy {
            languages.associateWith { lang -> parsePoEntries(File(i18nDir, "messages-$lang.po")) }
        }
        val template: Map<String, String> by lazy { parsePoEntries(File(i18nDir, "messages.pot")) }

        test("no extracted string of the wave is blank") {
            waveMessages.filter { it.msgid.isBlank() }.map { "${it.file}:${it.line}" }.shouldBeEmpty()
        }

        test("every string of this wave is in the template and all seven catalogs") {
            val missing =
                languages.flatMap { lang ->
                    uniqueMsgids.filterNot { catalogs.getValue(lang).containsKey(it) }.map { "$lang: \"$it\"" }
                } + uniqueMsgids.filterNot { template.containsKey(it) }.map { "pot: \"$it\"" }
            missing.shouldBeEmpty()
        }

        test("every string of this wave is translated (non-blank) in all seven catalogs") {
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

        test("every listed screen sentence really occurs in the screen it is attributed to") {
            screenMessages.forEach { (message, fileName) ->
                val source = File(clientSrc, fileName).readText()
                // The source carries the Kotlin-escaped form of the sentence.
                source.contains(message.replace("\"", "\\\"")) shouldBe true
            }
        }

        test("the total label and the report cells really live in ReportRows.kt") {
            val ids = extractWaveMessages(File(clientSrc, "ReportRows.kt")).map { it.msgid }
            listOf("Summe %1", "Gesamt", "Ergebnis", "Eröffnungssaldo", "Schlusssaldo", "Summe Passiva + Eigenkapital").forEach {
                (it in ids) shouldBe true
            }
        }

        // Audit V1.4.27 (A): four technical errors of the wave's first translation -- Italian "Soll" was "Avere" (= Haben, the
        // general ledger showed "Avere | Avere"), Italian "Bilanz" collided with "Jahresabschluss", English "Passiva" equalled
        // "Verbindlichkeiten" (both stand directly under each other in the balance sheet) and Spanish "Verbindlichkeiten" was
        // "Pasivos" next to "Pasivo". Two accounting core terms that are DIFFERENT concepts must never share a msgstr.
        val distinctCoreTerms =
            listOf(
                "Soll" to "Haben",
                "Aktiva" to "Passiva",
                "Passiva" to "Verbindlichkeiten",
                "Bilanz" to "Jahresabschluss",
            )

        test("accounting core terms that are different concepts never share a translation in any catalog") {
            val collisions =
                languages.flatMap { lang ->
                    distinctCoreTerms
                        .filter { (a, b) -> catalogs.getValue(lang).getValue(a) == catalogs.getValue(lang).getValue(b) }
                        .map { (a, b) -> "$lang: \"$a\" and \"$b\" are both \"${catalogs.getValue(lang).getValue(a)}\"" }
                }
            collisions.shouldBeEmpty()
        }

        test("the four corrected accounting terms keep their audited translation") {
            catalogs.getValue("it").getValue("Soll") shouldBe "Dare"
            catalogs.getValue("it").getValue("Bilanz") shouldBe "Stato patrimoniale"
            catalogs.getValue("en").getValue("Passiva") shouldBe "Liabilities & equity"
            catalogs.getValue("es").getValue("Verbindlichkeiten") shouldBe "Deudas"
        }

        test("sanity: the wave yielded strings at all (extraction is not broken)") {
            (uniqueMsgids.size >= 30) shouldBe true
        }
    })
