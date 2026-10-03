package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.28 "Konsensieren" -- rating secrecy and the "no server message" rule of the consensus screens, as a tripwire on the sources (same
 * heuristic as [ElectionSecrecyTripwireTest]; the behavioural evidence is in the Karma tests `ConsensusBoothDomTest` and friends).
 *
 *  - no `console.`/`println`/`localStorage`/`sessionStorage`/`pushState`/`replaceState`, no `notify...(... .message)`;
 *  - `castAt` is never read (a consensus' ratings are shown without a time);
 *  - the content of a rating (`receiptCode`, `SystemicConsensusBallotInput`, `castResistanceBallot`, `resistances`, `listResistanceBallots`) stays
 *    out of every file but the booth, the receipt and the one result view;
 *  - `receiptCode` appears only in the booth (hand-over) and the receipt file;
 *  - `listResistanceBallots` appears only in `ConsensusResultView.kt`, and only in the branch of an OPEN consensus;
 *  - the booth has no `data-*` attribute;
 *  - V1.9.42 (minimum participation): the two result renderers branch on `figuresWithheld` and never on the shape of `optionResults`,
 *    and `ConsensusAuthzUi.kt` never reads a `consensusIndex` (an anonymous result below the minimum has no figures).
 */
private val CLIENT_DIR =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private val CONSENSUS_FILES =
    listOf(
        "ConsensusScreen.kt",
        "ConsensusDetail.kt",
        "ConsensusOptions.kt",
        "ConsensusBooth.kt",
        // V1.9.41: the shared rating booth the consensus booth is now an adapter of
        "RatingBooth.kt",
        "ConsensusReceipt.kt",
        "ConsensusResultView.kt",
        "ConsensusOpenForm.kt",
        "ConsensusGuard.kt",
        "ConsensusLabels.kt",
        "ConsensusAuthzUi.kt",
        // V1.9.32: the consensus in the conference room -- card, booth host, operator and pure state. None of them logs, stores or toasts a rating
        "ConferenceConsensusCard.kt",
        "ConferenceConsensusBoothHost.kt",
        "ConferenceConsensusOperator.kt",
        "ConferenceConsensusRoomState.kt",
    )

/** V1.9.32: the files of the conference room that never touch the content of a rating (the booth is the only place a rating or a receipt exists). */
private val CONFERENCE_CONSENSUS_FILES =
    listOf(
        "ConferenceConsensusCard.kt",
        "ConferenceConsensusBoothHost.kt",
        "ConferenceConsensusOperator.kt",
        "ConferenceConsensusRoomState.kt",
    )

private val RATING_CONTENT =
    Regex("""\b(receiptCode|SystemicConsensusBallotInput|castResistanceBallot|resistances|listResistanceBallots)\b""")

/** Files that may not touch the content of a rating at all. */
private val RATING_BLIND_FILES =
    setOf(
        "ConsensusScreen.kt",
        "ConsensusDetail.kt",
        "ConsensusOptions.kt",
        "ConsensusLabels.kt",
        "ConsensusAuthzUi.kt",
        "ConsensusOpenForm.kt",
        // V1.9.41: the shared booth knows no service and no DTO, hence no rating content by name either
        "RatingBooth.kt",
        // V1.9.32
        "ConferenceConsensusCard.kt",
        "ConferenceConsensusBoothHost.kt",
        "ConferenceConsensusOperator.kt",
        "ConferenceConsensusRoomState.kt",
    )

private val FORBIDDEN_EVERYWHERE =
    listOf(
        Regex("""\bconsole\."""),
        Regex("""\bprintln\s*\("""),
        Regex("""\blocalStorage\b"""),
        Regex("""\bsessionStorage\b"""),
        Regex("""\bpushState\b"""),
        Regex("""\breplaceState\b"""),
        Regex("""notify\w*\([^)]*\.message"""),
        Regex("""\bcastAt\b"""),
    )

private fun codeLines(text: String): List<String> =
    text.lines().filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

internal fun consensusSecrecyFindings(
    fileName: String,
    text: String,
): List<String> {
    val findings = mutableListOf<String>()
    val code = codeLines(text)
    code.forEachIndexed { index, line ->
        FORBIDDEN_EVERYWHERE.forEach { rule -> if (rule.containsMatchIn(line)) findings += "$fileName: ${line.trim()}" }
        if (fileName in RATING_BLIND_FILES && RATING_CONTENT.containsMatchIn(line)) findings += "$fileName: ${line.trim()}"
        if (fileName == "ConsensusGuard.kt" && Regex("""\breceiptCode\b""").containsMatchIn(line)) findings += "$fileName: ${line.trim()}"
        if (fileName != "ConsensusBooth.kt" && fileName != "ConsensusReceipt.kt" && Regex("""\breceiptCode\b""").containsMatchIn(line)) {
            findings += "$fileName: receiptCode outside booth/receipt: ${line.trim()}"
        }
        if (fileName != "ConsensusResultView.kt" && Regex("""\blistResistanceBallots\b""").containsMatchIn(line)) {
            findings += "$fileName: listResistanceBallots outside the result view: ${line.trim()}"
        }
        if (fileName == "ConsensusResultView.kt" && Regex("""\blistResistanceBallots\b""").containsMatchIn(line)) {
            val window = code.subList(maxOf(0, index - 12), index + 1)
            if (window.none { it.contains("if (!") && it.contains("secret") }) {
                findings += "$fileName: listResistanceBallots outside the open branch: ${line.trim()}"
            }
        }
        if ((fileName == "ConsensusBooth.kt" || fileName == "RatingBooth.kt") &&
            Regex("""data-|setAttribute\("data""").containsMatchIn(line)
        ) {
            findings += "$fileName: data attribute: ${line.trim()}"
        }
    }
    return findings
}

/**
 * V1.9.39: a line that mentions a rationale (member input) must never hand it to an attribute that exposes it outside the widget text
 * (`title`, `aria-label`, `data-*`), to a toast/log/storage/history sink or read an exception message on the same line.
 */
private val RATIONALE_LINE = Regex("""(?i)rationale""")
private val RATIONALE_SINKS =
    listOf(
        Regex("""\btitle\b"""),
        Regex("""aria-label"""),
        Regex("""setAttribute\("data"""),
        Regex("""data-"""),
        Regex("""\bnotify\w*\("""),
        Regex("""\bconsole\b"""),
        Regex("""\blocalStorage\b"""),
        Regex("""\bsessionStorage\b"""),
        Regex("""\bpushState\b"""),
        Regex("""\.message\b"""),
    )

internal fun rationaleSinkFindings(
    fileName: String,
    text: String,
): List<String> =
    codeLines(text)
        .filter { line -> RATIONALE_LINE.containsMatchIn(line) && RATIONALE_SINKS.any { it.containsMatchIn(line) } }
        .map { "$fileName: rationale reaches a sink: ${it.trim()}" }

/** V1.9.42: the files that render a consensus result and so must decide on `figuresWithheld`. */
private val RESULT_RENDERER_FILES = listOf("ConsensusResultView.kt", "ConferenceConsensusOperator.kt")

private val OPTION_RESULTS_SHAPE_BRANCH = Regex("""optionResults\.(isEmpty|isNotEmpty|none|any)\b""")

/**
 * V1.9.42: the branch between "figures" and "withheld" is the server's `figuresWithheld` flag alone, never the emptiness of
 * `optionResults` (an open consensus without options is empty too), and the revote offer never reads a figure.
 */
internal fun consensusDisclosureFindings(
    fileName: String,
    text: String,
): List<String> {
    val code = codeLines(text)
    val findings = mutableListOf<String>()
    code.filter { OPTION_RESULTS_SHAPE_BRANCH.containsMatchIn(it) }.forEach {
        findings += "$fileName: branches on the shape of optionResults, use figuresWithheld: ${it.trim()}"
    }
    if (fileName in RESULT_RENDERER_FILES && code.none { it.contains("figuresWithheld") }) {
        findings += "$fileName: a result renderer that never looks at figuresWithheld"
    }
    if (fileName == "ConsensusAuthzUi.kt") {
        code.filter { Regex("""\bconsensusIndex\b""").containsMatchIn(it) }.forEach {
            findings += "$fileName: reads a consensusIndex: ${it.trim()}"
        }
    }
    return findings
}

/** V1.9.32: shapes that must not appear in the room's consensus files on top of the rules above. */
private val CONFERENCE_CONSENSUS_FORBIDDEN =
    listOf(
        Regex("""data-|setAttribute\("data"""),
        Regex("""\bAppState\b"""),
        Regex("""\.message\b"""),
        Regex("""notify\w*\([^)]*(ratings|receipt)"""),
    )

internal fun conferenceConsensusFindings(
    fileName: String,
    text: String,
): List<String> =
    codeLines(text)
        .filter { line -> CONFERENCE_CONSENSUS_FORBIDDEN.any { it.containsMatchIn(line) } }
        .map { "$fileName: ${it.trim()}" }

class ConsensusSecrecyTripwireTest :
    FunSpec({
        test("the consensus client files exist (tripwire is not vacuous)") {
            CONSENSUS_FILES.filterNot { File(CLIENT_DIR, it).isFile }.shouldBeEmpty()
        }

        test("no consensus client file logs, stores, routes or toasts anything it must not") {
            CONSENSUS_FILES.flatMap { consensusSecrecyFindings(fileName = it, text = File(CLIENT_DIR, it).readText()) }.shouldBeEmpty()
        }

        test(
            "V1.9.39: no consensus client file hands a rationale to title, aria-label, data-*, a toast, a log, storage or an exception message",
        ) {
            CONSENSUS_FILES.flatMap { rationaleSinkFindings(fileName = it, text = File(CLIENT_DIR, it).readText()) }.shouldBeEmpty()
            // the detector itself: positive and negative examples
            rationaleSinkFindings(fileName = "X.kt", text = "div.setAttribute(\"title\", option.rationale)").size shouldBe 1
            rationaleSinkFindings(fileName = "X.kt", text = "span.title = option.rationale").size shouldBe 1
            rationaleSinkFindings(fileName = "X.kt", text = "b.setAttribute(\"aria-label\", option.rationale)").size shouldBe 1
            rationaleSinkFindings(fileName = "X.kt", text = "b.setAttribute(\"data-why\", option.rationale)").size shouldBe 1
            rationaleSinkFindings(fileName = "X.kt", text = "notifyInfo(option.rationale)").size shouldBe 1
            rationaleSinkFindings(fileName = "X.kt", text = "console.log(option.rationale)").size shouldBe 1
            rationaleSinkFindings(fileName = "X.kt", text = "window.localStorage.setItem(a, option.rationale)").size shouldBe 1
            rationaleSinkFindings(fileName = "X.kt", text = "val m = e.message + option.rationale").size shouldBe 1
            rationaleSinkFindings(fileName = "X.kt", text = "parent.untrustedDiv(option.rationale, className = \"x\")").size shouldBe 0
            rationaleSinkFindings(fileName = "X.kt", text = "notifySuccess(tr(\"Begründung gespeichert.\"))").size shouldBe 0
            rationaleSinkFindings(fileName = "X.kt", text = "// option.rationale -> title").size shouldBe 0
        }

        test("V1.9.32: the room's consensus files carry no data attribute, no AppState, no exception message and no rating/receipt toast") {
            CONFERENCE_CONSENSUS_FILES
                .flatMap {
                    conferenceConsensusFindings(
                        fileName = it,
                        text = File(CLIENT_DIR, it).readText(),
                    )
                }.shouldBeEmpty()
            // the panel and the operator controls never touch a rating either
            listOf("ConferenceVotePanel.kt", "ConferenceVoteOperatorControls.kt").forEach { name ->
                codeLines(File(CLIENT_DIR, name).readText()).filter { RATING_CONTENT.containsMatchIn(it) } shouldBe emptyList()
            }
            conferenceConsensusFindings(fileName = "X.kt", text = "radio.setAttribute(\"data-v\", \"1\")").size shouldBe 1
            conferenceConsensusFindings(fileName = "X.kt", text = "val s = AppState.session").size shouldBe 1
            conferenceConsensusFindings(fileName = "X.kt", text = "notifyInfo(receipt)").size shouldBe 1
            conferenceConsensusFindings(fileName = "X.kt", text = "notifyInfo(tr(\"Fertig\"))").size shouldBe 0
            conferenceConsensusFindings(fileName = "X.kt", text = "// notifyInfo(receipt)").size shouldBe 0
        }

        test("the detector flags each forbidden shape and ignores comments and look-alikes") {
            consensusSecrecyFindings(fileName = "X.kt", text = "console.log(code)").size shouldBe 1
            consensusSecrecyFindings(fileName = "X.kt", text = "println(code)").size shouldBe 1
            consensusSecrecyFindings(fileName = "X.kt", text = "window.localStorage.setItem(a, code)").size shouldBe 1
            consensusSecrecyFindings(fileName = "X.kt", text = "window.sessionStorage.removeItem(a)").size shouldBe 1
            consensusSecrecyFindings(fileName = "X.kt", text = "window.history.pushState(null, \"\", url)").size shouldBe 1
            consensusSecrecyFindings(fileName = "X.kt", text = "notifyError(e.message)").size shouldBe 1
            consensusSecrecyFindings(fileName = "X.kt", text = "val t = ballot.castAt").size shouldBe 1
            consensusSecrecyFindings(fileName = "ConsensusDetail.kt", text = "val r = ballot.resistances").size shouldBe 1
            consensusSecrecyFindings(fileName = "ConsensusScreen.kt", text = "rpc.castResistanceBallot(x)").size shouldBe 1
            consensusSecrecyFindings(fileName = "ConsensusOptions.kt", text = "SystemicConsensusBallotInput(a, b)").size shouldBe 1
            consensusSecrecyFindings(fileName = "ConsensusDetail.kt", text = "val c = result.receiptCode").size shouldBe 2
            consensusSecrecyFindings(fileName = "ConsensusBooth.kt", text = "val c = result.receiptCode").size shouldBe 0
            consensusSecrecyFindings(fileName = "ConsensusReceipt.kt", text = "val c = result.receiptCode").size shouldBe 0
            consensusSecrecyFindings(fileName = "ConsensusScreen.kt", text = "rpc.listResistanceBallots(id)").size shouldBe 2
            consensusSecrecyFindings(fileName = "ConsensusResultView.kt", text = "rpc.listResistanceBallots(id)").size shouldBe 1
            consensusSecrecyFindings(
                fileName = "ConsensusResultView.kt",
                text = "if (!c.secret) {\n  rpc.listResistanceBallots(id)\n}",
            ).size shouldBe 0
            consensusSecrecyFindings(fileName = "ConsensusBooth.kt", text = "radio.setAttribute(\"data-v\", \"1\")").size shouldBe 1
            // V1.9.41: the shared rating booth holds no data attribute, no receipt code and no rating content by name
            consensusSecrecyFindings(fileName = "RatingBooth.kt", text = "radio.setAttribute(\"data-v\", \"1\")").size shouldBe 1
            consensusSecrecyFindings(fileName = "RatingBooth.kt", text = "val c = result.receiptCode").size shouldBe 2
            consensusSecrecyFindings(fileName = "RatingBooth.kt", text = "SystemicConsensusBallotInput(a, b)").size shouldBe 1
            consensusSecrecyFindings(fileName = "X.kt", text = "// console.log(code)").size shouldBe 0
            consensusSecrecyFindings(fileName = "X.kt", text = " * localStorage is never used").size shouldBe 0
            consensusSecrecyFindings(fileName = "X.kt", text = "val consoleLike = 1").size shouldBe 0
        }

        test("V1.9.42: the result renderers decide on figuresWithheld, the revote offer reads no figure") {
            CONSENSUS_FILES.flatMap { consensusDisclosureFindings(fileName = it, text = File(CLIENT_DIR, it).readText()) }.shouldBeEmpty()
            // the detector itself: positive and negative examples
            consensusDisclosureFindings(fileName = "ConsensusResultView.kt", text = "if (result.optionResults.isEmpty()) {").size shouldBe 2
            consensusDisclosureFindings(fileName = "X.kt", text = "if (r.optionResults.isNotEmpty()) x()").size shouldBe 1
            consensusDisclosureFindings(fileName = "X.kt", text = "val none = r.optionResults.none { it.a }").size shouldBe 1
            consensusDisclosureFindings(fileName = "X.kt", text = "val any = r.optionResults.any { it.a }").size shouldBe 1
            consensusDisclosureFindings(fileName = "X.kt", text = "r.optionResults.sortedBy { it.meanResistance }").size shouldBe 0
            consensusDisclosureFindings(fileName = "X.kt", text = "// optionResults.isEmpty() would be wrong").size shouldBe 0
            consensusDisclosureFindings(fileName = "ConferenceConsensusOperator.kt", text = "val x = 1").size shouldBe 1
            consensusDisclosureFindings(fileName = "ConferenceConsensusOperator.kt", text = "if (result.figuresWithheld) a()").size shouldBe
                0
            consensusDisclosureFindings(fileName = "ConsensusAuthzUi.kt", text = "winner.consensusIndex > warn").size shouldBe 1
            consensusDisclosureFindings(fileName = "ConsensusAuthzUi.kt", text = "result.groupConflictWarning").size shouldBe 0
            consensusDisclosureFindings(
                fileName = "ConsensusResultView.kt",
                text = "val i = option.consensusIndex // figures view",
            ).size shouldBe
                1
        }
    })
