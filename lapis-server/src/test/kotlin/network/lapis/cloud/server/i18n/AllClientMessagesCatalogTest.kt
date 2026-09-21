package network.lapis.cloud.server.i18n

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.4.31 (W5): every `tr("...")`/`gettext("...")` of the WHOLE client is covered by the catalogs -- not a hand-picked list of files per
 * wave (the waves before W5 each guarded only the files they had touched, so 373 sentences of other screens were in no catalog at all).
 *
 * What this checks, precisely (audit V1.4.31: the first KDoc claimed more than the test does): every `tr("...")`/`gettext("...")` call
 * whose first argument is a string literal (with `+` concatenation) or a named constant must be a msgid of the template and of all
 * seven catalogs, have a non-empty translation in each catalog and keep the `%N` placeholders of its msgid. It does NOT check that
 * every VISIBLE text goes through `tr()`: a German literal that never reaches `tr()`/`gettext()` is invisible to the scan above. The
 * two rules at the bottom ([RAW_GERMAN_FALLBACK] and the unwrapped-constant ledger [UNWRAPPED_CONSTANT_USES]) look for the shapes in which
 * that happened (`if (x) "Erledigt" else "Offen"`, `?: "unbekannt"`, `ifBlank { "keine" }`, `"" to "-- keine --"`, a German
 * `const val` used without `tr()`), each with a baseline, so a NEW one turns the build red -- they are heuristics with a stated recall
 * limit, not a proof. There is NO file exclusion. If a sentence must be exempt it goes into [EXEMPT_MSGIDS] with its reason -- a list
 * that never grows without a reviewer noticing.
 */
private val EXEMPT_MSGIDS: Map<String, String> = emptyMap()

/**
 * A German literal in a position that shows it as text without `tr()`/`gettext()`: the result of a ternary / `else` branch, an elvis
 * fallback, an `ifBlank { }` fallback, or the label half of a `"" to "label"` option pair. Only literals that LOOK German (start with
 * an upper-case letter or contain an umlaut/eszett) count, so code values (`"de"`, ids, CSS classes) are not flagged.
 */
private val RAW_GERMAN_FALLBACK =
    listOf(
        Regex("""\bif\s*\([^)]*\)\s*["](?:[A-ZÄÖÜ][^"]*|[^"]*[äöüß][^"]*)["]\s*else\b"""),
        Regex("""\belse\s*["](?:[A-ZÄÖÜ][a-zäöüß][^"]*|[^"]*[äöüß][^"]*)["]"""),
        Regex("""\?:\s*["](?:[A-ZÄÖÜ][a-zäöüß][^"]*|[^"]*[äöüß][^"]*)["]"""),
        Regex("""ifBlank\s*\{\s*["](?:[A-ZÄÖÜ]|[^"]*[äöüß])"""),
        Regex("""["]["]\s+to\s+["](?:[A-ZÄÖÜ-][^"]*|[^"]*[äöüß][^"]*)["]"""),
    )

/** file -> number of tolerated occurrences (with why). Empty: every finding of this rule was fixed in the audit round. */
private val RAW_GERMAN_FALLBACK_BASELINE: Map<String, Pair<Int, String>> = emptyMap()

/**
 * `const val`/`val` constants whose value looks like German prose, counted per use that is NOT the direct argument of `tr(`/`gettext(`.
 * The baseline is a ledger of what is still to be looked at: many of these uses are legitimate (a `+` concatenation inside a `tr(...)`,
 * an argument of a wrapper that translates, a brand name that is not translated), which a scan cannot tell from a raw use. A NEW
 * unwrapped use, or a new German constant used unwrapped, breaks the test. The board-membership copy constants (six raw sentences,
 * audit M4) were the finding that made this rule: they are `tr(...)`-wrapped now and left the ledger.
 */
private val UNWRAPPED_CONSTANT_USES: Map<String, Int> =
    mapOf(
        // brand name of the product, deliberately not translated
        "DEFAULT_TITLE" to 3,
        "PLATFORM_NAME" to 1,
        // parts of a constant `+` chain that is itself the argument of tr()/gettext() (the extractor evaluates the chain)
        "DUNNING_GATE_CONFLICT_HINT" to 2,
        "SEPA_GATE_CONFLICT_HINT" to 4,
        // StatuteQaUi returns MSGIDS ("the reason text (a msgid)"); the screen wraps them
        "REASON_FAILED" to 2,
        "REASON_PENDING" to 2,
        "REASON_UNSUPPORTED" to 2,
    )

private fun looksGerman(value: String): Boolean =
    Regex("[äöüß]").containsMatchIn(value) || Regex("^[A-ZÄÖÜ][a-zäöüß]+ ").containsMatchIn(value)

class AllClientMessagesCatalogTest :
    FunSpec({
        val constants by lazy { clientStringConstants() }
        val messages by lazy {
            clientKotlinFiles()
                .flatMap { file ->
                    extractMessages(fileName = file.name, text = file.readText(), constants = constants)
                }.filter {
                    it.msgid !in
                        EXEMPT_MSGIDS
                }
        }
        val msgids by lazy { messages.map { it.msgid }.distinct() }
        val template by lazy { parseCatalog(File(CATALOG_DIR, "messages.pot")) }
        val catalogs by lazy { CATALOG_LANGUAGES.associateWith { parseCatalog(File(CATALOG_DIR, "messages-$it.po")) } }

        test("the scan sees the client (not vacuous): thousands of calls, hundreds of files") {
            clientKotlinFiles().size shouldBeGreaterThan 100
            messages.size shouldBeGreaterThan 4000
            msgids.size shouldBeGreaterThan 3000
        }

        test("no extracted message is blank") {
            messages.filter { it.msgid.isBlank() }.map { "${it.file}:${it.line}" }.shouldBeEmpty()
        }

        test("every message of the client is in the template and in all seven catalogs") {
            val missing =
                msgids.flatMap { id ->
                    buildList {
                        if (!template.containsKey(id)) add("pot: \"$id\"")
                        CATALOG_LANGUAGES.forEach { lang -> if (!catalogs.getValue(lang).containsKey(id)) add("$lang: \"$id\"") }
                    }
                }
            missing.shouldBeEmpty()
        }

        test("every message is translated (non-blank) in all seven catalogs") {
            val untranslated =
                CATALOG_LANGUAGES.flatMap { lang ->
                    msgids.filter { catalogs.getValue(lang)[it]?.isBlank() == true }.map { "$lang: \"$it\"" }
                }
            untranslated.shouldBeEmpty()
        }

        test("no catalog entry at all has an empty translation (also entries no scan reaches)") {
            val empty =
                CATALOG_LANGUAGES.flatMap { lang ->
                    catalogs
                        .getValue(lang)
                        .filter { (id, str) -> id.isNotEmpty() && str.isBlank() }
                        .keys
                        .map { "$lang: \"$it\"" }
                }
            empty.shouldBeEmpty()
        }

        test("every translation keeps the %N placeholders of its msgid") {
            val placeholder = Regex("""%\d""")
            val broken =
                CATALOG_LANGUAGES.flatMap { lang ->
                    msgids
                        .filter { id ->
                            val translated = catalogs.getValue(lang)[id] ?: return@filter false
                            placeholder.findAll(id).map { it.value }.toSet() != placeholder.findAll(translated).map { it.value }.toSet()
                        }.map { "$lang: \"$it\"" }
                }
            broken.shouldBeEmpty()
        }

        test(
            "no German literal reaches the screen as a ternary/elvis/ifBlank/option-pair fallback without tr()/gettext(), and the rule sees one",
        ) {
            val findings =
                clientKotlinFiles().flatMap { file ->
                    file
                        .readLines()
                        .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                        .filter { line -> RAW_GERMAN_FALLBACK.any { it.containsMatchIn(line) } }
                        .map { file.name to it.trim() }
                }
            val byFile = findings.groupBy({ it.first }, { it.second })
            val problems =
                (byFile.keys + RAW_GERMAN_FALLBACK_BASELINE.keys).sorted().mapNotNull { file ->
                    val found = byFile[file].orEmpty()
                    val allowed = RAW_GERMAN_FALLBACK_BASELINE[file]?.first ?: 0
                    if (found.size != allowed) "$file: ${found.size} raw German fallback(s), baseline $allowed: $found" else null
                }
            problems.shouldBeEmpty()
            listOf(
                "    val label = if (resolved) \"Erledigt\" else \"Offen\"",
                "    val who = a ?: \"Unbekannt\"",
                "    x.joinToString(\", \").ifBlank { \"Keine\" }",
                "    val options = listOf(\"\" to \"-- keine --\")",
            ).forEach { sample -> (RAW_GERMAN_FALLBACK.any { it.containsMatchIn(sample) }) shouldBe true }
            listOf(
                "    val label = if (resolved) gettext(\"Erledigt\") else gettext(\"Offen\")",
                "    private fun initialLanguage(): String = localStorage[KEY] ?: \"de\"",
                "    val id = input?.id ?: \"lapis-field-1\"",
            ).forEach { sample -> (RAW_GERMAN_FALLBACK.any { it.containsMatchIn(sample) }) shouldBe false }
        }

        test("a German constant is not shown raw: every use is the argument of tr()/gettext(), or is in the audited ledger") {
            val constants = clientStringConstants().filterValues { looksGerman(it) }
            val code = clientKotlinFiles().joinToString("\n") { withoutCommentLines(it.readText()) }
            val counts =
                constants.keys
                    .associateWith { name ->
                        Regex("""(?<![\w"])${Regex.escape(name)}\b""")
                            .findAll(code)
                            .count { use ->
                                val lineStart = code.lastIndexOf('\n', use.range.first) + 1
                                val line =
                                    code.substring(
                                        lineStart,
                                        code.indexOf('\n', use.range.first).let {
                                            if (it <
                                                0
                                            ) {
                                                code.length
                                            } else {
                                                it
                                            }
                                        },
                                    )
                                val before = code.substring(maxOf(0, use.range.first - 40), use.range.first)
                                // `tr(NAME)` and `tr(Owner.NAME)` are wrapped uses
                                !line.contains("val $name") && !Regex("""(?:tr|gettext)\(\s*(?:[A-Za-z_]\w*\.)*$""").containsMatchIn(before)
                            }
                    }.filterValues { it > 0 }
            val problems =
                (counts.keys + UNWRAPPED_CONSTANT_USES.keys).sorted().mapNotNull { name ->
                    val found = counts[name] ?: 0
                    val allowed = UNWRAPPED_CONSTANT_USES[name] ?: 0
                    if (found != allowed) "$name: $found use(s) outside tr()/gettext(), ledger $allowed" else null
                }
            withClue(problems.joinToString("\n")) { problems.shouldBeEmpty() }
        }

        test("the extractor reads a concatenation, a named constant and an escape, and ignores comments") {
            val source =
                """
                // tr("Comment call")
                val CONST_A = "Part one, " + "part two"
                val CONST_B = "Head " + CONST_A + "."
                fun f() {
                    tr("Simple \"quoted\" text")
                    gettext(
                        "Line one " +
                            "line two %1",
                        x,
                    )
                    tr(CONST_B)
                    tr(dynamicValue)
                }
                """.trimIndent()
            val consts = mapOf("CONST_A" to "Part one, part two", "CONST_B" to "Head Part one, part two.")
            extractMessages(fileName = "X.kt", text = source, constants = consts).map { it.msgid } shouldBe
                listOf("Simple \"quoted\" text", "Line one line two %1", "Head Part one, part two.")
            unescapeKotlin("a\\nb\\u2013c\\\$d") shouldBe "a\nb–c\$d"
        }

        test("the constant evaluator resolves constants that refer to constants") {
            clientStringConstants().getValue("SEPA_MANDATE_CONFLICT_MESSAGE").contains("Monate") shouldBe true
        }
    })
