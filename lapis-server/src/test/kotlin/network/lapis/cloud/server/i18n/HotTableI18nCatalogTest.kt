package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * Welle V1.4.26 "UI/UX guideline wave W2 -- hot tables" -- i18n guard for the sentences this wave added,
 * same extraction/parsing logic as [UiFoundationI18nCatalogTest] (see [BankAccountI18nCatalogTest]'s KDoc
 * for the full reasoning; `jsTest` has no file system, hence this lives in `lapis-server`).
 *
 * ## Why an explicit list and not "scan the migrated screens"
 *
 * The eleven screens this wave touched carry hundreds of pre-existing strings, and the catalogs have known
 * gaps in them (the guideline's R51 only pins the EQUALITY of the msgid sets across the eight catalogs, not
 * the coverage of the source). Scanning a whole screen would therefore fail for somebody else's debt and
 * say nothing about this wave. `DataTableState.kt` is scanned completely -- it is a small, wave-owned helper
 * file whose every string is new or from W1.
 */
class HotTableI18nCatalogTest :
    FunSpec({
        val clientSrc: File =
            File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }

        /** Scanned completely: the shared pure helper file of W1/W2. */
        val waveSourceFiles = listOf("DataTableState.kt")

        /** The sentences W2 added inside the eleven migrated screens, with the file each must occur in. */
        val screenMessages: List<Pair<String, String>> =
            listOf(
                "In diesem Zeitraum stehen keine Geburtstage oder Jubiläen an." to "MemberAnniversariesScreen.kt",
                "In diesem Zeitraum steht kein Geburtstag an." to "MemberAnniversariesScreen.kt",
                "In diesem Zeitraum steht kein Jubiläum an." to "MemberAnniversariesScreen.kt",
                "Kein Eintrag passt zu den gewählten Filtern." to "MemberAnniversariesScreen.kt",
                "Geburtstage" to "MemberAnniversariesScreen.kt",
                "Jubiläen" to "MemberAnniversariesScreen.kt",
                "Suche nach Titel oder Mitglied" to "MemberHonorsScreen.kt",
                "Keine Ehrung in der Kategorie \"%1\"." to "MemberHonorsScreen.kt",
                "Suche nach Familienname oder Zahler" to "MemberFamiliesScreen.kt",
                "Keine Familie passt zu \"%1\"." to "MemberFamiliesScreen.kt",
                "Suche nach Mitglied oder Mandatsreferenz" to "SepaMandatesScreen.kt",
                "Noch keine SEPA-Mandate erfasst." to "SepaMandatesScreen.kt",
                "Kein Mandat mit dem Status \"%1\"." to "SepaMandatesScreen.kt",
                "Suche nach Mitglied oder Hinweis" to "PaymentTransactionsScreen.kt",
                "Alle Zahlungseingänge sind gebucht." to "PaymentTransactionsScreen.kt",
                "Noch keine Zahlungseingänge erfasst." to "PaymentTransactionsScreen.kt",
                "Noch kein Bankkonto angelegt." to "BankAccountsScreen.kt",
                "Bankkonto löschen" to "BankAccountsScreen.kt",
                "Bankkonto %1 (%2) löschen? Bereits gebuchte Kontoauszüge bleiben erhalten." to "BankAccountsScreen.kt",
                "Kein aktiver externer Spender vorhanden. Inaktive Spender einblenden, um auch stillgelegte zu sehen." to
                    "DonorsScreen.kt",
                "Suche nach Mitglied" to "DunningCasesScreen.kt",
                "Kein offener Mahnvorgang." to "DunningCasesScreen.kt",
                "Noch keine Mahnvorgänge erfasst." to "DunningCasesScreen.kt",
                "Noch kein SEPA-Lauf angelegt." to "SepaBatchesScreen.kt",
                "Keine Rücklastschrift im gewählten Zeitraum." to "SepaBatchesScreen.kt",
                "Noch keine Rücklastschriften erfasst." to "SepaBatchesScreen.kt",
                "Kein aktives Konto vorhanden. Inaktive Konten einblenden, um auch stillgelegte zu sehen." to "LedgerScreen.kt",
                "Buchung suchen (Beschreibung)" to "LedgerScreen.kt",
                "Keine Buchung passt zu \"%1\"." to "LedgerScreen.kt",
                "Keine Buchung mit dem Status \"%1\"." to "LedgerScreen.kt",
                "Keine Buchung mit dem Status \"%1\" im gewählten Zeitraum." to "LedgerScreen.kt",
                "Noch keine Buchungen erfasst." to "LedgerScreen.kt",
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

        test("the counter and no-match sentences of this wave really live in DataTableState.kt") {
            val ids = extractWaveMessages(File(clientSrc, "DataTableState.kt")).map { it.msgid }
            listOf(
                "%1 von %2 geladenen Einträgen angezeigt",
                "%1 von %2 Einträgen geladen",
                "%1 Einträge geladen",
                "Kein geladener Eintrag passt zu \"%1\".",
            ).forEach { (it in ids) shouldBe true }
        }

        test("every listed screen sentence really occurs in the screen it is attributed to") {
            screenMessages.forEach { (message, fileName) ->
                val source = File(clientSrc, fileName).readText()
                // The source carries the Kotlin-escaped form of the sentence.
                source.contains(message.replace("\"", "\\\"")) shouldBe true
            }
        }

        test("the two empty-state sentences of a filtered vs. unfiltered list are really different texts") {
            // The whole point of splitting them (guideline 2.4/R41): "no data yet" and "nothing matches"
            // call for different reactions, so no catalog may collapse them into one sentence.
            languages.forEach { lang ->
                val catalog = catalogs.getValue(lang)
                catalog.getValue("Noch keine Mahnvorgänge erfasst.") shouldNotBe catalog.getValue("Kein offener Mahnvorgang.")
                catalog.getValue("Noch keine Buchungen erfasst.") shouldNotBe catalog.getValue("Keine Buchungen im gewählten Zeitraum.")
            }
        }

        test("sanity: the wave yielded strings at all (extraction is not broken)") {
            (uniqueMsgids.size >= 30) shouldBe true
        }
    })
