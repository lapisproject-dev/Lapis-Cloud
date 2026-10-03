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

private const val REASON_DOMAIN_ICON_8 =
    "DOMAIN_ICON: icon-only 'Neu erzeugen' (generate a new temporary password), not a refresh of data"

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
        "S3:ConferenceScreen.kt" to LedgerEntry(count = 11, reason = REASON_DOMAIN_ICON_4),
        "S3:ConferenceVotePanel.kt" to LedgerEntry(count = 1, reason = REASON_DOMAIN_ICON_5),
        "S3:DocumentsScreen.kt" to LedgerEntry(count = 1, reason = REASON_DOMAIN_ICON_6),
        "S3:MailingListRows.kt" to LedgerEntry(count = 1, reason = REASON_DOMAIN_ICON_7),
        "S3:MemberPasswordResetDialog.kt" to LedgerEntry(count = 1, reason = REASON_DOMAIN_ICON_8),
        "S3:SearchableSelect.kt" to LedgerEntry(count = 1, reason = REASON_DOMAIN_ICON_9),
    )

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
    })
