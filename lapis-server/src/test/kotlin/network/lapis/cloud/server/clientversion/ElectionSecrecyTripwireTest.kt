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
 *  - `castAt` read anywhere but `ElectionResultUi.kt`'s open-election branch -- a secret election's ballots are shown without time;
 *  - V1.9.46: `listElectionBallots(` anywhere but `ElectionsScreen.kt`, and there only behind `electionBallotsListable(` (never for a
 *    secret election); the old "ballots without names" sentence of the anonymised table is gone for good.
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
        // V1.9.27: the meritocratic vote in the room -- card, bid view, operator and the bid form. None of them logs, stores or toasts a message
        "ConferenceMeritVoteCard.kt",
        "ConferenceMeritVoteOperator.kt",
        "ConferenceMeritBidView.kt",
        "VoteBallotForm.kt",
        // V1.9.32: the consensus booth host of the room -- it carries one Boolean ("a receipt is on screen"), never a receipt
        "ConferenceConsensusBoothHost.kt",
    )

/**
 * V1.9.26: the files of the conference panel that must never touch a ballot's content at all. The booth (`ElectionBooth.kt`) is the only
 * place a selection or a receipt code exists; the panel, the operator controls and the mirror see counters, statuses and titles.
 */
private val BALLOT_BLIND_FILES =
    setOf(
        "ConferenceVotePanel.kt",
        "ConferenceVoteOperatorControls.kt",
        "ConferenceVoteStreamMirror.kt",
        "ConferenceMeritVoteCard.kt",
        "ConferenceMeritVoteOperator.kt",
        "ConferenceMeritBidView.kt",
        "ConferenceConsensusBoothHost.kt",
    )

/**
 * V1.9.27: the files of the conference room that must never read the CONTENT of a meritocratic bid. The room DTO carries no amount; these
 * files may not fetch one either (`listVoteBallots`, `getVote`), may not name a basket total, a stake, a settlement or the second price,
 * and may not show a member's name. The only file allowed to build a stake is `VoteBallotForm.kt`, and only inside `VoteBallotInput(`.
 */
private val MERIT_BLIND_FILES =
    setOf(
        "ConferenceMeritVoteCard.kt",
        "ConferenceMeritVoteOperator.kt",
        "ConferenceMeritBidView.kt",
        "ConferenceVotePanel.kt",
        "ConferenceVoteOperatorControls.kt",
    )

private val MERIT_CONTENT =
    Regex("""\b(listVoteBallots|basketTotal\w*|stakeLtr|settledLtr|secondPrice\w*|memberDisplayName)\b|\bgetVote\s*\(""")

private val BALLOT_CONTENT =
    Regex("""\b(receiptCode|selectedOptionIds|selectedOptionLabels|ElectionBallotInput|listElectionBallots|castElectionBallot)\b""")

private const val BALLOTS_LISTABLE_GUARD = "electionBallotsListable("
private const val REMOVED_SECRET_TABLE_SENTENCE = "Bei einer geheimen Wahl werden die Stimmzettel"

/** V1.9.46: a `listElectionBallots(` call outside `ElectionsScreen.kt`, or there without the guard in the same line or the 3 code lines before. */
internal fun ballotListCallFindings(
    fileName: String,
    text: String,
): List<String> {
    val code = codeLines(text)
    return code.withIndex().mapNotNull { (index, line) ->
        if (!line.contains("listElectionBallots(")) return@mapNotNull null
        val guarded =
            fileName == "ElectionsScreen.kt" && code.subList(maxOf(0, index - 3), index + 1).any { it.contains(BALLOTS_LISTABLE_GUARD) }
        if (guarded) null else "$fileName: ${line.trim()}"
    }
}

/** V1.9.46: the removed sentence of the anonymised ballot table in a code line (comments are ignored). */
internal fun removedSecretTableFindings(
    fileName: String,
    text: String,
): List<String> = codeLines(text).filter { it.contains(REMOVED_SECRET_TABLE_SENTENCE) }.map { "$fileName: ${it.trim()}" }

private fun codeLines(text: String): List<String> =
    text.lines().filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

/**
 * V1.9.53 (minimum participation): the code of the declaration that starts at [signature], up to the next top-level declaration
 * (`fun`/`private fun`/`internal fun`/`class` at column 0). Empty when the signature is absent.
 */
internal fun declarationBody(
    text: String,
    signature: String,
): String {
    val start = text.indexOf(signature)
    if (start < 0) return ""
    val next = Regex("""\n(private |internal )?(fun|class) """).find(text, start + signature.length)
    return codeLines(text.substring(start, next?.range?.first ?: text.length)).joinToString("\n")
}

private val WITHHELD_RENDERER_FORBIDDEN =
    Regex("""perOptionVotes|voteCount|lapis-election-bar|sortedByDescending|maxVotes|aria-valuenow|\bwidth\b|\bperc\b""")

/** V1.9.53: findings in the code of the withheld-result renderer: any figure, bar or ranking, or an `Int` parameter other than the rule constant. */
internal fun withheldRendererFindings(body: String): List<String> {
    val findings = mutableListOf<String>()
    WITHHELD_RENDERER_FORBIDDEN.findAll(body).forEach { findings += "withheld renderer mentions ${it.value}" }
    Regex("""(\w+)\s*:\s*Int\b""").findAll(body).forEach {
        if (it.groupValues[1] !=
            "minimumResponses"
        ) {
            findings += "Int parameter ${it.groupValues[1]}"
        }
    }
    return findings
}

/** V1.9.53: client code that decides on the shape of the figures instead of the server's flag. */
internal fun figureShapeBranchFindings(
    fileName: String,
    text: String,
): List<String> =
    codeLines(text)
        .filter { Regex("""voteCount\s*==\s*0|perOptionVotes\.isEmpty\(\)|perOptionVotes\.isNotEmpty\(\)""").containsMatchIn(it) }
        .map { "$fileName: branches on the shape of the figures, use figuresWithheld: ${it.trim()}" }

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
        if (fileName in MERIT_BLIND_FILES && MERIT_CONTENT.containsMatchIn(line)) findings += "$fileName: ${line.trim()}"
    }
    if (fileName == "VoteBallotForm.kt") findings += voteBallotFormFindings(text)
    return findings
}

/**
 * `VoteBallotForm.kt` may name `stakeLtr` only as an argument of `VoteBallotInput(` (a line with the constructor call within the six lines
 * above), and must never read the returned ballot (`result.` -- it carries the stake): `result != null` is the only use.
 */
internal fun voteBallotFormFindings(text: String): List<String> {
    val findings = mutableListOf<String>()
    val code = codeLines(text)
    code.forEachIndexed { index, line ->
        if (Regex("""\bstakeLtr\b""").containsMatchIn(line)) {
            val window = code.subList(maxOf(0, index - 6), index + 1)
            if (window.none { it.contains("VoteBallotInput(") }) {
                findings += "VoteBallotForm.kt: stakeLtr outside VoteBallotInput(: ${line.trim()}"
            }
        }
        if (Regex("""\bresult\.""").containsMatchIn(line)) findings += "VoteBallotForm.kt: reads the returned ballot: ${line.trim()}"
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
            // V1.9.46: the secret branch of the ballot section ends in a `return` before any row is built; no time, no table
            val secretBranch = text.substringAfter("if (e.secret) {").substringBefore("if (e.status != ElectionStatus.TALLIED) {")
            secretBranch.contains("castAt") shouldBe false
            secretBranch.contains("dataTable") shouldBe false
            secretBranch.contains("return") shouldBe true
        }

        test("V1.9.46: listElectionBallots is called only from ElectionsScreen.kt and only behind electionBallotsListable") {
            val files = CLIENT_DIR.listFiles { f -> f.isFile && f.name.endsWith(".kt") }!!.toList()
            files.isNotEmpty() shouldBe true
            files.flatMap { ballotListCallFindings(fileName = it.name, text = it.readText()) }.shouldBeEmpty()
            File(CLIENT_DIR, "ElectionsScreen.kt").readText().contains("listElectionBallots(") shouldBe true
        }

        test("V1.9.46: electionBallotsListable excludes secret elections") {
            val text = File(CLIENT_DIR, "ElectionResultUi.kt").readText()
            val definition = text.substringAfter("internal fun electionBallotsListable(").substringBefore("\n\n")
            definition.contains("!e.secret") shouldBe true
        }

        test("V1.9.46: the anonymised-ballot-table sentence no longer exists anywhere in the client") {
            val files = CLIENT_DIR.listFiles { f -> f.isFile && f.name.endsWith(".kt") }!!.toList()
            files.flatMap { removedSecretTableFindings(fileName = it.name, text = it.readText()) }.shouldBeEmpty()
        }

        test("V1.9.46: the ballot-list detectors flag the bad shapes and accept the guarded call") {
            fun listCalls(
                file: String,
                code: String,
            ) = ballotListCallFindings(fileName = file, text = code).size

            fun removed(code: String) = removedSecretTableFindings(fileName = "X.kt", text = code).size
            listCalls("ElectionsScreen.kt", "val b = elections.listElectionBallots(id)") shouldBe 1
            listCalls("ElectionBoardUi.kt", "if (electionBallotsListable(e)) rpc.listElectionBallots(id)") shouldBe 1
            listCalls("ElectionsScreen.kt", "if (electionBallotsListable(e)) rpc.listElectionBallots(id) else x") shouldBe 0
            listCalls("ElectionsScreen.kt", "if (\n  electionBallotsListable(e)\n) {\n  rpc.listElectionBallots(id)\n}") shouldBe 0
            listCalls("ElectionsScreen.kt", "// listElectionBallots(id)") shouldBe 0
            removed("tr(\"Bei einer geheimen Wahl werden die Stimmzettel ohne Namen\")") shouldBe 1
            removed(" * Bei einer geheimen Wahl werden die Stimmzettel") shouldBe 0
            removed("tr(\"Aus Gründen des Wahlgeheimnisses\")") shouldBe 0
        }

        test("V1.9.32: the consensus receipt hook scope gives the previous hook back by identity and carries only a Boolean") {
            val code = codeLines(File(CLIENT_DIR, "ConferenceConsensusBoothHost.kt").readText()).joinToString("\n")
            code.contains("consensusReceiptVisibilityHook === onChange") shouldBe true
            code.contains("private val onChange: (Boolean) -> Unit") shouldBe true
            electionSecrecyFindings(fileName = "ConferenceConsensusBoothHost.kt", text = "val c = result.receiptCode").size shouldBe 1
        }

        test("V1.9.53: the result renderer decides on figuresWithheld first, the withheld renderer shows no figure, bar or ranking") {
            val text = File(CLIENT_DIR, "ElectionResultUi.kt").readText()
            val compact = declarationBody(text = text, signature = "internal fun renderElectionResultCompact(")
            (compact.isNotEmpty()) shouldBe true
            val firstBranch = compact.indexOf("if (result.figuresWithheld)")
            (firstBranch >= 0) shouldBe true
            (firstBranch < compact.indexOf("perOptionVotes")) shouldBe true
            (firstBranch < compact.indexOf("renderResultRow(")) shouldBe true
            // ordered by position, never by the figures: the order must not tell a ranking
            compact.contains("sortedByDescending") shouldBe false
            compact.contains("sortedBy { it.position }") shouldBe true
            val withheld = declarationBody(text = text, signature = "private fun renderElectionResultWithheld(")
            (withheld.isNotEmpty()) shouldBe true
            withheldRendererFindings(withheld).shouldBeEmpty()
        }

        test("V1.9.53: the withheld-renderer detector flags figures, bars, rankings and Int parameters") {
            withheldRendererFindings("val v = result.perOptionVotes").size shouldBe 1
            withheldRendererFindings("option.voteCount").size shouldBe 1
            withheldRendererFindings("div(className = \"lapis-election-bar\")").size shouldBe 1
            withheldRendererFindings("list.sortedByDescending { it }").size shouldBe 1
            withheldRendererFindings("bar.width = x.perc").size shouldBe 2
            withheldRendererFindings("votes: Int,").size shouldBe 1
            withheldRendererFindings("minimumResponses: Int,").size shouldBe 0
            withheldRendererFindings("showHint: Boolean,").size shouldBe 0
        }

        test("V1.9.53: the decision line of a resolution and the audit snapshot read the figures only after the figuresWithheld branch") {
            val meetings = codeLines(File(CLIENT_DIR, "MeetingsScreen.kt").readText()).joinToString("\n")
            val audit = codeLines(File(CLIENT_DIR, "AuditLogScreen.kt").readText()).joinToString("\n")
            (meetings.indexOf("resolution.figuresWithheld") in 0 until meetings.indexOf("resolution.votesYes")) shouldBe true
            (audit.indexOf("if (figuresWithheld)") in 0 until audit.indexOf("snapshot.votesYes")) shouldBe true
        }

        test("V1.9.53: no client file branches on the shape of the figures") {
            val files = CLIENT_DIR.listFiles { f -> f.isFile && f.name.endsWith(".kt") }!!.toList()
            files.flatMap { figureShapeBranchFindings(fileName = it.name, text = it.readText()) }.shouldBeEmpty()
            figureShapeBranchFindings(fileName = "X.kt", text = "if (option.voteCount == 0) hide()").size shouldBe 1
            figureShapeBranchFindings(fileName = "X.kt", text = "if (result.perOptionVotes.isEmpty()) hide()").size shouldBe 1
            figureShapeBranchFindings(fileName = "X.kt", text = "if (result.figuresWithheld) hide()").size shouldBe 0
            figureShapeBranchFindings(fileName = "X.kt", text = "// voteCount == 0").size shouldBe 0
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

        test("the merit files may not read a bid's content (V1.9.27)") {
            electionSecrecyFindings(fileName = "ConferenceMeritVoteCard.kt", text = "rpc.listVoteBallots(id)").size shouldBe 1
            electionSecrecyFindings(fileName = "ConferenceMeritBidView.kt", text = "val v = rpc.getVote(id)").size shouldBe 1
            electionSecrecyFindings(fileName = "ConferenceMeritVoteOperator.kt", text = "val t = vote.basketTotalLtr").size shouldBe 1
            electionSecrecyFindings(fileName = "ConferenceVotePanel.kt", text = "val s = ballot.stakeLtr").size shouldBe 1
            electionSecrecyFindings(fileName = "ConferenceVoteOperatorControls.kt", text = "x.settledLtr").size shouldBe 1
            electionSecrecyFindings(fileName = "ConferenceMeritBidView.kt", text = "x.secondPriceLtr").size shouldBe 1
            electionSecrecyFindings(fileName = "ConferenceMeritVoteCard.kt", text = "b.memberDisplayName").size shouldBe 1
            electionSecrecyFindings(fileName = "ConferenceMeritVoteCard.kt", text = "val v = voteDto // not a bid").size shouldBe 0
            electionSecrecyFindings(fileName = "ConferenceMeritVoteCard.kt", text = " * a stakeLtr is never shown here").size shouldBe 0
            electionSecrecyFindings(fileName = "ElectionBooth.kt", text = "x.memberDisplayName").size shouldBe 0
        }

        test("VoteBallotForm.kt builds a stake only inside VoteBallotInput and never reads the returned ballot") {
            voteBallotFormFindings(File(CLIENT_DIR, "VoteBallotForm.kt").readText()).shouldBeEmpty()
            voteBallotFormFindings("val x = a.stakeLtr").size shouldBe 1
            voteBallotFormFindings("VoteBallotInput(\n    stakeLtr = 1,\n)").size shouldBe 0
            voteBallotFormFindings("if (result != null) { notify(result.stake) }").size shouldBe 1
            voteBallotFormFindings("if (result != null) { onChanged() }").size shouldBe 0
            voteBallotFormFindings("// stakeLtr in a comment").size shouldBe 0
        }
    })
