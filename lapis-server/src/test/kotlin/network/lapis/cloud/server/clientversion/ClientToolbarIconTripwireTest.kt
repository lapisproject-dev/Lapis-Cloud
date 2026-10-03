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

/** V1.9.45: the finance screens, held strictly -- a plain button for a verb the `ActionIcon` table maps unambiguously is a finding, never ledgered. */
private val R57_FINANCE_STRICT_FILES =
    setOf(
        "CostCentersScreen.kt",
        "DonorsScreen.kt",
        "LedgerScreen.kt",
        "OpenItemsScreen.kt",
        "SepaBatchesScreen.kt",
        "SepaMandatesScreen.kt",
        "SepaMandateSection.kt",
        "SepaSettingsScreen.kt",
        "BankStatementImportScreen.kt",
        "BankAccountsScreen.kt",
        "AccountingExportScreen.kt",
        "FinancialReportsScreen.kt",
        "ContributionsScreen.kt",
        "DunningCasesScreen.kt",
    )

private const val TABLE_VERBS = "anlegen|hinzufügen|speichern|bearbeiten|stornieren|widerrufen|deaktivieren|duplizieren"

private val PLAIN_TABLE_VERB_BUTTON = Regex("""(?<![\w])[bB]utton\(\s*(?:text\s*=\s*)?(?:tr|gettext)\("[^"]*(?:$TABLE_VERBS)"""")

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

internal fun plainTableVerbButtons(source: String): Int = PLAIN_TABLE_VERB_BUTTON.findAll(codeLines(source).joinToString("\n")).count()

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
 */
private val R57_STRING_TABLE_ACTION_LEDGER: Map<String, Int> =
    mapOf(
        "BankAccountsScreen.kt" to 1,
        "DataScreenLayout.kt" to 1,
        "DocumentsScreen.kt" to 2,
        "KeycloakLinkScreen.kt" to 2,
        "MemberAddressAdminDialog.kt" to 1,
        "MemberAdministrationScreen.kt" to 6,
        "MemberFamiliesScreen.kt" to 2,
        "MembershipTiersScreen.kt" to 1,
        "OpenItemsScreen.kt" to 1,
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
        }

        test("R57 detector (finance): a plain button for a table verb is found, an actionButton or an unmapped verb is not") {
            plainTableVerbButtons("row.button(tr(\"Posten stornieren\"), style = S)") shouldBe 1
            plainTableVerbButtons("val b = Button(tr(\"Kostenstelle anlegen\"), style = S)") shouldBe 1
            plainTableVerbButtons("row.button(\n    tr(\"Mandat widerrufen\"),\n)") shouldBe 1
            plainTableVerbButtons("row.actionButton(ActionIcon.UNDO, tr(\"Posten stornieren\"))") shouldBe 0
            plainTableVerbButtons("val b = newActionButton(ActionIcon.ADD, tr(\"Konto anlegen\"))") shouldBe 0
            plainTableVerbButtons("row.button(tr(\"Ausgleichen …\"))") shouldBe 0
        }

        test("R57 (V1.9.45): the finance screens hold no plain button for a verb the ActionIcon table maps") {
            val byName = clientFiles().associateBy { it.name }
            val actual =
                R57_FINANCE_STRICT_FILES
                    .filter { it in byName }
                    .associateWith { plainTableVerbButtons(byName.getValue(it).readText()) }
                    .filterValues { it > 0 }
            withClue("use actionButton/newActionButton(ActionIcon.X, ...): $actual") { actual shouldBe emptyMap() }
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
