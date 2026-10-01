package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.22 "Wahlen" -- ballot secrecy and the "no server message" rule of the elections screens, as a tripwire on the sources. The
 * detector is a heuristic over source text (like [ClientUntrustedWidgetTextTripwireTest]); the behavioural evidence is in the
 * Karma tests (`ElectionSecrecyDomTest`, `ElectionBoothDomTest`).
 *
 * What may never appear in an elections client file:
 *  - `console.` and `println` -- nothing about a ballot is ever logged;
 *  - `localStorage`/`sessionStorage` -- a receipt code or a selection in browser storage survives the tab;
 *  - `pushState`/`replaceState` -- a code in the URL ends up in history, the server log and `Referer`;
 *  - `.message` next to a `notify...` call -- Kilua RPC never transmits an exception message, and what the server wrote can contain
 *    member UUIDs ("Member <uuid> already voted..."), so no elections code shows `e.message`;
 *  - `castAt` read anywhere but `ElectionResultUi.kt`'s open-election branch -- a secret election's ballots are shown without time.
 */
private val CLIENT_DIR =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private val ELECTION_FILES =
    listOf(
        "ElectionsScreen.kt",
        "ElectionBoardUi.kt",
        "ElectionResultUi.kt",
        "ElectionBooth.kt",
        "ElectionOpenForm.kt",
        "ElectionGuard.kt",
        "ElectionLabels.kt",
        "ElectionPhase.kt",
        "ElectionAuthzUi.kt",
        "ElectionMajorityExplain.kt",
        // V1.9.25: the conference voting panel embeds the booth; the receipt code never reaches it, and it stores/logs nothing either
        "ConferenceVotePanel.kt",
        // V1.9.26: the operator side and the stream mirror of the room's voting panel -- they handle counters, statuses and titles, never a ballot
        "ConferenceVoteOperatorControls.kt",
        "ConferenceVoteStreamMirror.kt",
    )

/**
 * V1.9.26: the files of the conference panel that must never touch a ballot's content at all. The booth (`ElectionBooth.kt`) is the only
 * place a selection or a receipt code exists; the panel, the operator controls and the mirror see counters, statuses and titles.
 */
private val BALLOT_BLIND_FILES = setOf("ConferenceVotePanel.kt", "ConferenceVoteOperatorControls.kt", "ConferenceVoteStreamMirror.kt")

private val BALLOT_CONTENT =
    Regex("""\b(receiptCode|selectedOptionIds|selectedOptionLabels|ElectionBallotInput|listElectionBallots|castElectionBallot)\b""")

private fun codeLines(text: String): List<String> =
    text.lines().filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

private val FORBIDDEN_EVERYWHERE =
    listOf(
        Regex("""\bconsole\."""),
        Regex("""\bprintln\s*\("""),
        Regex("""\blocalStorage\b"""),
        Regex("""\bsessionStorage\b"""),
        Regex("""\bpushState\b"""),
        Regex("""\breplaceState\b"""),
        Regex("""notify\w*\([^)]*\.message"""),
    )

internal fun electionSecrecyFindings(
    fileName: String,
    text: String,
): List<String> {
    val findings = mutableListOf<String>()
    codeLines(text).forEach { line ->
        FORBIDDEN_EVERYWHERE.forEach { rule -> if (rule.containsMatchIn(line)) findings += "$fileName: ${line.trim()}" }
        if (fileName != "ElectionResultUi.kt" && Regex("""\bcastAt\b""").containsMatchIn(line)) findings += "$fileName: ${line.trim()}"
        if (fileName in BALLOT_BLIND_FILES && BALLOT_CONTENT.containsMatchIn(line)) findings += "$fileName: ${line.trim()}"
    }
    return findings
}

class ElectionSecrecyTripwireTest :
    FunSpec({
        test("the elections client files exist (tripwire is not vacuous)") {
            ELECTION_FILES.filterNot { File(CLIENT_DIR, it).isFile }.shouldBeEmpty()
        }

        test("no elections client file logs, stores, routes or toasts anything it must not") {
            ELECTION_FILES.flatMap { electionSecrecyFindings(fileName = it, text = File(CLIENT_DIR, it).readText()) }.shouldBeEmpty()
        }

        test("the open-election branch is the only place that reads a ballot's cast time") {
            val text = File(CLIENT_DIR, "ElectionResultUi.kt").readText()
            val lines = codeLines(text).filter { Regex("""\bcastAt\b""").containsMatchIn(it) }
            lines.size shouldBe 1
            // the one read sits in the `else` of `if (e.secret)`: the secret branch builds a BallotRow without a time
            val secretBranch = text.substringAfter("if (e.secret) {").substringBefore("} else {")
            secretBranch.contains("castAt") shouldBe false
            secretBranch.contains("BallotRow(name = null") shouldBe true
        }

        test("the detector flags each forbidden shape and ignores comments and look-alikes") {
            electionSecrecyFindings(fileName = "X.kt", text = "console.log(code)").size shouldBe 1
            electionSecrecyFindings(fileName = "X.kt", text = "println(code)").size shouldBe 1
            electionSecrecyFindings(fileName = "X.kt", text = "window.localStorage.setItem(a, code)").size shouldBe 1
            electionSecrecyFindings(fileName = "X.kt", text = "window.sessionStorage.removeItem(a)").size shouldBe 1
            electionSecrecyFindings(fileName = "X.kt", text = "window.history.pushState(null, \"\", url)").size shouldBe 1
            electionSecrecyFindings(fileName = "X.kt", text = "notifyError(e.message)").size shouldBe 1
            electionSecrecyFindings(fileName = "X.kt", text = "val t = result.castAt").size shouldBe 1
            electionSecrecyFindings(fileName = "ElectionResultUi.kt", text = "val t = ballot.castAt").size shouldBe 0
            electionSecrecyFindings(fileName = "ConferenceVoteOperatorControls.kt", text = "val c = result.receiptCode").size shouldBe 1
            electionSecrecyFindings(fileName = "ConferenceVotePanel.kt", text = "rpc.castElectionBallot(input)").size shouldBe 1
            electionSecrecyFindings(fileName = "ConferenceVoteStreamMirror.kt", text = "listElectionBallots(id)").size shouldBe 1
            electionSecrecyFindings(fileName = "ElectionBooth.kt", text = "val c = result.receiptCode").size shouldBe 0
            electionSecrecyFindings(fileName = "ConferenceVotePanel.kt", text = " * the receiptCode never reaches this file").size shouldBe
                0
            electionSecrecyFindings(fileName = "X.kt", text = "// console.log(code)").size shouldBe 0
            electionSecrecyFindings(fileName = "X.kt", text = " * localStorage is never used").size shouldBe 0
            electionSecrecyFindings(fileName = "X.kt", text = "notifyError(tr(\"Nicht gefunden.\"))").size shouldBe 0
            electionSecrecyFindings(fileName = "X.kt", text = "val consoleLike = 1").size shouldBe 0
        }
    })
