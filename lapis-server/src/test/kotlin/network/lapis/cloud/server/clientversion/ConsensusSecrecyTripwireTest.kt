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
 *  - the booth has no `data-*` attribute.
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
        "ConsensusReceipt.kt",
        "ConsensusResultView.kt",
        "ConsensusOpenForm.kt",
        "ConsensusGuard.kt",
        "ConsensusLabels.kt",
        "ConsensusAuthzUi.kt",
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
        if (fileName == "ConsensusBooth.kt" && Regex("""data-|setAttribute\("data""").containsMatchIn(line)) {
            findings += "$fileName: data attribute: ${line.trim()}"
        }
    }
    return findings
}

class ConsensusSecrecyTripwireTest :
    FunSpec({
        test("the consensus client files exist (tripwire is not vacuous)") {
            CONSENSUS_FILES.filterNot { File(CLIENT_DIR, it).isFile }.shouldBeEmpty()
        }

        test("no consensus client file logs, stores, routes or toasts anything it must not") {
            CONSENSUS_FILES.flatMap { consensusSecrecyFindings(fileName = it, text = File(CLIENT_DIR, it).readText()) }.shouldBeEmpty()
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
            consensusSecrecyFindings(fileName = "X.kt", text = "// console.log(code)").size shouldBe 0
            consensusSecrecyFindings(fileName = "X.kt", text = " * localStorage is never used").size shouldBe 0
            consensusSecrecyFindings(fileName = "X.kt", text = "val consoleLike = 1").size shouldBe 0
        }
    })
