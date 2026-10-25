package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Tripwires of V1.9.208 (UI/UX guideline R58, mobile extension), in the pattern of [ClientToolbarIconTripwireTest]: the client sources are
 * scanned as text, comment lines are exempt, every detector proves itself against a positive and a negative example.
 *
 * - A responsive action button (`labelMode = ActionLabelMode.RESPONSIVE`, icon only below 576 px) lives in an `actionGroup`, never in a
 *   toolbar and never in a dense table (those keep the `btn-sm` icon button of R58).
 * - At most one labelled (`ALWAYS`) action per `actionGroup`, except the ledgered groups whose labelled actions exclude each other.
 * - `theme.css` keeps the 44 px touch target and the no-wrap rule of `.lapis-action-btn`, and the R56 toolbar stays bottom-aligned.
 */
private val GROUP_CLIENT_DIR: File =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val GROUP_THEME_CSS: File =
    File("../lapis-client/src/jsMain/resources/theme.css")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/theme.css") }

private val RESPONSIVE_MODE = Regex("""labelMode\s*=\s*ActionLabelMode\.RESPONSIVE""")
private val GROUP_DECLARATION = Regex("""^\s*(?:val|var)\s+(\w+)\s*=\s*(?:[\w.]+\.)?actionGroup\(""")

/**
 * Groups whose labelled (`ALWAYS`) actions are mutually exclusive variants of ONE slot, so only one shows at a time:
 * file -> variable -> count. MemberEventsScreen: "Anmelden" / "Warteliste" / "Anmelden und bezahlen" (no own registration) versus
 * "Abmelden" (an active registration). May only shrink.
 */
private val ALWAYS_LEDGER: Map<String, Map<String, Int>> = mapOf("MemberEventsScreen.kt" to mapOf("actions" to 2))

private fun groupCommentLine(line: String): Boolean =
    line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

private fun groupCode(source: String): String = source.split('\n').joinToString("\n") { if (groupCommentLine(it)) "" else it }

/** The text of the call whose name starts at [start] (balanced parentheses, so a multi-line call is read whole). */
private fun callText(
    code: String,
    start: Int,
): String {
    val open = code.indexOf('(', start)
    if (open < 0) return ""
    var depth = 0
    var i = open
    while (i < code.length) {
        when (code[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return code.substring(start, i + 1)
            }
        }
        i++
    }
    return code.substring(start)
}

/** Number of `actionButton`/`actionLink` calls per `actionGroup` variable that keep their label (not RESPONSIVE, not iconOnly). */
internal fun alwaysActionsPerGroup(source: String): Map<String, Int> {
    val code = groupCode(source)
    val variables = code.split('\n').mapNotNull { GROUP_DECLARATION.find(it)?.groupValues?.get(1) }.toSet()
    return variables.associateWith { v ->
        Regex("""(?<![\w.])$v\.(actionButton|actionLink)\(""").findAll(code).count { match ->
            val call = callText(code = code, start = match.range.first)
            !RESPONSIVE_MODE.containsMatchIn(call) && !Regex("""iconOnly\s*=\s*true""").containsMatchIn(call)
        }
    }
}

/** True when the file uses a responsive mode without ever creating an `actionGroup`. */
internal fun responsiveWithoutGroup(source: String): Boolean {
    val code = groupCode(source)
    return RESPONSIVE_MODE.containsMatchIn(code) && !Regex("""(?<![\w])actionGroup\(""").containsMatchIn(code)
}

/** True when a responsive action sits in a `lapisToolbar` variable. */
internal fun responsiveInToolbar(source: String): Boolean {
    val code = groupCode(source)
    val toolbars = Regex("""(?:val|var)\s+(\w+)\s*=\s*(?:[\w.]+\.)?lapisToolbar\(""").findAll(code).map { it.groupValues[1] }
    return toolbars.any { v ->
        Regex("""(?<![\w.])$v\.actionButton\(""").findAll(code).any {
            RESPONSIVE_MODE.containsMatchIn(callText(code = code, start = it.range.first))
        }
    }
}

private fun groupFiles(): List<File> =
    GROUP_CLIENT_DIR
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.name }
        .toList()

class ClientActionGroupTripwireTest :
    FunSpec({
        test("detector: ALWAYS actions per group are counted, RESPONSIVE and iconOnly are not") {
            val src =
                """
                val actions = card.actionGroup()
                actions.actionLink(ActionIcon.ENTER, tr("Zum Raum"), "#x")
                actions.actionButton(
                    ActionIcon.EDIT,
                    tr("Bearbeiten"),
                    labelMode = ActionLabelMode.RESPONSIVE,
                )
                actions.actionButton(ActionIcon.DELETE, tr("Löschen"), iconOnly = true)
                actions.actionButton(ActionIcon.SAVE, tr("Speichern"))
                other.actionButton(ActionIcon.SAVE, tr("Speichern"))
                // actions.actionButton(ActionIcon.SAVE, tr("Kommentar"))
                """.trimIndent()
            alwaysActionsPerGroup(src) shouldBe mapOf("actions" to 2)
            alwaysActionsPerGroup("val bar = row.lapisToolbar()\nbar.actionButton(ActionIcon.SAVE, tr(\"X\"))") shouldBe emptyMap()
        }

        test("detector: a responsive mode needs an actionGroup, and never sits in a toolbar") {
            responsiveWithoutGroup("x.actionButton(K, tr(\"A\"), labelMode = ActionLabelMode.RESPONSIVE)") shouldBe true
            val grouped = "val g = c.actionGroup()\ng.actionButton(K, tr(\"A\"), labelMode = ActionLabelMode.RESPONSIVE)"
            responsiveWithoutGroup(grouped) shouldBe false
            responsiveWithoutGroup("// labelMode = ActionLabelMode.RESPONSIVE") shouldBe false
            val inBar = "val bar = c.lapisToolbar()\nbar.actionButton(K, tr(\"A\"), labelMode = ActionLabelMode.RESPONSIVE)"
            responsiveInToolbar(inBar) shouldBe true
            responsiveInToolbar("val bar = c.lapisToolbar()\nbar.actionButton(K, tr(\"A\"))") shouldBe false
        }

        test("V1.9.208: a responsive action button is only used inside an actionGroup, never in a toolbar") {
            val actual = groupFiles().filter { f -> f.name != "ActionButton.kt" && responsiveWithoutGroup(f.readText()) }.map { it.name }
            withClue("RESPONSIVE without an actionGroup in the same file: $actual") { actual shouldBe emptyList() }
            val inToolbar = groupFiles().filter { responsiveInToolbar(it.readText()) }.map { it.name }
            withClue("RESPONSIVE inside a lapisToolbar: $inToolbar") { inToolbar shouldBe emptyList() }
        }

        test("V1.9.208 (R58): at most one labelled action per actionGroup, except the ledgered mutually exclusive variants") {
            val actual =
                groupFiles()
                    .associate { it.name to alwaysActionsPerGroup(it.readText()) }
                    .mapValues { (_, groups) -> groups.filterValues { it > 1 } }
                    .filterValues { it.isNotEmpty() }
            actual shouldBe ALWAYS_LEDGER
        }

        test("V1.9.208: ActionButton.kt hides the label span below 576 px from sight and from assistive technology") {
            val code = File(GROUP_CLIENT_DIR, "network/lapis/cloud/client/ActionButton.kt").readText()
            withClue("label span classes") { code.contains("lapis-action-label d-none d-sm-inline") shouldBe true }
            withClue("label span is aria-hidden") { Regex("""span\(label[^\n]*aria-hidden""").containsMatchIn(code) shouldBe true }
            withClue("name through title + aria-label") { code.contains("tableActionTooltip(label)") shouldBe true }
            withClue("never btn-sm") { code.contains("RESPONSIVE action button is never btn-sm") shouldBe true }
        }

        test("V1.9.208: theme.css keeps the 44 px touch target, the no-wrap rule and the action group; R56 stays bottom-aligned") {
            val css = GROUP_THEME_CSS.readText()
            val btn =
                Regex("""\.lapis-action-btn\s*\{[^}]*\}""")
                    .find(css)
                    ?.value
                    .orEmpty()
                    .replace(" ", "")
            withClue(".lapis-action-btn rule") {
                (btn.contains("min-height:44px") && btn.contains("min-width:44px") && btn.contains("white-space:nowrap")) shouldBe true
            }
            val group =
                Regex("""\.lapis-action-group\s*\{[^}]*\}""")
                    .find(css)
                    ?.value
                    .orEmpty()
                    .replace(" ", "")
            withClue(".lapis-action-group rule") { (group.contains("flex-wrap:wrap") && group.contains("display:flex")) shouldBe true }
            val toolbar = Regex("""\.lapis-toolbar\s*\{[^}]*\}""").find(css)?.value.orEmpty()
            withClue("the R56 toolbar keeps its bottom alignment") { toolbar.contains("align-items: flex-end") shouldBe true }
            val reduced = css.lastIndexOf("@media (prefers-reduced-motion: reduce)")
            withClue("R54: the new rules stand above the last reduced-motion block") {
                (css.indexOf(".lapis-action-btn {") in 0 until reduced) shouldBe true
            }
            val checkbox =
                Regex("""\.lapis-inline-row \.form-check,[^{]*\{[^}]*\}""")
                    .find(css)
                    ?.value
                    .orEmpty()
                    .replace(" ", "")
            withClue("a checkbox in a filter row has no float/margin offset") {
                listOf("margin:0", "padding-left:0", "align-items:center").all { checkbox.contains(it) } shouldBe true
            }
        }
    })
