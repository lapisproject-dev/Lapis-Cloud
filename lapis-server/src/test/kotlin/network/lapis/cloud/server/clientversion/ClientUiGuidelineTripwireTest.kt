package network.lapis.cloud.server.clientversion

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
            ),
        R14 to
            mapOf(
                // W3 accounting / banking
                "AccountingExportScreen.kt" to
                    listOf(
                        "itemsPanel.table(",
                        "panel.table(",
                        "panel.table(",
                    ),
                // W3 accounting / banking
                "BankAccountsScreen.kt" to
                    listOf(
                        "val table = tableHost.table(headerNames = headers, types = setOf(TableType.STRIPED, TableType.HOVER))",
                    ),
                // W3 accounting / banking
                "BankStatementImportScreen.kt" to
                    listOf(
                        "lineTable = lineTableHost.table(headerNames = headers, types = setOf(TableType.STRIPED, TableType.HOVER))",
                    ),
                // W3 accounting / banking
                "CostCentersScreen.kt" to
                    listOf(
                        "listPanel.table(",
                    ),
                // W3 accounting / banking
                "DonorsScreen.kt" to
                    listOf(
                        "listPanel.table(",
                    ),
                // W4 dunning
                "DunningCasesScreen.kt" to
                    listOf(
                        "listPanel.table(",
                        "panel.table(",
                    ),
                // W4 dunning
                "DunningSettingsScreen.kt" to
                    listOf(
                        "listPanel.table(",
                    ),
                // W3 accounting / banking
                "LedgerScreen.kt" to
                    listOf(
                        "accountListPanel.table(",
                        "journalListPanel.table(",
                        "panel.table(",
                    ),
                // W2 member area
                "MemberAnniversariesScreen.kt" to
                    listOf(
                        "resultPanel.table(",
                    ),
                // W2 member area
                "MemberFamiliesScreen.kt" to
                    listOf(
                        "body.table(",
                        "table ?: listPanel .table(",
                    ),
                // W2 member area
                "MemberFinancialHistoryScreen.kt" to
                    listOf(
                        "tableWrapper.table(",
                    ),
                // W2 member area
                "MemberHonorsScreen.kt" to
                    listOf(
                        "table ?: listPanel .table(",
                    ),
                // W3 accounting / banking
                "PaymentTransactionsScreen.kt" to
                    listOf(
                        "tableHost.table(",
                    ),
                // W4 dunning
                "ReceivableDunningSettingsScreen.kt" to
                    listOf(
                        "listPanel.table(",
                    ),
                // W3 accounting / banking
                "SepaBatchesScreen.kt" to
                    listOf(
                        "currentTable ?: listPanel .table(",
                        "panel.table(",
                        "panel.table(",
                        "returnsPanel.table(",
                    ),
                // W3 accounting / banking
                "SepaMandatesScreen.kt" to
                    listOf(
                        "currentTable ?: listPanel .table(",
                    ),
                // W5 admin / integrations
                "WebhookDeliveryLogPanel.kt" to
                    listOf(
                        "scrollWrapper.table(",
                    ),
            ),
        R15 to
            mapOf(
                // W3 accounting / banking
                "AccountingExportScreen.kt" to
                    listOf(
                        "itemsPanel.table(",
                        "panel.table(",
                        "panel.table(",
                    ),
                // W3 accounting / banking
                "BankAccountsScreen.kt" to
                    listOf(
                        "val table = tableHost.table(headerNames = headers, types = setOf(TableType.STRIPED, TableType.HOVER))",
                    ),
                // W3 accounting / banking
                "BankStatementImportScreen.kt" to
                    listOf(
                        "lineTable = lineTableHost.table(headerNames = headers, types = setOf(TableType.STRIPED, TableType.HOVER))",
                    ),
                // W4 dunning
                "DunningSettingsScreen.kt" to
                    listOf(
                        "listPanel.table(",
                    ),
                // W2 member area
                "MemberAnniversariesScreen.kt" to
                    listOf(
                        "resultPanel.table(",
                    ),
                // W2 member area
                "MemberFamiliesScreen.kt" to
                    listOf(
                        "body.table(",
                        "table ?: listPanel .table(",
                    ),
                // W2 member area
                "MemberFinancialHistoryScreen.kt" to
                    listOf(
                        "tableWrapper.table(",
                    ),
                // W2 member area
                "MemberHonorsScreen.kt" to
                    listOf(
                        "table ?: listPanel .table(",
                    ),
                // W3 accounting / banking
                "PaymentTransactionsScreen.kt" to
                    listOf(
                        "tableHost.table(",
                    ),
                // W5 admin / integrations
                "WebhookDeliveryLogPanel.kt" to
                    listOf(
                        "scrollWrapper.table(",
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
                // W2 member area
                "MemberFamiliesScreen.kt" to
                    listOf(
                        "val openButton = actionsCell.button(\"\", icon = \"fas fa-arrow-right\", style = ButtonStyle.OUTLINEPRIMARY)",
                        "val removeButton = actionsCell.button(\"\", icon = \"fas fa-user-minus\", style = ButtonStyle.OUTLINEDANGER)",
                    ),
                // W2 member area
                "MemberHonorsScreen.kt" to
                    listOf(
                        "val deleteButton = actionsCell.button(\"\", icon = \"fas fa-trash\", style = ButtonStyle.OUTLINEDANGER)",
                        "val editButton = actionsCell.button(\"\", icon = \"fas fa-pen\", style = ButtonStyle.OUTLINEPRIMARY)",
                    ),
                // W2 member area
                "MemberPasswordResetDialog.kt" to
                    listOf(
                        "val regenerateButton = passwordRow.button(\"\", icon = \"fas fa-rotate\", style = ButtonStyle.OUTLINESECONDARY)",
                    ),
            ),
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

// ── the spec ──────────────────────────────────────────────────────────────────────────────────────────

class ClientUiGuidelineTripwireTest :
    FunSpec({
        test("the scanned client sources exist and are plentiful (tripwire is not vacuous)") {
            CLIENT_KOTLIN_DIR.isDirectory shouldBe true
            THEME_CSS.isFile shouldBe true
            (clientKotlinFiles().size > 50) shouldBe true
        }

        listOf(R2, R9, R14, R15, R39).forEach { rule ->
            test("$rule: findings per file equal the debt ledger exactly (NEW = new violation, PAID OFF = delete the ledger line)") {
                ledgerDiff(actual = actualFindings(rule), ledger = BASELINE.getValue(rule)) shouldBe emptyList()
            }
        }

        test("R14/R15 see every table( call of the client, also the ones broken before the dot (27 including the standardTable helper)") {
            clientKotlinFiles().sumOf { tableCalls(it.readText()).size } shouldBe 27
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

        test("R54 names exactly the three known transitions today") {
            val css = THEME_CSS.readText()
            val reduced = parseCssRules(css).filter { REDUCED_MOTION in it.atRules }.flatMap { selectorsOf(it) }.toSet()
            reduced shouldBe setOf("body", ".lapis-conference-controls-row", ".lapis-conference-background-preview")
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

        test("fingerprints drop the line number and join a chain broken before the dot with its previous line") {
            val code = "fun f() {\n    val a = 1\n    panel\n        .table(\n        )\n}"
            fingerprintAt(code = code, offset = code.indexOf("table(")) shouldBe "panel .table("
            val shifted = "// c1\n// c2\n$code"
            fingerprintAt(code = codeOnly(shifted), offset = codeOnly(shifted).indexOf("table(")) shouldBe "panel .table("
        }
    })
