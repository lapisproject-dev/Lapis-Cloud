package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.rpc.singleBallotsDisclosable
import java.io.File

/**
 * V1.9.46 -- single ballots of a SECRET election are never delivered, as a tripwire on the sources (same shape as
 * [ServerConsensusDisclosureTripwireTest]):
 *
 *  - an `ElectionBallotDto` is built in exactly one file (`rpc/ElectionBallotDisclosure.kt`);
 *  - `disclosedElectionBallots` decides (`electionSingleBallotsDisclosable`) BEFORE it touches the ballot or selection table, and that
 *    decision delegates to the one shared rule `singleBallotsDisclosable`;
 *  - `listElectionBallots` only delegates to it;
 *  - the ballot and selection tables are read only by a fixed list of files. A new reader must be looked at by a person, not added
 *    silently to the list.
 */
private val SERVER_DIR =
    File("src/main/kotlin/network/lapis/cloud/server")
        .let { if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server") }

private const val DISCLOSURE_FILE = "rpc/ElectionBallotDisclosure.kt"

/**
 * Cast + receipt check + tally (aggregate) in the service, own participation, the member's own named ballots in the DSGVO export.
 * V1.9.53: `rpc/ElectionResultDisclosure.kt` is the single decision point of the minimum participation (it counts the ballots and
 * the per-option selections); it was added on purpose, see `ServerElectionResultDisclosureTripwireTest`.
 */
private val BALLOT_TABLE_ALLOWLIST =
    setOf(
        DISCLOSURE_FILE,
        "rpc/ElectionResultDisclosure.kt",
        "rpc/ElectionService.kt",
        "rpc/ElectionOwnParticipation.kt",
        "dsgvo/ElectionPersonalData.kt",
    )

private fun serverSources(): List<Pair<String, String>> =
    SERVER_DIR
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" && !it.path.contains("${File.separator}db${File.separator}generated${File.separator}") }
        .map { it.relativeTo(SERVER_DIR).path.replace(File.separatorChar, '/') to it.readText() }
        .toList()

private fun codeOnly(text: String): List<String> =
    text.lines().filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

internal fun electionBallotDtoConstructionFindings(sources: List<Pair<String, String>>): List<String> =
    sources
        .filter { (name, _) -> name != DISCLOSURE_FILE }
        .flatMap { (name, text) ->
            codeOnly(text)
                .filter { Regex("""\bElectionBallotDto\s*\(""").containsMatchIn(it) }
                .map { "$name builds a ballot DTO outside the disclosure file: ${it.trim()}" }
        }

internal fun electionBallotTableReadFindings(sources: List<Pair<String, String>>): List<String> =
    sources
        .filter { (name, _) -> name !in BALLOT_TABLE_ALLOWLIST }
        .flatMap { (name, text) ->
            codeOnly(text)
                .filter { Regex("""\bElectionBallot(Selection)?Table\b""").containsMatchIn(it) && !it.trimStart().startsWith("import ") }
                .map { "$name reads the ballot or selection table: ${it.trim()}" }
        }

class ServerElectionDisclosureTripwireTest :
    FunSpec({
        test("the server sources are found (tripwire is not vacuous)") {
            val sources = serverSources()
            sources.any { it.first == DISCLOSURE_FILE } shouldBe true
            BALLOT_TABLE_ALLOWLIST.all { allowed -> sources.any { it.first == allowed } } shouldBe true
        }

        test("an election ballot DTO is only ever built by the disclosure file") {
            electionBallotDtoConstructionFindings(serverSources()).shouldBeEmpty()
            electionBallotDtoConstructionFindings(listOf("rpc/Other.kt" to "val d = ElectionBallotDto(id = x)")).size shouldBe 1
            electionBallotDtoConstructionFindings(listOf("rpc/Other.kt" to "fun f(): List<ElectionBallotDto> = emptyList()")).size shouldBe
                0
            electionBallotDtoConstructionFindings(listOf("rpc/Other.kt" to "// ElectionBallotDto(a)")).size shouldBe 0
            electionBallotDtoConstructionFindings(listOf(DISCLOSURE_FILE to "ElectionBallotDto(id = x)")).size shouldBe 0
        }

        test("the ballot and selection tables are only read by the allowlisted files") {
            electionBallotTableReadFindings(serverSources()).shouldBeEmpty()
            electionBallotTableReadFindings(listOf("rpc/Other.kt" to "ElectionBallotTable.selectAll()")).size shouldBe 1
            electionBallotTableReadFindings(listOf("rpc/Other.kt" to "(ElectionBallotSelectionTable innerJoin x)")).size shouldBe 1
            electionBallotTableReadFindings(listOf("rpc/Other.kt" to "import x.ElectionBallotTable")).size shouldBe 0
            electionBallotTableReadFindings(listOf("rpc/Other.kt" to " * [ElectionBallotTable] note")).size shouldBe 0
            electionBallotTableReadFindings(listOf(DISCLOSURE_FILE to "ElectionBallotSelectionTable.selectAll()")).size shouldBe 0
        }

        test("disclosedElectionBallots decides before it touches the ballot or selection table, and delegates to the shared rule") {
            val disclosure = serverSources().first { it.first == DISCLOSURE_FILE }.second
            val start = disclosure.indexOf("internal fun disclosedElectionBallots")
            (start >= 0) shouldBe true
            val body = disclosure.substring(start)
            val gate = body.indexOf("electionSingleBallotsDisclosable")
            val ballotRead = body.indexOf("ElectionBallotTable")
            val selectionRead = body.indexOf("ElectionBallotSelectionTable")
            (gate >= 0) shouldBe true
            (gate < ballotRead) shouldBe true
            (gate < selectionRead) shouldBe true
            val rule = disclosure.substring(disclosure.indexOf("internal fun electionSingleBallotsDisclosable"), start)
            rule.contains("singleBallotsDisclosable(secret)") shouldBe true
        }

        test("listElectionBallots delegates to disclosedElectionBallots and touches neither ballot nor selection table itself") {
            val service = serverSources().first { it.first == "rpc/ElectionService.kt" }.second
            val start = service.indexOf("override suspend fun listElectionBallots")
            val end = service.indexOf("override suspend fun verifyReceipt")
            (start in 0 until end) shouldBe true
            val body = service.substring(start, end)
            body.contains("disclosedElectionBallots") shouldBe true
            body.contains("ElectionBallotTable") shouldBe false
            body.contains("ElectionBallotSelectionTable") shouldBe false
        }

        test("the shared rule: a secret vote discloses nothing, an open one does") {
            singleBallotsDisclosable(secret = true) shouldBe false
            singleBallotsDisclosable(secret = false) shouldBe true
        }
    })
