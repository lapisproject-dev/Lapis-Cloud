package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.31 "Umfragen" -- answer secrecy and the "no server message" rule of the poll screens, as a tripwire on the sources (same heuristic as
 * [ConsensusSecrecyTripwireTest]; the behavioural evidence is in the Karma tests `PollBoothDomTest` and friends). An answer in a poll is
 * anonymous like a vote, so the chosen option is treated like a secret ballot:
 *
 *  - no `console.`/`println`/`localStorage`/`sessionStorage`/`pushState`/`replaceState`, and `.message` is never read at all;
 *  - what an answer contains (`PollResponseInput`, `castPollResponse`, `optionId`) stays out of every file but the booth and the guard (the
 *    result view reads `optionId` only to match a result row to its option text, and never builds or sends an answer);
 *  - `chosenIndex` -- the one place the choice lives -- exists only in `PollBooth.kt`;
 *  - the booth has no toast, no `data-*` attribute, no `value`/`checked` attribute, and no `setAttribute` that touches an option;
 *  - the weighted/head result fields appear only in the result view and the pure gates;
 *  - a count of answers is shown only behind the closed-poll gate;
 *  - text a member typed never goes into a `content =` assignment or a legend raw (the global untrusted-text tripwire only knows
 *    `div`, `span`, `p`, headings and `link` -- a legend or a label would slip through it).
 */
private val CLIENT_DIR =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private val POLL_FILES =
    listOf(
        "PollScreen.kt",
        "PollListView.kt",
        "PollCreateForm.kt",
        "PollAuthzUi.kt",
        "PollLabels.kt",
        "PollDetail.kt",
        "PollBooth.kt",
        "PollResultView.kt",
        "PollGuard.kt",
    )

private val FORBIDDEN_EVERYWHERE =
    listOf(
        Regex("""\bconsole\."""),
        Regex("""\bprintln\s*\("""),
        Regex("""\blocalStorage\b"""),
        Regex("""\bsessionStorage\b"""),
        Regex("""\bpushState\b"""),
        Regex("""\breplaceState\b"""),
        Regex("""\.message\b"""),
        Regex("""notify\w*\([^)]*\.message"""),
    )

private val ANSWER_CONTENT = Regex("""\b(PollResponseInput|castPollResponse)\b""")
private val OPTION_ID = Regex("""\boptionId\b""")
private val RESULT_FIELDS = Regex("""\b(sharePercent|weightedResult|headResult)\b""")
private val UNTRUSTED_CONTENT = Regex("""content\s*=\s*\w+\.(text|question|description|createdByDisplayName)\b""")
private val UNTRUSTED_LEGEND = Regex("""TAG\.LEGEND\s*,\s*content\s*=\s*(?!sanitize|pollOptionText)\w+\.""")

private val ANSWER_FILES = setOf("PollBooth.kt", "PollGuard.kt")
private val OPTION_ID_FILES = ANSWER_FILES + "PollResultView.kt"
private val RESULT_FIELD_FILES = setOf("PollResultView.kt", "PollAuthzUi.kt")
private val COUNT_FILES = setOf("PollListView.kt", "PollDetail.kt", "PollResultView.kt")

private fun codeLines(text: String): List<String> =
    text.lines().filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

internal fun pollSecrecyFindings(
    fileName: String,
    text: String,
): List<String> {
    val findings = mutableListOf<String>()
    val code = codeLines(text)
    code.forEachIndexed { index, line ->
        fun flag(why: String) {
            findings += "$fileName: $why: ${line.trim()}"
        }
        FORBIDDEN_EVERYWHERE.forEach { rule -> if (rule.containsMatchIn(line)) flag("forbidden") }
        if (ANSWER_CONTENT.containsMatchIn(line) && fileName !in ANSWER_FILES) flag("answer content outside booth/guard")
        if (OPTION_ID.containsMatchIn(line) && fileName !in OPTION_ID_FILES) flag("optionId outside booth/guard/result view")
        if (fileName == "PollResultView.kt" &&
            ANSWER_CONTENT.containsMatchIn(line)
        ) {
            flag("the result view must not build or send an answer")
        }
        if (Regex("""\bchosenIndex\b""").containsMatchIn(line) && fileName != "PollBooth.kt") flag("chosenIndex outside the booth")
        if (RESULT_FIELDS.containsMatchIn(line) && fileName !in RESULT_FIELD_FILES) flag("result field outside the result view")
        if (UNTRUSTED_CONTENT.containsMatchIn(line)) flag("untrusted text as raw content")
        if (UNTRUSTED_LEGEND.containsMatchIn(line)) flag("untrusted legend text not sanitized")
        if (fileName in COUNT_FILES && fileName != "PollResultView.kt" && Regex("""\bresponseCount\b""").containsMatchIn(line)) {
            val window = code.subList(maxOf(0, index - 6), index + 1)
            if (window.none {
                    it.contains(
                        "showsResponseCount",
                    ) ||
                        it.contains("PollStatus.CLOSED")
                }
            ) {
                flag("count outside the closed-poll gate")
            }
        }
        if (fileName == "PollBooth.kt") {
            if (Regex("""\bnotify\w*\s*\(""").containsMatchIn(line)) flag("toast in the booth")
            if (Regex("""data-|setAttribute\("data""").containsMatchIn(line)) flag("data attribute")
            if (Regex("""setAttribute\(\s*"value"""").containsMatchIn(line)) flag("value attribute")
            if (Regex("""setAttribute\(\s*"checked"""").containsMatchIn(line)) flag("checked attribute (property only)")
            if (Regex("""setAttribute\(""").containsMatchIn(line) && Regex("""\.id\b|\boptions?\b|optionId""").containsMatchIn(line)) {
                flag("setAttribute touching an option")
            }
            if (Regex("""navigateTo\(""").containsMatchIn(line)) flag("navigation from the booth")
        }
    }
    return findings
}

class PollSecrecyTripwireTest :
    FunSpec({
        test("the poll client files exist (tripwire is not vacuous)") {
            POLL_FILES.filterNot { File(CLIENT_DIR, it).isFile }.shouldBeEmpty()
        }

        test("no poll client file logs, stores, routes or toasts anything it must not, and the choice stays in the booth") {
            POLL_FILES.flatMap { pollSecrecyFindings(fileName = it, text = File(CLIENT_DIR, it).readText()) }.shouldBeEmpty()
        }

        test("the detector flags each forbidden shape and ignores comments and look-alikes") {
            pollSecrecyFindings(fileName = "X.kt", text = "console.log(x)").size shouldBe 1
            pollSecrecyFindings(fileName = "X.kt", text = "println(x)").size shouldBe 1
            pollSecrecyFindings(fileName = "X.kt", text = "window.localStorage.setItem(a, b)").size shouldBe 1
            pollSecrecyFindings(fileName = "X.kt", text = "window.sessionStorage.removeItem(a)").size shouldBe 1
            pollSecrecyFindings(fileName = "X.kt", text = "window.history.pushState(null, \"\", url)").size shouldBe 1
            pollSecrecyFindings(fileName = "X.kt", text = "window.history.replaceState(null, \"\", url)").size shouldBe 1
            pollSecrecyFindings(fileName = "X.kt", text = "val t = e.message").size shouldBe 1
            pollSecrecyFindings(fileName = "X.kt", text = "notifyError(e.message)").size shouldBe 2
            pollSecrecyFindings(fileName = "PollListView.kt", text = "rpc.castPollResponse(x)").size shouldBe 1
            pollSecrecyFindings(fileName = "PollDetail.kt", text = "PollResponseInput(a, b)").size shouldBe 1
            pollSecrecyFindings(fileName = "PollBooth.kt", text = "PollResponseInput(pollId = a, optionId = b)").size shouldBe 0
            pollSecrecyFindings(fileName = "PollGuard.kt", text = "rpc.castPollResponse(input)").size shouldBe 0
            pollSecrecyFindings(fileName = "PollDetail.kt", text = "val id = x.optionId").size shouldBe 1
            pollSecrecyFindings(fileName = "PollResultView.kt", text = "val id = x.optionId").size shouldBe 0
            pollSecrecyFindings(fileName = "PollResultView.kt", text = "PollResponseInput(a, b)").size shouldBe 2
            pollSecrecyFindings(fileName = "PollDetail.kt", text = "var chosenIndex = 1").size shouldBe 1
            pollSecrecyFindings(fileName = "PollBooth.kt", text = "var chosenIndex = 1").size shouldBe 0
            pollSecrecyFindings(fileName = "PollListView.kt", text = "val s = r.sharePercent").size shouldBe 1
            pollSecrecyFindings(fileName = "PollDetail.kt", text = "r.weightedResult.size").size shouldBe 1
            pollSecrecyFindings(fileName = "PollResultView.kt", text = "r.weightedResult.size").size shouldBe 0
            pollSecrecyFindings(fileName = "PollAuthzUi.kt", text = "r.headResult.size").size shouldBe 0
            pollSecrecyFindings(fileName = "PollDetail.kt", text = "val n = poll.responseCount").size shouldBe 1
            pollSecrecyFindings(
                fileName = "PollDetail.kt",
                text = "if (showsResponseCount(poll)) {\n  val n = poll.responseCount\n}",
            ).size shouldBe
                0
            pollSecrecyFindings(fileName = "PollListView.kt", text = "if (x.status == PollStatus.CLOSED) n = x.responseCount").size shouldBe
                0
            pollSecrecyFindings(fileName = "X.kt", text = "div(content = poll.question)").size shouldBe 1
            pollSecrecyFindings(fileName = "X.kt", text = "span(content = option.text)").size shouldBe 1
            pollSecrecyFindings(fileName = "X.kt", text = "foo.content = poll.description").size shouldBe 1
            pollSecrecyFindings(fileName = "X.kt", text = "tag(TAG.LEGEND, content = poll.question)").size shouldBe 2
            pollSecrecyFindings(
                fileName = "X.kt",
                text = "tag(TAG.LEGEND, content = sanitizeUntrustedI18nText(poll.question))",
            ).size shouldBe
                0
            pollSecrecyFindings(fileName = "PollBooth.kt", text = "notifyError(a)").size shouldBe 1
            pollSecrecyFindings(fileName = "PollBooth.kt", text = "radio.setAttribute(\"data-v\", \"1\")").size shouldBe 1
            pollSecrecyFindings(fileName = "PollBooth.kt", text = "radio.setAttribute(\"value\", x)").size shouldBe 1
            pollSecrecyFindings(fileName = "PollBooth.kt", text = "radio.setAttribute(\"checked\", \"checked\")").size shouldBe 1
            pollSecrecyFindings(fileName = "PollBooth.kt", text = "radio.setAttribute(\"id\", option.id)").size shouldBe 1
            pollSecrecyFindings(fileName = "PollBooth.kt", text = "radio.setAttribute(\"id\", id)").size shouldBe 0
            pollSecrecyFindings(fileName = "PollBooth.kt", text = "radio.setAttribute(\"name\", RADIO_GROUP)").size shouldBe 0
            pollSecrecyFindings(fileName = "PollBooth.kt", text = "navigateTo(\"/polls\")").size shouldBe 1
            pollSecrecyFindings(fileName = "X.kt", text = "// console.log(x)").size shouldBe 0
            pollSecrecyFindings(fileName = "X.kt", text = " * localStorage is never used").size shouldBe 0
            pollSecrecyFindings(fileName = "X.kt", text = "val consoleLike = 1").size shouldBe 0
        }
    })
