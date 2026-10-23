package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

/**
 * Tripwire for the back-button rule (V1.9.194): going back is ONE component -- `overviewBackButton`/`backButton` in `MasterDetail.kt`
 * (arrow icon plus a visible text that names the goal, natural width). A static text scan of the client sources, not a proof of the
 * rendered DOM (that is `MasterDetailDomTest` and the booth/conference DOM tests in `lapis-client`).
 *
 * It fails on
 *  1. a label that starts with the bare word "Zurück" (`tr("Zurück")`, `tr("Zurück zur Liste")`) -- the goal belongs in the text ("Zur Liste");
 *  2. a RAW `button(tr("Zur Übersicht"))` / "Zur Liste" / "Zur Wahlübersicht": the helper builds those;
 *  3. `ActionIcon.BACK` outside the helper and the named [ALLOWED_FILES];
 *  4. a back button (a line that builds one) together with `w-100` or `d-grid`.
 *
 * The allow-list holds the buttons that are NOT page navigation. They step back INSIDE a screen (an inline confirmation step, R27:
 * "Abbrechen" left, confirm right), so "Zur Übersicht" would be wrong there. Each entry says why.
 *
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
private val CLIENT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

/** File name -> why it may keep a bare "Zurück" label and/or `ActionIcon.BACK`. */
private val ALLOWED_FILES: Map<String, String> =
    mapOf(
        "MasterDetail.kt" to "the helper itself",
        "OpenItemDialogs.kt" to "confirmation step of a dialog (R27): undoes the inline confirmation, no navigation",
        "CarpoolScreen.kt" to "confirmation step (R27): undoes the inline confirmation, no navigation",
        "CarpoolParticipationUi.kt" to "confirmation step (R27): undoes the inline confirmation, no navigation",
        "RegionalChaptersScreen.kt" to "confirmation step (R27): undoes the inline confirmation, no navigation",
        "PollDetail.kt" to "close label of a modal that has no 'Abbrechen' next to it, no navigation",
        "PollBooth.kt" to "review step of the voting booth ('Auswahl ändern'): steps back to the choice inside the booth",
        "ElectionBooth.kt" to "review step of the voting booth ('Auswahl ändern'): steps back to the choice inside the booth",
        "RatingBooth.kt" to "review step of the rating booth ('Bewertung ändern'): steps back to the choice inside the booth",
        "ArticleImportScreen.kt" to
            "in-screen step from the import preview back to editing; the label names the goal ('Zurück zum Bearbeiten')",
        "EventImportScreen.kt" to
            "in-screen step from the import preview back to editing; the label names the goal ('Zurück zum Bearbeiten')",
        "ConferenceControlBar.kt" to "a control-bar action of the room ('Zurück zum Hauptraum'), not page navigation",
        "ConferenceScreen.kt" to "accessible name of that control-bar action ('Zurück zum Hauptraum')",
        "CarpoolMessagesUi.kt" to "uses the arrow as 'older messages' icon (not a back button); to be replaced by another icon",
    )

private val BARE_BACK_LABEL = Regex("""(?:tr|gettext)\(\s*"Zurück(?=[ "])""")
private val RAW_BACK_BUTTON = Regex("""(?<![\w])button\(\s*(?:tr|gettext)\(\s*"(?:Zur Übersicht|Zur Liste|Zur Wahlübersicht)""")
private val BACK_ICON = Regex("""ActionIcon\.BACK\b""")
private val BACK_BUILDER = Regex("""(?:ActionIcon\.BACK|(?<![\w])backButton\(|overviewBackButton\()""")
private val FULL_WIDTH = Regex("""\b(?:w-100|d-grid)\b""")

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

/** The findings of one source text; [name] is the file name (the allow-list key). */
internal fun backButtonFindings(
    name: String,
    text: String,
): List<String> {
    val allowed = name in ALLOWED_FILES
    val out = mutableListOf<String>()
    text.lines().forEachIndexed { index, line ->
        if (isCommentLine(line)) return@forEachIndexed
        val where = "$name:${index + 1}: ${line.trim()}"
        if (!allowed && BARE_BACK_LABEL.containsMatchIn(line)) out += "bare 'Zurück' label -- name the goal ('Zur Liste'): $where"
        if (RAW_BACK_BUTTON.containsMatchIn(line)) out += "raw back button -- use overviewBackButton/backButton: $where"
        if (!allowed && BACK_ICON.containsMatchIn(line)) out += "ActionIcon.BACK outside the helper -- use backButton: $where"
        if (BACK_BUILDER.containsMatchIn(line) && FULL_WIDTH.containsMatchIn(line)) out += "back button must not be full width: $where"
    }
    return out
}

class ClientBackButtonTripwireTest :
    FunSpec({
        val files = CLIENT_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        test("the scan reads the client sources") {
            files.size shouldBeGreaterThan 100
        }

        test("no client source builds a back button of its own") {
            files.flatMap { backButtonFindings(name = it.name, text = it.readText()) }.shouldBeEmpty()
        }

        test("every allow-listed file exists and really uses the pattern it is excused for") {
            val byName = files.associateBy { it.name }
            ALLOWED_FILES.keys.forEach { name ->
                (name in byName) shouldBeTrueWith "allow-listed file missing: $name"
            }
            ALLOWED_FILES.keys
                .filter { it != "MasterDetail.kt" }
                .filterNot { name ->
                    val text = byName.getValue(name).readText()
                    BACK_ICON.containsMatchIn(text) || BARE_BACK_LABEL.containsMatchIn(text) || text.contains("\"Zurück zum Hauptraum\"")
                }.shouldBeEmpty()
        }

        test("the detector flags a bare label, a raw back button, a stray icon and a full-width back button") {
            backButtonFindings(name = "X.kt", text = """button(tr("Zurück"), style = ButtonStyle.OUTLINESECONDARY)""").shouldNotBeEmpty()
            backButtonFindings(
                name = "X.kt",
                text = """root.actionButton(ActionIcon.BACK, tr("Zurück zur Liste"))""",
            ).size shouldBeGreaterThan
                1
            backButtonFindings(
                name = "X.kt",
                text = """root.button(tr("Zur Übersicht"), style = ButtonStyle.OUTLINESECONDARY)""",
            ).shouldNotBeEmpty()
            backButtonFindings(
                name = "X.kt",
                text = """val b = newActionButton(ActionIcon.BACK, tr("Zur Liste"), ButtonStyle.LINK)""",
            ).shouldNotBeEmpty()
            backButtonFindings(name = "X.kt", text = """root.backButton(tr("Zur Liste")).addCssClass("w-100")""").shouldNotBeEmpty()
        }

        test("the detector accepts the helper, an unrelated 'Zurück...' word and the allow-listed files") {
            backButtonFindings(name = "X.kt", text = """root.backButton(tr("Zur Liste")) { leave() }""").shouldBeEmpty()
            backButtonFindings(name = "X.kt", text = """root.overviewBackButton(Routes.POLLS)""").shouldBeEmpty()
            backButtonFindings(name = "X.kt", text = """actionButton(ActionIcon.UNDO, tr("Zurückziehen"))""").shouldBeEmpty()
            backButtonFindings(name = "X.kt", text = """    // tr("Zurück") in a comment""").shouldBeEmpty()
            backButtonFindings(
                name = "CarpoolScreen.kt",
                text = """confirmBox.actionButton(ActionIcon.BACK, tr("Zurück"))""",
            ).shouldBeEmpty()
        }
    })

private infix fun Boolean.shouldBeTrueWith(message: String) {
    if (!this) throw AssertionError(message)
}
