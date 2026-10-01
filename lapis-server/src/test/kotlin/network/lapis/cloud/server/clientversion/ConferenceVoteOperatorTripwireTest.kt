package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.26 "Abstimmen im Konferenzraum", Welle 3 -- tripwires on the sources of the operator side and the stream mirror.
 *
 *  - PARITY: the client's `quiescedForSecretBallot` (a copy of a rule) says "free" for exactly the stream statuses the server's private
 *    `SecretBallotStreamLock.isQuiescedForBallot` says "free" for, both are exhaustive `when`s without `else`, and both name every status.
 *    A new status is a compile error on each side, and a changed decision on one side fails here.
 *  - WRITES: every election write of the conference files (`openVoting`, `closeVoting`, `approveTally`, `tally`, `abortElection`) sits inside
 *    `runOperatorAction(...)`, and that function runs through `runGuardedAction` (double-click guard); no file launches a write by itself.
 *  - NO SERVER TEXT: none of the conference voting files reads an exception's `.message`.
 *  - THE BANNER has no button: it is an undismissable status.
 */
private val CLIENT_DIR =
    File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }

private val SERVER_LOCK_FILE =
    File("src/main/kotlin/network/lapis/cloud/server/rpc/SecretBallotStreamLock.kt")
        .let { if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server/rpc/SecretBallotStreamLock.kt") }

private val STATUS_FILE =
    File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain/ConferenceStream.kt")
        .let { if (it.exists()) it else File("lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain/ConferenceStream.kt") }

private val CONFERENCE_VOTE_FILES = listOf("ConferenceVotePanel.kt", "ConferenceVoteOperatorControls.kt", "ConferenceVoteStreamMirror.kt")

private fun codeOnlyLines(text: String): String =
    text
        .lines()
        .filterNot { line ->
            line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        }.joinToString("\n")

/** The `{ ... }` of the first `when` after `fun <name>` (braces balanced). */
internal fun whenBlockOf(
    text: String,
    functionMarker: String,
): String {
    val fn = text.indexOf(functionMarker)
    check(fn >= 0) { "function not found: $functionMarker" }
    val open = text.indexOf('{', text.indexOf("when", fn))
    var depth = 0
    var i = open
    while (i < text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(open, i + 1)
            }
        }
        i++
    }
    error("unbalanced when block: $functionMarker")
}

private val BRANCH = Regex("""((?:\s*(?:ConferenceStreamStatus\.\w+|null)\s*,?)+)\s*->\s*(true|false)""")
private val STATUS_NAME = Regex("""ConferenceStreamStatus\.(\w+)""")

/** status name -> the branch result, parsed from a `when` block. */
internal fun branchTable(block: String): Map<String, Boolean> =
    BRANCH
        .findAll(block)
        .flatMap { m -> STATUS_NAME.findAll(m.groupValues[1]).map { it.groupValues[1] to (m.groupValues[2] == "true") } }
        .toMap()

/** The text of every `runOperatorAction(...)` call (arguments and trailing lambda), parens/braces balanced. */
internal fun runOperatorActionSpans(text: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var from = 0
    while (true) {
        val start = text.indexOf("runOperatorAction(", from)
        if (start < 0) break
        var i = start + "runOperatorAction".length
        var depth = 0
        while (i < text.length) {
            if (text[i] == '(') depth++
            if (text[i] == ')') {
                depth--
                if (depth == 0) break
            }
            i++
        }
        var end = i
        var j = i + 1
        while (j < text.length && text[j].isWhitespace()) j++
        if (j < text.length && text[j] == '{') {
            var braces = 0
            var k = j
            while (k < text.length) {
                if (text[k] == '{') braces++
                if (text[k] == '}') {
                    braces--
                    if (braces == 0) break
                }
                k++
            }
            end = k
        }
        spans += start..end
        from = end + 1
    }
    return spans
}

private val ELECTION_WRITE = Regex("""\brpc\.(openVoting|closeVoting|approveTally|tally|abortElection)\b""")

/** `rpc.<write>` uses that sit outside every `runOperatorAction(...)` span. */
internal fun unguardedElectionWrites(text: String): List<String> {
    val code = codeOnlyLines(text)
    val spans = runOperatorActionSpans(code)
    return ELECTION_WRITE
        .findAll(code)
        .filter { m -> spans.none { m.range.first in it } }
        .map { it.value }
        .toList()
}

class ConferenceVoteOperatorTripwireTest :
    FunSpec({
        test("the files exist (tripwire is not vacuous)") {
            (CONFERENCE_VOTE_FILES + "ConferenceScreen.kt").filterNot { File(CLIENT_DIR, it).isFile }.shouldBeEmpty()
            SERVER_LOCK_FILE.isFile shouldBe true
            STATUS_FILE.isFile shouldBe true
        }

        test("parity: the client mirror frees exactly the stream statuses the server frees, both exhaustive without else") {
            val server = whenBlockOf(text = SERVER_LOCK_FILE.readText(), functionMarker = "fun isQuiescedForBallot")
            val client =
                whenBlockOf(
                    text = File(CLIENT_DIR, "ConferenceVoteStreamMirror.kt").readText(),
                    functionMarker = "fun ConferenceStreamStatus?.quiescedForSecretBallot",
                )
            val serverTable = branchTable(server)
            val clientTable = branchTable(client)
            serverTable.filterValues { it }.keys shouldBe clientTable.filterValues { it }.keys
            serverTable.filterValues { !it }.keys shouldBe clientTable.filterValues { !it }.keys
            Regex("""\belse\s*->""").containsMatchIn(server) shouldBe false
            Regex("""\belse\s*->""").containsMatchIn(client) shouldBe false
            val all =
                STATUS_FILE
                    .readText()
                    .substringAfter("enum class ConferenceStreamStatus {")
                    .substringBefore("}")
                    .split(',', '\n')
                    .map { it.trim() }
                    .filter { it.matches(Regex("[A-Z_]+")) }
                    .toSet()
            (all.size >= 7) shouldBe true
            serverTable.keys shouldBe all
            clientTable.keys shouldBe all
            // the table the client's tests pin: PAUSED, ENDED and FAILED are free
            clientTable.filterValues { it }.keys shouldBe setOf("PAUSED", "ENDED", "FAILED")
        }

        test("the parity scanner flags a changed decision (it is not blind)") {
            val paused = "ConferenceStreamStatus.PAUSED"
            val ended = "ConferenceStreamStatus.ENDED"
            val live = "ConferenceStreamStatus.LIVE"
            val a = "when (s) { $paused, $ended -> true\n $live -> false }"
            val b = "when (s) { $paused -> true\n $ended, $live -> false }"
            (branchTable(a).filterValues { it }.keys == branchTable(b).filterValues { it }.keys) shouldBe false
            branchTable(
                "when (this) { null,\n ConferenceStreamStatus.PAUSED,\n -> true\n ConferenceStreamStatus.LIVE,\n -> false }",
            ) shouldBe
                mapOf("PAUSED" to true, "LIVE" to false)
        }

        test("R29 (V1.9.26): every election write of the conference files sits inside runOperatorAction") {
            CONFERENCE_VOTE_FILES.forEach { name ->
                unguardedElectionWrites(File(CLIENT_DIR, name).readText()) shouldBe emptyList()
            }
            // the writes exist (not vacuous) and the writing function runs through the double-click guard
            val operator = File(CLIENT_DIR, "ConferenceVoteOperatorControls.kt").readText()
            ELECTION_WRITE.findAll(codeOnlyLines(operator)).count() shouldBe 6
            val body = operator.substringAfter("private fun runOperatorAction(").substringBefore("    fun dispose()")
            body.contains("runGuardedAction(") shouldBe true
        }

        test("R29 (V1.9.26) scanner: flags a write outside the wrapper, accepts one inside") {
            unguardedElectionWrites("AppScope.launch { rpc.closeVoting(id) }") shouldBe listOf("rpc.closeVoting")
            unguardedElectionWrites("runOperatorAction(b, nudge = true) { rpc.closeVoting(id) }") shouldBe emptyList()
            unguardedElectionWrites("runOperatorAction(b, nudge = true) { rpc.tally(id) }\nrpc.abortElection(id)") shouldBe
                listOf("rpc.abortElection")
            unguardedElectionWrites("// rpc.closeVoting(id)") shouldBe emptyList()
            unguardedElectionWrites("val x = rpc.getElection(id)") shouldBe emptyList()
        }

        test("no conference voting file reads an exception message") {
            CONFERENCE_VOTE_FILES.forEach { name ->
                val lines = codeOnlyLines(File(CLIENT_DIR, name).readText()).lines().filter { Regex("""\.message\b""").containsMatchIn(it) }
                lines shouldBe emptyList()
            }
        }

        test("the secret-ballot banner has no button and is a polite status") {
            val screen = File(CLIENT_DIR, "ConferenceScreen.kt").readText()
            val banner = screen.substringAfter("val secretBallotPauseBanner =").substringBefore("fun updateSecretBallotBanner()")
            Regex("""\b[Bb]utton\(""").containsMatchIn(banner) shouldBe false
            banner.contains("\"role\", \"status\"") shouldBe true
            banner.contains("\"aria-live\", \"polite\"") shouldBe true
            banner.contains("\"role\", \"alert\"") shouldBe false
        }
    })
