package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.math.roundToInt

/**
 * Tripwires for the UI/UX guideline (Welle V1.4.25, W1 "Fundament", see
 * `docs/architecture/ui-ux-guideline.adoc`), in the pattern of [ClientTrAttributeLeakTest]: the client
 * sources and `theme.css` are scanned as text (`jsTest` under Karma has no file system), comment lines are
 * exempt, every rule proves itself against a positive and a negative example.
 *
 * ## Baselines are a debt ledger of fingerprints
 *
 * Only 3 of 67 screens were migrated in W1, so most rules still have known offenders. They are listed in
 * [BASELINE] **one fingerprint per finding**: rule -> file name -> the trimmed source line of each offending
 * spot (no line number, so unrelated edits above it do not disturb the ledger; a line that starts with `.`
 * -- a call chain broken before the dot -- is prefixed with the previous code line, otherwise `.table(` would
 * identify nothing). The comparison is a multiset per file. So the test fails
 *
 * - on a **new** finding (a violation the ledger does not list -- also when one old violation was fixed and
 *   a new one was added in the same file, which a plain per-file count could not see), and
 * - on a **paid-off** finding (its ledger line must be deleted, otherwise the ledger silently keeps a slot
 *   free for the next violation).
 *
 * A file that is not in the ledger is held strictly, so the three W1 screens (`MemberAdministrationScreen`,
 * `ContributionsScreen`, `OpenItemsScreen`) and the new building blocks are strict from day one. Editing a
 * ledgered line (renaming a variable on it) changes its fingerprint and turns the test red on purpose: the
 * author has to look at the debt and re-list or pay it. Two findings with the identical trimmed line in one
 * file are indistinguishable; the multiset still pins their number.
 *
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
private val CLIENT_DIR: File =
    File("../lapis-client/src/jsMain")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain") }

private val CLIENT_KOTLIN_DIR: File = File(CLIENT_DIR, "kotlin")

private val THEME_CSS: File = File(CLIENT_DIR, "resources/theme.css")

/** Rule ids of the source rules that report per-file findings (the CSS rules R11/R54 report messages). */
private const val R2 = "R2 no fixed width >= 600px"
private const val R9 = "R9 no colour literal (hex, rgb/rgba/hsl/hsla, 0xRRGGBB)"
private const val R14 = "R14 table without STRIPED+HOVER+SMALL"
private const val R15 = "R15 table without RESPONSIVE"
private const val R39 = "R39 icon-only button without title+aria-label"
private const val R55 = "R55 fixed px width (pseudo-table column)"

/**
 * The one `button("", icon = ...)` that is justified without `title`/`aria-label` in its own call: the
 * factory `tableActionButton` in `DataScreenLayout.kt` creates it and labels it in the very next statement
 * (`tableActionTooltip` sets `title` AND `aria-label`). Exempted by fingerprint, not by file: any other
 * icon-only button in that file is checked like everywhere else. `R39 justified exemption is still needed`
 * fails when the line is gone, so the exemption cannot outlive its reason.
 */
private val R39_JUSTIFIED: Map<String, Set<String>> =
    mapOf(
        "DataScreenLayout.kt" to setOf("val actionButton = button(\"\", icon = icon, style = style)"),
    )

/**
 * The debt ledger: rule -> file name -> the fingerprint of every remaining finding (format: see the class
 * comment above), each entry annotated with the wave that pays it off (W2-W5 of the UI/UX guideline
 * roll-out; the wave assignment by screen family is provisional -- the fingerprints are not).
 */
private val BASELINE: Map<String, Map<String, List<String>>> =
    mapOf(
        R2 to emptyMap(),
        R9 to
            mapOf(
                // W5 conference: semi-transparent overlay chip background
                "ConferenceScreen.kt" to
                    listOf(
                        "\"position:absolute;left:6px;bottom:6px;background:rgba(0,0,0,0.55);color:var(--lapis-media-overlay-text);\" +",
                        "\"position:absolute;left:6px;top:6px;background:rgba(0,0,0,0.55);color:var(--lapis-media-overlay-text);\" +",
                        "\"position:absolute;right:6px;top:6px;background:rgba(0,0,0,0.55);color:var(--lapis-media-overlay-text);\" +",
                    ),
                // whiteboard paper is a document colour (KDoc on WHITEBOARD_PAPER)
                "ConferenceWhiteboardController.kt" to
                    listOf(
                        "private const val WHITEBOARD_PAPER = \"#ffffff\"",
                    ),
                // Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- ArticleLabels.colorHex's four
                // status colours are the exact hex values the implementation plan specifies (a fixed status-
                // color legend, not a Bootstrap semantic badge shade) -- see that function's own KDoc.
                "ArticleLabels.kt" to
                    listOf(
                        "ArticleDisplayStatus.DRAFT -> \"#6B7280\"",
                        "ArticleDisplayStatus.SUBMITTED -> \"#2563EB\"",
                        "ArticleDisplayStatus.PUBLISHED -> \"#16A34A\"",
                        "ArticleDisplayStatus.REJECTED, ArticleDisplayStatus.UNPUBLISHED -> \"#DC2626\"",
                    ),
            ),
        R14 to
            mapOf(
                // W4 accounting export
                "AccountingExportScreen.kt" to
                    listOf(
                        "itemsPanel.table(",
                        "panel.table(",
                        "panel.table(",
                    ),
                // W4 banking
                "BankStatementImportScreen.kt" to
                    listOf(
                        "lineTable = lineTableHost.table(headerNames = headers, types = setOf(TableType.STRIPED, TableType.HOVER))",
                    ),
                // W4 dunning settings
                "DunningSettingsScreen.kt" to
                    listOf(
                        "listPanel.table(",
                    ),
                // W4 receivable dunning settings
                "ReceivableDunningSettingsScreen.kt" to
                    listOf(
                        "listPanel.table(",
                    ),
            ),
        R15 to
            mapOf(
                // W4 accounting export
                "AccountingExportScreen.kt" to
                    listOf(
                        "itemsPanel.table(",
                        "panel.table(",
                        "panel.table(",
                    ),
                // W4 banking
                "BankStatementImportScreen.kt" to
                    listOf(
                        "lineTable = lineTableHost.table(headerNames = headers, types = setOf(TableType.STRIPED, TableType.HOVER))",
                    ),
                // W4 dunning settings
                "DunningSettingsScreen.kt" to
                    listOf(
                        "listPanel.table(",
                    ),
            ),
        R39 to
            mapOf(
                // W5 shell / navigation
                "App.kt" to
                    listOf(
                        "navbar.button(",
                    ),
                // W5 conference
                "ConferenceScreen.kt" to
                    listOf(
                        "controlsRow.button(\"\", icon = \"fas fa-arrow-left\", style = ButtonStyle.PRIMARY).apply { addCssClass(\"ms-2\") }",
                        "controlsRow.button(\"\", icon = \"fas fa-display\", style = ButtonStyle.OUTLINESECONDARY)",
                        "overlayControls.button(\"\", icon = \"fas fa-expand\", style = ButtonStyle.OUTLINESECONDARY) { addCssClass(\"btn-sm\") }",
                        "val cameraButton = controlsRow.button(\"\", icon = \"fas fa-video\", style = ButtonStyle.OUTLINESECONDARY)",
                        "val chatToggleButton = controlsRow.button(\"\", icon = \"fas fa-comments\", style = ButtonStyle.OUTLINESECONDARY)",
                        "val leaveButton = controlsRow.button(\"\", icon = \"fas fa-phone-slash\", style = ButtonStyle.DANGER)",
                        "val micButton = controlsRow.button(\"\", icon = \"fas fa-microphone\", style = ButtonStyle.OUTLINESECONDARY)",
                        "val moreToggleButton = controlsRow.button(\"\", icon = \"fas fa-ellipsis\", style = ButtonStyle.OUTLINESECONDARY)",
                        "val rosterToggleButton = controlsRow.button(\"\", icon = \"fas fa-users\", style = ButtonStyle.OUTLINESECONDARY)",
                    ),
                // V1.9.4 own-uploaded-background remove (×) button -- same pattern as the conference control
                // buttons above: aria-label is set right after construction via RawAttributes, not inline in
                // the same call/lambda, so this scan cannot see it (no `title` at all, by design -- the tile's
                // own visible label ("Eigenes Bild") already names it, the × only needs an accessible name via
                // aria-label, not a redundant tooltip).
                "ConferenceBackgroundSection.kt" to
                    listOf(
                        "wrapper.button(\"\", icon = \"fas fa-xmark\", style = ButtonStyle.OUTLINEDANGER) {",
                    ),
            ),
        // Welle V1.4.27 (W3) / V1.4.31 (W5): the pseudo-table columns (`width = N.px` on a row/header cell). W5 paid off the last
        // four ledger entries: AuctionScreen, CrowdfundingScreen and PoliticianScreen became `dataTable`s
        // (PseudoTableGoldenDomTest), and SocialNetworkScreen's boost amount input (a form field, not a table) lost its fixed width.
        // The ledger is EMPTY: any `width = N.px` in the client is a new finding.
        R55 to emptyMap(),
    )

// ── scanning primitives ───────────────────────────────────────────────────────────────────────────────

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

/** Source with comment lines blanked (line numbers and offsets stay meaningful for the call extraction). */
private fun codeOnly(text: String): String = text.lines().joinToString(separator = "\n") { if (isCommentLine(it)) "" else it }

/**
 * The ledger fingerprint of the finding at [offset] of the comment-blanked [code]: the trimmed line, or --
 * for a line that starts with `.` (call chain broken before the dot) -- the previous code line plus it.
 */
internal fun fingerprintAt(
    code: String,
    offset: Int,
): String {
    val lines = code.lines()
    val lineIndex = code.substring(startIndex = 0, endIndex = offset).count { it == '\n' }
    val line = lines[lineIndex].trim()
    if (!line.startsWith(".")) return line
    val previous =
        lines
            .take(lineIndex)
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
    return "$previous $line"
}

/**
 * The text of the call whose `(` is at [openParen], through its balanced `)` and -- if one follows directly
 * -- the trailing lambda `{ ... }`. Strings are not parsed: the scanned call sites contain no unbalanced
 * parentheses inside string literals; a mismatch would show as an empty/short extraction and fail a rule
 * loudly rather than pass silently.
 */
private fun callWithTrailingLambda(
    text: String,
    openParen: Int,
): String {
    fun balanced(
        from: Int,
        open: Char,
        close: Char,
    ): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            if (text[i] == open) depth++
            if (text[i] == close) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return text.length - 1
    }
    val closeParen = balanced(from = openParen, open = '(', close = ')')
    var next = closeParen + 1
    while (next < text.length && text[next] == ' ') next++
    val end = if (next < text.length && text[next] == '{') balanced(from = next, open = '{', close = '}') else closeParen
    return text.substring(openParen, end + 1)
}

/**
 * `table(` as a call: not preceded by a letter/digit/underscore (so `standardTable(`, `dataTable(` and
 * `reasonAcceptable(` are not calls of the KVision `table`), but preceded by anything else -- including a `.`
 * at the start of a line (`panel\n    .table(`), the call chain the first version of this regex missed.
 */
private val TABLE_CALL = Regex("""(?<![A-Za-z0-9_])table\(""")
private val ANY_FIXED_WIDTH = Regex("""(?<![A-Za-z])width\s*=\s*(\d+)\.px""")
private val FIXED_WIDTH = Regex("""(?<![A-Za-z])width\s*=\s*(\d{3,})\.px""")
private val COLOUR_LITERAL = Regex("""#[0-9a-fA-F]{3,8}\b|\b(?:rgb|rgba|hsl|hsla)\(|\b0x[0-9a-fA-F]{6,8}\b""")

/** `button("", icon =`, also with the named-argument form `button(text = "", icon =` (and `Button(`). */
private val ICON_ONLY_BUTTON = Regex("""(?<![A-Za-z])[bB]utton\(\s*(?:text\s*=\s*)?""\s*,\s*icon\s*=""")

internal fun fixedWideWidthFindings(text: String): List<String> {
    val code = codeOnly(text)
    return FIXED_WIDTH
        .findAll(code)
        .filter {
            it.groupValues[1].toInt() >= 600
        }.map { fingerprintAt(code = code, offset = it.range.first) }
        .toList()
}

/**
 * R55: any `width = N.px` -- a fixed column width on a row or header cell is the mark of a pseudo-table
 * (`hPanel` rows built by hand), which the report/table grammar replaces. `maxWidth`/`minWidth` are excluded by
 * the lookbehind, exactly like R2.
 */
internal fun fixedWidthFindings(text: String): List<String> {
    val code = codeOnly(text)
    return ANY_FIXED_WIDTH.findAll(code).map { fingerprintAt(code = code, offset = it.range.first) }.toList()
}

/** `reportTable(` as a call (not `xreportTable(`); the declaration `fun Container.reportTable(` is skipped by its own text. */
private val REPORT_TABLE_CALL = Regex("""(?<![A-Za-z0-9_])reportTable\(""")

/** Anything that would give a report table sort controls: an `onSort` handler, sort state/options, a sort key. */
private val SORT_WIRING = Regex("""\b(?:onSort|sortOptions|sortKey|focusSortKey)\b|\bsort\s*=""")

/**
 * C7c (audit V1.4.27): a `reportTable(...)` call never carries sort wiring. A report is a DOCUMENT (guideline: the
 * grammar has NO sort buttons and no card list); sortable rows belong to `dataTable`. Honest limit: this is a text
 * check of the call's own argument list and trailing lambda -- it does not follow a table that is later passed to
 * other code, and a sort control added by a separate statement after the call is not seen. The runtime side is
 * `ReportTableDomTest.reportTable_hasNoSortButtonsAndNoCardList`.
 */
internal fun reportTableSortFindings(text: String): List<String> {
    val code = codeOnly(text)
    return REPORT_TABLE_CALL
        .findAll(code)
        // The declaration `fun Container.reportTable(caption, headers, ...)` is not a call site.
        .filter { !code.substring(maxOf(0, it.range.first - 20), it.range.first).contains("fun Container.") }
        .filter { SORT_WIRING.containsMatchIn(callWithTrailingLambda(text = code, openParen = it.range.last)) }
        .map { fingerprintAt(code = code, offset = it.range.first) }
        .toList()
}

internal fun colourLiteralFindings(text: String): List<String> {
    val code = codeOnly(text)
    return COLOUR_LITERAL.findAll(code).map { fingerprintAt(code = code, offset = it.range.first) }.toList()
}

private class TableCall(
    val text: String,
    val fingerprint: String,
)

private fun tableCalls(text: String): List<TableCall> {
    val code = codeOnly(text)
    return TABLE_CALL
        .findAll(code)
        .map {
            TableCall(
                text = callWithTrailingLambda(text = code, openParen = it.range.last),
                fingerprint = fingerprintAt(code = code, offset = it.range.first),
            )
        }.toList()
}

internal fun tablesWithoutDensityTypesFindings(text: String): List<String> =
    tableCalls(text)
        .filter { call -> listOf("TableType.STRIPED", "TableType.HOVER", "TableType.SMALL").any { it !in call.text } }
        .map { it.fingerprint }

internal fun tablesWithoutResponsiveFindings(text: String): List<String> =
    tableCalls(text).filter { "ResponsiveType.RESPONSIVE" !in it.text }.map { it.fingerprint }

internal fun iconOnlyButtonFindings(text: String): List<String> {
    val code = codeOnly(text)
    return ICON_ONLY_BUTTON
        .findAll(code)
        .filter { match ->
            val call = callWithTrailingLambda(text = code, openParen = code.indexOf('(', match.range.first))
            !(call.contains("title") && (call.contains("aria-label") || call.contains("ariaLabel")))
        }.map { fingerprintAt(code = code, offset = it.range.first) }
        .toList()
}

// ── R29: a writing coroutine without a guard (V1.4.29 audit M-6) ─────────────────────────────────────
// R29 says: every writing call is protected against a double click. The protection is `form.submit`/`runBusy`/`runGuardedAction`
// (all of which run the action through `runGuardedAction`, the only place with an `AppScope.launch` in `FormGrammar.kt`), or a
// one-shot dialog. A plain `AppScope.launch { ... rpcService<S>().write(...) }` in a migrated file has NONE of them. This scan finds
// those -- HONESTLY: it cannot tell a write from a read by anything but the method NAME (the read prefixes below), it does not see
// a guard implemented by hand (a `disabled = true` around the launch), and it cannot judge whether a write is idempotent. So it is a
// RATCHET with a named baseline, not a proof: the count may only go down, and the baseline lists what exists today.

/** RPC method names that start like this are reads (or pure computations): a double click on them changes nothing. */
private val R29_READ_PREFIXES =
    listOf("list", "get", "find", "count", "search", "unread", "is", "has", "can", "preview", "load", "read", "fetch", "download", "export")

private val LAUNCH_CALL = Regex("""AppScope\.launch\s*\{""")
private val RPC_CALL = Regex("""rpcService<\w+>\(\)\s*\.\s*(\w+)\(""")

/** The body of the `{ ... }` block whose opening brace is at [open] (braces balanced; strings are not parsed, as in [callWithTrailingLambda]). */
private fun braceBody(
    text: String,
    open: Int,
): String {
    var depth = 0
    var i = open
    while (i < text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(open, i + 1)
            }
        }
        i++
    }
    return text.substring(open)
}

/** Names that start like a write but are reads (`openCheckIn` loads the door roster; `openVote` IS a write and stays one). */
private val R29_READ_NAMES = setOf("openCheckIn")

private val GUARD_CALL = Regex("""(?<![A-Za-z0-9_])(?:runBusy|runGuardedAction|submit)\(""")

/** [body] with every guarded block (`runBusy(...) { }`, `runGuardedAction(...) { }`, `form.submit(...) { }`) blanked: a write inside one has its guard. */
private fun withoutGuardedBlocks(body: String): String {
    var out = body
    for (match in GUARD_CALL.findAll(body).toList().reversed()) {
        val call = callWithTrailingLambda(text = body, openParen = match.range.last)
        out = out.replaceRange(match.range.last, match.range.last + call.length, " ".repeat(call.length))
    }
    return out
}

/** One entry per `AppScope.launch { }` whose body calls a NON-read service method outside a guarded block: `<first line of the launch> -> <method, ...>`. */
internal fun unguardedWriteLaunchFindings(text: String): List<String> {
    val code = codeOnly(text)
    return LAUNCH_CALL
        .findAll(code)
        .mapNotNull { match ->
            val body = withoutGuardedBlocks(braceBody(text = code, open = match.range.last))
            val writes =
                RPC_CALL
                    .findAll(body)
                    .map { it.groupValues[1] }
                    .filterNot { name ->
                        name in R29_READ_NAMES ||
                            R29_READ_PREFIXES.any { name.startsWith(it) && (name.length == it.length || name[it.length].isUpperCase()) }
                    }.distinct()
                    .toList()
            if (writes.isEmpty()) null else "${fingerprintAt(code = code, offset = match.range.first)} -> ${writes.joinToString(", ")}"
        }.toList()
}

/**
 * SCOPE, stated honestly (audit V1.4.31): the scan covers only the files in [R24_MIGRATED] (32 of the 170 client files). In the whole client
 * about 190 of 403 writing `AppScope.launch` blocks are unguarded (`ConferenceScreen` 21, `AuctionScreen`/`ContributionsScreen`/`PoliticianScreen` 8 each);
 * the number 39 below is NOT "the unguarded writes of the client". The three irreversible paths of the audit (membership exit, member card,
 * document delete) are in unmigrated files and are guarded and tested separately (`WritePathsSingleShotDomTest`).
 *
 * The baseline: unguarded writing launches in the migrated files, by file (V1.4.29 audit). NOT a list of justified exemptions -- a list
 * of things nobody has judged yet (many are a one-click toggle whose second call is harmless, or sit behind a confirmation dialog
 * that is one-shot now). The number may only go down; a migrated file that gains a writing launch fails the count.
 */
private const val R29_UNGUARDED_WRITE_LAUNCH_MAX = 39

// ── R24: the form grammar (Welle V1.4.28, W4a) ────────────────────────────────────────────────────────

/**
 * `text(`, `password(` or `textArea(` as a call: not preceded by a letter/digit/underscore (so `gettext(`,
 * `richText(`, `resolvedAttributeText(` and `textField(` are not calls of the KVision widgets), but preceded by
 * anything else -- including a `.` (`panel.text(`) or nothing (`val x = text(`). Only a call that carries a
 * `label =` counts: that is a labelled FORM field; an unlabelled `textArea` (a read-only snippet display) is not.
 */
private val FIELD_CALL = Regex("""(?<![A-Za-z0-9_])(?:text|password|textArea)\(""")

/** The first string literal of a `label = tr("...")` / `label = "..."` argument: what tells two multi-line calls on the same receiver apart. */
private val LABEL_LITERAL = Regex("""label = (?:tr|gettext)?\(?"((?:[^"\\]|\\.)*)"""")

/**
 * [fingerprintAt], made precise for a call whose first line does not carry its `label =` (a multi-line call): the fingerprint of
 * `filterRow.select(` alone would justify EVERY select of that receiver, so the label literal is appended. A call that carries its
 * label on the first line keeps the plain line (the existing ledger entries do not change).
 */
private fun labelledFingerprint(
    code: String,
    match: MatchResult,
    call: String,
): String {
    val line = fingerprintAt(code = code, offset = match.range.first)
    if (line.contains("label =")) return line
    val literal = LABEL_LITERAL.find(call)?.groupValues?.get(1) ?: return line
    return "$line [label \"$literal\"]"
}

internal fun labelledFieldFindings(text: String): List<String> {
    val code = codeOnly(text)
    return FIELD_CALL
        .findAll(code)
        .mapNotNull { match ->
            val call = callWithTrailingLambda(text = code, openParen = match.range.last)
            if (call.contains("label =")) labelledFingerprint(code = code, match = match, call = call) else null
        }.toList()
}

/** [this] without the elements of [other], counted: a fingerprint justified once does not justify a second identical call. */
private fun <T> List<T>.minusMultiset(other: List<T>): List<T> {
    val rest = other.toMutableList()
    return filterNot { rest.remove(it) }
}

/**
 * The screens migrated to the form grammar (`lapisForm`, `FormGrammar.kt`): 11 in W4a, 11 more in W4b (V1.4.29). They are held
 * STRICTLY: zero labelled `text`/`password`/`textArea` calls and (positive checks) a `lapisForm(` call plus at least as many
 * `buttons(`/`finish(` closers as forms -- nobody may quietly rebuild one of them by hand. **This is ALL the tripwire
 * checks**: it does not verify `aria-required`, the required marks, the button order or the error display -- those are
 * covered by the DOM tests (`FormGrammarDomTest`, `LoginFormGrammarDomTest`, `SecretFieldDomTest`, `FormGrammarPart2DomTest`)
 * and by the design review, not by this scan. A ledger of fingerprints (the shape of R2..R55) would need ~250 lines for the
 * not yet migrated screens and would turn red on every renamed variable in any W4c file, so R24 is a strict set plus a
 * global downward ratchet ([R24_REMAINING_MAX]); the ratchet is lowered by the wave that migrates the next screens.
 */
private val R24_MIGRATED: Set<String> =
    setOf(
        // W4a
        "LoginScreen.kt",
        "RegistrationScreen.kt",
        "FriendRegistrationScreen.kt",
        "PasswordResetDeepLinkScreen.kt",
        "MemberPasswordResetDialog.kt",
        "SepaSettingsScreen.kt",
        "DunningSettingsScreen.kt",
        "ReceivableDunningSettingsScreen.kt",
        "ConferenceStreamDestinationsScreen.kt",
        "BackupScreen.kt",
        "ApiKeysScreen.kt",
        // W4b (V1.4.29)
        "ConfirmDialog.kt",
        "MemberAdministrationScreen.kt",
        // V1.9.48: the direct-creation form moved out of MemberAdministrationScreen.kt, the chapter form out of RegionalChaptersScreen.kt.
        "MemberDirectCreationForm.kt",
        "RegionalChapterCreateForm.kt",
        "EventCheckInScreen.kt",
        "MeetingsScreen.kt",
        "MotionsScreen.kt",
        "CommitteesScreen.kt",
        "BoardMembershipScreen.kt",
        "CommunicationScreen.kt",
        "ContributionReliefQueueScreen.kt",
        "SocialModerationScreen.kt",
        "StatuteQaScreen.kt",
        // W4c (V1.4.30): the finance forms and dialogs -- cut by whole files, the filters justified below.
        "LedgerScreen.kt",
        "OpenItemsScreen.kt",
        "OpenItemDialogs.kt",
        "SepaBatchesScreen.kt",
        "SepaMandateSection.kt",
        "DonorsScreen.kt",
        "BankAccountsScreen.kt",
        "BankStatementImportScreen.kt",
        "CostCentersScreen.kt",
        "AccountingExportScreen.kt",
        // W4d: DashboardScreen's change-password form (three labelled password fields removed).
        "DashboardScreen.kt",
        // W4d: Mandatstext (board card), Politiker-Status erteilen (member select + mandate text),
        // Gewichts-Snapshot auslösen (month text). `includeFormerSelect` (an area/action selector, never
        // submitted) is justified below; every existing write AppScope.launch in the file (castRating,
        // retractRating, revokePoliticianStatus, the politicianRankingEnabled toggle) was moved onto
        // `runGuardedAction` in the same wave so R29 does not rise when the file joins this set.
        "PoliticianScreen.kt",
        // W4d batch 2 (DocumentsScreen): Neuer Ordnername, Neuer Dokumenttitel + Sichtbarkeit, Neue Version
        // hochladen (upload, registered via `register`) + Änderungshinweis. The search filter is justified
        // below (FILTER_IS_NOT_A_FORM); the Wissensbasis checkbox is an IMMEDIATE SWITCH, justified in
        // R24B_JUSTIFIED. Every existing write AppScope.launch in the file (createFolder, createDocument,
        // deleteDocument -- already guarded, setKnowledgeBaseRelease, reindexKnowledgeDocument) was moved
        // onto `form.submit`/`runGuardedAction` in the same wave so R29 does not rise when the file joins
        // this set.
        "DocumentsScreen.kt",
        // W4d batch 3 (TravelExpenseScreen): Zweck/Von/Bis (header form, used for both "Entwurf anlegen" and
        // "Entwurf speichern"), Beschreibung + Kilometer/Tage/Betrag (add-a-line form) and Beleg hochladen
        // (upload, registered via `register`). The rates/configuration banner stays a data-driven panel
        // (`ui-ux-guideline.adoc` Known gaps), not a form. Every existing write `AppScope.launch` in the file
        // (submitReport, removeLine, deleteReceipt, withdrawReport, createDraft-as-copy) was moved onto
        // `runGuardedAction` in the same wave so R29 does not rise when the file joins this set.
        "TravelExpenseScreen.kt",
        // W4d batch 4 (TravelExpenseApprovalsScreen): unlike TravelExpenseScreen's read-only rates banner, this
        // screen's admin rates section (Kilometersatz/Tagespauschale) IS an editable, submitted form -- it moves
        // into `lapisForm` too. Plus the two decision panels (Entscheidungsnotiz, Pflicht -- 1:1 nach
        // `ContributionReliefQueueScreen.renderReliefRequestedDecidePanel`/`renderReliefApprovedRetryPanel`s
        // Vorbild). The status filter (`statusSelect`) is justified below (FILTER_IS_NOT_A_FORM, R24B). Every
        // existing write `AppScope.launch` in the file (updateTravelExpenseRates, decideReport, retryPosting)
        // was moved onto `form.submit`/`form.runBusy` in the same wave so R29 does not rise when the file joins
        // this set.
        "TravelExpenseApprovalsScreen.kt",
        // W4d batch 5 (VolunteerAllowanceScreen): Kategorie (required selectField, UNCHANGEABLE on an
        // existing payment) + Betrag/Tätigkeitsbeschreibung/Zahlungsdatum (required text-like fields),
        // one form used for both "Entwurf anlegen" and "Entwurf speichern" -- 1:1 nach
        // `TravelExpenseScreen.renderReportHeaderForm`s Vorbild. No filter/selector to justify: the
        // Kategorie select is a genuine required form field, not a filter (unlike
        // `TravelExpenseApprovalsScreen.kt`'s statusSelect). Every existing write `AppScope.launch` in
        // the file (submitPayment, withdrawPayment on both the draft editor and an own payment card,
        // declareSelf) was moved onto `runGuardedAction` in the same wave so R29 does not rise when
        // the file joins this set.
        "VolunteerAllowanceScreen.kt",
        // W4d batch 6 (VolunteerAllowanceApprovalsScreen, last file of the wave): the paper-declaration
        // recording form (Unterschrieben am, single required date field) and the combined decision panel
        // (Entscheidungsnotiz, Pflicht; plus a cap-acknowledgment checkbox only when the annual cap is
        // exceeded) -- 1:1 nach `TravelExpenseApprovalsScreen.renderRequestedDecisionPanel`s Vorbild. The
        // cap-acknowledgment checkbox is a genuine field of the strict set but deliberately NOT
        // `required = true` in the grammar sense (it is only required when approving, never when
        // rejecting -- see the file's own KDoc); its "must be checked to approve" rule is enforced
        // manually before `form.submit`. The status filter (`statusSelect`) is justified below
        // (FILTER_IS_NOT_A_FORM, R24B). Every existing write `AppScope.launch` in the file (decidePayment,
        // retryPosting, recordPaperDeclaration, voidPaperDeclaration) was moved onto
        // `form.submit`/`form.runBusy`/`runGuardedAction` in the same wave so R29 does not rise when the
        // file joins this set.
        "VolunteerAllowanceApprovalsScreen.kt",
        // V1.7.2 sub-wave 2b review fix (MINOR 5): KeycloakLinkScreen.kt already used the
        // `lapisForm`/`confirmDialog` grammar correctly when it was first added, but was never added
        // to this set -- its unlink-write `AppScope.launch` (inside `confirmDialog`) was therefore not
        // counted by the R29 ratchet at all. Moved onto `runGuardedAction` (same pattern as
        // `PoliticianScreen.politicianRevokeConfirmDialog`) in the same wave so R29 does not rise when
        // the file joins this set.
        "KeycloakLinkScreen.kt",
        // Welle V1.9.12 "Mitfahrerzentrale": every labelled text field (Von/Nach/Abfahrtsdatum/
        // Abfahrtszeit/Freie Plätze/Notiz in the create/edit form, Nachricht in the contact form)
        // goes through `lapisForm`'s `textField`/`textAreaField`/`selectField` from the start --
        // the file's only RAW `select(...)` call is the feed's "Art" type filter (justified below,
        // FILTER_IS_NOT_A_FORM: it narrows the feed, is never submitted, has no validation).
        "CarpoolScreen.kt",
    )

/** Screens examined that have NO labelled text field to migrate: strict too, but there is no form to build. */
private val R24_STRICT_WITHOUT_FORM: Set<String> =
    setOf(
        // The factories themselves (`textField`/`passwordField`/`textAreaField`/`selectField`/`checkField` call the raw widget):
        // strict too, with the five raw calls justified below -- a sixth raw call in there is a finding, not a silent one.
        "FormGrammar.kt",
        "PaymentGatewaySettingsScreen.kt",
        "EmbedIntegrationScreen.kt",
        // W4b: only filters / a selector (justified below), or no field at all (PostalMailScreen: two bespoke confirm modals).
        "PostalMailScreen.kt",
        "MemberAnniversariesScreen.kt",
        "MyVolunteerShiftsScreen.kt",
        // W4c (V1.4.30): two screens of the finance wave that hold only a filter and actions behind confirmation dialogs -- no form to
        // build (the mandate list revokes through `confirmWithReasonDialog`, the dunning list issues/cancels/skips/resets through
        // dialogs); every writing call in them is guarded (`runGuardedAction`).
        "SepaMandatesScreen.kt",
        "DunningCasesScreen.kt",
        // W4d (this batch): no labelled text field at all -- only an IMMEDIATE SWITCH checkbox (justified below,
        // R24B_JUSTIFIED, same reason as `StatuteQaScreen.kt`).
        "NonprofitComplianceReportsScreen.kt",
        // V1.9.6 "Vorstands-Karte, Client-Hälfte": only the PLZ/Ort search filter (justified below,
        // FILTER_IS_NOT_A_FORM) -- no form to build.
        "MemberMapScreen.kt",
        // V1.9.22 "Wahlen": born on the form grammar -- every form goes through `lapisForm`, every write through
        // `form.submit`/`runGuardedAction`. The status filter of the list is justified below (FILTER_IS_NOT_A_FORM).
        "ElectionsScreen.kt",
        "ElectionBoardUi.kt",
        "ElectionResultUi.kt",
        "ElectionBooth.kt",
        "ElectionOpenForm.kt",
        // V1.9.25 "Abstimmen im Konferenzraum": the voting panel has no form of its own (the booth it embeds is `ElectionBooth.kt`); its one
        // write path, entering the booth, goes through `runGuardedAction`. Strict set, not `R24_MIGRATED`: that one demands a `lapisForm(`.
        "ConferenceVotePanel.kt",
        // V1.9.26 "Abstimmen im Konferenzraum", Welle 3: the operator controls (buttons and confirmation dialogs only, no field) and the pure
        // stream mirror (no widget at all). Every write goes through `runOperatorAction` -> `runGuardedAction`.
        "ConferenceVoteOperatorControls.kt",
        "ConferenceVoteStreamMirror.kt",
        // V1.9.28 "Konsensieren": born on the form grammar (`lapisForm` for the open form, the option form and the receipt check; the booth
        // has radio buttons and no text field). The status filter of the list is justified below (FILTER_IS_NOT_A_FORM).
        "ConsensusScreen.kt",
        "ConsensusDetail.kt",
        "ConsensusOptions.kt",
        "ConsensusBooth.kt",
        "ConsensusReceipt.kt",
        "ConsensusResultView.kt",
        "ConsensusOpenForm.kt",
        // V1.9.31 "Umfragen": strict from day one -- the create form is a `lapisForm`, every write goes through `runBusy`/`runGuardedAction`.
        "PollScreen.kt",
        "PollListView.kt",
        "PollCreateForm.kt",
        "PollAuthzUi.kt",
        "PollLabels.kt",
        "PollDetail.kt",
        "PollBooth.kt",
        "PollResultView.kt",
        "PollGuard.kt",
        // V1.9.41 consensus polls: born strict (the shared rating booth, its two adapters, the result view and the pure draft checks).
        "RatingBooth.kt",
        "PollRatingBooth.kt",
        "PollRatingResultView.kt",
        "PollCreateDraft.kt",
        // V1.9.33 (new, born on the form grammar): the member's address / GwG forms. Two `lapisForm`s, each with its own save button.
        "MemberAddressCard.kt",
        // V1.9.29 "KI-Zugang": no form at all -- one immediate switch (a `CheckBox` constructor, saved by itself, confirmed by a dialog when turned
        // off) and a revoke button per connection; every write goes through `runGuardedAction`, so R29 does not rise.
        "McpAccessCard.kt",
        // V1.9.33: no field at all -- buttons and confirmation dialogs only (card revoke, event register / withdraw); every write goes
        // through `runGuardedAction`, so R29 does not rise. (Strict set, not `R24_MIGRATED`: that one demands a `lapisForm(`.)
        "MemberCardRevokeCard.kt",
        "MemberEventsScreen.kt",
        // V1.9.35: board-side refunds, the audited two-step address read, "Zahlung fortsetzen" and the audit-marker labels. The only
        // fields live inside the shared `MemberAddressCard` (strict above); every write goes through `runGuardedAction`/`runBusy`.
        "MemberAddressAdminDialog.kt",
        "EventRefundsSection.kt",
        "MemberEventPaymentUi.kt",
        "AuditMarkerLabels.kt",
        // V1.9.36: the reply form extracted from the inbox row (born on the form grammar: `lapisForm`, `textAreaField`, `submit`); the
        // partner list and the conversation view have no field and write only through `runGuardedAction`.
        "DirectMessageReplyForm.kt",
    )

/** The one reason every entry of [R24_JUSTIFIED] shares: a filter is not a form. */
private const val FILTER_IS_NOT_A_FORM = "Filter: no required field, no submit, no validation"

/**
 * Justified exceptions inside a migrated file, by fingerprint (the pattern of [R39_JUSTIFIED]): the two webhook URL
 * fields of `ApiKeysScreen` belong to W4c (the webhook management form), not to the W4a issue form; the search/filter
 * fields of W4b are FILTERS ([FILTER_IS_NOT_A_FORM]) -- the rule "filter fields are not forms" (`ui-ux-guideline.adoc`).
 */
private val R24_JUSTIFIED: Map<String, List<String>> =
    mapOf(
        // The factories: the raw widget calls the grammar wraps.
        "FormGrammar.kt" to
            listOf(
                "val control = host.text(type = type, value = value, label = label)",
                "val control = host.password(value = value, label = label)",
                "val control = host.textArea(rows = rows, value = value, label = label)",
            ),
        "ApiKeysScreen.kt" to
            listOf(
                "val urlInput = row.text(label = tr(\"Webhook-URL (https://…)\"))",
                "val urlInput = editRow.text(label = tr(\"Neue Webhook-URL\")) { hide() }",
            ),
        // FILTER_IS_NOT_A_FORM:
        "MemberAdministrationScreen.kt" to
            listOf("val searchInput = filterRow.text(label = tr(\"Suche nach Name, E-Mail oder Personennummer\"))"),
        "EventCheckInScreen.kt" to listOf("val searchField = root.text(label = tr(\"Name suchen\"))"),
        "MemberAnniversariesScreen.kt" to listOf("val searchInput = filterRow.text(label = tr(\"Suche nach Name\"))"),
        // W4c (V1.4.30) -- FILTER_IS_NOT_A_FORM: the search / filter fields of the finance screens. The labels of the date filters carry no
        // format any more ("Von"/"Bis"; the example sits in a hint line).
        "LedgerScreen.kt" to
            listOf(
                "val accountSearchInput = accountsFilterRow.text(label = tr(\"Konto suchen (Nummer oder Name)\"))",
                "val journalSearchInput = journalFilterRow.text(label = tr(\"Buchung suchen (Beschreibung)\"))",
            ),
        "OpenItemsScreen.kt" to listOf("val searchInput = filterRow.text(label = tr(\"Suche (Gegenpartei, Beleg)\"))"),
        "SepaBatchesScreen.kt" to
            listOf(
                "val fromInput = filterRow.text(label = tr(\"Von\"))",
                "val toInput = filterRow.text(label = tr(\"Bis\"))",
            ),
        "SepaMandatesScreen.kt" to listOf("val searchInput = filterRow.text(label = tr(\"Suche nach Mitglied oder Mandatsreferenz\"))"),
        "DonorsScreen.kt" to listOf("val donorSearchInput = filterRow.text(label = tr(\"Spender suchen (Name)\"))"),
        // The live search of the assignment workbench: it narrows a list of candidates, it is never submitted.
        "BankStatementImportScreen.kt" to listOf("val searchInput = body.text(label = tr(\"Mitgliedsname oder Beitragssatz\"))"),
        "CostCentersScreen.kt" to
            listOf(
                "val costCenterSearchInput = filterRow.text(label = tr(\"Kostenstelle suchen (Code oder Name)\"))",
            ),
        "DunningCasesScreen.kt" to listOf("val searchInput = filterRow.text(label = tr(\"Suche nach Mitglied\"))"),
        // W4d batch 2 -- FILTER_IS_NOT_A_FORM: narrows the document list of the open folder, never submitted.
        "DocumentsScreen.kt" to
            listOf("searchInput = searchRow.text(label = tr(\"Dokumente in diesem Ordner durchsuchen\"))"),
        // V1.9.6 -- FILTER_IS_NOT_A_FORM: narrows the PLZ/Ort table, never submitted. Renamed V1.9.9
        // ("Tabelle filtern (PLZ oder Ort)") once the map-overlay Ortssuche field below made the
        // original "PLZ oder Ort" label ambiguous between the two. V1.9.9 also adds the overlay field
        // itself -- narrows nothing existing, it drives `MemberMapMapController.flyToPlace`, but is
        // exactly as much "a filter, not a form" as the table one: no required field, no submit.
        "MemberMapScreen.kt" to
            listOf(
                "val searchInput = tablePanel.text(label = tr(\"Tabelle filtern (PLZ oder Ort)\"))",
                "val field = overlay.text(label = tr(\"Ort suchen\"))",
            ),
    )

/**
 * The downward ratchet: labelled fields outside the strict set (297 in 54 files when W4a landed, 228 after W4b, 225 in 41 files after
 * the V1.4.29 audit moved `FormGrammar.kt` -- the factories -- into the strict set, 166 after W4c (V1.4.30: 59 labelled fields of the
 * twelve finance screens moved in; ten of them are justified filters), 163 after W4d's DashboardScreen batch (three change-password
 * fields moved in), 160 after W4d's PoliticianScreen batch (its month/mandate text fields moved in; the AREA/ACTION SELECTOR stays a
 * raw select, justified in R24B, not here), 156 after W4d's DocumentsScreen batch (all four of the file's labelled `text(` calls --
 * Ordnername, Dokumenttitel, Änderungshinweis, and the search filter -- leave the "outside" bucket once the file joins the strict
 * set; three move into `textField`/`register`, the search filter is justified below, not removed), 149 after W4d's
 * TravelExpenseScreen batch (all seven of the file's labelled `text(` calls -- Zweck, Von, Bis, Beschreibung, Kilometer, Tage,
 * Betrag -- leave the "outside" bucket; none are justified filters, all seven genuinely move into `textField`), 142 after the
 * TravelExpenseApprovalsScreen and VolunteerAllowanceScreen batches (Kilometersatz/Tagespauschale plus both decision panels'
 * notes, then Betrag/Tätigkeitsbeschreibung/Zahlungsdatum move in), 140 after W4d's VolunteerAllowanceApprovalsScreen batch --
 * the wave's last file (the paper-declaration date field and the combined decision panel's note leave the "outside" bucket;
 * the cap-acknowledgment checkbox is a select/checkBox field, counted under R24B, not here). 143 after the follow-up wave
 * "Wiederkehrende Veranstaltungen: Client/iCal/i18n" adds a brand-new file, `EventSeriesEditor.kt` -- its three labelled
 * `text(` calls (Intervall, Anzahl der Termine, Enddatum) are a small, embedded recurrence-rule editor inside
 * `EventsScreen.kt`'s creation form, which itself is not (yet) built with `lapisForm` -- migrating just this one embedded
 * widget to the form grammar while its host screen stays a plain widget tree would not remove any real debt, only rename it.
 * 144 after Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" adds a brand-new file,
 * `ChapterRosterScreen.kt` -- its one labelled `text(` call (the roster's "Suche nach Name oder E-Mail" filter) is the
 * same FILTER_IS_NOT_A_FORM shape every other roster/filter search input in this codebase already takes (see
 * `MemberAdministrationScreen.kt`'s own `searchInput` in [R24_JUSTIFIED] below) -- a search box has nothing to submit
 * and no invalid state to report, so it stays a plain labelled `text(` call outside the form grammar, same as its host
 * file's counterpart on `MemberAdministrationScreen.kt`. Only ever lowered.
 */
private const val R24_REMAINING_MAX = 144

private fun r24Findings(file: File): List<String> = labelledFieldFindings(file.readText()).minusMultiset(R24_JUSTIFIED[file.name].orEmpty())

// ── R24B: labelled SELECT / CHECKBOX fields (Welle V1.4.29, W4b) ─────────────────────────────────────────
// A SECOND, separate counter (its own ratchet; it shares R24's strict file set, so it is NOT independent of it) -- R24 is NOT redefined (a measure changed in the middle of the measuring is no measure): a
// labelled `select(`/`checkBox(` is a form field too (it can be required, can be invalid, has an error to show), and W4a
// held only text-like fields. Same mechanics as R24.

/** `select(` / `checkBox(` as a call that carries a `label =`: a labelled CHOICE field (a `segmentedControl(`/`selectFilter(` is not one). */
private val SELECT_CALL = Regex("""(?<![A-Za-z0-9_])(?:select|checkBox)\(""")

internal fun labelledSelectFindings(text: String): List<String> {
    val code = codeOnly(text)
    return SELECT_CALL
        .findAll(code)
        .mapNotNull { match ->
            val call = callWithTrailingLambda(text = code, openParen = match.range.last)
            if (call.contains("label =")) labelledFingerprint(code = code, match = match, call = call) else null
        }.toList()
}

/**
 * Justified choice fields inside the strict set, by fingerprint. Three reasons only, each named in the comment above its
 * group: a FILTER ([FILTER_IS_NOT_A_FORM]), an AREA/ACTION SELECTOR (picks what the screen shows or which action button applies;
 * it is never submitted), an IMMEDIATE SWITCH (saves by itself, no submit) and a SELECTION LIST (a checkbox per member -- one
 * error slot per member would be absurd; "at least one" is a cross rule).
 */
private val R24B_JUSTIFIED: Map<String, List<String>> =
    mapOf(
        // The factories: the raw widget calls the grammar wraps.
        "FormGrammar.kt" to
            listOf(
                "val control = host.select(options = options, value = value, label = label)",
                "val control = host.checkBox(value = value, label = label)",
            ),
        // FILTER_IS_NOT_A_FORM
        "DunningSettingsScreen.kt" to listOf("val includeInactiveCheck = filterRow.checkBox(label = tr(\"Inaktive Stufen anzeigen\"))"),
        "ReceivableDunningSettingsScreen.kt" to
            listOf("val includeInactiveCheck = filterRow.checkBox(label = tr(\"Inaktive Stufen anzeigen\"))"),
        "BoardMembershipScreen.kt" to listOf("val includeResolvedCheck = reminderFilterRow.checkBox(label = tr(\"Erledigte anzeigen\"))"),
        // V1.9.22: the status filter of the elections list (a multi-line `select(` call, fingerprinted by its label literal).
        "ElectionsScreen.kt" to listOf("filterRow.select( [label \"Status\"]"),
        // V1.9.28: the status filter of the consensus list.
        "ConsensusScreen.kt" to listOf("filterRow.select( [label \"Status\"]"),
        "CommitteesScreen.kt" to
            listOf(
                "val includeInactiveCheck = filterRow.checkBox(label = tr(\"Inaktive Gremien anzeigen\"))",
                "val includeEndedCheck = rosterFilterRow.checkBox(label = tr(\"Ausgeschiedene anzeigen\"))",
            ),
        "ContributionReliefQueueScreen.kt" to
            listOf(
                "val statusSelect = filterRow.select(options = statusOptions, value = \"\", label = tr(\"Status\"))",
                "val kindSelect = filterRow.select(options = kindOptions, value = \"\", label = tr(\"Art\"))",
                "val reviewDueOnlyCheck = filterRow.checkBox(label = tr(\"Nur zur Wiedervorlage fällig\"))",
            ),
        "MeetingsScreen.kt" to
            listOf(
                "val committeeFilterSelect = filterRow.select(options = listOf(\"\" to tr(\"Alle Gremien\")), value = \"\", label = tr(\"Gremium\"))",
                "val statusFilterSelect = filterRow.select(options = statusFilterOptions, value = \"\", label = tr(\"Status\"))",
                // SELECTION LIST (a checkbox per eligible member). Split across three lines and the label
                // now sanitized (security fix, untrusted-text-sanitization-gaps) -- the fingerprint below is
                // the actual per-line text the scanner now sees, not the old single-line compound statement.
                "recipientsPanel.checkBox(label = sanitizeUntrustedI18nText(member.displayName))",
            ),
        "MotionsScreen.kt" to
            listOf(
                "val committeeFilterSelect = filterRow.select(options = listOf(\"\" to tr(\"Alle Gremien\")), value = \"\", label = tr(\"Gremium\"))",
                "val statusFilterSelect = filterRow.select(options = statusFilterOptions, value = \"\", label = tr(\"Status\"))",
            ),
        // two filters (reports, erasure requests) with the same line: the multiset justifies BOTH, a third would be a finding
        "SocialModerationScreen.kt" to
            listOf(
                "val statusSelect = filterRow.select(options = statusOptions, value = \"\", label = tr(\"Status\"))",
                "val statusSelect = filterRow.select(options = statusOptions, value = \"\", label = tr(\"Status\"))",
            ),
        "MemberAnniversariesScreen.kt" to listOf("filterRow.select( [label \"Zeitraum\"]"),
        // Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- FILTER_IS_NOT_A_FORM,
        // same reason as every statusSelect/committeeFilterSelect above: narrows the member roster by
        // chapter, never submitted.
        "MemberAdministrationScreen.kt" to listOf("filterRow.select( [label \"Landesverband\"]"),
        // AREA / ACTION SELECTOR (never submitted)
        "CommunicationScreen.kt" to listOf("val listSelect = row.select(options = emptyList(), label = tr(\"Mailingliste\"))"),
        "MyVolunteerShiftsScreen.kt" to
            listOf("val eventSelect = eventSelectRow.select(options = emptyList(), label = tr(\"Veranstaltung\"))"),
        "PaymentGatewaySettingsScreen.kt" to listOf("actionsRow.select( [label \"Anbieter\"]"),
        // W4d (this batch): the "Nur aktive Profile" / "Inklusive ehemaliger Profile" toggle picks what the
        // list below shows -- never submitted, same reason as CommunicationScreen.kt's listSelect above.
        "PoliticianScreen.kt" to listOf("listControlsRow.select( [label \"Anzeige\"]"),
        // IMMEDIATE SWITCH (saves by itself, no submit)
        "StatuteQaScreen.kt" to
            listOf("consentPanel.checkBox( [label \"Ich stimme zu, dass meine Fragen von einer KI beantwortet werden\"]"),
        // W4d (this batch): the Kleinunternehmer-Regelung checkbox saves itself on change -- no surrounding
        // form, no submit button, same reason as StatuteQaScreen.kt's consent checkbox above.
        "NonprofitComplianceReportsScreen.kt" to
            listOf("kleinunternehmerRow.checkBox(value = settings.isKleinunternehmer, label = tr(\"Kleinunternehmer nach § 19 UStG\"))"),
        // W4d batch 2: the "Wissensbasis" checkbox of a document row saves itself on change -- no
        // surrounding form, no submit button, same reason as StatuteQaScreen.kt's consent checkbox above.
        "DocumentsScreen.kt" to
            listOf("val box = row.checkBox(value = current().released, label = tr(\"Wissensbasis\"))"),
        // W4c (V1.4.30) -- FILTER_IS_NOT_A_FORM
        "LedgerScreen.kt" to
            listOf("val includeInactiveAccountsCheck = accountsFilterRow.checkBox(label = tr(\"Inaktive Konten anzeigen\"))"),
        "OpenItemsScreen.kt" to
            listOf(
                "filterRow.checkBox(value = status in state.filter.statuses, label = openItemStatusLabel(status))",
                "val overdueCheck = filterRow.checkBox(value = false, label = tr(\"Nur überfällige\"))",
                "filterRow.select( [label \"Seitengröße\"]",
            ),
        "DonorsScreen.kt" to listOf("val includeInactiveCheck = filterRow.checkBox(label = tr(\"Inaktive Spender anzeigen\"))"),
        "BankStatementImportScreen.kt" to listOf("val includeNonPositiveCheck = root.checkBox(label = tr(\"Auch Abbuchungen anzeigen\"))"),
        "CostCentersScreen.kt" to listOf("val includeInactiveCheck = filterRow.checkBox(label = tr(\"Inaktive Kostenstellen anzeigen\"))"),
        "DunningCasesScreen.kt" to
            listOf(
                "val onlyOpenCheck = filterRow.checkBox(value = true, label = tr(\"Nur offene Vorgänge\"))",
                "filterRow.select( [label \"Seitengröße\"]",
            ),
        // W4d batch 4 -- FILTER_IS_NOT_A_FORM: narrows the approval queue by status, never submitted.
        "TravelExpenseApprovalsScreen.kt" to
            listOf("val statusSelect = filterRow.select(options = statusOptions, value = \"\", label = tr(\"Status\"))"),
        // W4d batch 6 -- FILTER_IS_NOT_A_FORM: narrows the approval queue by status, never submitted (same
        // reason as TravelExpenseApprovalsScreen.kt's statusSelect above).
        "VolunteerAllowanceApprovalsScreen.kt" to
            listOf("val statusSelect = filterRow.select(options = statusOptions, value = \"\", label = tr(\"Status\"))"),
        // Welle V1.9.12 "Mitfahrerzentrale" -- FILTER_IS_NOT_A_FORM: narrows the feed by
        // OFFER/REQUEST, never submitted, same reason as the approval-queue statusSelects above.
        "CarpoolScreen.kt" to listOf("filterRow.select( [label \"Art\"]"),
    )

/**
 * The downward ratchet for labelled choice fields outside the strict set: 131 in 38 files (measured in the V1.4.29 audit; the figure
 * "45 files" of the wave's own comment was wrong), 80 after W4c (V1.4.30: 51 labelled choice fields of the twelve finance screens moved
 * in; nine of them are justified filters), 77 after the first W4d batch (NonprofitComplianceReportsScreen's Kleinunternehmer checkbox
 * and PoliticianScreen's member select + area selector moved in; both files' remaining choice fields are justified, not removed),
 * 75 after the DocumentsScreen batch (its Sichtbarkeit select moves into `selectField`; the Wissensbasis checkbox leaves the
 * "outside" bucket too, justified below as an immediate switch). Unchanged at 75 after the TravelExpenseScreen batch: the file
 * joins the strict set with zero labelled `select(`/`checkBox(` calls of its own (its rates/configuration banner is a
 * data-driven panel, not a form -- see `ui-ux-guideline.adoc` Known gaps), so nothing moves out of this bucket. 74 after the
 * TravelExpenseApprovalsScreen batch (its statusSelect moves in, justified as a filter), 73 after the VolunteerAllowanceScreen
 * batch (unchanged: zero labelled `select(`/`checkBox(` calls of its own), 71 after W4d's VolunteerAllowanceApprovalsScreen
 * batch -- the wave's last file (its statusSelect moves in, justified as a filter same as TravelExpenseApprovalsScreen.kt's;
 * the cap-acknowledgment checkField moves in too, NOT justified -- it is a genuine field of the decision form). 77 after the
 * follow-up wave "Wiederkehrende Veranstaltungen: Client/iCal/i18n" adds `EventSeriesEditor.kt` (a brand-new file, not in the
 * strict set -- see [R24_REMAINING_MAX]'s own comment for why): its "Wiederkehrende Veranstaltung (Serie)" checkbox, the
 * "Wiederholung"/"Alle …"/"Monatliche Variante"/"Serie endet …" selects of the recurrence editor, and the scope-dialog's
 * "Betrifft" select -- six labelled choice fields, none of them justified filters (each genuinely feeds the series
 * create/edit RPC call, unlike the accepted status-filter exceptions above). Only ever
 * lowered.
 *
 * 69 after Welle V1.9.16 "Durchsuchbare Personenauswahl": eight labelled `select(` calls that chose a PERSON (`LtrLedgerScreen` x5,
 * `PriceOracleScreen`, `MemberHonorsScreen`, the stream-participant choice in `ConferenceScreen`) became `searchableSelect(`
 * calls (`SearchableSelect.kt`). HONEST COVERAGE NOTE: [SELECT_CALL]'s `(?<![A-Za-z0-9_])` guard means `searchableSelect(` and
 * `searchableSelectField(` are NOT counted -- the count dropped because those fields left the scanner's view, not because
 * they became form fields. `selectField(`/`searchableSelectField(` were never counted (they ARE the grammar). The person pickers
 * that stay plain selects are policed by [ClientPersonSelectTripwireTest] instead. Only ever lowered.
 */
private const val R24B_REMAINING_MAX = 69

private fun r24bFindings(file: File): List<String> =
    labelledSelectFindings(file.readText()).minusMultiset(R24B_JUSTIFIED[file.name].orEmpty())

// ── R36B: create forms are collapsed (Welle V1.9.40) ─────────────────────────────────────────────────────

private const val R36B = "R36B create form collapsed behind one title-row button"

/**
 * What R36B calls a *visible create form*, as a HEURISTIC (an honest approximation, not a proof): (1) a section title `h2(tr("Neu ..."))`
 * -- the heading that used to introduce an always-visible create section -- and (2) a call of a create-form builder, i.e. a
 * `render...Creation/CreateForm/CreationForm/SubmissionForm/AppointmentForm...(` function called anywhere that is NOT inside a
 * `collapsibleCreateForm(...)` call. A create form that is built under another name, or hand-rolled inside a screen, is invisible to
 * this scan; the Design-Team review and the DOM tests of the pilot screens (`CollapsibleCreateFormScreensDomTest`) cover those.
 * Edit forms (a row's "Bearbeiten"), filters and one-shot displays are not create forms; the ones that look like one to the heuristic
 * are listed in [R36B_EXEMPT] with a reason.
 */
private val CREATE_SECTION_TITLE = Regex("""\.h[1-6]\(\s*tr\(\s*"Neu[^"]*"""")

/** V1.9.49: the same title written as a bold `div` (the mailing-list form's "Neue Mailingliste anlegen" line was one). */
private val CREATE_TITLE_DIV = Regex("""\.div\(\s*tr\(\s*"Neu[^"]*"\s*\)\s*\)\s*\{[^}]*fw-(?:bold|semibold)""")

/**
 * V1.9.49: a shared create/edit builder called for CREATING -- `renderXxxForm(..., existing = null, ...)` -- outside a collapsible create
 * form. The convention (collapsible-forms.adoc): create calls of a shared builder name the argument `existing = null`, so this finds them.
 * Edit calls (`existing = row`) and declarations (`existing: T? = null`, a colon) are no findings.
 */
private val CREATE_VIA_EXISTING_NULL = Regex("""(?<![A-Za-z0-9_])render\w*\((?:[^()]|\([^()]*\))*\bexisting\s*=\s*null""")
private val CREATE_FORM_CALL =
    Regex(
        """(?<![A-Za-z0-9_])render\w*(?:Creation|CreateForm|Create\w*Form|CreationForm|CreateListingForm|SubmissionForm|SubmitProjectForm|AppointmentForm|NewEntryForm|NewBatchSection|RecordReturnForm|MandateForm|UploadPanel)\w*\(""",
    )
private val DECLARATION_PREFIX = Regex("""\bfun\s+(?:[A-Za-z0-9_<>?]+\.)?$""")
private val COLLAPSIBLE_CALL = Regex("""(?<![A-Za-z0-9_])collapsibleCreateForm(?:<[^>]*>)?\(""")

/** The character ranges of every `collapsibleCreateForm(...) { ... }` call of the comment-blanked [code]. */
private fun collapsibleCallRanges(code: String): List<IntRange> =
    COLLAPSIBLE_CALL
        .findAll(code)
        .map { match ->
            match.range.first until
                match.range.last + callWithTrailingLambda(text = code, openParen = match.range.last).length
        }.toList()

internal fun visibleCreateFormFindings(text: String): List<String> {
    val code = codeOnly(text)
    val collapsed = collapsibleCallRanges(code)
    val titles =
        (CREATE_SECTION_TITLE.findAll(code) + CREATE_TITLE_DIV.findAll(code)).map { fingerprintAt(code = code, offset = it.range.first) }
    val calls =
        (CREATE_FORM_CALL.findAll(code) + CREATE_VIA_EXISTING_NULL.findAll(code))
            .distinctBy { it.range.first }
            // `fun renderXxxCreation(` and the extension form `fun SimplePanel.renderXxxCreateForm(` (V1.9.41) are declarations, not calls
            .filter { !DECLARATION_PREFIX.containsMatchIn(code.substring(maxOf(0, it.range.first - 60), it.range.first)) }
            .filter { call -> collapsed.none { call.range.first in it } }
            .map { fingerprintAt(code = code, offset = it.range.first) }
    return (titles + calls).toList()
}

private class R36bEntry(
    val fingerprints: List<String>,
    val reason: String,
)

/** Not a create form at all, though the heuristic sees one -- by file, with the reason (always visible, never silent). */
private val R36B_EXEMPT: Map<String, R36bEntry> =
    mapOf(
        "ApiKeysScreen.kt" to
            R36bEntry(
                fingerprints =
                    listOf(
                        "card.h2(tr(\"Neuer Schlüssel -- jetzt speichern\")) { addCssClass(\"h5\") }",
                        "card.h2(tr(\"Neues Signaturgeheimnis -- jetzt speichern\")) { addCssClass(\"h5\") }",
                    ),
                reason = "One-time display of a newly issued API key / signing secret (\"save it now\"): a result card, not a create form.",
            ),
        "SepaMandateSection.kt" to
            R36bEntry(
                fingerprints = listOf("renderSepaMandateForm("),
                reason = "The member's own single mandate, already behind the \"Mandat erteilen\" click; not a list-plus-create screen.",
            ),
        "SocialNetworkScreen.kt" to
            R36bEntry(
                fingerprints =
                    listOf(
                        "root.h2(tr(\"Neuen Beitrag verfassen\")) { addCssClass(\"h5\") }",
                    ),
                reason =
                    "The post composer of the social network (\"Neuen Beitrag verfassen\"): " +
                        "the screen is the composer, not a list with a create form.",
            ),
    )

// ── R36C: no start/new button without an icon in the content (V1.9.51) ────────────────────────────────

/**
 * A plain `button(tr("Neue[r|n|s] <Noun>"))` / `Button(tr("... jetzt starten"))` outside a `collapsibleCreateForm(...)` call: the "new" or
 * "start" action of a screen belongs in the title row ([PageHeader.actionSlot]) as a `newActionButton(ActionIcon.ADD, ...)`, not in the
 * content as a text-only button (V1.9.51: the conference lobby's "Besprechung jetzt starten"). Labels of more than the noun
 * ("Neues Passwort setzen") are not "new X" buttons and are not matched.
 */
private val START_NEW_BUTTON =
    Regex("""(?:\bButton|\.button)\(\s*(?:text\s*=\s*)?tr\(\s*"(?:Neue[nrs]?\s\p{L}+|[^"]*jetzt starten)"""")

internal fun startNewButtonFindings(text: String): List<String> {
    val code = codeOnly(text)
    val collapsed = collapsibleCallRanges(code)
    return START_NEW_BUTTON
        .findAll(code)
        .filter { match -> collapsed.none { match.range.first in it } }
        .map { fingerprintAt(code = code, offset = it.range.first) }
        .toList()
}

/** Start buttons that stay text-only on purpose: a confirmation of a domain verb, by file, with the reason. */
private val R36C_EXEMPT: Map<String, R36bEntry> =
    mapOf(
        "ConferenceScreen.kt" to
            R36bEntry(
                fingerprints = listOf("Button(tr(\"Aufzeichnung jetzt starten\"), style = ButtonStyle.WARNING).apply {"),
                reason =
                    "Confirmation button of the recording-consent modal (domain verb, WARNING style): it confirms a consequence, " +
                        "it is not the page's \"new\" action.",
            ),
    )

/** Start/new buttons that are real violations and still unfixed: EMPTY, a new finding is fixed or (with a reason) exempted. */
private const val R36C_REMAINING_MAX = 0

private fun r36cActual(): Map<String, List<String>> =
    clientKotlinFiles()
        .associate { it.name to startNewButtonFindings(it.readText()) }
        .filterValues { it.isNotEmpty() }

/** The conference lobby and its title-row action are shown and hidden together (V1.9.51). */
private val RAW_LOBBY_VISIBILITY = Regex("""\blobbyPanel\s*\.\s*(?:show|hide)\(\)""")

internal fun rawLobbyVisibilityCalls(text: String): List<String> = RAW_LOBBY_VISIBILITY.findAll(codeOnly(text)).map { it.value }.toList()

/**
 * Real create forms that are still always visible: the debt of the later waves, by file, with the wave that pays it.
 * V1.9.49: EMPTY -- the last four groups (Wirtschaft, Konferenz-Verwaltung, Dokumente, Compliance) are converted. A new finding is
 * either converted (collapsibleCreateForm) or, with a reason, moved to [R36B_EXEMPT]; it never goes back in here.
 */
private val R36B_NOT_YET_COLLAPSED: Map<String, R36bEntry> = emptyMap()

/**
 * Ratchet over the debt: the number of findings outside the converted screens. Only ever lowered (by the wave that converts a group),
 * and it may not drop more than 3 below the cap without the cap being lowered -- so a quietly converted screen has to be taken out of the
 * ledger in the same commit. [R36B_NOT_YET_COLLAPSED] and [R36B_EXEMPT] pin the exact fingerprints; this pins the total.
 * V1.9.45 finance: 39 -> 30 (CostCenters 2, Donors 2, Ledger 3, OpenItems 1, SepaBatches 1 paid off).
 * V1.9.47 events: 30 -> 21 (Catering 2, EventRooms 2, EventVolunteerShifts 2, Events 3 paid off).
 * V1.9.48 community: 21 -> 14 (Crm 3, Crowdfunding 2, MemberAdministration 1, RegionalChapters 1 paid off).
 * V1.9.49 rest: 14 -> 0 (Auction 2, ConferenceStreamDestinations 2, Documents 2, DsgvoCompliance 8 paid off).
 */
private const val R36B_REMAINING_MAX = 0

/** The governance pilot of V1.9.40, the finance group of V1.9.45, the events group of V1.9.47, the community group of V1.9.48 and the rest of V1.9.49: converted, so held strictly (no finding at all) and required to use the component. */
private val R36B_CONVERTED: Set<String> =
    setOf(
        "CommitteesScreen.kt",
        "BoardMembershipScreen.kt",
        "MeetingsScreen.kt",
        "MotionsScreen.kt",
        "PollListView.kt",
        "CostCentersScreen.kt",
        "DonorsScreen.kt",
        "LedgerScreen.kt",
        "OpenItemsScreen.kt",
        "SepaBatchesScreen.kt",
        "SepaMandatesScreen.kt",
        "BankStatementImportScreen.kt",
        "EventsScreen.kt",
        "EventRoomsScreen.kt",
        "EventVolunteerShiftsScreen.kt",
        "CateringScreen.kt",
        // V1.9.48 community group. The builder-only files (CrowdfundingForms.kt, MemberDirectCreationForm.kt, RegionalChapterCreateForm.kt) hold
        // no collapsibleCreateForm call, so they cannot be listed here; the global ledger-equality test keeps them strict (any finding in a file
        // outside the ledger is red).
        "CrmContactsScreen.kt",
        "CrmCollapsibleForms.kt",
        "CrowdfundingScreen.kt",
        "MemberAdministrationScreen.kt",
        "RegionalChaptersScreen.kt",
        // V1.9.49 rest. Builder-only files (AuctionCreateListingForm.kt, DocumentsCreateForms.kt, DsgvoComplianceForms.kt,
        // MailingListCreateForm.kt, TravelExpenseHeaderForm.kt) hold no collapsibleCreateForm call -- strict through the global equality test.
        "AuctionScreen.kt",
        "ConferenceStreamDestinationsScreen.kt",
        "DocumentsScreen.kt",
        "DsgvoComplianceScreen.kt",
        "CommunicationScreen.kt",
        "TravelExpenseScreen.kt",
        "VolunteerAllowanceScreen.kt",
        "DunningSettingsScreen.kt",
        "ReceivableDunningSettingsScreen.kt",
        // V1.9.62 Begegnungsraum: the room list is held strictly from the start (its create form is collapsed behind one title-row button).
        "EncounterSpaceScreen.kt",
    )

private fun r36bActual(): Map<String, List<String>> =
    clientKotlinFiles()
        .associate { it.name to visibleCreateFormFindings(it.readText()) }
        .filterValues { it.isNotEmpty() }

private fun r36bLedger(): Map<String, List<String>> = (R36B_EXEMPT + R36B_NOT_YET_COLLAPSED).mapValues { it.value.fingerprints }

private fun scanFile(
    rule: String,
    file: File,
): List<String> {
    val text = file.readText()
    return when (rule) {
        R2 -> fixedWideWidthFindings(text)
        R9 -> colourLiteralFindings(text)
        R14 -> tablesWithoutDensityTypesFindings(text)
        R15 -> tablesWithoutResponsiveFindings(text)
        R39 -> iconOnlyButtonFindings(text).filterNot { it in R39_JUSTIFIED[file.name].orEmpty() }
        R55 -> fixedWidthFindings(text)
        else -> error("unknown rule $rule")
    }
}

private fun clientKotlinFiles(): List<File> = CLIENT_KOTLIN_DIR.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

private fun actualFindings(rule: String): Map<String, List<String>> =
    clientKotlinFiles()
        .associate { it.name to scanFile(rule = rule, file = it) }
        .filterValues { it.isNotEmpty() }

/**
 * Differences between the [actual] findings and the [ledger] as readable lines: `NEW` (found, not listed --
 * a new violation) and `PAID OFF` (listed, not found any more -- delete the ledger line). A multiset per file,
 * so "one fixed, one new" in the same file yields both lines.
 */
internal fun ledgerDiff(
    actual: Map<String, List<String>>,
    ledger: Map<String, List<String>>,
): List<String> {
    val diff = mutableListOf<String>()
    (actual.keys + ledger.keys).toSortedSet().forEach { file ->
        val unlisted = actual[file].orEmpty().toMutableList()
        val stale = mutableListOf<String>()
        ledger[file].orEmpty().forEach { listed -> if (!unlisted.remove(listed)) stale += listed }
        unlisted.forEach { diff += "NEW      $file: $it" }
        stale.forEach { diff += "PAID OFF $file: $it (delete this ledger line)" }
    }
    return diff
}

// ── CSS rules ─────────────────────────────────────────────────────────────────────────────────────────

/** One innermost CSS rule: its selector list, its declarations and the at-rule preludes it sits in. */
internal data class CssRule(
    val selector: String,
    val body: String,
    val atRules: List<String>,
)

private fun stripCssComments(css: String): String = css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")

/** Minimal brace-walking CSS reader (comments stripped) -- enough for the flat, hand-written `theme.css`. */
internal fun parseCssRules(css: String): List<CssRule> {
    val text = stripCssComments(css)
    val rules = mutableListOf<CssRule>()
    val preludes = ArrayDeque<String>()
    val bodies = ArrayDeque<StringBuilder>()
    var prelude = StringBuilder()
    for (ch in text) {
        when (ch) {
            '{' -> {
                preludes.addLast(prelude.toString().trim())
                bodies.addLast(StringBuilder())
                prelude = StringBuilder()
            }
            '}' -> {
                val body = bodies.removeLast().toString()
                val selector = preludes.removeLast()
                val nested = body.contains('{') || body.contains('}')
                if (!nested && !selector.startsWith("@")) {
                    rules += CssRule(selector = selector, body = body, atRules = preludes.toList())
                }
                bodies.lastOrNull()?.append(body)
                prelude = StringBuilder()
            }
            else -> {
                if (bodies.isEmpty()) {
                    prelude.append(ch)
                } else {
                    bodies.last().append(ch)
                    prelude.append(ch)
                }
            }
        }
    }
    return rules
}

private fun selectorsOf(rule: CssRule): List<String> =
    rule.selector
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

private const val REDUCED_MOTION = "@media (prefers-reduced-motion: reduce)"

/** `true` if the block starting at [blockStart] closes and nothing but whitespace follows it. */
private fun isLastBlock(
    css: String,
    blockStart: Int,
): Boolean {
    var depth = 0
    var i = css.indexOf('{', blockStart)
    while (i < css.length) {
        if (css[i] == '{') depth++
        if (css[i] == '}') {
            depth--
            if (depth == 0) return css.substring(i + 1).isBlank()
        }
        i++
    }
    return false
}

/** Longest allowed `transition` duration in milliseconds (R54: motion is a hint, not a show). */
internal const val MAX_TRANSITION_MS = 200

private val TIME_VALUE = Regex("""(?<![\w.-])(\d*\.?\d+)(ms|s)(?![\w-])""")

private fun millis(match: MatchResult): Int {
    val value = match.groupValues[1].toDouble()
    return (if (match.groupValues[2] == "s") value * 1000 else value).roundToInt()
}

/**
 * The durations (ms) a `transition` shorthand or `transition-duration` value declares: per comma-separated
 * transition the FIRST time value is the duration (the second would be the delay, which is not capped here).
 * `transition: none` has none.
 */
internal fun transitionDurationsMs(
    property: String,
    value: String,
): List<Int> =
    if (property == "transition-duration") {
        TIME_VALUE.findAll(value).map { millis(it) }.toList()
    } else {
        value.split(",").mapNotNull { part -> TIME_VALUE.find(part)?.let { millis(it) } }
    }

private val TRANSITION_DECLARATION = Regex("""(?:^|[;\s])(transition(?:-duration)?)\s*:\s*([^;]+)""")

/**
 * R54: every selector with a `transition:` is listed in the ONE `prefers-reduced-motion` block, and vice
 * versa -- and no transition outside that block is longer than [MAX_TRANSITION_MS].
 */
internal fun reducedMotionViolations(css: String): List<String> {
    val rules = parseCssRules(css)
    val violations = mutableListOf<String>()
    val reducedBlocks = css.split(REDUCED_MOTION).size - 1
    if (reducedBlocks != 1) violations += "expected exactly one $REDUCED_MOTION block, found $reducedBlocks"
    if (reducedBlocks == 1 && !isLastBlock(css = css, blockStart = css.indexOf(REDUCED_MOTION))) {
        violations += "the $REDUCED_MOTION block must be the last block of the file"
    }
    val transitionRules =
        rules
            .filter { REDUCED_MOTION !in it.atRules }
            .filter { Regex("""(^|[;\s])transition\s*:""").containsMatchIn(it.body) }
    val transitionSelectors = transitionRules.flatMap { selectorsOf(it) }.toSet()
    val reducedSelectors = rules.filter { REDUCED_MOTION in it.atRules }.flatMap { selectorsOf(it) }.toSet()
    (transitionSelectors - reducedSelectors).forEach { violations += "transition without reduced-motion rule: $it" }
    (reducedSelectors - transitionSelectors).forEach { violations += "reduced-motion rule without a transition: $it" }
    rules
        .filter { REDUCED_MOTION !in it.atRules }
        .forEach { rule ->
            TRANSITION_DECLARATION.findAll(rule.body).forEach { declaration ->
                transitionDurationsMs(property = declaration.groupValues[1], value = declaration.groupValues[2]).forEach { ms ->
                    if (ms > MAX_TRANSITION_MS) violations += "transition longer than $MAX_TRANSITION_MS ms ($ms ms): ${rule.selector}"
                }
            }
        }
    return violations
}

private val DECLARATION = Regex("""(--lapis-[a-z0-9-]+)\s*:\s*([^;]+);""")

private const val LIGHT_SELECTOR = ":root"
private const val SYSTEM_DARK_SELECTOR = ":root:not([data-theme=\"light\"])"
private const val EXPLICIT_DARK_SELECTOR = ":root[data-theme=\"dark\"]"
private const val DARK_SCHEME = "@media (prefers-color-scheme: dark)"

/** The custom properties of every `:root` block of one kind, merged (a token may be split over several blocks). */
private fun tokensOf(
    rules: List<CssRule>,
    selector: String,
    inAtRule: String?,
): Map<String, String> =
    rules
        .filter { rule -> rule.selector == selector && (if (inAtRule == null) rule.atRules.isEmpty() else inAtRule in rule.atRules) }
        .flatMap { DECLARATION.findAll(it.body).toList() }
        .associate { it.groupValues[1] to it.groupValues[2].trim() }

/** The three theme layers of `theme.css`: light `:root`, system-dark and explicit-dark (ALL blocks of each). */
internal class ThemeTokens(
    val light: Map<String, String>,
    val systemDark: Map<String, String>,
    val explicitDark: Map<String, String>,
)

internal fun themeTokens(css: String): ThemeTokens {
    val rules = parseCssRules(css)
    return ThemeTokens(
        light = tokensOf(rules = rules, selector = LIGHT_SELECTOR, inAtRule = null),
        systemDark = tokensOf(rules = rules, selector = SYSTEM_DARK_SELECTOR, inAtRule = DARK_SCHEME),
        explicitDark = tokensOf(rules = rules, selector = EXPLICIT_DARK_SELECTOR, inAtRule = null),
    )
}

private val COLOUR_VALUE =
    Regex("""#[0-9a-fA-F]{3,8}\b|\b(?:rgb|rgba|hsl|hsla|hwb|lab|lch|oklab|oklch|color|color-mix)\(""", RegexOption.IGNORE_CASE)
private val COLOUR_KEYWORDS = setOf("white", "black", "transparent", "currentcolor")
private val VAR_ALIAS = Regex("""var\(\s*(--lapis-[a-z0-9-]+)""")

/**
 * The names of the colour tokens in [tokens]: a value with a hex colour, a colour function (`rgb(`, `rgba(`,
 * `hsl(`, `color-mix(` ...), a colour keyword, or a `var(--lapis-x)` alias of another colour token. Font
 * stacks, widths and other non-colour tokens (`--lapis-font-body`, `--lapis-sidebar-width`) are deliberately
 * NOT colour tokens: they have no dark counterpart.
 */
internal fun colourTokenNames(tokens: Map<String, String>): Set<String> {
    val colours = tokens.filterValues { COLOUR_VALUE.containsMatchIn(it) || it.lowercase() in COLOUR_KEYWORDS }.keys.toMutableSet()
    var grew = true
    while (grew) {
        grew = false
        tokens.forEach { (name, value) ->
            val alias = VAR_ALIAS.find(value)?.groupValues?.get(1)
            if (name !in colours && alias != null && alias in colours) grew = colours.add(name) || grew
        }
    }
    return colours
}

/**
 * R11: every colour token of ANY light `:root` block exists in both dark blocks, no colour token exists only
 * in a dark block, and the dark blocks agree.
 */
internal fun tokenParityViolations(css: String): List<String> {
    val tokens = themeTokens(css)
    val colourTokens = colourTokenNames(tokens.light)
    val violations = mutableListOf<String>()
    (colourTokens - tokens.systemDark.keys).forEach { violations += "$it missing in the system-dark block" }
    (colourTokens - tokens.explicitDark.keys).forEach { violations += "$it missing in the explicit-dark block" }
    (colourTokenNames(tokens.systemDark) + colourTokenNames(tokens.explicitDark) - colourTokens).forEach {
        violations += "$it is a colour token of a dark block without a light value"
    }
    (tokens.systemDark.keys + tokens.explicitDark.keys).toSet().forEach { name ->
        if (tokens.systemDark[name] != tokens.explicitDark[name]) violations += "$name differs between the two dark blocks"
    }
    return violations
}

// ── the marker leak (V1.4.30 audit, M2): `tr(...)` as an ARGUMENT of `gettext` ─────────────────────────────
// `gettext("... %1", tr("Kein Konto"))` substitutes KVision's marker prefix (`###KvI18nS###`) into a text that is already resolved --
// it is VISIBLE on screen. Security audit W6b follow-up (major finding 1, round 2) REMOVED the manager's marker-argument
// resolution as a blocker: trust-by-content ("the argument starts with the marker") is exactly the forgery this app's `trFormat`
// KDoc warns against, so `I18nCatalogManager.gettext` now unconditionally SANITIZES every `String` argument (strips the marker,
// never resolves it) instead. This source-level rule is therefore both the fence AND the net: there is no manager-side safety
// net left to fall back on, so a `tr(...)` result passed here shows the unresolved German `msgid` (marker stripped, never
// translated) on screen -- the rule below must stay enforced. Same for a message that a `FieldCheck`/`AmountInput` carries: it
// must be RESOLVED text (`gettext`).

/** The text of the call whose `(` is at [openParen], through its balanced `)`; string literals are skipped, so a `)` inside a text does not count. */
private fun balancedCall(
    code: String,
    openParen: Int,
): String {
    var depth = 0
    var index = openParen
    var inString = false
    while (index < code.length) {
        val c = code[index]
        if (inString) {
            if (c == '\\') {
                index++
            } else if (c == '"') {
                inString = false
            }
        } else {
            when (c) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(openParen, index + 1)
                }
            }
        }
        index++
    }
    return code.substring(openParen)
}

/** [text] with the CONTENT of every string literal removed, so `"str(x)"` is not seen as a `tr(` call. */
private fun withoutStringContents(text: String): String {
    val out = StringBuilder()
    var inString = false
    var index = 0
    while (index < text.length) {
        val c = text[index]
        if (inString) {
            if (c == '\\') {
                index++
            } else if (c == '"') {
                inString = false
                out.append('"')
            }
        } else {
            out.append(c)
            if (c == '"') inString = true
        }
        index++
    }
    return out.toString()
}

private val TR_CALL = Regex("""(?<![A-Za-z_.])tr\(""")

/** One finding per call of [callStart] (a regex ending in `\(`) whose arguments contain a `tr(` call. */
internal fun trInsideCallFindings(
    text: String,
    callStart: Regex,
): List<String> =
    callStart
        .findAll(codeOnly(text))
        .mapNotNull { match ->
            val call = balancedCall(code = codeOnly(text), openParen = match.range.last)
            if (TR_CALL.containsMatchIn(withoutStringContents(call))) {
                call
                    .lines()
                    .first()
                    .take(120)
                    .trim()
            } else {
                null
            }
        }.toList()

private val GETTEXT_CALL = Regex("""\bgettext\(""")
private val INVALID_CALL = Regex("""\b(?:FieldCheck|AmountInput)\.Invalid\(""")

/**
 * Forms per migrated file (a FLOOR, not an exact count): `contains("lapisForm(")` alone would let a file lose all but one of its forms and stay
 * green. Removing a form on purpose lowers the number here in the same commit; adding one needs no edit.
 */
private val R24_MIN_FORMS: Map<String, Int> =
    mapOf(
        "LedgerScreen.kt" to 3,
        "OpenItemsScreen.kt" to 1,
        "OpenItemDialogs.kt" to 3,
        "SepaBatchesScreen.kt" to 2,
        "SepaMandateSection.kt" to 1,
        "DonorsScreen.kt" to 2,
        "BankAccountsScreen.kt" to 3,
        "BankStatementImportScreen.kt" to 2,
        "CostCentersScreen.kt" to 2,
        "AccountingExportScreen.kt" to 4,
    )

// ── the spec ──────────────────────────────────────────────────────────────────────────────────────────

class ClientUiGuidelineTripwireTest :
    FunSpec({
        test("the scanned client sources exist and are plentiful (tripwire is not vacuous)") {
            CLIENT_KOTLIN_DIR.isDirectory shouldBe true
            THEME_CSS.isFile shouldBe true
            (clientKotlinFiles().size > 50) shouldBe true
        }

        listOf(R2, R9, R14, R15, R39, R55).forEach { rule ->
            test("$rule: findings per file equal the debt ledger exactly (NEW = new violation, PAID OFF = delete the ledger line)") {
                ledgerDiff(actual = actualFindings(rule), ledger = BASELINE.getValue(rule)) shouldBe emptyList()
            }
        }

        test("R14/R15 see every table( call of the client, also the ones broken before the dot (7 including the standardTable helper)") {
            clientKotlinFiles().sumOf { tableCalls(it.readText()).size } shouldBe 7
        }

        test("C7c: no reportTable( call of the client carries sort wiring (a report is never sortable)") {
            clientKotlinFiles().flatMap { reportTableSortFindings(it.readText()).map { finding -> "${it.name}: $finding" } } shouldBe
                emptyList()
            // not vacuous: the client really has report tables, and the scanner finds them
            (clientKotlinFiles().sumOf { REPORT_TABLE_CALL.findAll(codeOnly(it.readText())).count() } > 10) shouldBe true
        }

        test("C7c flags a reportTable call with onSort/sort/sortOptions/sortKey, accepts a plain one and a dataTable") {
            reportTableSortFindings("panel.reportTable(caption = c, headers = h, onSort = { })").size shouldBe 1
            reportTableSortFindings("panel.reportTable(caption = c, headers = h, sort = state)").size shouldBe 1
            reportTableSortFindings("panel.reportTable(caption = c, headers = h) { sortOptions = o }").size shouldBe 1
            reportTableSortFindings("panel.reportTable(caption = c, headers = h)").size shouldBe 0
            reportTableSortFindings("panel.reportTable(caption = c, headers = h, captionVisible = false)").size shouldBe 0
            reportTableSortFindings("panel.dataTable(columns = c, rows = r, onSort = { })").size shouldBe 0
            reportTableSortFindings("// panel.reportTable(caption = c, headers = h, onSort = { })").size shouldBe 0
            reportTableSortFindings("fun Container.reportTable(caption: String, headers: List<TableHeader>): Table {").size shouldBe 0
        }

        test("R24: the migrated screens hold no labelled text/password/textArea call and build their forms with lapisForm") {
            val byName = clientKotlinFiles().associateBy { it.name }
            (R24_MIGRATED + R24_STRICT_WITHOUT_FORM).forEach { name ->
                val file = byName.getValue(name)
                r24Findings(file) shouldBe emptyList()
            }
            R24_MIGRATED.forEach { name ->
                byName.getValue(name).readText().contains("lapisForm(") shouldBe true
            }
        }

        test(
            "R24: every lapisForm( of a migrated screen is closed by buttons( or finish( (the form is completed, required marks are decided)",
        ) {
            val byName = clientKotlinFiles().associateBy { it.name }
            R24_MIGRATED.forEach { name ->
                val code = codeOnly(byName.getValue(name).readText())
                val forms = Regex("""\blapisForm\(""").findAll(code).count()
                val closers = Regex("""\.(?:buttons|finish)\(""").findAll(code).count()
                (closers >= forms) shouldBe true
            }
        }

        test("R24 justified exemptions are still needed (a stale exemption must go)") {
            R24_JUSTIFIED.forEach { (fileName, fingerprints) ->
                val raw = clientKotlinFiles().first { it.name == fileName }.readText().let { labelledFieldFindings(it) }
                withClue(fileName) { fingerprints.minusMultiset(raw) shouldBe emptyList() }
            }
        }

        test("R24 ratchet: the labelled fields outside the strict set only ever go down, and the scanner is not vacuous") {
            val strict = R24_MIGRATED + R24_STRICT_WITHOUT_FORM
            val outside = clientKotlinFiles().filter { it.name !in strict }.sumOf { labelledFieldFindings(it.readText()).size }
            // Not vacuous: the not yet migrated W4b2/W4c screens are full of them. The floor sits just under the ratchet
            // ([R24_REMAINING_MAX] - 5): a scanner that stopped finding most of them (a broken regex) fails HERE, instead of
            // passing a ratchet that only ever checks "not more". Lowering the ratchet lowers the floor with it.
            withClue("labelled fields outside the strict set: $outside") {
                (outside >= R24_REMAINING_MAX - 5) shouldBe true
                (outside <= R24_REMAINING_MAX) shouldBe true
            }
        }

        test("R24 flags a labelled text/password/textArea call, ignores gettext/richText/textField, unlabelled calls and comments") {
            labelledFieldFindings("val a = panel.text(label = tr(\"E-Mail\"))") shouldBe
                listOf("val a = panel.text(label = tr(\"E-Mail\"))")
            labelledFieldFindings("val a = text(type = InputType.EMAIL, label = tr(\"E-Mail\"))").size shouldBe 1
            labelledFieldFindings("root.password(label = tr(\"Passwort\")) { addCssClasses(\"x\") }").size shouldBe 1
            labelledFieldFindings("body.textArea(rows = 2, label = tr(\"Begründung\"))").size shouldBe 1
            labelledFieldFindings("form.passwordField(label = tr(\"Passwort\"))").size shouldBe 0
            labelledFieldFindings("form.textField(label = tr(\"E-Mail\"))").size shouldBe 0
            labelledFieldFindings("notifyError(gettext(\"Fehler\", label = x))").size shouldBe 0
            labelledFieldFindings("val t = richText(label = x)").size shouldBe 0
            labelledFieldFindings("root.textArea(value = snippet, rows = 12) { readonly = true }").size shouldBe 0
            labelledFieldFindings("// panel.text(label = tr(\"E-Mail\"))").size shouldBe 0
        }

        test("R29 ratchet: writing AppScope.launch blocks without a guard in the migrated files only ever go down") {
            val findings =
                clientKotlinFiles()
                    .filter { it.name in R24_MIGRATED && it.name != "FormGrammar.kt" }
                    .flatMap { file -> unguardedWriteLaunchFindings(file.readText()).map { "${file.name}: $it" } }
            withClue("unguarded writing launches: $findings") {
                (findings.size <= R29_UNGUARDED_WRITE_LAUNCH_MAX) shouldBe true
                // not vacuous: a scanner that stopped finding them (a broken regex) fails here, not silently
                (findings.size >= R29_UNGUARDED_WRITE_LAUNCH_MAX - 5) shouldBe true
            }
        }

        test("R29 flags a writing launch, ignores reads, guarded blocks, hand-written reads and comments") {
            unguardedWriteLaunchFindings("AppScope.launch {\n    val r = guarded { rpcService<IThing>().deleteThing(id) }\n}").size shouldBe
                1
            unguardedWriteLaunchFindings("AppScope.launch { guarded { rpcService<IThing>().listThings() } }").size shouldBe 0
            unguardedWriteLaunchFindings("AppScope.launch { guarded { rpcService<IThing>().getThing(id) } }").size shouldBe 0
            unguardedWriteLaunchFindings("AppScope.launch { guarded { rpcService<IEventService>().openCheckIn(id) } }").size shouldBe 0
            unguardedWriteLaunchFindings("AppScope.launch { guarded { rpcService<IGov>().openVote(input) } }").size shouldBe 1
            unguardedWriteLaunchFindings(
                "AppScope.launch { form.runBusy(b) { guarded { rpcService<IThing>().deleteThing(id) } } }",
            ).size shouldBe
                0
            unguardedWriteLaunchFindings("AppScope.launch { runGuardedAction(b) { rpcService<IThing>().deleteThing(id) } }").size shouldBe 0
            unguardedWriteLaunchFindings("// AppScope.launch { rpcService<IThing>().deleteThing(id) }").size shouldBe 0
            // a read-prefixed NAME that merely starts with the letters (`island`) is a write
            unguardedWriteLaunchFindings("AppScope.launch { rpcService<IThing>().islandCreate() }").size shouldBe 1
        }

        test(
            "R36B: the visible create forms equal the exemptions plus the debt ledger exactly (NEW = collapse it, PAID OFF = delete the line)",
        ) {
            val diff = ledgerDiff(actual = r36bActual(), ledger = r36bLedger())
            withClue(diff.joinToString(separator = "\n", prefix = "\n")) { diff shouldBe emptyList() }
        }

        test("R36B: the converted pilot screens use collapsibleCreateForm and hold no visible create form") {
            val byName = clientKotlinFiles().associateBy { it.name }
            R36B_CONVERTED.forEach { name ->
                withClue(name) {
                    byName.getValue(name).readText().contains("collapsibleCreateForm") shouldBe true
                    visibleCreateFormFindings(byName.getValue(name).readText()) shouldBe emptyList()
                }
            }
        }

        test("R36B: every exemption and every debt entry names its reason, and no file is in both lists") {
            (R36B_EXEMPT + R36B_NOT_YET_COLLAPSED).forEach { (file, entry) ->
                withClue(file) { entry.reason.isNotBlank() shouldBe true }
            }
            R36B_EXEMPT.keys.intersect(R36B_NOT_YET_COLLAPSED.keys) shouldBe emptySet()
            R36B_CONVERTED.intersect((R36B_EXEMPT + R36B_NOT_YET_COLLAPSED).keys) shouldBe emptySet()
        }

        test("R36B ratchet (V1.9.49): the debt ledger is empty and the scanner is not vacuous") {
            R36B_NOT_YET_COLLAPSED shouldBe emptyMap()
            R36B_NOT_YET_COLLAPSED.values.sumOf { it.fingerprints.size } shouldBe R36B_REMAINING_MAX
            // The scanner must still FIND the exempt findings, otherwise "no finding anywhere" would prove nothing.
            val found = r36bActual().values.sumOf { it.size }
            withClue("exempt findings the scanner sees: $found") { found shouldBe R36B_EXEMPT.values.sumOf { it.fingerprints.size } }
            R36B_EXEMPT.values.sumOf { it.fingerprints.size } shouldBe 4
        }

        test("R36B flags a create-section title and an unwrapped create-form call, ignores a wrapped one, a declaration and comments") {
            visibleCreateFormFindings("root.h2(tr(\"Neues Konto anlegen\")) { addCssClass(\"h5\") }").size shouldBe 1
            visibleCreateFormFindings("renderCommitteeCreation(root, ::refresh)") shouldBe
                listOf("renderCommitteeCreation(root, ::refresh)")
            visibleCreateFormFindings("renderMeetingCreationForm(panel, a, b) { reload() }").size shouldBe 1
            visibleCreateFormFindings("internal fun renderCommitteeCreation(\n    root: SimplePanel,\n)").size shouldBe 0
            visibleCreateFormFindings(
                "collapsibleCreateForm<Unit>(\n    actionSlot = a,\n) { _, close -> renderCommitteeCreation(this, ::refresh, close) }",
            ).size shouldBe 0
            visibleCreateFormFindings("// renderCommitteeCreation(root, ::refresh)\n* root.h2(tr(\"Neues Gremium\"))").size shouldBe 0
            visibleCreateFormFindings("root.h2(tr(\"Übersicht\"))").size shouldBe 0
            // a call after the wrapped one is not covered by it
            visibleCreateFormFindings(
                "collapsibleCreateForm<Unit>(a) { _, c -> renderXCreation(this, c) }\nrenderXCreation(root, f)",
            ).size shouldBe 1
        }

        test("R36C (V1.9.51): the start/new buttons of the content equal the exemptions exactly, and the scanner is not vacuous") {
            val diff = ledgerDiff(actual = r36cActual(), ledger = R36C_EXEMPT.mapValues { it.value.fingerprints })
            withClue(diff.joinToString(separator = "\n", prefix = "\n")) { diff shouldBe emptyList() }
            R36C_EXEMPT.values.forEach { it.reason.isNotBlank() shouldBe true }
            R36C_EXEMPT.values.sumOf { it.fingerprints.size } shouldBe 1
            (r36cActual().values.sumOf { it.size } - R36C_EXEMPT.values.sumOf { it.fingerprints.size }) shouldBe R36C_REMAINING_MAX
        }

        test(
            "R36C finds a text-only start/new button, ignores an icon button, a refresh label, a row action, comments and a wrapped call",
        ) {
            startNewButtonFindings("lobbyPanel.button(tr(\"Besprechung jetzt starten\"), style = ButtonStyle.PRIMARY)").size shouldBe 1
            startNewButtonFindings("val b = Button(tr(\"Neuen Schlüssel ausstellen\"), style = ButtonStyle.PRIMARY)").size shouldBe 0
            startNewButtonFindings("val b = Button(tr(\"Neuer Schlüssel\"), style = ButtonStyle.PRIMARY)").size shouldBe 1
            startNewButtonFindings("val b = Button(text = tr(\"Neue Sitzung\"))").size shouldBe 1
            startNewButtonFindings(
                "val b = newActionButton(ActionIcon.ADD, tr(\"Besprechung jetzt starten\"), ButtonStyle.OUTLINEPRIMARY)",
            ).size shouldBe
                0
            startNewButtonFindings("root.button(tr(\"Neu laden\"))").size shouldBe 0
            startNewButtonFindings("root.button(tr(\"Neu ausstellen\"))").size shouldBe 0
            startNewButtonFindings("val b = Button(tr(\"Neues Passwort setzen\"))").size shouldBe 0
            startNewButtonFindings("// lobbyPanel.button(tr(\"Besprechung jetzt starten\"))\n* Button(tr(\"Neue Sitzung\"))").size shouldBe
                0
            startNewButtonFindings(
                "collapsibleCreateForm<Unit>(a) { _, c -> panel.button(tr(\"Neue Sitzung\")) }",
            ).size shouldBe 0
            startNewButtonFindings(
                "collapsibleCreateForm<Unit>(a) { _, c -> x() }\npanel.button(tr(\"Neue Sitzung\"))",
            ).size shouldBe 1
        }

        test("V1.9.51: ConferenceScreen shows and hides the lobby only through setConferenceLobbyVisible (the header action must follow)") {
            val screen = clientKotlinFiles().first { it.name == "ConferenceScreen.kt" }.readText()
            rawLobbyVisibilityCalls(screen) shouldBe emptyList()
            codeOnly(screen).contains("setConferenceLobbyVisible(") shouldBe true
            rawLobbyVisibilityCalls("lobbyPanel.hide()") shouldBe listOf("lobbyPanel.hide()")
            rawLobbyVisibilityCalls("    lobbyPanel.show()") shouldBe listOf("lobbyPanel.show()")
            rawLobbyVisibilityCalls("setConferenceLobbyVisible(lobbyPanel, false)") shouldBe emptyList()
            rawLobbyVisibilityCalls("// lobbyPanel.hide()\n* lobbyPanel.show()") shouldBe emptyList()
            rawLobbyVisibilityCalls("callPanel.hide()") shouldBe emptyList()
        }

        test("R36B (V1.9.49) also finds a bold div title, a Create...Form builder and a shared builder called with existing = null") {
            visibleCreateFormFindings("panel.div(tr(\"Neue Mailingliste anlegen\")) { addCssClass(\"fw-bold\") }").size shouldBe 1
            visibleCreateFormFindings("panel.div(tr(\"Neue Mailingliste anlegen\")) { addCssClass(\"fw-semibold\") }").size shouldBe 1
            // a div without the bold class, or one that is not a "Neu..." title, is no create-form title
            visibleCreateFormFindings("panel.div(tr(\"Neue Mailingliste anlegen\")) { addCssClass(\"text-muted\") }").size shouldBe 0
            visibleCreateFormFindings("panel.div(tr(\"Liste verwalten\")) { addCssClasses(\"fw-bold mt-2\") }").size shouldBe 0
            visibleCreateFormFindings("renderCreateMailingListForm(root, a) { }").size shouldBe 1
            visibleCreateFormFindings("fun SimplePanel.renderCreateMailingListForm(\n    a: A,\n)").size shouldBe 0
            visibleCreateFormFindings("renderDunningLevelForm(root, existing = null, onSaved = ::loadLevels)") shouldBe
                listOf("renderDunningLevelForm(root, existing = null, onSaved = ::loadLevels)")
            visibleCreateFormFindings("editPanel.renderTomForm(existing = tom) { editPanel.hide() }").size shouldBe 0
            visibleCreateFormFindings("renderDunningLevelForm(modal, existing = level, modal = modal) { }").size shouldBe 0
            val declaration = "internal fun renderDunningLevelForm(\n    root: SimplePanel,\n    existing: DunningLevelDto? = null,\n)"
            visibleCreateFormFindings(declaration).size shouldBe 0
            visibleCreateFormFindings(
                "collapsibleCreateForm<Unit>(a) { _, close -> vPanel().renderTomForm(existing = null, collapse = close, onSaved = ::r) }",
            ).size shouldBe 0
            visibleCreateFormFindings("// renderTomForm(existing = null)").size shouldBe 0
            visibleCreateFormFindings("renderTomForm(existing = null)\nrenderTomForm(existing = null)").size shouldBe 2
        }

        test("R36B (V1.9.45) also knows the finance builders: new-entry, new-batch, record-return, mandate and upload form") {
            visibleCreateFormFindings("renderNewEntryForm(panel, a)") shouldBe listOf("renderNewEntryForm(panel, a)")
            visibleCreateFormFindings(
                "collapsibleCreateForm<Unit>(a) { _, c -> renderNewEntryForm(this, a, collapse = c) }",
            ).size shouldBe 0
            visibleCreateFormFindings("renderSepaMandateForm(").size shouldBe 1
            visibleCreateFormFindings("renderNewBatchSection(root) { reload() }").size shouldBe 1
            visibleCreateFormFindings("renderRecordReturnForm(root) { reload() }").size shouldBe 1
            visibleCreateFormFindings("internal fun renderUploadPanel(\n    panel: SimplePanel,\n)").size shouldBe 0
            visibleCreateFormFindings("renderUploadPanel(box, onUploadStarted = {}) { }").size shouldBe 1
        }

        test("R36B (V1.9.45): every collapsed finance and events form hangs its button in the page header's action slot") {
            val byName = clientKotlinFiles().associateBy { it.name }
            val financeFiles =
                listOf(
                    "CostCentersScreen.kt",
                    "DonorsScreen.kt",
                    "LedgerScreen.kt",
                    "OpenItemsScreen.kt",
                    "SepaBatchesScreen.kt",
                    "SepaMandatesScreen.kt",
                    "BankStatementImportScreen.kt",
                    "EventsScreen.kt",
                    "EventRoomsScreen.kt",
                    "EventVolunteerShiftsScreen.kt",
                    "CateringScreen.kt",
                    "CrmContactsScreen.kt",
                    "CrmCollapsibleForms.kt",
                    "CrowdfundingScreen.kt",
                    "MemberAdministrationScreen.kt",
                    "RegionalChaptersScreen.kt",
                    "AuctionScreen.kt",
                    "ConferenceStreamDestinationsScreen.kt",
                    "DocumentsScreen.kt",
                    "DsgvoComplianceScreen.kt",
                    "CommunicationScreen.kt",
                    "TravelExpenseScreen.kt",
                    "VolunteerAllowanceScreen.kt",
                    "DunningSettingsScreen.kt",
                    "ReceivableDunningSettingsScreen.kt",
                )
            financeFiles.forEach { name ->
                val code = codeOnly(byName.getValue(name).readText())
                COLLAPSIBLE_CALL.findAll(code).forEach { match ->
                    val lambda = callWithTrailingLambda(text = code, openParen = match.range.last)
                    val call = code.substring(match.range.first, match.range.last + lambda.length)
                    withClue("$name: ${call.take(120)}") {
                        (
                            Regex(
                                """actionSlot\s*=\s*(?:header\.actionSlot|entrySlot|accountSlot|distributionSlot|titleSlot|folderSlot|documentButtonSlot|draftButtonSlot)""",
                            ).containsMatchIn(call)
                        ) shouldBe
                            true
                    }
                }
            }
        }

        test("R24B: the strict screens hold no labelled select/checkBox call outside their justified fingerprints") {
            val byName = clientKotlinFiles().associateBy { it.name }
            (R24_MIGRATED + R24_STRICT_WITHOUT_FORM).forEach { name ->
                withClue(name) { r24bFindings(byName.getValue(name)) shouldBe emptyList() }
            }
        }

        test("R24B justified exemptions are still needed (a stale exemption must go)") {
            R24B_JUSTIFIED.forEach { (fileName, fingerprints) ->
                val raw = clientKotlinFiles().first { it.name == fileName }.readText().let { labelledSelectFindings(it) }
                withClue(fileName) { fingerprints.minusMultiset(raw) shouldBe emptyList() }
            }
        }

        test("R24B ratchet: the labelled choice fields outside the strict set only ever go down, and the scanner is not vacuous") {
            val strict = R24_MIGRATED + R24_STRICT_WITHOUT_FORM
            val outside = clientKotlinFiles().filter { it.name !in strict }.sumOf { labelledSelectFindings(it.readText()).size }
            withClue("labelled select/checkBox fields outside the strict set: $outside") {
                (outside >= R24B_REMAINING_MAX - 5) shouldBe true
                (outside <= R24B_REMAINING_MAX) shouldBe true
            }
        }

        test("R24B flags a labelled select/checkBox call, ignores segmentedControl, selectFilter, selectField, checkField and comments") {
            labelledSelectFindings("val s = panel.select(options = o, label = tr(\"Rolle\"))") shouldBe
                listOf("val s = panel.select(options = o, label = tr(\"Rolle\"))")
            labelledSelectFindings("val c = filterRow.checkBox(label = tr(\"Aktiv\"))").size shouldBe 1
            labelledSelectFindings("topRow.select(options = o, value = v, label = tr(\"Status\")) { addCssClass(\"x\") }").size shouldBe 1
            labelledSelectFindings("val s = select(options = o, label = tr(\"Rolle\"))").size shouldBe 1
            labelledSelectFindings("form.selectField(label = tr(\"Rolle\"), options = o)").size shouldBe 0
            labelledSelectFindings("form.checkField(label = tr(\"Aktiv\"))").size shouldBe 0
            labelledSelectFindings("panel.segmentedControl(label = tr(\"Ansicht\"))").size shouldBe 0
            labelledSelectFindings("panel.selectFilter(label = tr(\"Status\"))").size shouldBe 0
            labelledSelectFindings("val s = panel.select(options = o)").size shouldBe 0
            labelledSelectFindings("// panel.select(options = o, label = tr(\"Rolle\"))").size shouldBe 0
        }

        test("R24/R24B fingerprints tell multi-line calls on one receiver apart by their label literal (no receiver-wide exemption)") {
            labelledSelectFindings("filterRow.select(\n    options = o,\n    label = tr(\"Zeitraum\"),\n)") shouldBe
                listOf("filterRow.select( [label \"Zeitraum\"]")
            labelledSelectFindings(
                "filterRow.select(\n    options = o,\n    label = tr(\"A\"),\n)\nfilterRow.select(\n    options = o,\n    label = tr(\"B\"),\n)",
            ) shouldBe listOf("filterRow.select( [label \"A\"]", "filterRow.select( [label \"B\"]")
            labelledFieldFindings("val t = panel.text(\n    label = tr(\"Suche\"),\n)") shouldBe
                listOf("val t = panel.text( [label \"Suche\"]")
            // a call that carries its label on the first line keeps the plain line
            labelledSelectFindings("val s = panel.select(options = o, label = tr(\"Rolle\"))") shouldBe
                listOf("val s = panel.select(options = o, label = tr(\"Rolle\"))")
        }

        test("a justified fingerprint justifies exactly as many calls as it is listed (multiset, not set)") {
            listOf("a", "a", "b").minusMultiset(listOf("a")) shouldBe listOf("a", "b")
            listOf("a").minusMultiset(listOf("a", "a")) shouldBe emptyList()
            // a second identical call in a justified file is a finding again
            listOf("x.text(label = tr(\"A\"))", "x.text(label = tr(\"A\"))").minusMultiset(listOf("x.text(label = tr(\"A\"))")) shouldBe
                listOf("x.text(label = tr(\"A\"))")
        }

        test("the coarse-pointer rule gives checkboxes a 44 px target too (M-8 of the V1.4.29 audit)") {
            val coarse =
                parseCssRules(THEME_CSS.readText())
                    .filter { "@media (pointer: coarse)" in it.atRules }
                    .filter { "min-height: 44px" in it.body }
                    .flatMap { selectorsOf(it) }
            (".lapis-form .form-check" in coarse) shouldBe true
            (".lapis-form .form-check .form-check-label" in coarse) shouldBe true
        }

        test("V1.9.66: the chat composer's field and send button reach a 44 px target under pointer: coarse") {
            val coarse =
                parseCssRules(THEME_CSS.readText())
                    .filter { "@media (pointer: coarse)" in it.atRules }
            val minHeight = coarse.filter { "min-height: 44px" in it.body }.flatMap { selectorsOf(it) }
            val minWidth = coarse.filter { "min-width: 44px" in it.body }.flatMap { selectorsOf(it) }
            (".lapis-chat-composer .form-control" in minHeight) shouldBe true
            (".lapis-chat-composer-send" in minHeight) shouldBe true
            (".lapis-chat-composer-send" in minWidth) shouldBe true
        }

        test("R39 justified exemption is still needed (a stale exemption must go)") {
            R39_JUSTIFIED.forEach { (fileName, fingerprints) ->
                val raw = clientKotlinFiles().first { it.name == fileName }.readText().let { iconOnlyButtonFindings(it) }
                (fingerprints - raw.toSet()) shouldBe emptySet()
            }
        }

        test("R11: every colour token is defined in all three theme blocks, and both dark blocks agree") {
            tokenParityViolations(THEME_CSS.readText()) shouldBe emptyList()
        }

        test("R11 reads the real theme.css meaningfully: many colour tokens, and the second :root block is merged, not ignored") {
            val tokens = themeTokens(THEME_CSS.readText())
            (colourTokenNames(tokens.light).size > 30) shouldBe true
            tokens.light.containsKey("--lapis-sidebar-width") shouldBe true // lives in the second `:root` block
            colourTokenNames(tokens.light).contains("--lapis-sidebar-width") shouldBe false
        }

        test("R54: every transition is switched off by the single prefers-reduced-motion block, none is longer than 200 ms") {
            reducedMotionViolations(THEME_CSS.readText()) shouldBe emptyList()
        }

        test(
            "R54 names exactly the seven known transitions today (V1.9.62: the hand and event symbols of the encounter room; " +
                "V1.9.71: the floating conference window -- an inventory change, not a raised budget, still <= 200 ms and off under reduce)",
        ) {
            val css = THEME_CSS.readText()
            val reduced = parseCssRules(css).filter { REDUCED_MOTION in it.atRules }.flatMap { selectorsOf(it) }.toSet()
            reduced shouldBe
                setOf(
                    "body",
                    ".lapis-conference-controls-row",
                    ".lapis-conference-background-preview",
                    ".lapis-busy",
                    ".lapis-encounter-seat-hand",
                    ".lapis-encounter-seat-event",
                    ".lapis-conference-float",
                )
        }

        test("the 44 px touch-target rule under pointer: coarse leaves an inline btn-link alone") {
            val coarse =
                parseCssRules(THEME_CSS.readText())
                    .filter { "@media (pointer: coarse)" in it.atRules }
                    .filter { "min-height: 44px" in it.body }
                    .flatMap { selectorsOf(it) }
            (".btn:not(.btn-link)" in coarse) shouldBe true
            (".btn" in coarse) shouldBe false
        }

        test("W5: controls outside .lapis-form get the coarse-pointer 44 px target, and the form grammar's own rules are not touched") {
            val coarse =
                parseCssRules(THEME_CSS.readText())
                    .filter { "@media (pointer: coarse)" in it.atRules }
                    .flatMap { selectorsOf(it) }
            // Audit fix: no `:not(.lapis-form *)` any more (complex :not() is not reliable before Safari 16.4) -- the generic rules apply everywhere and
            // the more specific `.lapis-form` rules below them win with the same values.
            coarse.none { it.contains(":not(.lapis-form") } shouldBe true
            (".form-control" in coarse) shouldBe true
            (".form-select" in coarse) shouldBe true
            (".form-check" in coarse) shouldBe true
            (".lapis-form .form-check .form-check-input" in coarse) shouldBe true
        }

        test("audit M8: the remaining Bootstrap controls reach 44 px under pointer: coarse, and the skip link is not blown up at rest") {
            val rules = parseCssRules(THEME_CSS.readText()).filter { "@media (pointer: coarse)" in it.atRules }
            val touch = rules.filter { "min-height: 44px" in it.body }.flatMap { selectorsOf(it) }
            listOf(".btn-close", ".dropdown-item", ".navbar .nav-link", ".btn-link.lapis-icon-only").forEach { (it in touch) shouldBe true }
            // `.btn:not(.btn-link)` reaches the skip link (a `.btn`): at rest it must stay 1 x 1 px, so a later rule resets it
            val reset = rules.filter { "min-height: 0" in it.body && "min-width: 0" in it.body }.flatMap { selectorsOf(it) }
            (".lapis-skip-link:not(:focus)" in reset) shouldBe true
        }

        test(
            "audit B1: text-danger in table cells reads the calibrated red as a TEXT rule -- the inherited --bs-danger-rgb is NOT overridden",
        ) {
            val css = THEME_CSS.readText()
            val rules = parseCssRules(css)
            // The variable override leaked into `.text-bg-danger` (badge surface = RGBA(var(--bs-danger-rgb))): dark-mode badges became pale (2.53:1).
            rules.filter { "--bs-danger-rgb" in it.body }.flatMap { selectorsOf(it) } shouldBe emptyList()
            val textRule = rules.filter { "--lapis-total-danger-rgb" in it.body && "color:" in it.body }.flatMap { selectorsOf(it) }
            listOf(
                ".table > tbody > tr > td.text-danger",
                ".table > tbody > tr > th.text-danger",
                ".table > tbody > tr > td .text-danger",
                ".table > tbody > tr > th .text-danger",
            ).forEach { (it in textRule) shouldBe true }
            css.contains("--bs-danger-rgb: var(--lapis-total-danger-rgb)") shouldBe false
        }

        test("W5: the invalid icon no longer ignores --lapis-invalid, in every field with .is-invalid (not only inside .lapis-form)") {
            val rules = parseCssRules(THEME_CSS.readText())
            val noIcon = rules.filter { "background-image: none" in it.body }.flatMap { selectorsOf(it) }
            (("html .form-control.is-invalid") in noIcon) shouldBe true
            // The select keeps its chevron: no `background-image: none` there.
            (("html .form-select.is-invalid:not([multiple]):not([size])") in noIcon) shouldBe false
        }

        test("the new colour tokens exist in all three blocks") {
            val tokens = themeTokens(THEME_CSS.readText())
            val expected =
                setOf(
                    "--lapis-tile-bg",
                    "--lapis-tile-border",
                    "--lapis-tile-text",
                    "--lapis-media-bg",
                    "--lapis-media-overlay-text",
                    "--lapis-guest-fill",
                    "--lapis-guest-glyph",
                    "--lapis-invalid",
                )
            listOf(tokens.light, tokens.systemDark, tokens.explicitDark).forEach { block ->
                (expected - block.keys) shouldBe emptySet()
            }
        }

        // ── every rule proves itself against a bad and a good example ──

        test("R2 flags width >= 600px, ignores maxWidth, small widths and comments") {
            fixedWideWidthFindings("width = 960.px").size shouldBe 1
            fixedWideWidthFindings("    width = 600.px").size shouldBe 1
            fixedWideWidthFindings("maxWidth = 1440.px").size shouldBe 0
            fixedWideWidthFindings("width = 320.px").size shouldBe 0
            fixedWideWidthFindings("// width = 960.px").size shouldBe 0
        }

        test("R55 flags any fixed px width, ignores maxWidth/minWidth, comments and other units") {
            fixedWidthFindings("headerRow.div(tr(\"Betrag\")) { width = 130.px }") shouldBe
                listOf("headerRow.div(tr(\"Betrag\")) { width = 130.px }")
            fixedWidthFindings("width = 60.px").size shouldBe 1
            fixedWidthFindings("maxWidth = 900.px").size shouldBe 0
            fixedWidthFindings("minWidth = 640.px").size shouldBe 0
            fixedWidthFindings("width = 100.perc").size shouldBe 0
            fixedWidthFindings("// width = 130.px").size shouldBe 0
        }

        test("R9 flags hex, rgb/rgba/hsl/hsla and 0xRRGGBB literals, ignores comments, route anchors and short hex masks") {
            colourLiteralFindings("style = \"color:#fff;\"").size shouldBe 1
            colourLiteralFindings("val c = \"#A855F7\"").size shouldBe 1
            colourLiteralFindings("* the #FFFFFF glyph").size shouldBe 0
            colourLiteralFindings("style = \"background:rgba(0,0,0,0.55)\"").size shouldBe 1
            colourLiteralFindings("style = \"color: rgb(10, 20, 30)\"").size shouldBe 1
            colourLiteralFindings("style = \"color: hsl(210 50% 40%)\"").size shouldBe 1
            colourLiteralFindings("val c = 0xFFAA33").size shouldBe 1
            colourLiteralFindings("val mask = 0xFF").size shouldBe 0
            colourLiteralFindings("url = \"#members\"").size shouldBe 0
            colourLiteralFindings("// rgba(0,0,0,0.5)").size shouldBe 0
        }

        test("R14/R15 flag tables missing density types or the responsive frame, accept the full call") {
            val bad = "table(headerNames = listOf(\"a\"), types = setOf(TableType.STRIPED, TableType.HOVER))"
            tablesWithoutDensityTypesFindings(bad).size shouldBe 1
            tablesWithoutResponsiveFindings(bad).size shouldBe 1
            val good =
                "panel.table(\n headerNames = h,\n types = setOf(TableType.STRIPED, TableType.HOVER, TableType.SMALL),\n" +
                    " responsiveType = ResponsiveType.RESPONSIVE,\n)"
            tablesWithoutDensityTypesFindings(good).size shouldBe 0
            tablesWithoutResponsiveFindings(good).size shouldBe 0
            // standardTable / dataTable are not `table(` calls and are exactly what the rule wants.
            tablesWithoutDensityTypesFindings("panel.standardTable(headers)").size shouldBe 0
            tablesWithoutResponsiveFindings("panel.dataTable(columns = c, rows = r)").size shouldBe 0
            tablesWithoutResponsiveFindings("// panel.table(x)").size shouldBe 0
        }

        test("R14/R15 see a call chain broken before the dot (panel NEWLINE .table( ...)) -- the miss of the first regex") {
            val chained =
                "val t =\n    currentTable ?: listPanel\n        .table(\n            headerNames = h,\n" +
                    "            types = setOf(TableType.STRIPED),\n        )"
            tablesWithoutDensityTypesFindings(chained) shouldBe listOf("currentTable ?: listPanel .table(")
            tablesWithoutResponsiveFindings(chained) shouldBe listOf("currentTable ?: listPanel .table(")
            val chainedGood =
                "panel\n    .table(\n        types = setOf(TableType.STRIPED, TableType.HOVER, TableType.SMALL),\n" +
                    "        responsiveType = ResponsiveType.RESPONSIVE,\n    )"
            tablesWithoutDensityTypesFindings(chainedGood).size shouldBe 0
            tablesWithoutResponsiveFindings(chainedGood).size shouldBe 0
            // and the unchained forms keep working, also `table(` right after an opening bracket or `=`
            tablesWithoutResponsiveFindings("val t = table(headerNames = h)").size shouldBe 1
            tablesWithoutResponsiveFindings("run { table(headerNames = h) }").size shouldBe 1
        }

        test("R39 flags an icon-only button without title+aria-label, accepts one that carries both") {
            iconOnlyButtonFindings("button(\"\", icon = \"fas fa-pen\")").size shouldBe 1
            iconOnlyButtonFindings("button(\"\", icon = \"fas fa-pen\") { title = \"x\" }").size shouldBe 1
            iconOnlyButtonFindings(
                "button(\"\", icon = \"fas fa-pen\") {\n title = t\n setAttribute(\"aria-label\", t)\n}",
            ).size shouldBe 0
            iconOnlyButtonFindings("button(\"Speichern\", icon = \"fas fa-save\")").size shouldBe 0
        }

        test("R39 also flags the named-argument form button(text = \"\", icon = ...)") {
            iconOnlyButtonFindings("button(text = \"\", icon = \"fas fa-pen\")").size shouldBe 1
            iconOnlyButtonFindings("Button(text = \"\", icon = \"fas fa-pen\", style = s)").size shouldBe 1
            iconOnlyButtonFindings(
                "button(text = \"\", icon = \"fas fa-pen\") {\n title = t\n setAttribute(\"aria-label\", t)\n}",
            ).size shouldBe 0
            iconOnlyButtonFindings("button(text = \"Ok\", icon = \"fas fa-pen\")").size shouldBe 0
        }

        test("R11 flags a colour token missing from a dark block, and differing dark blocks") {
            val ok =
                ":root { --lapis-a: #FFF; }\n" +
                    "@media (prefers-color-scheme: dark) { :root:not([data-theme=\"light\"]) { --lapis-a: #000; } }\n" +
                    ":root[data-theme=\"dark\"] { --lapis-a: #000; }"
            tokenParityViolations(ok) shouldBe emptyList()
            val missing = ok.replace(":root[data-theme=\"dark\"] { --lapis-a: #000; }", ":root[data-theme=\"dark\"] { }")
            tokenParityViolations(missing).isNotEmpty() shouldBe true
            val differing =
                ok.replace(":root[data-theme=\"dark\"] { --lapis-a: #000; }", ":root[data-theme=\"dark\"] { --lapis-a: #111; }")
            tokenParityViolations(differing).isNotEmpty() shouldBe true
        }

        test("R11 covers non-hex colours: rgba(), hsl(), color-mix() and var() aliases need dark values too") {
            fun css(
                lightValue: String,
                dark: String,
            ) = ":root { --lapis-a: $lightValue; }\n" +
                "@media (prefers-color-scheme: dark) { :root:not([data-theme=\"light\"]) { $dark } }\n" +
                ":root[data-theme=\"dark\"] { $dark }"
            listOf("rgba(0, 0, 0, 0.5)", "hsl(210 50% 40%)", "color-mix(in srgb, #fff 50%, #000)", "white").forEach { value ->
                tokenParityViolations(css(lightValue = value, dark = "")).isNotEmpty() shouldBe true
                tokenParityViolations(css(lightValue = value, dark = "--lapis-a: rgba(1, 1, 1, 0.5);")) shouldBe emptyList()
            }
            val alias =
                ":root { --lapis-a: #fff; --lapis-b: var(--lapis-a); }\n" +
                    "@media (prefers-color-scheme: dark) { :root:not([data-theme=\"light\"]) { --lapis-a: #000; } }\n" +
                    ":root[data-theme=\"dark\"] { --lapis-a: #000; }"
            tokenParityViolations(alias).any { "--lapis-b" in it } shouldBe true
        }

        test("R11 reads every :root block and ignores non-colour tokens") {
            val css =
                ":root { --lapis-a: #FFF; }\n" +
                    ":root { --lapis-late: #ABC; --lapis-font: Georgia, serif; --lapis-width: 264px; }\n" +
                    "@media (prefers-color-scheme: dark) { :root:not([data-theme=\"light\"]) { --lapis-a: #000; } }\n" +
                    ":root[data-theme=\"dark\"] { --lapis-a: #000; }"
            // `--lapis-late` sits in the SECOND light block and has no dark value: must be found; font/width must not.
            tokenParityViolations(css).filter { "--lapis-late" in it }.size shouldBe 2
            tokenParityViolations(css).none { "--lapis-font" in it || "--lapis-width" in it } shouldBe true
        }

        test("R11 flags a colour token that exists only in a dark block") {
            val css =
                ":root { --lapis-a: #FFF; }\n" +
                    "@media (prefers-color-scheme: dark) { :root:not([data-theme=\"light\"]) { --lapis-a: #000; --lapis-x: #111; } }\n" +
                    ":root[data-theme=\"dark\"] { --lapis-a: #000; --lapis-x: #111; }"
            tokenParityViolations(css).any { "--lapis-x" in it } shouldBe true
        }

        test("R54 flags a transition without a reduced-motion rule, and a stale reduced-motion selector") {
            val ok = ".a { transition: opacity 1s; }\n@media (prefers-reduced-motion: reduce) { .a { transition: none; } }\n"
            reducedMotionViolations(ok).any { "longer than" in it } shouldBe true // 1 s is over the cap, the pairing is fine
            val fine = ".a { transition: opacity 0.2s ease; }\n@media (prefers-reduced-motion: reduce) { .a { transition: none; } }\n"
            reducedMotionViolations(fine) shouldBe emptyList()
            val missing =
                ".a { transition: opacity 0.1s; }\n.b { transition: color 0.1s; }\n" +
                    "@media (prefers-reduced-motion: reduce) { .a { transition: none; } }\n"
            reducedMotionViolations(missing).isNotEmpty() shouldBe true
            val stale = ".a { transition: opacity 0.1s; }\n@media (prefers-reduced-motion: reduce) { .a, .gone { transition: none; } }\n"
            reducedMotionViolations(stale).isNotEmpty() shouldBe true
            reducedMotionViolations(".a { transition: opacity 0.1s; }").isNotEmpty() shouldBe true
        }

        test("R54 caps the duration at 200 ms: 0.2s and 200ms pass, 0.25s, 250ms, 1s and a long second transition fail") {
            fun css(transition: String) = ".a { $transition }\n@media (prefers-reduced-motion: reduce) { .a { transition: none; } }\n"
            listOf(
                "transition: opacity 0.2s ease;",
                "transition: opacity 200ms;",
                "transition: opacity .15s, color 120ms ease-in-out;",
            ).forEach {
                reducedMotionViolations(css(it)) shouldBe emptyList()
            }
            listOf(
                "transition: opacity 0.25s;",
                "transition: opacity 250ms;",
                "transition: opacity 1s;",
                "transition: opacity 0.1s, transform 0.5s;",
                "transition-duration: 300ms;",
            ).forEach { reducedMotionViolations(css(it)).any { v -> "longer than" in v } shouldBe true }
            transitionDurationsMs(property = "transition", value = "opacity 0.2s ease 0.5s") shouldBe listOf(200) // delay not a duration
            transitionDurationsMs(property = "transition", value = "none") shouldBe emptyList()
        }

        test("ledger diff: one violation fixed and a NEW one added in the same file turns red (a plain count would not)") {
            val ledger = mapOf("A.kt" to listOf("panel.table(", "other.table("))
            ledgerDiff(actual = ledger, ledger = ledger) shouldBe emptyList()
            // one fixed (other.table( gone), one new (third.table() -- the count per file is unchanged (2 == 2)
            val swapped = mapOf("A.kt" to listOf("panel.table(", "third.table("))
            ledgerDiff(actual = swapped, ledger = ledger) shouldBe
                listOf("NEW      A.kt: third.table(", "PAID OFF A.kt: other.table( (delete this ledger line)")
            // a file that is not in the ledger is held strictly
            ledgerDiff(actual = mapOf("B.kt" to listOf("x.table(")), ledger = ledger).contains("NEW      B.kt: x.table(") shouldBe true
            // a duplicated identical line is a multiset: 2 listed, 1 found -> one PAID OFF
            ledgerDiff(actual = mapOf("A.kt" to listOf("a")), ledger = mapOf("A.kt" to listOf("a", "a"))).size shouldBe 1
        }

        test(
            "V1.4.30 audit M2: no client source passes a tr(...) result as an ARGUMENT of gettext (KVision's marker would show on screen)",
        ) {
            val findings =
                clientKotlinFiles().flatMap { file ->
                    trInsideCallFindings(text = file.readText(), callStart = GETTEXT_CALL).map { "${file.name}: $it" }
                }
            findings shouldBe emptyList()
        }

        test(
            "V1.4.30 audit M2: a FieldCheck.Invalid / AmountInput.Invalid message is resolved text -- no tr( inside, and none in the rule files",
        ) {
            val findings =
                clientKotlinFiles().flatMap { file ->
                    trInsideCallFindings(text = file.readText(), callStart = INVALID_CALL).map { "${file.name}: $it" }
                }
            findings shouldBe emptyList()
            // The two files that produce field-rule messages hold no tr( call at all (only gettext): a message is text, not a live label.
            val byName = clientKotlinFiles().associateBy { it.name }
            listOf("FormRules.kt", "OpenItemFormValidation.kt").forEach { name ->
                withClue(name) { TR_CALL.containsMatchIn(withoutStringContents(codeOnly(byName.getValue(name).readText()))) shouldBe false }
            }
        }

        test(
            "the marker-leak scanner flags tr( inside gettext( (also through a nested call and a chain), ignores strings, comments and widget content",
        ) {
            val flag = { code: String -> trInsideCallFindings(text = code, callStart = GETTEXT_CALL).size }
            flag("notify(gettext(\"a %1\", tr(\"x\")))") shouldBe 1
            flag("gettext(\"a %1\", if (ok) tr(\"x\") else tr(\"y\"))") shouldBe 1
            flag("gettext(\"a %1\", foo?.let { bar(it) } ?: tr(\"x\"))") shouldBe 1
            flag("gettext(\"a %1\", \"str(x) and tr(y)\")") shouldBe 0
            flag("// gettext(\"a %1\", tr(\"x\"))") shouldBe 0
            flag("panel.div(tr(\"x\"))") shouldBe 0
            flag("gettext(\"a\")") shouldBe 0
            flag("gettext(\"a %1\", Regex(\"(a)\").find(b))") shouldBe 0
            trInsideCallFindings(text = "FieldCheck.Invalid(tr(\"x\"))", callStart = INVALID_CALL).size shouldBe 1
            trInsideCallFindings(text = "FieldCheck.Invalid(gettext(\"x\"))", callStart = INVALID_CALL).size shouldBe 0
        }

        test(
            "V1.4.30 audit: every migrated finance file keeps AT LEAST as many lapisForm( calls as the wave built (a count, not just a presence)",
        ) {
            val byName = clientKotlinFiles().associateBy { it.name }
            R24_MIN_FORMS.forEach { (name, minimum) ->
                val forms = Regex("""\blapisForm\(""").findAll(codeOnly(byName.getValue(name).readText())).count()
                withClue("$name: $forms forms, at least $minimum expected") { (forms >= minimum) shouldBe true }
            }
        }

        test("fingerprints drop the line number and join a chain broken before the dot with its previous line") {
            val code = "fun f() {\n    val a = 1\n    panel\n        .table(\n        )\n}"
            fingerprintAt(code = code, offset = code.indexOf("table(")) shouldBe "panel .table("
            val shifted = "// c1\n// c2\n$code"
            fingerprintAt(code = codeOnly(shifted), offset = codeOnly(shifted).indexOf("table(")) shouldBe "panel .table("
        }
    })
