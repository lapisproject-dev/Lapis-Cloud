package network.lapis.cloud.server.i18n

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.4.30 "UI/UX guideline wave W4c -- form grammar, part 3" -- i18n guard for the twelve finance screens this wave moved onto the
 * form grammar (ledger, open items and their dialogs, SEPA batches/mandates, donors, bank accounts, statement import, cost centers,
 * the accounting export and the dunning cases). Same extraction/parsing logic as [FormGrammarPart2I18nCatalogTest] (see there for the
 * reasoning; `jsTest` has no file system, hence this lives in `lapis-server`).
 *
 * **Every file is scanned completely and the exception list is EMPTY** ([foreignGaps]): this wave found 173 literals in these twelve
 * files that had no translation in one or more catalogs (the dunning list, the accounting export and the long SEPA conflict messages
 * had never been translated at all) and put every one of them in the eight catalogs. `tr(CONSTANT)` / `gettext(CONSTANT)` is resolved to
 * the constant's text; a constant that is itself a chain `"..." + OTHER_CONSTANT + "..."` is resolved through the other constant too (the
 * SEPA conflict messages), which the part-2 guard did not do -- it saw a truncated text that exists in no catalog.
 *
 * Three rules of the wave are pinned here (all three pinned by part 2 as well, for its files): **no label carries `(optional)`**, **no
 * msgid carries a date format**, and **no msgid carries a numeric bound of a field** ("Höchstens 100 Zeichen" belongs in a `%N`
 * placeholder). Plus the one the finance vocabulary needs: **the core accounting terms stay pairwise different words in every language**.
 */
class FormGrammarPart3I18nCatalogTest :
    FunSpec({
        val clientSrc: File =
            File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }

        /** Scanned completely: the twelve finance files of the wave -- the migrated forms AND the filter/dialog-only screens. */
        val waveSourceFiles =
            listOf(
                "LedgerScreen.kt",
                "OpenItemsScreen.kt",
                "OpenItemDialogs.kt",
                "SepaBatchesScreen.kt",
                "SepaMandatesScreen.kt",
                "SepaMandateSection.kt",
                "DonorsScreen.kt",
                "BankAccountsScreen.kt",
                "BankStatementImportScreen.kt",
                "CostCentersScreen.kt",
                "AccountingExportScreen.kt",
                "DunningCasesScreen.kt",
                // V1.4.30 audit: the grammar and the two files that produce field-rule messages are held to the catalogs too.
                "FormGrammar.kt",
                "FormRules.kt",
                "OpenItemFormValidation.kt",
            )

        /** file -> msgids that are known to be missing for reasons outside this wave. Empty: nothing is excused. */
        val foreignGaps: Map<String, Set<String>> = emptyMap()

        val languages = listOf("en", "es", "fr", "it", "nl", "pl", "ru")

        /** Sentences this wave put into the catalog (previously untranslated or new): held to the French non-breaking-space rule. */
        val newSentences =
            listOf(
                "Beispiel: 1234,56.",
                "Beispiel: 2026-03-14.",
                "Beispiel: %1.",
                "Pflichtangabe für diesen Spendertyp.",
                "-- Position wählen --",
                "Betroffene Zeile: %1",
                "Soll %1 · Haben %2 · Differenz %3",
                "Soll und Haben stimmen nicht überein (Differenz %1).",
                "Eine Buchung braucht mindestens zwei Buchungszeilen.",
                "Eine Buchung braucht mindestens eine Sollzeile und eine Habenzeile.",
                "Der Betrag darf den verrechenbaren Betrag (%1) nicht übersteigen.",
                "Bitte ein Mitglied wählen.",
                "Bitte eine Spenderkategorie wählen.",
                "Für ein Mitglied kann die Spenderkategorie nicht anonym sein.",
                "Bitte ein Token eingeben.",
                "Pflicht bei \"Sonstiger Grund\".",
                "Die Verbindung wurde unterbrochen, eine Antwort des Servers fehlt. Der Auszug wurde möglicherweise importiert -- " +
                    "prüfen Sie die Importliste, bevor Sie die Datei erneut hochladen.",
            )

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

        /**
         * A `"a" + "b"` chain starting at the quote at [start]; returns the unescaped text and the position after the chain. A `+ NAME`
         * operand that is a known `const val` ([known]) is resolved to its text too (the SEPA conflict messages are
         * `"..." + SEPA_GATE_CONFLICT_HINT + "."`) -- without it the guard would see a truncated text that exists in no catalog.
         */
        fun parseLiteralChain(
            text: String,
            start: Int,
            known: Map<String, String> = emptyMap(),
        ): Pair<String, Int> {
            var pos = start
            val combined = StringBuilder()
            while (true) {
                val (segment, nextPos) = parseStringLiteral(text, pos)
                combined.append(unescapeKotlinString(segment))
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
                    val operand = Regex("""[A-Z][A-Z0-9_]*\b""").matchAt(text, afterPlus)
                    val value = operand?.let { known[it.value] }
                    if (operand != null && value != null) {
                        combined.append(value)
                        pos = operand.range.last + 1
                        var again = pos
                        while (again < text.length && text[again].isWhitespace()) again++
                        if (again < text.length && text[again] == '+') {
                            var afterSecondPlus = again + 1
                            while (afterSecondPlus < text.length && text[afterSecondPlus].isWhitespace()) afterSecondPlus++
                            if (afterSecondPlus < text.length && text[afterSecondPlus] == '"') {
                                pos = afterSecondPlus
                                continue
                            }
                        }
                    }
                }
                break
            }
            return combined.toString() to pos
        }

        /**
         * Every `const val NAME = "..."` (also `"a" + "b"` chains and chains through another constant) of the client sources: a `tr(NAME)` /
         * `gettext(NAME)` call is resolved to its value, so a constant text is held to the catalogs like a literal one. Three passes: a
         * constant may be built from constants that are declared later (or in another file).
         */
        val constants: Map<String, String> by lazy {
            val declaration = Regex("""const val ([A-Z][A-Z0-9_]*)\s*=\s*(?=")""")
            val texts =
                clientSrc
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .map { it.readText() }
                    .toList()
            var found: Map<String, String> = emptyMap()
            repeat(3) {
                val next = mutableMapOf<String, String>()
                for (text in texts) {
                    for (match in declaration.findAll(text)) {
                        next[match.groupValues[1]] = parseLiteralChain(text, match.range.last + 1, found).first
                    }
                }
                found = next
            }
            found
        }

        fun extractWaveMessages(file: File): List<WaveMessage> {
            val text = file.readText()
            val messages = mutableListOf<WaveMessage>()
            val callStart = Regex("""\b(?:tr|gettext)\(""")
            val constantArgument = Regex("""([A-Z][A-Z0-9_]*)\s*[,)]""")
            for (match in callStart.findAll(text)) {
                var pos = match.range.last + 1
                while (pos < text.length && text[pos].isWhitespace()) pos++
                if (pos >= text.length) continue
                val lineNumber = text.substring(0, pos).count { it == '\n' } + 1
                if (text[pos] == '"') {
                    messages += WaveMessage(file.name, lineNumber, parseLiteralChain(text, pos, constants).first)
                } else {
                    // `tr(SOME_CONSTANT)`: resolved to the constant's text. A name that is no `const val` (a qualified or a local one) is skipped:
                    // a limit of this guard.
                    val name = constantArgument.matchAt(text, pos)?.groupValues?.get(1) ?: continue
                    val value = constants[name] ?: continue
                    messages += WaveMessage(file.name, lineNumber, value)
                }
            }
            return messages
        }

        /** msgid -> msgstr, and (second) the msgids the catalog marks `#, fuzzy` -- a fuzzy entry is a draft, not a translation. */
        fun parsePoEntriesAndFuzzy(file: File): Pair<Map<String, String>, Set<String>> {
            val entries = mutableMapOf<String, String>()
            val fuzzy = mutableSetOf<String>()
            var nextIsFuzzy = false
            var currentIsFuzzy = false
            var currentId: StringBuilder? = null
            var currentStr: StringBuilder? = null
            var mode = 0

            fun unescape(s: String) = s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")

            fun flush() {
                val id = currentId
                val str = currentStr
                if (id != null && str != null) {
                    entries[unescape(id.toString())] = unescape(str.toString())
                    if (currentIsFuzzy) fuzzy += unescape(id.toString())
                }
            }
            file.forEachLine { rawLine ->
                val line = rawLine.trim()
                when {
                    // The flag line precedes its entry's msgid.
                    line.startsWith("#,") && line.contains("fuzzy") -> nextIsFuzzy = true
                    line.startsWith("msgid \"") -> {
                        flush()
                        currentIsFuzzy = nextIsFuzzy
                        nextIsFuzzy = false
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
            return entries to fuzzy
        }

        val waveMessages: List<WaveMessage> by lazy {
            waveSourceFiles.flatMap { name ->
                extractWaveMessages(File(clientSrc, name)).filterNot { it.msgid in foreignGaps[name].orEmpty() }
            }
        }
        val uniqueMsgids: List<String> by lazy { waveMessages.map { it.msgid }.filter { it.isNotBlank() }.distinct() }
        val parsedCatalogs: Map<String, Pair<Map<String, String>, Set<String>>> by lazy {
            languages.associateWith { lang -> parsePoEntriesAndFuzzy(File(i18nDir, "messages-$lang.po")) }
        }
        val catalogs: Map<String, Map<String, String>> by lazy { parsedCatalogs.mapValues { it.value.first } }
        val template: Map<String, String> by lazy { parsePoEntriesAndFuzzy(File(i18nDir, "messages.pot")).first }

        test("no extracted string of the wave is blank") {
            waveMessages.filter { it.msgid.isBlank() }.map { "${it.file}:${it.line}" }.shouldBeEmpty()
        }

        test("every string of the scanned files is in the template and all seven catalogs") {
            val missing =
                languages.flatMap { lang ->
                    uniqueMsgids.filterNot { catalogs.getValue(lang).containsKey(it) }.map { "$lang: \"$it\"" }
                } + uniqueMsgids.filterNot { template.containsKey(it) }.map { "pot: \"$it\"" }
            missing.shouldBeEmpty()
        }

        test("every string of the scanned files is translated (non-blank) in all seven catalogs") {
            val untranslated =
                languages.flatMap { lang ->
                    uniqueMsgids.filter { catalogs.getValue(lang)[it]?.isBlank() != false }.map { "$lang: \"$it\"" }
                }
            untranslated.shouldBeEmpty()
        }

        test("no string of the scanned files is marked fuzzy (a fuzzy entry is a draft, not a translation)") {
            val fuzzy =
                languages.flatMap { lang ->
                    uniqueMsgids.filter { it in parsedCatalogs.getValue(lang).second }.map { "$lang: \"$it\"" }
                }
            fuzzy.shouldBeEmpty()
        }

        test("every translation keeps the %N placeholders of its msgid, each as often as the msgid has it") {
            val placeholder = Regex("""%\d""")

            // Compared as a multiset (sorted list), not as a set: a translation that drops the second `%1` of a msgid which
            // uses it twice would otherwise pass.
            fun placeholders(text: String): List<String> =
                placeholder
                    .findAll(text)
                    .map { it.value }
                    .sorted()
                    .toList()
            val broken =
                languages.flatMap { lang ->
                    uniqueMsgids
                        .filter { id -> placeholders(id) != placeholders(catalogs.getValue(lang).getValue(id)) }
                        .map { "$lang: \"$it\"" }
                }
            broken.shouldBeEmpty()
        }

        test("no msgid of the scanned files embeds a numeric range or bound of a field (constants are %N placeholders)") {
            // "Stufennummer (1-1000)", "Wartefrist in Tagen (1-365)": a label with its range in the msgid says the range in eight
            // catalogs; changing the constant would silently falsify all of them. The range belongs in a hint via placeholders.
            val embedded =
                Regex(
                    """\(\s*\d+\s*[-–]\s*\d+\s*\)|\(optional, max\.|\b(?:höchstens|mindestens|maximal|mehr als|bis zu|max\.?|min\.?)\s+\d+\b""",
                    RegexOption.IGNORE_CASE,
                )
            waveMessages.filter { embedded.containsMatchIn(it.msgid) }.map { "${it.file}:${it.line} \"${it.msgid}\"" }.shouldBeEmpty()
        }

        test("the new sentences of the wave are in the scan (extraction finds them, also through a constant)") {
            val ids = waveMessages.map { it.msgid }
            newSentences.forEach { (it in ids) shouldBe true }
            listOf(
                "%1 · Zeile %2",
                "Bitte eine Datei auswählen.",
                "Bitte einen Betrag angeben.",
                "Zur Kenntnis genommen",
            ).forEach { (it in ids) shouldBe true }
        }

        test("the SEPA conflict messages are held to the catalogs in their FULL text (resolved through the shared gate-hint constant)") {
            val sepa = waveMessages.filter { it.file == "SepaMandateSection.kt" }.map { it.msgid }
            val mandate = sepa.first { it.startsWith("Das Mandat konnte nicht erteilt werden") }
            mandate.endsWith("zu viele Anfragen in kurzer Zeit gestellt.") shouldBe true
            // and that full text is really translated in all seven catalogs (the assertion above would be vacuous otherwise)
            languages.forEach { lang -> catalogs.getValue(lang).getValue(mandate).isNotBlank() shouldBe true }
        }

        test(
            "Soll, Haben, Aktivkonto, Passivkonto, Betrag, Konto, Buchung, Gebühr, Sphäre, Kostenstelle, Mahnung, ... stay different words in every catalog",
        ) {
            // A translation table maps German words to target words; two DIFFERENT accounting terms must never collapse onto ONE target word
            // ("assets" / "liabilities" vs "debit" / "credit", "settle" vs "net", "dunning letter" vs "dunning notice").
            val terms =
                listOf(
                    "Soll",
                    "Haben",
                    "Aktivkonto",
                    "Passivkonto",
                    "Betrag",
                    "Konto",
                    "Buchung",
                    "Gebühr",
                    "Sphäre",
                    "Kostenstelle",
                    "Mahnung",
                    "Mahnhinweise",
                    "Mahnstufe",
                    "Ausgleichen …",
                    "Verrechnen …",
                    "Beleg",
                    "Belegnummer",
                    "Lauf",
                    // V1.4.30 audit: pairs that had collapsed in one or more languages. [Stornieren] (the confirming button of an
                    // irreversible reversal) and [Abbrechen] (the button beside it) were the SAME word in all seven languages: a modal footer
                    // read [Cancel][Cancel] on three money paths. Also the BARE buttons (the "…" variants were pinned, the plain ones were not).
                    "Stornieren",
                    "Abbrechen",
                    "Storniert",
                    "abgebrochen",
                    "Summe",
                    "Mahnwesen",
                    "Gebührenkonto",
                    "Aufwandskonto",
                    "Hochladen",
                    "Übertragen",
                    "Saldo",
                    "Ausgleiche",
                    "Ausgleichen",
                    "Abrechnen",
                    "Verwendungszweck",
                    "Grund",
                )
            languages.forEach { lang ->
                val words = terms.map { catalogs.getValue(lang).getValue(it).lowercase() }
                withClue("$lang: ${terms.zip(words)}") { (words.toSet().size == words.size) shouldBe true }
            }
        }

        test("the Polish ledger accounts are all 'Konto ...' (a 'Rachunek ...' is the bank account only)") {
            listOf(
                "Beitragserlöskonto",
                "Spendenerlöskonto",
                "Veranstaltungserlöskonto",
                "Ehrenamtspauschalen-Aufwandskonto",
                "Gebührenkonto",
            ).forEach { id -> withClue(id) { catalogs.getValue("pl").getValue(id).startsWith("Konto ") shouldBe true } }
            catalogs.getValue("pl").getValue("Bankkonto").startsWith("Rachunek") shouldBe true
        }

        test("the English example sentence uses the English decimal separator") {
            catalogs.getValue("en").getValue("Beispiel: 1234,56.") shouldBe "Example: 1234.56."
        }

        test("structural invariants of the translations (not frozen wording)") {
            // The star convention of case (a) holds in every language ("* Required field", "* Champ obligatoire", ...).
            languages.forEach { lang -> catalogs.getValue(lang).getValue("* Pflichtfeld").startsWith("* ") shouldBe true }
            // French: a colon, semicolon, question or exclamation mark is preceded by a NON-BREAKING space (U+00A0), never a breakable
            // one -- a line break must not strand the mark at the start of the next line. Held for every sentence THIS wave added to
            // the catalog ([newSentences]); the rest of the French catalog predates this and uses ordinary spaces throughout (a
            // catalog-wide clean-up, not part of this wave).
            val breakablePunctuation = Regex(""" [:;?!]""")
            val frenchOffenders =
                newSentences
                    .filter { breakablePunctuation.containsMatchIn(catalogs.getValue("fr").getValue(it)) }
                    .map { "\"${catalogs.getValue("fr").getValue(it)}\"" }
            frenchOffenders.shouldBeEmpty()
        }

        test("the error message of the amount field names the field with the word the label uses (Betrag)") {
            // A relation, not a frozen wording: whatever the label says, the message must say the same word.
            languages.forEach { lang ->
                val field = catalogs.getValue(lang).getValue("Betrag").lowercase()
                val error = catalogs.getValue(lang).getValue("Der Betrag muss größer als 0 sein.").lowercase()
                withClue("$lang: \"$field\" in \"$error\"") { error.contains(field) shouldBe true }
            }
        }

        test("sanity: the scan yielded strings at all (extraction is not broken)") {
            (uniqueMsgids.size >= 600) shouldBe true
        }
    })
