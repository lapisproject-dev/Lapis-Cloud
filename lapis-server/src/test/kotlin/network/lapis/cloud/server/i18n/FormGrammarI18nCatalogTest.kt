package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.4.28 "UI/UX guideline wave W4a -- form grammar, part 1" -- i18n guard for the sentences of the screens this
 * wave moved onto the form grammar, same extraction/parsing logic as [PseudoTableI18nCatalogTest] (see there for the
 * reasoning; `jsTest` has no file system, hence this lives in `lapis-server`).
 *
 * **Every migrated file is scanned completely** (audit V1.4.28: the first version scanned only the two building blocks plus
 * an explicit list of sentences, so 46 literals of two of the migrated screens had no catalog entry and nobody noticed).
 * [foreignGaps] is the honest exception list: pre-existing gaps of somebody else's code that this guard must not fail
 * for -- currently EMPTY, every literal of the scanned files is in the catalogs. A `tr(CONSTANT)` / `gettext(variable)`
 * call is NOT extracted (only string literals are), which is a limit of this guard, not a proof of completeness.
 * Every constant is a `%N` placeholder, never part of a msgid.
 */
class FormGrammarI18nCatalogTest :
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
                "Validation.kt",
                "OpenItemFormValidation.kt",
                "LoginScreen.kt",
                "RegistrationScreen.kt",
                "FriendRegistrationScreen.kt",
                "PasswordResetDeepLinkScreen.kt",
                "MemberPasswordResetDialog.kt",
                "SepaSettingsScreen.kt",
                "DunningSettingsScreen.kt",
                "ReceivableDunningSettingsScreen.kt",
                "ReceivableDunningLevelValidation.kt",
                "ConferenceStreamDestinationsScreen.kt",
                "BackupScreen.kt",
                "BackupHttp.kt",
                "ApiKeysScreen.kt",
                "EmbedIntegrationScreen.kt",
            )

        /** file -> msgids that are known to be missing for reasons outside this wave. Empty: nothing is excused. */
        val foreignGaps: Map<String, Set<String>> = emptyMap()

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
                "* Pflichtfeld",
                "Alle Felder sind Pflichtfelder.",
                "Dieses Feld muss ausgefüllt werden.",
                "Bitte korrigieren Sie diese Felder: %1.",
                "Passwort anzeigen",
                "Passwort verbergen",
                "Bitte eine gültige E-Mail-Adresse eingeben.",
                "Die E-Mail-Adresse ist zu lang (höchstens %1 Zeichen).",
                "Bitte eine ganze Zahl zwischen %1 und %2 eingeben.",
                "Die Gebühr muss zwischen %1 und %2 liegen.",
            ).forEach { (it in ids) shouldBe true }
        }

        test("the two files the first audit found incomplete are scanned and their literals are in the catalogs") {
            val ids = waveMessages.filter { it.file in setOf("MemberPasswordResetDialog.kt", "DunningSettingsScreen.kt") }.map { it.msgid }
            listOf(
                "Temporäres Passwort",
                "Stufennummer",
                "Wartefrist in Tagen",
                "Zulässig: %1 bis %2.",
            ).forEach { (it in ids) shouldBe true }
        }

        test("structural invariants of the translations (not frozen wording)") {
            // The star convention of case (a) holds in every language ("* Required field", "* Champ obligatoire", ...).
            languages.forEach { lang -> catalogs.getValue(lang).getValue("* Pflichtfeld").startsWith("* ") shouldBe true }
            // French: a colon, semicolon, question or exclamation mark is preceded by a NON-BREAKING space (U+00A0), never a
            // breakable one -- a line break must not strand the mark at the start of the next line. Held for the sentences of
            // the building blocks (`FormGrammar.kt`, `FormRules.kt`); the rest of the French catalog predates this and uses
            // ordinary spaces throughout (a catalog-wide clean-up, not part of this wave).
            val breakablePunctuation = Regex(""" [:;?!]""")
            val buildingBlockIds =
                waveMessages.filter { it.file in setOf("FormGrammar.kt", "FormRules.kt") }.map { it.msgid }.distinct()
            val frenchOffenders =
                buildingBlockIds
                    .filter { breakablePunctuation.containsMatchIn(catalogs.getValue("fr").getValue(it)) }
                    .map { "\"${catalogs.getValue("fr").getValue(it)}\"" }
            frenchOffenders.shouldBeEmpty()
            // The sentence that ends in a colon keeps its colon before the placeholder in every language.
            languages.forEach { lang ->
                catalogs.getValue(lang).getValue("Bitte korrigieren Sie diese Felder: %1.").contains(":") shouldBe true
            }
        }

        test("the error message of a field names the field with the word the label uses (Begründung)") {
            // A relation, not a frozen wording: whatever the label says, the message must say the same word.
            languages.forEach { lang ->
                val field = catalogs.getValue(lang).getValue("Begründung").lowercase()
                val error = catalogs.getValue(lang).getValue("Bitte eine Begründung mit %1 bis %2 Zeichen angeben.").lowercase()
                error.contains(field) shouldBe true
            }
        }

        test("the two show/hide sentences of the reveal toggle are different words in every catalog") {
            languages.forEach { lang ->
                (catalogs.getValue(lang).getValue("Passwort anzeigen") != catalogs.getValue(lang).getValue("Passwort verbergen")) shouldBe
                    true
            }
        }

        test("sanity: the scan yielded strings at all (extraction is not broken)") {
            (uniqueMsgids.size >= 200) shouldBe true
        }
    })
