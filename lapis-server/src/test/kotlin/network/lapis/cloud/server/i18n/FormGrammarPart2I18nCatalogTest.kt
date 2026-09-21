package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.4.29 "UI/UX guideline wave W4b -- form grammar, part 2" -- i18n guard for the screens this wave moved onto the form
 * grammar (member administration, the door check-in, meetings, motions, committees, board membership, communication, the relief
 * queue, social moderation, the statute Q&A) and for the two building blocks it extended. Same extraction/parsing logic as
 * [FormGrammarI18nCatalogTest] (see there for the reasoning; `jsTest` has no file system, hence this lives in `lapis-server`).
 *
 * **Every file is scanned completely and the exception list is EMPTY** ([foreignGaps]): the V1.4.28 audit found 46 literals
 * without a catalog entry precisely because an exception list and a partial scan had hidden them. This wave found 103 more in
 * `MemberAdministrationScreen` and `EventCheckInScreen` that had never been translated and put every one of them in the eight
 * catalogs. `tr(CONSTANT)` / `gettext(CONSTANT)` is resolved to the constant's text (`const val`); a `gettext(variable)` is NOT extracted, which is a limit of this
 * guard, not a proof of completeness.
 *
 * Two rules of the wave are pinned here: **no label carries `(optional)`** (the star / the legend is the only marking system) and
 * **no msgid carries a date format** ("JJJJ-MM-TT" is wrong in every one of the seven other languages -- the example sits in the
 * hint, "Beispiel: 2026-03-14.", a sentence that is right in all of them).
 */
class FormGrammarPart2I18nCatalogTest :
    FunSpec({
        val clientSrc: File =
            File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }

        /** Scanned completely: the building blocks of the form grammar, their rules and every screen migrated to them. */
        val waveSourceFiles =
            listOf(
                "FormGrammar.kt",
                "FormRules.kt",
                "ConfirmDialog.kt",
                "MemberAdministrationScreen.kt",
                "EventCheckInScreen.kt",
                "MeetingsScreen.kt",
                "MotionsScreen.kt",
                "CommitteesScreen.kt",
                "BoardMembershipScreen.kt",
                "CommunicationScreen.kt",
                "ContributionReliefQueueScreen.kt",
                "SocialModerationScreen.kt",
                "StatuteQaScreen.kt",
                "PostalMailScreen.kt",
                "MemberAnniversariesScreen.kt",
                "MyVolunteerShiftsScreen.kt",
            )

        /** file -> msgids that are known to be missing for reasons outside this wave. Empty: nothing is excused. */
        val foreignGaps: Map<String, Set<String>> = emptyMap()

        val languages = listOf("en", "es", "fr", "it", "nl", "pl", "ru")

        /** Sentences this wave put into the catalog (previously untranslated or new): held to the French non-breaking-space rule. */
        val newSentences =
            listOf(
                "Beispiel: 2026-03-14.",
                "Beispiel: 2026-08-15T18:00.",
                "Eingecheckt: %1",
                "Bitte dieses Kästchen ankreuzen.",
                "Bitte einen gültigen Termin angeben.",
                "Bitte eine ganze Zahl eingeben.",
                "Bitte eine ganze Zahl von %1 oder größer eingeben.",
                "Aktueller Tarif: %1",
                "Bitte einen Tarif auswählen.",
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

        /** A `"a" + "b"` chain starting at the quote at [start]; returns the unescaped text and the position after the chain. */
        fun parseLiteralChain(
            text: String,
            start: Int,
        ): Pair<String, Int> {
            var pos = start
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
            return unescapeKotlinString(combined.toString()) to pos
        }

        /**
         * Every `const val NAME = "..."` (also `"a" + "b"` chains) of the client sources: a `tr(NAME)` / `gettext(NAME)` call is
         * resolved to its value, so a constant text is held to the catalogs like a literal one (audit V1.4.29 m-10: four constants
         * had no catalog entry because only literals were extracted).
         */
        val constants: Map<String, String> by lazy {
            val found = mutableMapOf<String, String>()
            val declaration = Regex("""const val ([A-Z][A-Z0-9_]*)\s*=\s*(?=")""")
            clientSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                for (match in declaration.findAll(text)) {
                    found[match.groupValues[1]] = parseLiteralChain(text, match.range.last + 1).first
                }
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
                    messages += WaveMessage(file.name, lineNumber, parseLiteralChain(text, pos).first)
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
            val embedded = Regex("""\(\s*\d+\s*[-–]\s*\d+\s*\)|\(optional, max\.""")
            waveMessages.filter { embedded.containsMatchIn(it.msgid) }.map { "${it.file}:${it.line} \"${it.msgid}\"" }.shouldBeEmpty()
        }

        test("the building blocks really carry the new sentences (extraction finds them)") {
            val ids = waveMessages.map { it.msgid }
            listOf(
                "Bitte dieses Kästchen ankreuzen.",
                "Bitte einen gültigen Termin angeben.",
                "Bitte eine ganze Zahl eingeben.",
                "Bitte eine ganze Zahl von %1 oder größer eingeben.",
                "Bitte eine Begründung mit %1 bis %2 Zeichen angeben.",
                "%1 bis %2 Zeichen.",
            ).forEach { (it in ids) shouldBe true }
        }

        test("the two files with the most previously untranslated sentences are scanned and their literals are in the catalogs") {
            val ids = waveMessages.filter { it.file in setOf("MemberAdministrationScreen.kt", "EventCheckInScreen.kt") }.map { it.msgid }
            listOf(
                "Sterbedatum",
                "Beispiel: 2026-03-14.",
                "Ticket-Code",
                "Code unbekannt.",
                "Beitragstarif",
            ).forEach { (it in ids) shouldBe true }
        }

        test("no label or hint carries an '(optional)' suffix (the star and the legend are the only marking system)") {
            val marked = Regex("""\(\s*optional\b|\(\s*Pflicht\s*\)""", RegexOption.IGNORE_CASE)
            waveMessages.filter { marked.containsMatchIn(it.msgid) }.map { "${it.file}:${it.line} \"${it.msgid}\"" }.shouldBeEmpty()
        }

        test("no msgid carries a date format (the example sits in the hint, in a sentence that is right in every language)") {
            val dateFormat = Regex("""JJJJ|YYYY|\bTT\b|\bhh:mm\b|HH:MM""")
            waveMessages.filter { dateFormat.containsMatchIn(it.msgid) }.map { "${it.file}:${it.line} \"${it.msgid}\"" }.shouldBeEmpty()
        }

        test("the formerly numeric labels are now labels with a placeholder hint") {
            val ids = waveMessages.map { it.msgid }
            (("Quorum in %") in ids) shouldBe true
            (("%1 bis %2.") in ids) shouldBe true
            (ids.none { it.contains("3-1000") || it.contains("0-100") }) shouldBe true
        }

        test("structural invariants of the translations (not frozen wording)") {
            // The star convention of case (a) holds in every language ("* Required field", "* Champ obligatoire", ...).
            languages.forEach { lang -> catalogs.getValue(lang).getValue("* Pflichtfeld").startsWith("* ") shouldBe true }
            // French: a colon, semicolon, question or exclamation mark is preceded by a NON-BREAKING space (U+00A0), never a
            // breakable one -- a line break must not strand the mark at the start of the next line. Held for the sentences of
            // the building blocks (`FormGrammar.kt`, `FormRules.kt`) and for every sentence THIS wave added to the catalog
            // (`newSentences`); the rest of the French catalog predates this and uses ordinary spaces throughout (a
            // catalog-wide clean-up, not part of this wave).
            val breakablePunctuation = Regex(""" [:;?!]""")
            val buildingBlockIds =
                waveMessages.filter { it.file in setOf("FormGrammar.kt", "FormRules.kt") }.map { it.msgid }.distinct()
            val frenchOffenders =
                (buildingBlockIds + newSentences)
                    .filter { breakablePunctuation.containsMatchIn(catalogs.getValue("fr").getValue(it)) }
                    .map { "\"${catalogs.getValue("fr").getValue(it)}\"" }
            frenchOffenders.shouldBeEmpty()
            // The sentence that ends in a colon keeps its colon before the placeholder in every language.
            languages.forEach { lang ->
                catalogs.getValue(lang).getValue("Bitte korrigieren Sie diese Felder: %1.").contains(":") shouldBe true
            }
        }

        test("the error message of a field names the field with the word the label uses (Begründung, Entscheidungsnotiz)") {
            // A relation, not a frozen wording: whatever the label says, the message must say the same word.
            languages.forEach { lang ->
                val field = catalogs.getValue(lang).getValue("Begründung").lowercase()
                val error = catalogs.getValue(lang).getValue("Bitte eine Begründung mit %1 bis %2 Zeichen angeben.").lowercase()
                error.contains(field) shouldBe true
                // The decision-note error names the note, not "the reason": the stem of the label "Entscheidungsnotiz" (the first six
                // letters -- Polish inflects the noun: "Notatka decyzyjna" / "notatkę decyzyjną").
                val noteStem =
                    catalogs
                        .getValue(lang)
                        .getValue("Entscheidungsnotiz")
                        .lowercase()
                        .take(6)
                val noteError = catalogs.getValue(lang).getValue("Bitte eine Entscheidungsnotiz eingeben.").lowercase()
                noteError.contains(noteStem) shouldBe true
            }
        }

        test("Begründung, Entscheidungsnotiz, Notiz and Beschreibung stay four different words in every catalog") {
            languages.forEach { lang ->
                val words = listOf("Begründung", "Entscheidungsnotiz", "Notiz", "Beschreibung").map { catalogs.getValue(lang).getValue(it) }
                (words.toSet().size == words.size) shouldBe true
            }
        }

        test("sanity: the scan yielded strings at all (extraction is not broken)") {
            (uniqueMsgids.size >= 300) shouldBe true
        }

        test("a tr(CONSTANT) call is resolved to the constant's text and held to the catalogs (the mailing send caption)") {
            waveMessages.any {
                it.file == "CommunicationScreen.kt" &&
                    it.msgid.startsWith(
                        "Der Versand ist in dieser Version ein interner",
                    )
            } shouldBe
                true
        }

        test("the new sentences of the audit round are in the wave scan (constants of the hint, the placeholder and the Ablehnen note)") {
            val ids = waveMessages.map { it.msgid }
            listOf(
                "— bitte wählen —",
                "Kommagetrennt, mindestens %1 verschiedene Optionen.",
                "Nur für \"Ablehnen\" erforderlich.",
                "Höchstens %1 Zeichen.",
                "Optionen",
            ).forEach { (it in ids) shouldBe true }
        }
    })
