package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Security audit W6b follow-up round 3 (major finding A): [sanitizeUntrustedI18nText]'s own KDoc
 * (`I18nCatalogManager.kt`) says KVision's `Widget` resolves ANY widget content that starts with
 * `KV_I18N_MARKER` through `I18n.trans` on render -- so EVERY server-/member-controlled field handed to a
 * `div`/`span`/`p`/`h1..h6` call as plain widget content must go through [sanitizeUntrustedI18nText] first,
 * unconditionally, or a forged marker + money-sentinel payload renders as a fabricated, freely chosen amount.
 * Before this test, the rule was enforced at exactly two call sites in the whole client
 * (`grep -rn sanitizeUntrustedI18nText lapis-client/src/jsMain` -- `MotionsScreen.kt` only) and nothing caught
 * a new screen skipping it.
 *
 * Round 3 left [KNOWN_UNSANITIZED_WIDGET_TEXT_CALLS] at 58 (fixing every one in one pass was judged too large a
 * change to land and verify safely in one round). Round 4 (`network.lapis.cloud.client.UntrustedText`) closed the
 * engstelle instead of repeating the same manual `sanitizeUntrustedI18nText(...)` wrap 58 more times: five thin
 * helpers (`untrustedDiv`/`untrustedSpan`/`untrustedP`/`untrustedHeading`/`untrustedCardTitle`) each sanitize
 * unconditionally, and every one of the 58 round-3 findings was migrated onto one of them, bringing the ledger to
 * [KNOWN_UNSANITIZED_WIDGET_TEXT_CALLS] = 1. The single remaining entry, `ApiKeysScreen.kt` (`result.rawKey`), is
 * a deliberate, documented exception (see the inline comment at that call site): it is the actual secret value
 * shown to the operator exactly once, and silently stripping bytes from it would risk a corrupted key being
 * copied -- sanitizing it is the wrong fix, not a missed one.
 *
 * This remains a LEDGER test, not a zero-tolerance gate, so a genuinely new, justified exception can still be
 * added by raising [KNOWN_UNSANITIZED_WIDGET_TEXT_CALLS] together with an inline comment at the call site (as
 * with `rawKey`) -- but the constant may otherwise only go DOWN as call sites are migrated onto the helpers
 * above (or wrapped in [sanitizeUntrustedI18nText] directly). A PR that adds a NEW unsanitized call site, or
 * reverts a fixed one, fails this test.
 *
 * The detector is necessarily a heuristic (regex over source text, like [ClientMoneyFormatTripwireTest]): it
 * only catches a DIRECT `receiver.call(dotted.field.access)` argument, not one first assigned to a local `val`
 * or wrapped in an intermediate helper -- false negatives are possible, false positives (a genuinely trusted
 * dotted constant, e.g. an enum member) are handled by excluding them from the ledger count explicitly if they
 * ever show up, not by weakening the regex. Known gap, not closed by this round: a local `val` holding an
 * untrusted DTO field before it reaches a widget call (`val label = donor.displayName; row.div(label)`) is
 * invisible to this regex -- see the "Known gaps" note in `docs/architecture/ui-ux-guideline.adoc`, section
 * "Untrusted widget text (security rounds 1-4)".
 *
 * Round 5 (money-forgery hardening follow-up, major finding): the construction-time shape above -- `receiver.div(x)`
 * -- is only HALF of how untrusted text reaches `Widget`/`Template.content`. The other half is a plain ASSIGNMENT
 * to an already-constructed widget, `widget.content = dtoField.field`, which hits the exact same render-time
 * resolution path (see [sanitizeUntrustedI18nText]'s KDoc on `I18nCatalogManager.kt`) but has no call-with-parens
 * shape for [RAW_DOTTED_WIDGET_TEXT_CALL] to match. [RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT] is a second, independent
 * detector + ledger for exactly that shape; [network.lapis.cloud.client.untrustedContent] is its sanitizing helper
 * (analogous to `untrustedDiv`/etc. for the construction-time shape).
 */
private val WIDGET_TEXT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private fun isWidgetTextCommentLine(line: String): Boolean =
    line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

/**
 * `receiver.div(...)`/`span(...)`/`p(...)`/`h1(...)`..`h6(...)`/`link(...)` whose FIRST argument is a raw dotted
 * field access (`foo.bar`, `foo.bar.baz`) -- a server-/member-/DTO-controlled value handed straight to KVision as
 * widget content, with no sanitizing wrapper of any kind. A bare single-word local (no dot) is excluded on
 * purpose: that shape is far too common for trusted locals (`tr(...)` results, loop indices, etc.) to serve as a
 * signal.
 *
 * Security audit W6b, round 7 (major finding 1): `link` was missing from this list even though [io.kvision.html.Link]
 * (built via KVision's `link(...)` DSL function) renders its `label` through the exact same
 * `Widget.translate`/`I18n.trans` sink as `Tag.content` -- `container.link(document.title, url = ..., dataNavigo = ...)`
 * (`DocumentsScreen.kt`) and `receiptRow.link(receipt.originalFilename, url = ..., target = ...)`
 * (`TravelExpenseScreen.kt`/`TravelExpenseApprovalsScreen.kt`) passed a raw dotted field as `link`'s first
 * argument, invisibly, because the detector only looked for `div|span|p|h1..h6`. `link` takes several
 * positional-capable parameters (`label`, `url`, `icon`, ...), so the pattern below only requires the raw dotted
 * field IMMEDIATELY after the opening paren -- it does not need to be the call's only argument, unlike the
 * single-argument shape the other tags use.
 */
private val RAW_DOTTED_WIDGET_TEXT_CALL =
    Regex(
        """\.(?:div|span|p|h1|h2|h3|h4|h5|h6)\(\s*[a-zA-Z_]\w*(?:\.[a-zA-Z_]\w*)+\s*\)""" +
            """|\.link\(\s*[a-zA-Z_]\w*(?:\.[a-zA-Z_]\w*)+\s*[,)]""",
    )

/**
 * The ASSIGNMENT counterpart to [RAW_DOTTED_WIDGET_TEXT_CALL] (round 5): `receiverWidget.content = dtoField.field`
 * -- an already-constructed widget's `content` (see `io.kvision.html.Template`) overwritten with a raw dotted
 * field access, no sanitizing wrapper. Requires the receiver-dot before `content` on purpose (`widgetVar.content =`,
 * not a bare `content = ...` inside a builder-lambda's own property scope) -- that receiver-qualified shape is what
 * the round-5 finding actually reported and is what [network.lapis.cloud.client.untrustedContent] targets; a bare
 * in-lambda `content = ...` assignment is a related but distinct shape (harder to distinguish reliably from a
 * generic reusable function's `content` parameter default without risking false positives on genuinely trusted
 * `tr(...)`-result call sites) and is a documented, not-yet-automated known gap -- see the "Known gaps" note in
 * `docs/architecture/ui-ux-guideline.adoc`.
 */
private val RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT =
    Regex("""\w\.content\s*=\s*[a-zA-Z_]\w*(?:\.[a-zA-Z_]\w*)+""")

/**
 * Round 8 (major finding, follow-up to round 7): `Select`/`SimpleSelect`'s `options: List<StringPair>?` is the
 * third sink [RAW_DOTTED_WIDGET_TEXT_CALL]/[RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT] never covered -- an option's label
 * (rendered as `<option>` text through the exact same `Widget.translate`/`I18n.trans` path) is built almost
 * exclusively in this codebase via the `it.<idField> to it.<labelField>` pair shape (`it.id to it.displayName`,
 * `it.id to it.name`, `it.id to it.title`, `it.id to it.label`, ...) inside a `.map { ... }` feeding `options =`/
 * `.options =`. [RAW_OPTIONS_LABEL_MAP] catches that shape directly, independent of the specific field names, since
 * unlike [RAW_DOTTED_WIDGET_TEXT_CALL] it does not need to anchor on a fixed receiver-call name.
 * [network.lapis.cloud.client.untrustedOptions] is the sanitizing helper (analogous to `untrustedDiv`/etc.):
 * sanitizes only the label half of every pair, leaves the id/value half untouched.
 *
 * The trailing `(?!\()` (with a POSSESSIVE `\w*+`, not the default backtracking `\w*` -- Java's regex engine
 * supports possessive quantifiers, and only a possessive one actually blocks backtracking into a shorter match)
 * excludes `it.name to it.someLabelFn()`: a method call already returning a translated (`tr(...)`/`gettext(...)`)
 * label, not a raw field access -- e.g. `CateringScreen.kt`'s `it.name to it.cateringOrderStatusLabel()`. Without
 * the possessive quantifier, `\w*` backtracks one character short of the `(` and still reports a match.
 */
private val RAW_OPTIONS_LABEL_MAP =
    Regex("""\bit\.[a-zA-Z_]\w*\s+to\s+it\.[a-zA-Z_]\w*+(?!\()""")

/**
 * Any of these wrapping the same line means the argument is already resolved/sanitized -- not a violation.
 * Round 4 added the five [network.lapis.cloud.client]`.untrusted*` helpers: each sanitizes its `text` argument
 * unconditionally, so a call site that has been migrated onto one of them is safe even though it no longer
 * matches [RAW_DOTTED_WIDGET_TEXT_CALL] in the first place (an extra `untrusted` prefix sits between the
 * receiver and the opening paren). Listed anyway, explicitly, so the exclusion is documented rather than
 * implicit -- and so the "detector recognizes the new helpers as already-safe" test below has something
 * concrete to assert against. Round 5 added `untrustedContent(` for the assignment shape.
 */
private val ALREADY_SAFE =
    Regex(
        """sanitizeUntrustedI18nText\(|\btr\(|\bgettext\(|\btrFormat\(|""" +
            """\buntrustedDiv\(|\buntrustedSpan\(|\buntrustedP\(|\buntrustedHeading\(|\buntrustedCardTitle\(|""" +
            """\buntrustedContent\(|\buntrustedLink\(|\buntrustedOptions\(""",
    )

private fun rawUnsanitizedWidgetTextCalls(file: File): List<String> =
    file
        .readLines()
        .filterNot { isWidgetTextCommentLine(it) }
        .filter { RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn(it) && !ALREADY_SAFE.containsMatchIn(it) }
        .map { it.trim() }

private fun rawUnsanitizedWidgetTextAssignments(file: File): List<String> =
    file
        .readLines()
        .filterNot { isWidgetTextCommentLine(it) }
        .filter { RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT.containsMatchIn(it) && !ALREADY_SAFE.containsMatchIn(it) }
        .map { it.trim() }

private fun rawUnsanitizedOptionsLabelMaps(file: File): List<String> =
    file
        .readLines()
        .filterNot { isWidgetTextCommentLine(it) }
        .filter { RAW_OPTIONS_LABEL_MAP.containsMatchIn(it) && !ALREADY_SAFE.containsMatchIn(it) }
        .map { it.trim() }

/**
 * Ledger, W6b round 4: only ever lowered as call sites are migrated onto the `untrusted*` helpers (or wrapped in
 * [sanitizeUntrustedI18nText] directly) -- with the single documented exception of `ApiKeysScreen.kt`'s
 * `result.rawKey` (see the class KDoc above). Recount with `rawUnsanitizedWidgetTextCalls` over every `.kt` file
 * under `lapis-client/src/jsMain/kotlin` after fixing a batch -- never raise this constant to make a new
 * violation pass without an inline justification comment at the call site, matching `rawKey`'s.
 */
private const val KNOWN_UNSANITIZED_WIDGET_TEXT_CALLS = 1

/**
 * Ledger, W6b round 5 (assignment shape, [RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT]): four of the five round-5 findings
 * (`BankAccountsScreen.kt` `outcome.bankPrompt`, `AccountingExportScreen.kt` `disclaimer.text`/`requestedProvider.displayName`,
 * `ConferenceScreen.kt` `d.text`) were migrated onto [network.lapis.cloud.client.untrustedContent] and stay there.
 *
 * Round 6 review (major finding, regression) reverted the fifth, `EventsScreen.kt` `result.message`: it is a
 * deliberate, documented exception, not a missed migration. `result.message` is always constructed via `tr(...)`/
 * `gettext(...)` in `EventFormValidation.kt` -- it already carries the `KV_I18N_MARKER` prefix that
 * [sanitizeUntrustedI18nText] strips as part of neutralizing a forged marker. Routing it through
 * [network.lapis.cloud.client.untrustedContent] (as round 5 mistakenly did) strips that legitimate marker too and
 * silently breaks translation resolution: every non-German locale would render the raw German msgid instead of
 * the translated validation message. See the inline comment at the `EventsScreen.kt` call site and the "Known
 * gaps" note in `docs/architecture/ui-ux-guideline.adoc`. This brings the ledger back up to 1 -- analogous to
 * `ApiKeysScreen.kt`'s `result.rawKey` exception in [KNOWN_UNSANITIZED_WIDGET_TEXT_CALLS] above. Recount with
 * `rawUnsanitizedWidgetTextAssignments` after fixing a batch; never raise further without an inline justification
 * comment at the call site.
 */
private const val KNOWN_UNSANITIZED_WIDGET_TEXT_ASSIGNMENTS = 1

/**
 * Ledger, W6b round 8 ([RAW_OPTIONS_LABEL_MAP]). Originally two documented exceptions, both an enum's own `.name`
 * mapped to itself (`it.name to it.name`) -- a compile-time-fixed identifier, never a server-/member-controlled
 * field, so there was nothing to sanitize: `DocumentsScreen.kt` (`DocumentsAuthzUi.allowedCreateLevels(role)`, a
 * `DocumentAccessLevel` enum) and `MemberDirectCreationForm.kt` (`selectableRolesFor(callerRole)`, an
 * `AccountRole` enum).
 *
 * Welle V1.9.1 fixed the `DocumentsScreen.kt` one for an UNRELATED reason (a labeling bug, not a security finding):
 * the access-level dropdown showed the raw enum constant (`"PUBLIC_MEMBERS"`) instead of a translated label -- now
 * `it.name to documentAccessLevelLabel(it)` ([DocumentAccessLabels.kt]), which no longer matches
 * [RAW_OPTIONS_LABEL_MAP] at all (the right-hand side is a function call, not `it.<field>`). One documented
 * exception remains: `MemberDirectCreationForm.kt`. Every other `it.<field> to it.<field>` pair found in the
 * initial full-codebase sweep (21+ call sites, see [network.lapis.cloud.client.untrustedOptions] KDoc) was migrated
 * onto that helper. Recount with `rawUnsanitizedOptionsLabelMaps` after fixing a batch; never raise further without
 * an inline justification comment at the call site, matching the remaining exception above.
 */
private const val KNOWN_UNSANITIZED_OPTIONS_LABEL_MAPS = 1

// ============================================================================================================
// V1.9.17: table cells, dropDown labels, Tabulator (rules T-R1 .. T-R5)
// ============================================================================================================

/**
 * V1.9.17: KVision's `Cell`/`HeaderCell`/`Row` are `Tag`s -- `Tag.render` resolves a leading `###KvI18nS###` /
 * `###KvI18nP###` marker of ANY content string through `I18n.trans` (proved by `TableCellI18nMarkerDomTest`). The
 * rules below make `textCell`/`numCell`/`cellText` (`DataScreenLayout.kt`) the only way to put text into a table cell.
 * None of them has an ignore list: a new justified exception needs a new rule, not a softer regex.
 */
private fun tableCellScannedFiles(files: List<File>): List<File> =
    files.filter {
        it.name != "DataScreenLayout.kt" &&
            it.name != "DataTable.kt"
    }

/** T-R1: `cell(<anything>)` / `cell(content = ...)`. `cell()` and `cell { ... }` stay legal; `x.cell(...)` is a property call, not KVision's. */
private val RAW_CELL_TEXT_CALL = Regex("""(?<![\w.])cell\(\s*(?:content\s*=\s*)?[^)\s]""")

/** T-R2: a `Cell(...)` constructor call (not `HeaderCell(`): the other way to hand text to a cell. */
private val CELL_CONSTRUCTOR_CALL = Regex("""(?<![\w.])Cell\(""")

/** T-R3: `dropDown(variable, ...)` / `dropDown(a.b, ...)` -- a label that is a bare variable or field, not a call. */
private val DROPDOWN_VARIABLE_LABEL = Regex("""\bdropDown\(\s*[a-zA-Z_][\w.]*\s*[,)]""")

/** What makes a dropDown label variable acceptable within three lines: sanitized, or a resolved tr/gettext text. */
private val DROPDOWN_LABEL_SAFE = Regex("""sanitizeUntrustedI18nText\(|\btr\(|\bgettext\(""")

/** T-R4: any Tabulator import (no Tabulator use exists; its cell formatters are a separate sink that needs its own review). */
private val TABULATOR_IMPORT = Regex("""^\s*import\s+io\.kvision\.tabulator""", RegexOption.MULTILINE)

/** T-R5: sinks inside a `DataColumn(...)` block: `span(`/`div(`/`p(` (any receiver) and `.content =`. */
private val COLUMN_TEXT_SINK = Regex("""(?<![\w])(?:span|div|p)\(""")
private val COLUMN_CONTENT_ASSIGNMENT = Regex("""\.content\s*=\s*([^\n]*)""")
private val DATA_COLUMN_CALL = Regex("""(?<![\w.])DataColumn(?:<[^>]*>)?\(""")

/**
 * An argument of a `span(`/`div(`/`p(` call inside a `DataColumn` that is allowed: a plain string literal without a
 * template, or anything that runs through a text helper -- `tr`/`gettext`/`ngettext`/`trFormat`/`trusted`/
 * `sanitizeUntrustedI18nText`/`cellText`/`untrusted*`, or a developer formatter by naming convention
 * (`format*`, `*Token`, `*Label*`) whose result is built by `gettext` (which sanitizes its string arguments) or
 * `tr`. A bare field, a variable or a `.toString()` of one is not.
 */
private val COLUMN_TEXT_SAFE =
    Regex(
        """\b(?:tr|gettext|ngettext|trusted|trFormat|sanitizeUntrustedI18nText|cellText|untrusted\w*)\(""" +
            """|\b(?:format[A-Z]\w*|\w+Token|\w+Label\w*)\(""",
    )

/** Index of the `)` matching the `(` at [open], skipping string/char literals; -1 if unbalanced. */
private fun matchingParen(
    text: String,
    open: Int,
): Int {
    var depth = 0
    var i = open
    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("\"\"\"", i) -> {
                val end = text.indexOf("\"\"\"", i + 3)
                if (end < 0) return -1
                i = end + 2
            }
            c == '"' -> {
                i++
                while (i < text.length && text[i] != '"') {
                    if (text[i] == '\\') i++
                    i++
                }
            }
            c == '\'' -> {
                i++
                while (i < text.length && text[i] != '\'') {
                    if (text[i] == '\\') i++
                    i++
                }
            }
            c == '(' -> depth++
            c == ')' -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

private fun stripCommentLines(text: String): String = text.lines().joinToString("\n") { if (isWidgetTextCommentLine(it)) "" else it }

private fun rawCellTextCalls(text: String): List<String> =
    stripCommentLines(text).lines().filter { RAW_CELL_TEXT_CALL.containsMatchIn(it) }.map { it.trim() }

private fun cellConstructorCalls(text: String): List<String> =
    stripCommentLines(text).lines().filter { CELL_CONSTRUCTOR_CALL.containsMatchIn(it) }.map { it.trim() }

private fun unsafeDropDownLabels(text: String): List<String> {
    val lines = stripCommentLines(text).lines()
    return lines
        .withIndex()
        .filter { (index, line) ->
            DROPDOWN_VARIABLE_LABEL.containsMatchIn(line) &&
                (maxOf(0, index - 3)..minOf(lines.lastIndex, index + 3)).none { DROPDOWN_LABEL_SAFE.containsMatchIn(lines[it]) }
        }.map { it.value.trim() }
}

private fun unsafeDataColumnTexts(text: String): List<String> {
    val source = stripCommentLines(text)
    val findings = mutableListOf<String>()
    DATA_COLUMN_CALL.findAll(source).forEach { column ->
        val open = column.range.last
        val close = matchingParen(text = source, open = open)
        if (close < 0) return@forEach
        val block = source.substring(open, close + 1)
        COLUMN_TEXT_SINK.findAll(block).forEach { sink ->
            val argOpen = sink.range.last
            val argClose = matchingParen(text = block, open = argOpen)
            if (argClose < 0) return@forEach
            val argument = block.substring(argOpen + 1, argClose).trim()
            val namedNonContent = Regex("""^\w+\s*=""").containsMatchIn(argument) && !argument.startsWith("content")
            val literal = argument.startsWith("\"") && !argument.contains("\${") && argument.indexOf('"', 1) == argument.length - 1
            val safe = COLUMN_TEXT_SAFE.containsMatchIn(argument)
            if (argument.isNotEmpty() &&
                !namedNonContent &&
                !literal &&
                !safe
            ) {
                findings += block.substring(sink.range.first, argClose + 1).replace(Regex("\\s+"), " ")
            }
        }
        COLUMN_CONTENT_ASSIGNMENT.findAll(block).forEach { assignment ->
            if (!COLUMN_TEXT_SAFE.containsMatchIn(assignment.groupValues[1])) findings += assignment.value.trim()
        }
    }
    return findings
}

/**
 * T-R6 (review round): receiver-LESS `span(x.y)` / `div(x.y ?: "-")` inside a `Container.` extension or a `cell { }` /
 * `table.row { }` lambda. [RAW_DOTTED_WIDGET_TEXT_CALL] needs a `.` before the tag name and therefore never saw these.
 * The first argument is a dotted field access, optionally followed by an elvis fallback.
 */
private val RAW_RECEIVERLESS_DOTTED_CALL =
    Regex("""(?<![\w.])(?:span|div|p|h2|h3|h4|h5|h6)\(\s*[a-zA-Z_]\w*(?:\??\.[a-zA-Z_]\w*)+\s*(?:\?:[^)\n]*)?[,)]""")

private fun rawReceiverlessDottedCalls(text: String): List<String> =
    stripCommentLines(text)
        .lines()
        .filter { RAW_RECEIVERLESS_DOTTED_CALL.containsMatchIn(it) && !ALREADY_SAFE.containsMatchIn(it) }
        .map { it.trim() }

private val CELL_BLOCK_CALL = Regex("""(?<![\w])cell\s*\{""")

/** Index of the `}` matching the `{` at [open], skipping string/char literals; -1 if unbalanced. */
private fun matchingBrace(
    text: String,
    open: Int,
): Int {
    var depth = 0
    var i = open
    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("\"\"\"", i) -> {
                val end = text.indexOf("\"\"\"", i + 3)
                if (end < 0) return -1
                i = end + 2
            }
            c == '"' -> {
                i++
                while (i < text.length && text[i] != '"') {
                    if (text[i] == '\\') i++
                    i++
                }
            }
            c == '\'' -> {
                i++
                while (i < text.length && text[i] != '\'') {
                    if (text[i] == '\\') i++
                    i++
                }
            }
            c == '{' -> depth++
            c == '}' -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

/** T-R7: the same sink rule as T-R5, applied to the body of every `cell { ... }` block (manually built table cells). */
private fun unsafeCellBlockTexts(text: String): List<String> {
    val source = stripCommentLines(text)
    val findings = mutableListOf<String>()
    CELL_BLOCK_CALL.findAll(source).forEach { cell ->
        val open = cell.range.last
        val close = matchingBrace(text = source, open = open)
        if (close < 0) return@forEach
        val block = source.substring(open, close + 1)
        COLUMN_TEXT_SINK.findAll(block).forEach { sink ->
            val argOpen = sink.range.last
            val argClose = matchingParen(text = block, open = argOpen)
            if (argClose < 0) return@forEach
            val argument = block.substring(argOpen + 1, argClose).trim()
            val namedNonContent = Regex("""^\w+\s*=""").containsMatchIn(argument) && !argument.startsWith("content")
            val literal = argument.startsWith("\"") && !argument.contains("\${") && argument.indexOf('"', 1) == argument.length - 1
            if (argument.isNotEmpty() && !namedNonContent && !literal && !COLUMN_TEXT_SAFE.containsMatchIn(argument)) {
                findings += block.substring(sink.range.first, argClose + 1).replace(Regex("\\s+"), " ")
            }
        }
    }
    return findings
}

class ClientUntrustedWidgetTextTripwireTest :
    FunSpec({
        val files = WIDGET_TEXT_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        test("the scan sees the client sources") {
            (files.size > 100) shouldBe true
            files.any { it.name == "MotionsScreen.kt" } shouldBe true
        }

        test("the detector recognizes a raw dotted field access handed to div/span/p/h1..h6, and a sanitized one") {
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn("""row.div(amendment.title) { addCssClasses("flex-grow-1") }""") shouldBe true
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn("""nameArea.span(row.displayName) { addCssClasses("fw-bold") }""") shouldBe true
            // wrapped in sanitizeUntrustedI18nText(...), the div( is no longer directly followed by a bare dotted
            // identifier chain (an extra "(" sits in between), so the raw detector itself does not match here --
            // that is exactly why the line-level ALREADY_SAFE check exists as a second, line-scoped signal (below).
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn("""row.div(sanitizeUntrustedI18nText(amendment.title))""") shouldBe false
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn("""panel.h2(tr("Vote")) { addCssClass("h5") }""") shouldBe false
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn("""panel.div(gettext("Quorum: %1%", committee.quorumPercent))""") shouldBe false
            ALREADY_SAFE.containsMatchIn(
                """row.div(sanitizeUntrustedI18nText(amendment.title)) { addCssClasses("flex-grow-1") }""",
            ) shouldBe
                true
        }

        test("the detector recognizes the round-4 untrusted* helpers as already-safe") {
            ALREADY_SAFE.containsMatchIn(
                """headerRow.untrustedCardTitle(order.description)""",
            ) shouldBe true
            ALREADY_SAFE.containsMatchIn(
                """row.untrustedDiv(mandate.memberDisplayName, className = "flex-grow-1")""",
            ) shouldBe true
            ALREADY_SAFE.containsMatchIn(
                """container.untrustedSpan(donor.displayName, className = "fw-bold")""",
            ) shouldBe true
            ALREADY_SAFE.containsMatchIn(
                """panel.untrustedP(motion.rationale, className = "mb-0")""",
            ) shouldBe true
            ALREADY_SAFE.containsMatchIn(
                """panel.untrustedHeading(provider.displayName, 2, className = "h5")""",
            ) shouldBe true
        }

        test("round 7: the detector recognizes a raw link(...) call, and untrustedLink(...) as already-safe") {
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn(
                """container.link(document.title, url = "javascript:void(0)", dataNavigo = false)""",
            ) shouldBe true
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn(
                """receiptRow.link(receipt.originalFilename, url = TravelExpenseHttp.receiptDownloadUrl(receipt.id), target = "_blank")""",
            ) shouldBe true
            // a link(...) call whose first argument is a trusted tr(...) result (a static action label, not a
            // server-/member-controlled field) must not be flagged.
            RAW_DOTTED_WIDGET_TEXT_CALL.containsMatchIn(
                """actions.link(tr("Herunterladen"), url = DocumentHttp.downloadUrl(document.id, version.id))""",
            ) shouldBe false
            ALREADY_SAFE.containsMatchIn(
                """container.untrustedLink(document.title, url = "javascript:void(0)", dataNavigo = false)""",
            ) shouldBe true
        }

        test("the detector still fires on the lambda-cell shape (container.span(row.field) inside a DataColumn cell)") {
            rawUnsanitizedWidgetTextCalls(
                File.createTempFile("dataColumnCell", ".kt").apply {
                    writeText("""cell = { container, entry -> container.span(entry.description) { addCssClass("fw-bold") } },""")
                    deleteOnExit()
                },
            ).size shouldBe 1
        }

        test("the count of raw, unsanitized DTO-field widget text calls only ever goes down from the W6b round 4 ledger") {
            val findings = files.flatMap { f -> rawUnsanitizedWidgetTextCalls(f).map { "${f.name}: $it" } }
            (findings.size <= KNOWN_UNSANITIZED_WIDGET_TEXT_CALLS) shouldBe true
            // Non-vacuity guard: the ledger being small must not mean the detector stopped detecting anything --
            // it must still recognize a deliberately reintroduced violation (never actually added to source).
            (findings.size >= 1) shouldBe true
            findings.any { it.startsWith("ApiKeysScreen.kt:") && it.contains("result.rawKey") } shouldBe true
            // Regression guards: round 3's MotionsScreen.kt fix and round 4's closed sites must never reappear.
            findings.none { it.startsWith("MotionsScreen.kt:") && it.contains("amendment.title") } shouldBe true
            findings.none { it.contains("post.authorDisplayName") } shouldBe true
            findings.none { it.contains("report.purpose") } shouldBe true
            findings.none { it.contains("entry.description") } shouldBe true
            // Regression guards, round 7: the three raw link(...) findings (major finding 1) must never reappear.
            findings.none { it.startsWith("DocumentsScreen.kt:") && it.contains("document.title") } shouldBe true
            findings.none { it.contains("receipt.originalFilename") } shouldBe true
        }

        test("the assignment detector recognizes widget.content = dotted.field, and a sanitized/helper one") {
            RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT.containsMatchIn(
                """tanPromptLabel.content = outcome.bankPrompt""",
            ) shouldBe true
            RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT.containsMatchIn(
                """scrollBox.content = d.text""",
            ) shouldBe true
            RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT.containsMatchIn(
                """untrustedContent(tanPromptLabel, outcome.bankPrompt)""",
            ) shouldBe false
            RAW_DOTTED_WIDGET_TEXT_ASSIGNMENT.containsMatchIn(
                """tanExpiryLabel.content = gettext("Gültig bis %1 Uhr.", outcome.expiresAt.toString())""",
            ) shouldBe false
            ALREADY_SAFE.containsMatchIn(
                """untrustedContent(tanPromptLabel, outcome.bankPrompt)""",
            ) shouldBe true
        }

        test("the count of raw, unsanitized DTO-field widget text ASSIGNMENTS only ever goes down from the W6b round 5 ledger") {
            val findings = files.flatMap { f -> rawUnsanitizedWidgetTextAssignments(f).map { "${f.name}: $it" } }
            (findings.size <= KNOWN_UNSANITIZED_WIDGET_TEXT_ASSIGNMENTS) shouldBe true
            // Documented exception (round 6): EventsScreen.kt's result.message is a trusted tr()/gettext() result,
            // not untrusted DTO content -- see the KNOWN_UNSANITIZED_WIDGET_TEXT_ASSIGNMENTS KDoc above.
            findings.any { it.startsWith("EventsScreen.kt:") && it.contains("result.message") } shouldBe true
            // Regression guards: the other four round-5 findings must never reappear as a raw assignment.
            findings.none { it.contains("outcome.bankPrompt") } shouldBe true
            findings.none { it.contains("requestedProvider.displayName") } shouldBe true
            findings.none { it.startsWith("AccountingExportScreen.kt:") && it.contains("disclaimer.text") } shouldBe true
            findings.none { it.startsWith("ConferenceScreen.kt:") && it.contains("d.text") } shouldBe true
            // Non-vacuity guard: the detector must still recognize a violation shape when one is deliberately present.
            (
                rawUnsanitizedWidgetTextAssignments(
                    File.createTempFile("assignmentShape", ".kt").apply {
                        writeText("""label.content = donor.displayName""")
                        deleteOnExit()
                    },
                ).size
            ) shouldBe 1
        }

        test("round 8: the detector recognizes a raw it.field to it.field options map, and untrustedOptions(...) as already-safe") {
            RAW_OPTIONS_LABEL_MAP.containsMatchIn(
                """panel.select(options = recipientCandidates.map { it.id to it.displayName }, label = tr("Empfänger"))""",
            ) shouldBe true
            RAW_OPTIONS_LABEL_MAP.containsMatchIn(
                """row.select(options = currentBreakoutRooms.map { it.id to it.label }, value = currentAssignmentId)""",
            ) shouldBe true
            ALREADY_SAFE.containsMatchIn(
                """panel.select(options = untrustedOptions(recipientCandidates.map { it.id to it.displayName }), label = tr("Empfänger"))""",
            ) shouldBe true
        }

        test("the count of raw, unsanitized DTO-field options label maps only ever goes down from the W6b round 8 ledger") {
            val findings = files.flatMap { f -> rawUnsanitizedOptionsLabelMaps(f).map { "${f.name}: $it" } }
            (findings.size <= KNOWN_UNSANITIZED_OPTIONS_LABEL_MAPS) shouldBe true
            // Documented exception (round 8, one remaining since Welle V1.9.1): an enum's own `.name` mapped to
            // itself, not untrusted DTO content -- see the KNOWN_UNSANITIZED_OPTIONS_LABEL_MAPS KDoc above.
            findings.any { it.startsWith("MemberDirectCreationForm.kt:") && it.contains("it.name to it.name") } shouldBe true
            // Regression guard: DocumentsScreen.kt's exception was fixed (a labeling bug, see the KDoc above) and
            // must never reappear raw.
            findings.none { it.startsWith("DocumentsScreen.kt:") && it.contains("it.name to it.name") } shouldBe true
            // Regression guards: the round-8 findings fixed via untrustedOptions(...) must never reappear raw.
            findings.none { it.startsWith("LtrLedgerScreen.kt:") && it.contains("it.id to it.displayName") } shouldBe true
            findings.none { it.startsWith("MotionsScreen.kt:") && it.contains("it.id to it.label") } shouldBe true
            // Non-vacuity guard: the detector must still recognize a violation shape when one is deliberately present.
            (
                rawUnsanitizedOptionsLabelMaps(
                    File.createTempFile("optionsLabelMapShape", ".kt").apply {
                        writeText("""val options = candidates.map { it.id to it.displayName }""")
                        deleteOnExit()
                    },
                ).size
            ) shouldBe 1
        }
        test("V1.9.17 T-R1: no raw cell(<text>) outside DataScreenLayout.kt -- text goes through textCell/numCell") {
            val findings = tableCellScannedFiles(files).flatMap { f -> rawCellTextCalls(f.readText()).map { "${f.name}: $it" } }
            findings shouldBe emptyList()
        }

        test("V1.9.17 T-R1 self-test: the detector fires on raw cell text, not on cell() / cell { } / textCell(...)") {
            rawCellTextCalls("""cell(line.voucherNumber)""").size shouldBe 1
            rawCellTextCalls("""    cell(content = level.name) { addCssClass("x") }""").size shouldBe 1
            rawCellTextCalls("""cell("x")""").size shouldBe 1
            rawCellTextCalls("""val c = cell()""").size shouldBe 0
            rawCellTextCalls("""cell { span("x") }""").size shouldBe 0
            rawCellTextCalls("""textCell(line.voucherNumber)""").size shouldBe 0
            rawCellTextCalls("""headerCell(tr("Konto"), required = true)""").size shouldBe 0
            rawCellTextCalls("""// cell(line.voucherNumber)""").size shouldBe 0
        }

        test("V1.9.17 T-R2: no Cell(...) constructor outside the helper -- the other way to hand text to a cell") {
            val findings = tableCellScannedFiles(files).flatMap { f -> cellConstructorCalls(f.readText()).map { "${f.name}: $it" } }
            findings shouldBe emptyList()
            cellConstructorCalls("""val c = Cell(content = x)""").size shouldBe 1
            cellConstructorCalls("""val h = HeaderCell(content = x)""").size shouldBe 0
        }

        test("V1.9.17 T-R3: a dropDown label that is a bare variable must be sanitized or a tr/gettext text nearby") {
            val findings = files.flatMap { f -> unsafeDropDownLabels(f.readText()).map { "${f.name}: $it" } }
            findings shouldBe emptyList()
            unsafeDropDownLabels("""rightNav.dropDown(accountLabel, icon = "fas fa-user", forNavbar = true) {""").size shouldBe 1
            unsafeDropDownLabels("""rightNav.dropDown(session.displayName, forNavbar = true) {""").size shouldBe 1
            unsafeDropDownLabels("""rightNav.dropDown(accountTriggerLabel(session), forNavbar = true) {""").size shouldBe 0
            unsafeDropDownLabels("""rightNav.dropDown(current.first.uppercase(), forNavbar = true) {""").size shouldBe 0
            unsafeDropDownLabels("val label = sanitizeUntrustedI18nText(name)\nx.dropDown(label) {}").size shouldBe 0
        }

        test("V1.9.17 T-R4: no Tabulator import -- its use needs its own security review") {
            val findings = files.filter { TABULATOR_IMPORT.containsMatchIn(it.readText()) }.map { it.name }
            // "Tabulator-Einsatz braucht eigenen Security-Review (V1.9.17)"
            findings shouldBe emptyList()
            TABULATOR_IMPORT.containsMatchIn("import io.kvision.tabulator.Tabulator\n") shouldBe true
            TABULATOR_IMPORT.containsMatchIn("// import io.kvision.tabulator.Tabulator\n") shouldBe false
        }

        test("V1.9.17 T-R5: text sinks inside a DataColumn block are sanitized helpers, literals or resolved tr/gettext texts") {
            val findings =
                files
                    .filter { it.name != "DataTableState.kt" && it.name != "DataTable.kt" }
                    .flatMap { f -> unsafeDataColumnTexts(f.readText()).map { "${f.name}: $it" } }
            findings shouldBe emptyList()
        }

        test("V1.9.17 T-R5 self-test: fires on raw fields, toString and templates; not on helpers, literals, tr/gettext") {
            fun column(body: String) = "DataColumn(title = tr(\"X\"), cell = { container, row -> $body })"
            unsafeDataColumnTexts(column("container.span(row.name)")).size shouldBe 1
            unsafeDataColumnTexts(column("container.div(row.count.toString())")).size shouldBe 1
            unsafeDataColumnTexts(column("container.span(\" \${row.title} \")")).size shouldBe 1
            unsafeDataColumnTexts(column("container.span(row.name) { addCssClass(\"fw-bold\") }")).size shouldBe 1
            unsafeDataColumnTexts(column("container.cell.content = row.name")).size shouldBe 1
            unsafeDataColumnTexts(column("container.span(\n row.name\n)")).size shouldBe 1
            unsafeDataColumnTexts(column("container.span(\"–\")")).size shouldBe 0
            unsafeDataColumnTexts(column("container.span(tr(\"Ja\"))")).size shouldBe 0
            unsafeDataColumnTexts(column("container.div(gettext(\"%1 · %2\", row.a, row.b))")).size shouldBe 0
            unsafeDataColumnTexts(column("container.span(formatDate(row.date))")).size shouldBe 0
            unsafeDataColumnTexts(column("container.cellText(row.name)")).size shouldBe 0
            unsafeDataColumnTexts(column("container.untrustedSpan(row.name)")).size shouldBe 0
            unsafeDataColumnTexts(column("container.div(className = \"x\")")).size shouldBe 0
            unsafeDataColumnTexts(column("container.span(costCenterLabel(row))")).size shouldBe 0
            // A paren inside a string literal must not derail the block scan.
            unsafeDataColumnTexts(column("container.span(\"(keine Zuordnung)\"); container.span(row.name)")).size shouldBe 1
            // Outside a DataColumn block the rule does not apply.
            unsafeDataColumnTexts("""container.span(row.name)""").size shouldBe 0
        }

        test("V1.9.17 T-R6: no receiver-less span/div/p(x.y) with a dotted field anywhere in the client") {
            // DataScreenLayout.kt is exempt (like T-R1): ReportCell.Text carries developer i18n marker texts that MUST be resolved.
            val findings =
                files
                    .filter { it.name != "DataScreenLayout.kt" }
                    .flatMap { f -> rawReceiverlessDottedCalls(f.readText()).map { "${f.name}: $it" } }
            findings shouldBe emptyList()
        }

        test("V1.9.17 T-R6 self-test: fires on receiver-less dotted args incl. elvis, not on helpers/literals/locals") {
            rawReceiverlessDottedCalls("""span(row.displayName)""").size shouldBe 1
            rawReceiverlessDottedCalls("""div(line.counterpartyName ?: "—")""").size shouldBe 1
            rawReceiverlessDottedCalls("""div(line.counterpartyName ?: "—") { addCssClass("x") }""").size shouldBe 1
            rawReceiverlessDottedCalls("""untrustedSpan(row.displayName)""").size shouldBe 0
            rawReceiverlessDottedCalls("""x.untrustedDiv(line.counterpartyName ?: "—")""").size shouldBe 0
            rawReceiverlessDottedCalls("""span(tr("Ja"))""").size shouldBe 0
            rawReceiverlessDottedCalls("""span(label)""").size shouldBe 0
            rawReceiverlessDottedCalls("""// span(row.displayName)""").size shouldBe 0
        }

        test("V1.9.17 T-R7: text sinks inside a cell { } block are sanitized helpers, literals or resolved tr/gettext texts") {
            val findings =
                files
                    .filter { it.name != "DataTableState.kt" && it.name != "DataTable.kt" && it.name != "DataScreenLayout.kt" }
                    .flatMap { f -> unsafeCellBlockTexts(f.readText()).map { "${f.name}: $it" } }
            findings shouldBe emptyList()
        }

        test("V1.9.17 T-R7 self-test: fires on raw text in a cell block, not on helpers, literals or tr") {
            unsafeCellBlockTexts("""table.row { cell { div(line.name ?: "—") } }""").size shouldBe 1
            unsafeCellBlockTexts("""cell { span(name) }""").size shouldBe 1
            unsafeCellBlockTexts("""cell { untrustedDiv(line.name ?: "—"); div(tr("x")); span("–") }""").size shouldBe 0
            unsafeCellBlockTexts("""div(line.name)""").size shouldBe 0
        }
    })
