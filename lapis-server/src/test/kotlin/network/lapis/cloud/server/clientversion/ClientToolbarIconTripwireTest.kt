package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Tripwires of V1.9.43 (UI/UX guideline R56-R58), in the pattern of [ClientUiGuidelineTripwireTest]: the client sources are scanned as
 * text, comment lines are exempt, every detector proves itself against a positive and a negative example, ledgers may only SHRINK
 * (a new finding fails, a paid-off finding fails too until its ledger entry is deleted).
 *
 * - **R56 toolbar**: a row that holds a labelled/plain field AND a button must be a `lapisToolbar()`, not an `hPanel` with
 *   `align-items-center|end` (KVision wraps each field in `div.form-group.kv-mb-3`: label above, 16 px margin below -- centring or
 *   bottom-aligning the wrapper leaves the button 8-16 px off the control; measured in `ToolbarGeometryDomTest`). `btn-sm` is
 *   forbidden inside a toolbar.
 * - **R57 icons**: the aliases `fa-times`, `fa-edit`, `fa-refresh` are forbidden; the standard verbs (Aktualisieren, Bearbeiten, Löschen ...)
 *   are built with `actionButton` / `newActionButton` (icon from `ActionIcon`), a plain `button(tr("Bearbeiten"))` is ledgered debt.
 */
private val CLIENT_KOTLIN_DIR: File =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val THEME_CSS: File =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

private val FIELD_CALLS =
    setOf("select", "text", "textArea", "dateField", "dateTimeField", "checkBox", "radio", "spinner", "numeric", "searchableSelect")
private val BUTTON_CALLS = setOf("button", "actionButton")

private val ROW_DECLARATION =
    Regex("""^\s*(?:val|var)\s+(\w+)\s*=\s*(?:[\w.]+\.)?hPanel\(.*align-items-(?:center|end)""")

private val STANDARD_VERBS =
    "Aktualisieren|Neu laden|Erneut versuchen|Bearbeiten|Löschen|Entfernen|Speichern|Änderungen speichern|Abbrechen|Hinzufügen|" +
        "Schließen|Herunterladen|Details anzeigen"

private val PLAIN_STANDARD_BUTTON = Regex("""(?<![\w])[bB]utton\(\s*(?:text\s*=\s*)?(?:tr|gettext)\("(?:$STANDARD_VERBS)"""")
private val FORBIDDEN_ICON_ALIAS = Regex("""fa-(?:times|edit|refresh)\b""")
private val STRING_TABLE_ACTION = Regex("""tableActionButton\(\s*"""")

/**
 * V1.9.50 (S2): the verbs a button label may END on (`Anschrift speichern`, `Ausweis sperren …`, plain `Widerrufen`) -- a button for one of
 * them must be built with `actionButton`/`newActionButton` (icon from [network.lapis.cloud.client.ActionIcon], "same verb, same picture").
 * The label is read from the call itself, so the verb is found at the END of the text (V1.9.43-V1.9.49 only knew a hand-picked file list).
 * `anzeigen`/`ansehen` are in the list on purpose: a real action ("Auskunftsübersicht anzeigen") gets VIEW, a disclosure toggle
 * (`aria-expanded`, shows/hides a block) is not an action and sits in [R57_LEDGER] with the reason `DISCLOSURE`.
 */
private const val ICON_VERBS =
    "anlegen|hinzufügen|erstellen|speichern|bearbeiten|stornieren|widerrufen|deaktivieren|duplizieren|löschen|entfernen|hochladen|" +
        "herunterladen|einreichen|senden|beantragen|sperren|ansehen|anzeigen|schließen|abbrechen"

private val VERB_AT_LABEL_END = Regex("""^(?:.*\s)?(?:$ICON_VERBS)(?:\s…)?$""", RegexOption.IGNORE_CASE)
private val CALL_LABEL = Regex("""^\s*(?:text\s*=\s*)?(?:tr|gettext)\("([^"]*)"""")
private val STRING_ICON_ARGUMENT = Regex("""\bicon\s*=\s*"fa""")
private val INLINE_CREATE_LABEL = Regex("""^(?:Neue[rsmn]? \p{L}+|(?:.*\s)?(?:erstellen|anlegen))$""")

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

private fun clientFiles(): List<File> =
    CLIENT_KOTLIN_DIR
        .walkTopDown()
        .filter {
            it.isFile && it.extension == "kt"
        }.sortedBy { it.name }
        .toList()

/** The lines of [source] with comment lines blanked out (line numbers stay stable). */
private fun codeLines(source: String): List<String> = source.split('\n').map { if (isCommentLine(it)) "" else it }

/** Rows declared as `hPanel` with an `align-items-center|end` class that receive a field AND a button -> their declaration lines. */
internal fun misalignedFieldButtonRows(source: String): List<String> {
    val lines = codeLines(source)
    return lines.indices.mapNotNull { i ->
        val variable = ROW_DECLARATION.find(lines[i])?.groupValues?.get(1) ?: return@mapNotNull null
        val calls =
            lines
                .subList(i + 1, minOf(i + 60, lines.size))
                .mapNotNull { Regex("""^\s*(?:val \w+ = |\w+ = )?$variable\.(\w+)\(""").find(it)?.groupValues?.get(1) }
        if (calls.any { it in FIELD_CALLS } && calls.any { it in BUTTON_CALLS }) lines[i].trim() else null
    }
}

/** `btn-sm` / `small = true` on a call of a `lapisToolbar` row variable. */
internal fun smallButtonsInToolbars(source: String): List<String> {
    val lines = codeLines(source)
    val declaration = Regex("""^\s*(?:val|var)\s+(\w+)\s*=\s*(?:[\w.]+\.)?lapisToolbar\(""")
    return lines.indices.flatMap { i ->
        val variable = declaration.find(lines[i])?.groupValues?.get(1) ?: return@flatMap emptyList<String>()
        lines
            .subList(i + 1, minOf(i + 60, lines.size))
            .filter { it.trimStart().startsWith("$variable.") || it.contains("$variable.actionButton") }
            .filter { it.contains("btn-sm") || it.contains("small = true") }
            .map { it.trim() }
    }
}

internal fun plainStandardButtons(source: String): Int = PLAIN_STANDARD_BUTTON.findAll(codeLines(source).joinToString("\n")).count()

internal fun stringTableActionButtons(source: String): Int = codeLines(source).count { STRING_TABLE_ACTION.containsMatchIn(it) }

/**
 * The argument text of every plain `button(...)` / `Button(...)` call of [source] (comment lines blanked, parentheses balanced, string
 * literals skipped), as `(constructor?, arguments)`. `actionButton(`, `newActionButton(` and `tableActionButton(` are not matched: a letter
 * precedes their `Button(`.
 */
internal fun plainButtonCalls(source: String): List<Pair<Boolean, String>> {
    val code = codeLines(source).joinToString("\n")
    val result = mutableListOf<Pair<Boolean, String>>()
    for (match in PLAIN_BUTTON_CALL.findAll(code)) {
        var depth = 1
        var i = match.range.last + 1
        var inString = false
        while (i < code.length && depth > 0) {
            val c = code[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '(' -> depth++
                !inString && c == ')' -> depth--
            }
            i++
        }
        result += (match.groupValues[1] == "B") to code.substring(match.range.last + 1, (i - 1).coerceAtLeast(match.range.last + 1))
    }
    return result
}

private val PLAIN_BUTTON_CALL = Regex("""(?<![\w])([bB])utton\(""")

private fun labelOf(arguments: String): String? = CALL_LABEL.find(arguments)?.groupValues?.get(1)

/** S2: labels of plain buttons that END on an icon verb ([ICON_VERBS]). */
internal fun verbButtonsWithoutIcon(source: String): List<String> =
    plainButtonCalls(source).mapNotNull { (_, arguments) -> labelOf(arguments)?.takeIf { VERB_AT_LABEL_END.matches(it) } }

/** S3: plain buttons that take their icon as a Font Awesome STRING (`icon = "fas fa-..."`) instead of an `ActionIcon`. */
internal fun stringIconButtons(source: String): List<String> =
    plainButtonCalls(source)
        .filter { (_, arguments) -> STRING_ICON_ARGUMENT.containsMatchIn(arguments) }
        .map { (_, arguments) -> labelOf(arguments) ?: arguments.take(40).replace('\n', ' ').trim() }

/**
 * S4: a "new ..." / "... erstellen|anlegen" button hung straight into a container with `container.button(...)` -- the old hand-built
 * create button next to a list. (A `Button(...)` constructor result is handed to a form's button row or a slot, a submit button, not a
 * create entry; `newActionButton(ADD ...)` and `collapsibleCreateForm` never match.)
 */
internal fun inlineCreateButtons(source: String): List<String> =
    plainButtonCalls(source)
        .filter { (constructor, _) -> !constructor }
        .mapNotNull { (_, arguments) -> labelOf(arguments)?.takeIf { INLINE_CREATE_LABEL.matches(it) } }

internal fun forbiddenIconAliases(source: String): Int = codeLines(source).count { FORBIDDEN_ICON_ALIAS.containsMatchIn(it) }

/**
 * Rows that are justified `hPanel`s despite holding a field and a button, as `file -> declaration lines`. The conference roster row is a
 * list row of centred text and badges (not a filter toolbar); its one select drops the wrapper margin with `mb-0` so it stays centred.
 */
private val R56_ROW_LEDGER: Map<String, List<String>> =
    mapOf(
        "ConferenceScreen.kt" to listOf("val row = rosterList.hPanel(spacing = 6) { addCssClasses(\"align-items-center flex-wrap\") }"),
    )

/** R57 ledger: file -> number of plain `button(tr("<standard verb>"))` calls still without icon (calls with extra arguments such as `className`). */
private val R57_PLAIN_STANDARD_BUTTON_LEDGER: Map<String, Int> =
    mapOf(
        // update bar: `className = "lapis-update-reload"` is not covered by actionButton
        "ClientVersionWatcher.kt" to 1,
    )

/**
 * R57 ledger: file -> number of string-typed `tableActionButton("fas fa-...")` calls (domain verbs without a standard icon, see action-icons.adoc).
 * V1.9.45: BankAccountsScreen ("Als Standard setzen", star) and OpenItemsScreen ("Ausgleichen", money bill) stay -- domain verbs with no
 * `ActionIcon` entry; `action-icons.adoc` lists exactly these two as "domain icons that stay as strings".
 * V1.9.48: the six of MemberAdministrationScreen (receipt, medal, id card, image, comment-slash, key) are no longer debt but a reasoned list
 * in `action-icons.adoc` (each would be misread with a standard icon in a row of icon-only buttons).
 * V1.9.49: the two of DocumentsScreen ("Sichtbarkeit ändern", fa-user-lock) are paid off: `ActionIcon.ACCESS`.
 */
private val R57_STRING_TABLE_ACTION_LEDGER: Map<String, Int> =
    mapOf(
        "BankAccountsScreen.kt" to 1,
        "DataScreenLayout.kt" to 1,
        "KeycloakLinkScreen.kt" to 2,
        "MemberAddressAdminDialog.kt" to 1,
        "MemberAdministrationScreen.kt" to 6,
        "MemberFamiliesScreen.kt" to 2,
        "MembershipTiersScreen.kt" to 1,
        "OpenItemsScreen.kt" to 1,
    )

/** One justified exception of [R57_LEDGER]: how many findings the file may keep, and why. */
private data class LedgerEntry(
    val count: Int,
    val reason: String,
)

private const val REASON_DISCLOSURE =
    "DISCLOSURE: a show/hide toggle (aria-expanded, the label flips to 'ausblenden'), not an action -- an eye would promise an action"

private const val REASON_DIALOG =
    "DIALOG: 'Weiter bearbeiten' is the dismissing answer of a hand-built discard dialog, the dialog helper owns that button (confirmDiscardInputs)"

private const val REASON_DOMAIN_ICON =
    "DOMAIN_ICON: the three '<Zeilenart> hinzufügen' buttons carry the line-kind icon (car, calendar, receipt); a plain plus would make them identical"

private const val REASON_DOMAIN_ICON_2 =
    "DOMAIN_ICON: the navigation (hamburger) toggle of the shell, not an action verb"

private const val REASON_CLASSNAME =
    "CLASSNAME: the dismiss cross of the update pill carries a custom class (lapis-update-pill-close) that actionButton does not cover"

private const val REASON_DOMAIN_ICON_3 =
    "DOMAIN_ICON: the background picker toggle (image) and the icon-only 'remove own image' cross with a numbered aria-label"

private const val REASON_DOMAIN_ICON_4 =
    "DOMAIN_ICON: the conference control bar (microphone, camera, screen share, participants, chat, more, back to main room, leave, whiteboard, notes, expand) -- icon-only domain toggles"

private const val REASON_DOMAIN_ICON_5 =
    "DOMAIN_ICON: the icon-only voting toggle (ballot) with its pending-vote badge"

private const val REASON_DOMAIN_ICON_6 =
    "DOMAIN_ICON: 'Neu indexieren' (re-index the full-text search), not 'Aktualisieren'"

private const val REASON_DOMAIN_ICON_7 =
    "DOMAIN_ICON: 'Statistik' (chart) toggles the statistics block of a list row"

private const val REASON_DOMAIN_ICON_9 =
    "DOMAIN_ICON: the chevron of the searchable select (a combobox control, not an action)"

/**
 * V1.9.50 ledger for S2 (verb button without icon) and S3 (string-typed icon), keyed `RULE:File.kt`. S1: there is no strict-file list any
 * more -- every client file is held to the rule, and what stays is listed here WITH a reason. The ledger only shrinks (an exact match is
 * asserted: a new finding fails, a paid-off one fails until its entry is deleted).
 *
 * Reasons: DISCLOSURE = a show/hide toggle (`aria-expanded`), not an action (a "view" eye would promise an action);
 * DOMAIN_ICON = the icon names the object/kind, not the verb (conference controls, editor glyphs, three "<kind> hinzufügen" buttons that
 * a plain plus would make identical); CLASSNAME = a custom class `actionButton` does not cover; DIALOG = a dialog answer button, covered by
 * the dialog helper.
 */
private val R57_LEDGER: Map<String, LedgerEntry> =
    mapOf(
        "S2:ConsensusLabels.kt" to LedgerEntry(count = 1, reason = REASON_DISCLOSURE),
        "S2:ConsensusResultView.kt" to LedgerEntry(count = 1, reason = REASON_DISCLOSURE),
        "S2:DirectMessageConversation.kt" to LedgerEntry(count = 1, reason = REASON_DISCLOSURE),
        "S2:EventVolunteerShiftsScreen.kt" to LedgerEntry(count = 1, reason = REASON_DISCLOSURE),
        "S2:MailingHtmlEditor.kt" to LedgerEntry(count = 1, reason = REASON_DISCLOSURE),
        "S2:OpenItemsScreen.kt" to LedgerEntry(count = 1, reason = REASON_DISCLOSURE),
        "S2:VolunteerAllowanceDeclarationsCard.kt" to LedgerEntry(count = 1, reason = REASON_DISCLOSURE),
        "S2:WebhookDeliveryLogPanel.kt" to LedgerEntry(count = 1, reason = REASON_DISCLOSURE),
        "S2:ConferenceNotesController.kt" to LedgerEntry(count = 1, reason = REASON_DIALOG),
        "S2:TravelExpenseScreen.kt" to LedgerEntry(count = 1, reason = REASON_DOMAIN_ICON),
        "S3:App.kt" to LedgerEntry(count = 1, reason = REASON_DOMAIN_ICON_2),
        "S3:ClientVersionWatcher.kt" to LedgerEntry(count = 1, reason = REASON_CLASSNAME),
        "S3:ConferenceBackgroundSection.kt" to LedgerEntry(count = 2, reason = REASON_DOMAIN_ICON_3),
        "S3:ConferenceScreen.kt" to LedgerEntry(count = 9, reason = REASON_DOMAIN_ICON_4),
        "S3:ConferenceVotePanel.kt" to LedgerEntry(count = 1, reason = REASON_DOMAIN_ICON_5),
        "S3:DocumentsScreen.kt" to LedgerEntry(count = 1, reason = REASON_DOMAIN_ICON_6),
        "S3:MailingListRows.kt" to LedgerEntry(count = 1, reason = REASON_DOMAIN_ICON_7),
        "S3:SearchableSelect.kt" to LedgerEntry(count = 1, reason = REASON_DOMAIN_ICON_9),
    )

// ── V1.9.66: conference control bar + chat composer (R58 named exceptions, no wrapping bar, consent display stays) ─────────────

private val TOP_LEVEL_FUN = Regex("""^(?:(?:internal|private|public)\s+)?fun\s+(?:[\w.<>]+\.)?(\w+)\(""")

/**
 * The calls of [name] in [source] as `enclosing top-level function -> count` (comment lines blanked, the declaration itself skipped). A call
 * that sits in no top-level function is reported as `<none>`.
 */
internal fun callsByEnclosingFunction(
    source: String,
    name: String,
): Map<String, Int> {
    val lines = codeLines(source)
    val result = mutableMapOf<String, Int>()
    lines.forEachIndexed { index, line ->
        if (!line.contains("$name(") ||
            line.contains("fun $name(") ||
            Regex("""fun\s+\w+\.$name\(""").containsMatchIn(line)
        ) {
            return@forEachIndexed
        }
        val enclosing =
            (index downTo 0).firstNotNullOfOrNull { TOP_LEVEL_FUN.find(lines[it])?.groupValues?.get(1) } ?: "<none>"
        result.merge(enclosing, 1, Int::plus)
    }
    return result
}

/** The uses of [token] in [source] as `enclosing top-level function -> count` (comment lines blanked). */
internal fun usesByEnclosingFunction(
    source: String,
    token: String,
): Map<String, Int> {
    val lines = codeLines(source)
    val result = mutableMapOf<String, Int>()
    lines.forEachIndexed { index, line ->
        val uses = Regex(Regex.escape(token) + """\b""").findAll(line).count()
        if (uses == 0) return@forEachIndexed
        val enclosing = (index downTo 0).firstNotNullOfOrNull { TOP_LEVEL_FUN.find(lines[it])?.groupValues?.get(1) } ?: "<none>"
        result.merge(enclosing, uses, Int::plus)
    }
    return result
}

/**
 * The three (and only three) places that may build an icon-only button without `btn-sm` (R58 named exceptions a and b, V1.9.66; c, the
 * encounter room's bar, V1.9.74).
 */
private val R58_ICON_ONLY_FACTORY_CALLS: Map<String, Map<String, Int>> =
    mapOf(
        "ChatComposer.kt" to mapOf("lapisChatComposer" to 1),
        "ConferenceControlBar.kt" to mapOf("conferenceControlButton" to 1),
        "EncounterControlBar.kt" to mapOf("encounterControlButton" to 1),
    )

/** The declaration line(s) `val controlsRow =` plus the line after it, as one text. */
internal fun controlsRowDeclaration(source: String): String {
    val lines = codeLines(source)
    val at = lines.indexOfFirst { it.trimStart().startsWith("val controlsRow =") }
    return if (at < 0) "" else lines.subList(at, minOf(at + 3, lines.size)).joinToString("\n")
}

private val CONSENT_DISPLAY_NAMES =
    listOf("recordingBanner", "streamBanner", "statusBadgesPanel", "recordingDetailLine", "streamDetailLine", "streamTargetsPanel")

/** `val <name> =` lines of [source] with their indentation (4 = the body of the call screen's function, not a nested branch). */
internal fun declarationIndents(
    source: String,
    name: String,
): List<Int> =
    codeLines(source).mapNotNull { line ->
        if (Regex("""^\s*val\s+$name\s*=""").containsMatchIn(line)) line.length - line.trimStart().length else null
    }

class ClientToolbarIconTripwireTest :
    FunSpec({
        test("R56 detector: a centred row with a field and a button is found, a row of buttons or a lapisToolbar is not") {
            val bad =
                """
                val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                val select = filterRow.select(options = o, label = "Status")
                val refresh = filterRow.button(tr("Aktualisieren"))
                """.trimIndent()
            misalignedFieldButtonRows(bad).size shouldBe 1
            misalignedFieldButtonRows(bad.replace("align-items-center", "align-items-end")).size shouldBe 1
            val buttonsOnly =
                """
                val actionRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
                actionRow.button(tr("A"))
                actionRow.button(tr("B"))
                """.trimIndent()
            misalignedFieldButtonRows(buttonsOnly).size shouldBe 0
            misalignedFieldButtonRows(
                bad.replace("""hPanel(spacing = 8) { addCssClasses("align-items-center") }""", "lapisToolbar()"),
            ).size shouldBe
                0
            misalignedFieldButtonRows("// val r = x.hPanel(spacing = 8) { addCssClasses(\"align-items-center\") }").size shouldBe 0
        }

        test("R56: no hPanel row holds a field and a button -- use lapisToolbar()") {
            val actual = clientFiles().associate { it.name to misalignedFieldButtonRows(it.readText()) }.filterValues { it.isNotEmpty() }
            withClue("rows to convert to lapisToolbar() (or ledger with a reason): $actual") { actual shouldBe R56_ROW_LEDGER }
        }

        test("R56 detector: btn-sm inside a toolbar is found") {
            val bad =
                """
                val bar = root.lapisToolbar()
                bar.actionButton(ActionIcon.SAVE, tr("Speichern"), small = true)
                """.trimIndent()
            smallButtonsInToolbars(bad).size shouldBe 1
            smallButtonsInToolbars(bad.replace("small = true", "style = x")).size shouldBe 0
        }

        test("R56: no btn-sm / small button inside a lapisToolbar") {
            val actual = clientFiles().associate { it.name to smallButtonsInToolbars(it.readText()) }.filterValues { it.isNotEmpty() }
            actual shouldBe emptyMap()
        }

        test("R56: theme.css defines the toolbar with bottom alignment and without the field wrapper's margin") {
            val css = THEME_CSS.readText()
            val block = Regex("""\.lapis-toolbar\s*\{[^}]*\}""").find(css)?.value ?: ""
            withClue("`.lapis-toolbar` rule missing or not bottom-aligned") { block.contains("align-items: flex-end") shouldBe true }
            val wrapperRule = Regex("""\.lapis-toolbar > \.form-group[^{]*\{[^}]*\}""").find(css)?.value ?: ""
            withClue("the KVision field wrapper keeps its 16 px margin inside the toolbar") {
                wrapperRule.replace(" ", "").contains("margin-bottom:0") shouldBe true
            }
        }

        test("R57 detector: forbidden aliases and plain standard buttons") {
            forbiddenIconAliases("val b = button(\"\", icon = \"fas fa-times\")") shouldBe 1
            forbiddenIconAliases("// icon = \"fas fa-edit\"") shouldBe 0
            forbiddenIconAliases("icon = \"fas fa-xmark\"") shouldBe 0
            plainStandardButtons("row.button(tr(\"Bearbeiten\"), style = S)") shouldBe 1
            plainStandardButtons("row.button(\n    tr(\"Löschen\"),\n)") shouldBe 1
            plainStandardButtons("row.actionButton(ActionIcon.EDIT, tr(\"Bearbeiten\"))") shouldBe 0
            plainStandardButtons("row.button(tr(\"Bearbeiten und freigeben\"))") shouldBe 0
            stringTableActionButtons("a.tableActionButton(\"fas fa-star\", tr(\"X\"))") shouldBe 1
            stringTableActionButtons("a.tableActionButton(ActionIcon.EDIT, tr(\"X\"))") shouldBe 0
            stringTableActionButtons("a.tableActionButton(ActionIcon.ACCESS, tr(\"X\"))") shouldBe 0
        }

        test("R57 detector S2: a plain button whose label ENDS on an icon verb is found, an actionButton or a non-verb label is not") {
            verbButtonsWithoutIcon("row.button(tr(\"Anschrift speichern\"), style = S)") shouldBe listOf("Anschrift speichern")
            verbButtonsWithoutIcon("val b = Button(tr(\"Kurzvorstellung löschen\"), style = S)") shouldBe listOf("Kurzvorstellung löschen")
            verbButtonsWithoutIcon("val b = Button(tr(\"Ausweis sperren …\"), style = S)") shouldBe listOf("Ausweis sperren …")
            verbButtonsWithoutIcon("row.button(\n    tr(\"Löschung beantragen\"),\n)") shouldBe listOf("Löschung beantragen")
            verbButtonsWithoutIcon("row.button(text = gettext(\"%1 hinzufügen\", x))") shouldBe listOf("%1 hinzufügen")
            verbButtonsWithoutIcon("row.button(tr(\"Widerrufen\"))") shouldBe listOf("Widerrufen")
            verbButtonsWithoutIcon("row.button(tr(\"Speicherort\"))") shouldBe emptyList()
            verbButtonsWithoutIcon("row.button(tr(\"Meine Artikel\"))") shouldBe emptyList()
            verbButtonsWithoutIcon("row.button(tr(\"Ausgleichen …\"))") shouldBe emptyList()
            verbButtonsWithoutIcon("row.actionButton(ActionIcon.SAVE, tr(\"Anschrift speichern\"))") shouldBe emptyList()
            verbButtonsWithoutIcon("val b = newActionButton(ActionIcon.ADD, tr(\"Konto anlegen\"))") shouldBe emptyList()
            verbButtonsWithoutIcon("a.tableActionButton(ActionIcon.EDIT, tr(\"Posten stornieren\"))") shouldBe emptyList()
            verbButtonsWithoutIcon("// row.button(tr(\"Anschrift speichern\"))") shouldBe emptyList()
        }

        test("R57 detector S3: a string-typed icon on a plain button is found, an ActionIcon is not") {
            stringIconButtons("Button(tr(\"Foto hochladen\"), icon = \"fas fa-upload\", style = S)") shouldBe listOf("Foto hochladen")
            stringIconButtons("row.button(\n    \"\",\n    icon = \"fas fa-bars\",\n)").size shouldBe 1
            stringIconButtons("row.actionButton(ActionIcon.UPLOAD, tr(\"Foto hochladen\"))") shouldBe emptyList()
            stringIconButtons("row.button(tr(\"Foto hochladen\"), style = S)") shouldBe emptyList()
            stringIconButtons("// Button(tr(\"x\"), icon = \"fas fa-upload\")") shouldBe emptyList()
        }

        test("R57 detector S4: a hand-built create button next to a list is found, newActionButton(ADD) and a form submit are not") {
            inlineCreateButtons("listPanel.button(tr(\"Neuer Artikel\"), style = S)") shouldBe listOf("Neuer Artikel")
            inlineCreateButtons("root.button(tr(\"Eintrag erstellen\"), style = S)") shouldBe listOf("Eintrag erstellen")
            inlineCreateButtons("root.button(tr(\"Gremium anlegen\"))") shouldBe listOf("Gremium anlegen")
            inlineCreateButtons("val b = newActionButton(ActionIcon.ADD, tr(\"Neuer Artikel\"))") shouldBe emptyList()
            inlineCreateButtons("header.actionButton(ActionIcon.ADD, tr(\"Neuer Artikel\"))") shouldBe emptyList()
            inlineCreateButtons("val b = Button(tr(\"Sitzung anlegen\"), style = S)") shouldBe emptyList()
            inlineCreateButtons("root.button(tr(\"Endgültig wiederherstellen\"))") shouldBe emptyList()
            inlineCreateButtons("root.button(tr(\"Neues Passwort setzen\"))") shouldBe emptyList()
        }

        test("R57 ledger: every entry names its file, its rule and a reason, and the file exists") {
            val byName = clientFiles().associateBy { it.name }
            R57_LEDGER.forEach { (key, entry) ->
                val (rule, file) = key.split(':', limit = 2)
                withClue("ledger key '$key'") {
                    (rule in setOf("S2", "S3", "S4")) shouldBe true
                    (file in byName) shouldBe true
                    (entry.count > 0) shouldBe true
                    entry.reason.isNotBlank() shouldBe true
                }
            }
        }

        test("R57 (V1.9.50, S1-S4): no verb button without an icon, no string icon and no hand-built create button outside the ledger") {
            val files = clientFiles()
            val actual =
                buildMap {
                    files.forEach { file ->
                        val source = file.readText()
                        verbButtonsWithoutIcon(source).size.takeIf { it > 0 }?.let { put("S2:${file.name}", it) }
                        stringIconButtons(source).size.takeIf { it > 0 }?.let { put("S3:${file.name}", it) }
                        inlineCreateButtons(source).size.takeIf { it > 0 }?.let { put("S4:${file.name}", it) }
                    }
                }
            withClue(
                "use actionButton/newActionButton(ActionIcon.X, ...), a create entry is collapsibleCreateForm -- or ledger with a reason: $actual",
            ) {
                actual shouldBe R57_LEDGER.mapValues { it.value.count }
            }
        }

        test("R57: the aliases fa-times, fa-edit and fa-refresh are not used (ActionIcon names the picture)") {
            val actual = clientFiles().associate { it.name to forbiddenIconAliases(it.readText()) }.filterValues { it > 0 }
            actual shouldBe emptyMap()
        }

        test("R57: plain standard-verb buttons only shrink (ledger)") {
            val actual = clientFiles().associate { it.name to plainStandardButtons(it.readText()) }.filterValues { it > 0 }
            withClue("a standard verb button must be an actionButton/newActionButton (icon from ActionIcon): $actual") {
                actual shouldBe R57_PLAIN_STANDARD_BUTTON_LEDGER
            }
        }

        test("R57: string-typed tableActionButton calls only shrink (ledger)") {
            val actual = clientFiles().associate { it.name to stringTableActionButtons(it.readText()) }.filterValues { it > 0 }
            withClue("use tableActionButton(ActionIcon.X, ...) for a standard verb: $actual") {
                actual shouldBe R57_STRING_TABLE_ACTION_LEDGER
            }
        }

        test("R58 detector (V1.9.66): a call is attributed to its enclosing top-level function") {
            val source =
                """
                internal fun newIconOnlyActionButton(a: Int): Button = build(a)

                internal fun Container.lapisChatComposer(onSend: () -> Unit): ChatComposer {
                    val b = newIconOnlyActionButton(ActionIcon.SEND, x, y)
                    return b
                }

                fun somewhereElse() {
                    // newIconOnlyActionButton(ignored)
                    val c = newIconOnlyActionButton(ActionIcon.SEND, x, y)
                }
                """.trimIndent()
            callsByEnclosingFunction(source = source, name = "newIconOnlyActionButton") shouldBe
                mapOf("lapisChatComposer" to 1, "somewhereElse" to 1)
        }

        test(
            "R58 (V1.9.66, V1.9.74): newIconOnlyActionButton is called only by lapisChatComposer, conferenceControlButton and encounterControlButton -- one call each",
        ) {
            val actual =
                clientFiles()
                    .associate { it.name to callsByEnclosingFunction(source = it.readText(), name = "newIconOnlyActionButton") }
                    .filterValues { it.isNotEmpty() }
            withClue("an icon-only button without btn-sm is a named exception, not a general tool: $actual") {
                actual shouldBe R58_ICON_ONLY_FACTORY_CALLS
            }
        }

        test(
            "R58 (V1.9.66, extended V1.9.70 and V1.9.72): conferenceControlButton is only used inside ConferenceControlBar.kt " +
                "(the moderation group: two controls, the exit group: three controls, the dock bar: four controls)",
        ) {
            val actual =
                clientFiles()
                    .associate { it.name to callsByEnclosingFunction(source = it.readText(), name = "conferenceControlButton") }
                    .filterValues { it.isNotEmpty() }
            actual shouldBe
                mapOf(
                    "ConferenceControlBar.kt" to
                        mapOf("conferenceModerationGroup" to 2, "conferenceExitGroup" to 3, "conferenceDockBarControls" to 4),
                )
        }

        test("V1.9.66: the bar of the conference call never wraps; the chat composer overrides the narrow-width wrap of a toolbar") {
            controlsRowDeclaration(
                """    val controlsRow =
        callPanel.hPanel(spacing = 6) { addCssClasses("align-items-center flex-wrap lapis-conference-controls-row") }""",
            ).contains("flex-wrap") shouldBe true // the detector sees a wrapping declaration
            val screen = clientFiles().first { it.name == "ConferenceScreen.kt" }.readText()
            val declaration = controlsRowDeclaration(screen)
            withClue("controlsRow declaration: $declaration") {
                declaration.contains("lapis-conference-controls-row") shouldBe true
                declaration.contains("flex-wrap") shouldBe false
            }
            val css = THEME_CSS.readText()
            val bar = Regex("""\.lapis-conference-controls-row\s*\{[^}]*\}""").findAll(css).map { it.value }.toList()
            withClue("theme.css: .lapis-conference-controls-row must say flex-wrap: nowrap somewhere") {
                bar.any { it.contains("flex-wrap: nowrap") } shouldBe true
            }
            val composer = Regex("""\.lapis-toolbar\.lapis-chat-composer\s*\{[^}]*\}""").find(css)?.value ?: ""
            withClue("theme.css: the composer row must not wrap (Spezifitaet 0-3-0 beats the toolbar's narrow-width rule)") {
                composer.contains("flex-wrap: nowrap") shouldBe true
            }
        }

        test(
            "V1.9.66: the consent display (banners, badges, detail lines, target list) is built for EVERY participant, never inside a moderator branch",
        ) {
            val screen = clientFiles().first { it.name == "ConferenceScreen.kt" }.readText()
            CONSENT_DISPLAY_NAMES.forEach { name ->
                val indents = declarationIndents(source = screen, name = name)
                withClue(
                    "$name must be declared exactly once, in the body of the call screen (indent 4), not in a nested branch: $indents",
                ) {
                    indents shouldBe listOf(4)
                }
            }
            // the moderation group is the ONLY thing the moderator role gates in the bar
            screen.contains("conferenceModerationGroup(") shouldBe true
        }

        test("V1.9.66: the bar's wiring keeps every confirmation, the receipt lock on the end-for-all twin, and the observer teardown") {
            val screen = clientFiles().first { it.name == "ConferenceScreen.kt" }.readText()
            val endHandler = screen.substring(screen.indexOf("endForAllClick = click@{"))
            withClue("end for everyone asks first (the dialog guard, which opens endRoomConfirmDialog, before endRoom)") {
                (endHandler.indexOf("endDialogGuard.show(") in 0 until endHandler.indexOf("endRoom(")) shouldBe true
                screen.substring(screen.indexOf("internal class EndRoomDialogGuard")).contains("endRoomConfirmDialog(") shouldBe true
            }
            withClue("the twin of 'Für alle beenden' in the sheet is locked together with the primary during a receipt") {
                screen.contains("buttons = listOf(leaveButton, endButton, endTwin, backToMainButton)") shouldBe true
            }
            withClue("a stream is never started without the destination dialog, never stopped without a confirmation") {
                screen.contains("conferenceStreamToggleAction(activeStreamDto?.status)") shouldBe true
                screen.contains("startStreamDialog(") shouldBe true
                screen.contains("stopStreamConfirmDialog(labels)") shouldBe true
                screen.contains("pauseStreamConfirmDialog(labels)") shouldBe true
                screen.contains("resumeStreamConfirmDialog {") shouldBe true
                screen.contains("startRecordingConfirmDialog {") shouldBe true
                screen.contains("stopRecordingConfirmDialog {") shouldBe true
            }
            withClue("'Mehr' names a recording / stream whose control sits in the sheet, and the observer is disconnected with the call") {
                screen.contains("conferenceMoreButtonLabel(") shouldBe true
                screen.contains("overflow.dispose()") shouldBe true
            }
        }

        // ── V1.9.72: "Für alle beenden" next to "Verlassen" -- the dialog, not the distance, is the slip protection ─────────────────

        test("V1.9.72 detector: a use of a token is attributed to its enclosing top-level function, comments are exempt") {
            val source =
                """
                internal fun Container.conferenceExitGroup(a: Int): ConferenceExitGroup {
                    val end = root.conferenceControlButton(ActionIcon.END_FOR_ALL, x, y)
                    return end
                }

                private fun endRoomConfirmDialog() {
                    // ActionIcon.END_FOR_ALL in a comment
                    val b = newActionButton(ActionIcon.END_FOR_ALL, x, y)
                }
                """.trimIndent()
            usesByEnclosingFunction(source = source, token = "ActionIcon.END_FOR_ALL") shouldBe
                mapOf("conferenceExitGroup" to 1, "endRoomConfirmDialog" to 1)
        }

        test("V1.9.72: ActionIcon.END_FOR_ALL is used in exactly three places -- exit group, sheet twin, dialog confirmation") {
            val actual =
                clientFiles()
                    .associate { it.name to usesByEnclosingFunction(source = it.readText(), token = "ActionIcon.END_FOR_ALL") }
                    .filterValues { it.isNotEmpty() }
            withClue("the end-for-all symbol belongs to these three places and nowhere else (no second button, no shortcut): $actual") {
                actual shouldBe
                    mapOf(
                        "ConferenceControlBar.kt" to mapOf("conferenceExitGroup" to 1, "conferenceEndForAllTwin" to 1),
                        "ConferenceScreen.kt" to mapOf("endRoomConfirmDialog" to 1),
                    )
            }
        }

        test("V1.9.72: endRoom is called once, inside the confirm block behind the dialog guard -- no other trigger") {
            val calls =
                clientFiles()
                    .associate { file -> file.name to codeLines(file.readText()).count { it.contains(".endRoom(") } }
                    .filterValues { it > 0 }
            calls shouldBe mapOf("ConferenceScreen.kt" to 1)
            val screen = clientFiles().first { it.name == "ConferenceScreen.kt" }.readText()
            val handler = screen.substring(screen.indexOf("endForAllClick = click@{"))
            val guard = handler.indexOf("endDialogGuard.show(")
            val call = handler.indexOf(".endRoom(")
            withClue("endRoom( must sit in the trailing onConfirm block of endDialogGuard.show(...)") {
                (guard in 0 until call) shouldBe true
            }
            // no click handler of the exit group or any twin reaches endRoom except through endForAllClick
            screen.contains("onEndForAll = { endForAllClick() }") shouldBe true
        }

        test("V1.9.72: the dialog focuses 'Abbrechen', fires once and never autofocuses the confirming button") {
            val screen = clientFiles().first { it.name == "ConferenceScreen.kt" }.readText()
            val dialog = screen.substring(screen.indexOf("internal fun endRoomConfirmDialog("))
            val body = dialog.substring(0, dialog.indexOf("internal class EndRoomDialogGuard"))
            withClue("endRoomConfirmDialog: ConfirmOnce, focus on cancelButton, no autofocus") {
                body.contains("ConfirmOnce()") shouldBe true
                body.contains("once.run(confirmButton)") shouldBe true
                body.contains("cancelButton.getElement()?.focus()") shouldBe true
                body.contains("shown.bs.modal") shouldBe true
                codeLines(body).any { it.contains("autofocus", ignoreCase = true) } shouldBe false
                codeLines(body).any { it.contains("confirmButton") && it.contains("focus()") } shouldBe false
                // Escape / backdrop stay Bootstrap's defaults
                codeLines(body).any { it.contains("static") || it.contains("keyboard") } shouldBe false
            }
        }

        test("V1.9.72: the exit group has no ms-2 margin hack and keeps its 12 px gap; Verlassen is a conference control button") {
            val screen = clientFiles().first { it.name == "ConferenceScreen.kt" }.readText()
            codeLines(screen).any { it.contains("leaveButton.addCssClass(\"ms-2\")") } shouldBe false
            codeLines(screen).any { it.contains("addCssClass(\"ms-2\")") && it.contains("arrow-left") } shouldBe false
            val bar = clientFiles().first { it.name == "ConferenceControlBar.kt" }.readText()
            codeLines(bar).any { it.contains("\"ms-2\"") } shouldBe false
            bar.contains("lapis-conference-controls-group lapis-conference-controls-group--exit") shouldBe true
            bar.contains("CONFERENCE_EXIT_GAP_PX = 12.0") shouldBe true
            val css = THEME_CSS.readText()
            val exit = Regex("""\.lapis-conference-controls-group--exit\s*\{[^}]*\}""").find(css)?.value ?: ""
            withClue("theme.css: the exit group's gap is 12px, plain flex gap (no divider, no padding)") {
                exit.contains("gap: 12px") shouldBe true
                exit.contains("padding") shouldBe false
            }
        }
        // ── V1.9.74: the encounter room's icon bar (R58 named exception c), the exit group, the sheet ────────────────────────────

        test("V1.9.74: encounterControlButton is built only by the encounter room's three files, with fixed counts") {
            val actual =
                clientFiles()
                    .associate {
                        it.name to
                            codeLines(it.readText()).sumOf { line -> Regex("""\bencounterControlButton\(""").findAll(line).count() }
                    }.filterValues { it > 0 }
            withClue("the icon-only factory of the encounter bar stays inside the encounter room: $actual") {
                actual shouldBe
                    mapOf(
                        // the declaration itself
                        "EncounterControlBar.kt" to 1,
                        // chat, more (panels), scene, full screen (view), transmission (moderation), doors, leave (exit);
                        // V1.9.80: + the table microphone and "Kanzel lauter" (devices group, shown while one sits at a table)
                        // V1.9.84: + the hand and the event reactions loop (reaction group)
                        "EncounterRoom.kt" to 11,
                        // V1.9.90: the named R58 group "encounter devices (microphone and camera)"
                        "EncounterPulpitControls.kt" to 2,
                    )
            }
        }

        test(
            "V1.9.74: the encounter bar's CSS never wraps and never scrolls sideways; the exit group keeps its 12 px and sits at the end",
        ) {
            val css = THEME_CSS.readText()
            val bar = Regex("""\.lapis-encounter-controls\s*\{[^}]*\}""").find(css)?.value ?: ""
            withClue("theme.css .lapis-encounter-controls: $bar") {
                bar.contains("flex-wrap: nowrap") shouldBe true
                bar.contains("flex-wrap: wrap") shouldBe false
                Regex("""overflow-x:\s*(auto|scroll)""").containsMatchIn(bar) shouldBe false
            }
            val exit = Regex("""\.lapis-encounter-control-group--exit\s*\{[^}]*\}""").find(css)?.value ?: ""
            withClue("theme.css .lapis-encounter-control-group--exit: $exit") {
                exit.contains("gap: 12px") shouldBe true
                exit.contains("margin-inline-start: auto") shouldBe true
            }
            Regex("""\.lapis-encounter-control-overflowed\s*\{[^}]*display:\s*none""").containsMatchIn(css) shouldBe true
            val sheet = Regex("""\.lapis-encounter-more-sheet\s*\{[^}]*\}""").find(css)?.value ?: ""
            withClue("the sheet is a scroll surface of ledger class E1 and sits above the bar: $sheet") {
                sheet.contains("position: absolute") shouldBe true
                sheet.contains("overflow: auto") shouldBe true
            }
        }

        test(
            "V1.9.74: ActionIcon.CLOSE_DOORS is used in exactly three places of EncounterRoom -- bar button, sheet twin, dialog confirmation",
        ) {
            val room = clientFiles().first { it.name == "EncounterRoom.kt" }.readText()
            codeLines(room).sumOf { Regex("""ActionIcon\.CLOSE_DOORS""").findAll(it).count() } shouldBe 3
        }

        test("V1.9.74: closing the doors has one path -- the confirmation (focus on Abbrechen), reached from the bar and from the twin") {
            val room = clientFiles().first { it.name == "EncounterRoom.kt" }.readText()
            val dialog = room.substring(room.indexOf("private fun askCloseDoors()"))
            val body = dialog.substring(0, dialog.indexOf("// ── timers"))
            withClue("askCloseDoors: $body") {
                body.contains("focusCancel = true") shouldBe true
                body.contains("fullscreen.leaveIfActive()") shouldBe true
            }
            codeLines(room).count { it.contains("closeSpace(") } shouldBe 1
            codeLines(room).count { it.contains("askCloseDoors()") } shouldBe 3 // declaration + bar click + twin action
        }

        test("V1.9.74: 'Verlassen' lives in the bar only -- the header of the service view has no leave button any more") {
            val view = clientFiles().first { it.name == "EncounterServiceView.kt" }.readText()
            codeLines(view).any { it.contains("ActionIcon.LEAVE") } shouldBe false
            codeLines(view).any { it.contains("actionSlot.actionButton(") } shouldBe false
            val room = clientFiles().first { it.name == "EncounterRoom.kt" }.readText()
            codeLines(room).count { it.contains("ActionIcon.LEAVE") } shouldBe 1
        }

        test("V1.9.74: the icon bar writes its state through aria-pressed / aria-expanded, never through a changing label") {
            val room = clientFiles().first { it.name == "EncounterRoom.kt" }.readText()
            codeLines(room).any { Regex("""(sceneButton|fullscreenButton)\.text\s*=""").containsMatchIn(it) } shouldBe false
            codeLines(room).any { it.contains("tr(\"Szene ein\")") || it.contains("tr(\"Vollbild beenden\")") } shouldBe false
        }

        test("V1.9.74: the sheet's document listeners are removed on close and on dispose, and the overflow observer is disposed") {
            val room = clientFiles().first { it.name == "EncounterRoom.kt" }.readText()
            val sheetListeners =
                codeLines(room).count {
                    it.contains("document.addEventListener(\"keydown\"") ||
                        it.contains("document.addEventListener(\"click\"")
                }
            val removals =
                codeLines(room).count {
                    it.contains("document.removeEventListener(\"keydown\"") ||
                        it.contains("document.removeEventListener(\"click\"")
                }
            withClue("every document listener of the sheet has a removal") { removals shouldBe sheetListeners }
            room.contains("overflow.dispose()") shouldBe true
            codeLines(room).any { it.contains("addAfterInsertHook") } shouldBe false
        }

        test("V1.9.84 (R58 named exception group 'reaction group'): reactions are symbol-only, their sheet twins keep the word") {
            val room = codeLines(clientFiles().first { it.name == "EncounterRoom.kt" }.readText()).joinToString("\n")
            withClue("exactly two reaction call sites (hand, event loop) use the icon-only factory") {
                Regex("""\breactions\.encounterControlButton\(""").findAll(room).count() shouldBe 2
            }
            withClue("the reaction group must not fall back to a labelled actionButton") {
                room.contains("reactions.actionButton(") shouldBe false
            }
            withClue("the label function is used exactly three times: hand button, event button, sheet twin") {
                Regex("""\breactionLabelContent\(""").findAll(room).count() shouldBe 3
            }
            withClue("the hand has no sheet twin (a hand-raise never needs a second tap)") {
                room.contains("ControlBarSlot(EncounterControlSlot.Hand, handButton, null)") shouldBe true
            }
            val css = THEME_CSS.readText()
            withClue("theme.css must not bring the word of the reactions back (no white-space / font-size rule on the group)") {
                val wordRule = Regex("""\.lapis-encounter-control-group--reactions[^{]*\{[^}]*(white-space|font-size)""")
                wordRule.containsMatchIn(css) shouldBe false
            }
            val bar = codeLines(clientFiles().first { it.name == "EncounterControlBar.kt" }.readText()).joinToString("\n")
            withClue("the reaction group is labelled for assistive technology") {
                bar.contains("EncounterControlGroup.REACTIONS -> gettext(\"Reaktionen\")") shouldBe true
            }
            withClue("V1.9.90: the device group is labelled for assistive technology") {
                bar.contains("EncounterControlGroup.DEVICES -> gettext(\"Geräte\")") shouldBe true
            }
        }

        test("V1.9.90: devices come first and exit last in the enum; the mic band and its text button are gone") {
            val bar = codeLines(clientFiles().first { it.name == "EncounterControlBar.kt" }.readText()).joinToString("\n")
            withClue("DEVICES is the first, EXIT the last group of the DOM") {
                Regex("""enum class EncounterControlGroup \{\s*DEVICES,[^}]*,\s*EXIT\s*\}""").containsMatchIn(bar) shouldBe true
            }
            val pulpit = codeLines(clientFiles().first { it.name == "EncounterPulpitControls.kt" }.readText()).joinToString("\n")
            withClue("no text button in the device controls (R58 group, icon-only factory only)") {
                Regex("""\bactionButton\(""").findAll(pulpit).count() shouldBe 0
                pulpit.contains("lapis-encounter-mic-band") shouldBe false
            }
            withClue("theme.css carries no rule for the removed band") {
                THEME_CSS.readText().contains(".lapis-encounter-mic-band") shouldBe false
            }
        }

        test("V1.9.75: the reveal toggle and the password buttons carry visible text -- no icon-only button, no aria-pressed") {
            // The R58 list (chat composer, conference bar, encounter bar) stays unchanged: the eye never was an R58 exception, it was a
            // plain `Button("", icon = ...)`. From V1.9.75 it is a labelled actionButton; these two files may not fall back.
            listOf("FormGrammar.kt", "MemberPasswordResetDialog.kt").forEach { name ->
                val code = codeLines(clientFiles().first { it.name == name }.readText()).joinToString("\n")
                withClue("$name must not build an icon-only button") {
                    code.contains("newIconOnlyActionButton") shouldBe false
                    Regex("""iconOnly\s*=\s*true""").containsMatchIn(code) shouldBe false
                    Regex("""\bButton\(\s*""\s*,""").containsMatchIn(code) shouldBe false
                    Regex("""\.button\(\s*""\s*,""").containsMatchIn(code) shouldBe false
                }
            }
            val grammar = codeLines(clientFiles().first { it.name == "FormGrammar.kt" }.readText()).joinToString("\n")
            withClue("a reveal toggle with a changing name must not also set aria-pressed (the name and the state contradict)") {
                grammar.contains("\"aria-pressed\"") shouldBe false
            }
            val dashboard = codeLines(clientFiles().first { it.name == "DashboardScreen.kt" }.readText()).joinToString("\n")
            dashboard.contains("ActionIcon.PASSWORD") shouldBe true
            dashboard.contains("ActionIcon.LEAVE") shouldBe true
        }
    })
