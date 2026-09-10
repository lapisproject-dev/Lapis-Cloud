package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.4.5.1.1 -- i18n-Waechter. Prueft AUSSCHLIESSLICH die Textstrings DIESER Welle, gegen
 * alle sieben Sprachkataloge, auf VORHANDENSEIN und NICHT-LEEREN `msgstr`.
 *
 * Bewusst ausserhalb des Pruefumfangs: die 36 pro Katalog bereits leeren `msgstr` und die
 * 433 Code-Literale, die im Katalog gar nicht vorkommen (Stand 2026-09-10, u.a. "Zahlungseingänge",
 * "Mahnwesen", "Familienmitgliedschaften"). Diese Altlasten sind nicht Auftrag dieser Welle
 * (Design-Team, Zhuo; von Jobs unveraendert uebernommen) -- und ein Test, der am ersten Tag rot
 * ist, wird am zweiten Tag deaktiviert.
 *
 * Liegt in `lapis-server`, weil `jsTest` unter Karma/ChromeHeadless kein Dateisystem hat (siehe
 * `LapisAttributionTest` KDoc); der Zwei-Pfad-Fallback unten folgt `KumlModelLoader.kumlSourceDir`.
 *
 * **Extraktion ist bewusst NICHT auf einzeilige Aufrufe beschraenkt** -- ein `tr(`/`gettext(`-Aufruf
 * darf ueber mehrere Zeilen gehen, UND ein zu langer Textstring darf (ktlint `max-line-length`
 * 140 Zeichen erzwingt das faktisch) per `"…" + \n "…"`-Konkatenation auf mehrere Quellzeilen
 * verteilt werden -- exakt die Konvention, die dieses Repo selbst schon etabliert hat (siehe
 * `AuctionScreen.kt`/`BoardMembershipScreen.kt`) und die das echte `generatePotFile`-Tooling
 * bereits korrekt zu EINEM zusammenhaengenden `msgid` zusammenfuegt (verifiziert: `messages-en.po`
 * traegt "Fehlende Angaben fuer das Transparenzregister: ..." als EIN Eintrag, obwohl
 * `BoardMembershipScreen.kt` ihn per `+` auf zwei Zeilen aufteilt). [extractWaveMessages] muss
 * deshalb ebenfalls jede Kette benachbarter, per `+` verbundener String-Literale zu EINEM `msgid`
 * zusammenfuegen -- eine Version, die Konkatenation stattdessen verweigert, waere zwar einfacher,
 * würde aber jeden Textstring dieser Welle ueber ~110 sichtbare Zeichen hinaus in einen ktlint-
 * Konflikt zwingen (Erkenntnis aus dem ersten `clean check`-Lauf dieser Welle: mehrere der zehn
 * Ablehnungs-Meldungen in `BankStatementLabels.kt` sind laenger als das).
 */
class BankStatementI18nCatalogTest :
    FunSpec({
        val clientSrc: File =
            File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }

        val waveSourceFiles =
            listOf(
                "BankStatementImportScreen.kt",
                "BankStatementLabels.kt",
                "BankStatementHttp.kt",
                "BankStatementAuthzUi.kt",
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

        /**
         * Parses ONE `"…"` string literal starting at [pos] (which must point at the opening `"`)
         * and returns its raw (still-escaped) content plus the index just past the closing `"`.
         */
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
            return raw.toString() to (i + 1) // +1 skips the closing quote
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

                // Join every `"…"` literal chained onto this one via `+` (ktlint's own
                // line-length-forced line-break convention, see class KDoc) into ONE msgid.
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

        // .po-Format: `msgid`/`msgstr` koennen sich ueber mehrere `"…"`-Fortsetzungszeilen
        // erstrecken (Stolperfalle #4: ein naiver `grep '^msgstr ""$'` faengt genau diese Faelle
        // falsch -- siehe K2 im Wellen-Plan). Diese Funktion fuegt sie korrekt zusammen.
        fun parsePoEntries(file: File): Map<String, String> {
            val entries = mutableMapOf<String, String>()
            var currentId: StringBuilder? = null
            var currentStr: StringBuilder? = null
            var mode = 0 // 0 = none, 1 = msgid, 2 = msgstr

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

        test("kein extrahierter Textstring dieser Welle ist leer (Konkatenations-Zusammenfuehrung funktioniert)") {
            val empty = waveMessages.filter { it.msgid.isBlank() }.map { "${it.file}:${it.line}" }
            empty.shouldBeEmpty()
        }

        test("jeder Textstring dieser Welle hat in allen sieben Katalogen einen Eintrag") {
            val missing =
                languages.flatMap { lang ->
                    val catalog = catalogs.getValue(lang)
                    uniqueMsgids.filterNot { catalog.containsKey(it) }.map { "$lang: \"$it\"" }
                }
            missing.shouldBeEmpty()
        }

        test("jeder Textstring dieser Welle ist in allen sieben Katalogen nicht-leer uebersetzt") {
            val untranslated =
                languages.flatMap { lang ->
                    val catalog = catalogs.getValue(lang)
                    uniqueMsgids.filter { catalog[it]?.isBlank() != false }.map { "$lang: \"$it\"" }
                }
            untranslated.shouldBeEmpty()
        }

        test("sanity: die Welle hat ueberhaupt Textstrings gefunden (Extraktion ist nicht kaputt)") {
            (uniqueMsgids.size > 50) shouldBe true
        }
    })
