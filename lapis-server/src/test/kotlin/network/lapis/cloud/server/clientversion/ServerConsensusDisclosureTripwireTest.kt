package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.42 -- the server side of the minimum-participation rule for anonymous consensus, as a tripwire on the sources:
 *
 *  - a `SystemicConsensusResultDto` / `SystemicConsensusOptionResultDto` is built in exactly one file (`SystemicConsensusOutcome.kt`), the one
 *    that applies the withholding -- no second path can hand out figures;
 *  - the rating table (`SystemicConsensusResistanceTable`) is read only by a fixed list of files;
 *  - V1.9.44: single ballots of an anonymous consensus are never delivered. A `SystemicConsensusBallotDto` is built in exactly one
 *    file (`SystemicConsensusOutcome.kt`), whose `disclosedSystemicConsensusBallots` decides BEFORE it touches the ballot or rating
 *    table; `listResistanceBallots` only delegates to it; the ballot table is read only by a fixed list of files.
 */
private val SERVER_DIR =
    File("src/main/kotlin/network/lapis/cloud/server")
        .let { if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server") }

private const val OUTCOME_FILE = "rpc/SystemicConsensusOutcome.kt"

/** Files that may touch the ratings table: the outcome (aggregate), the reads (own receipt), the service (cast + ballot list). */
private val RESISTANCE_TABLE_ALLOWLIST =
    setOf(
        OUTCOME_FILE,
        "rpc/SystemicConsensusReads.kt",
        "rpc/SystemicConsensusService.kt",
    )

/** V1.9.44: files that may touch the ballot table (cast + receipt collision, outcome, reads, own participation, DSGVO export). */
private val BALLOT_TABLE_ALLOWLIST =
    setOf(
        OUTCOME_FILE,
        "rpc/SystemicConsensusService.kt",
        "rpc/SystemicConsensusReads.kt",
        "rpc/SystemicConsensusOwnParticipation.kt",
        "dsgvo/SystemicConsensusPersonalData.kt",
        "dsgvo/PersonalDataRegistry.kt",
    )

private fun serverSources(): List<Pair<String, String>> =
    SERVER_DIR
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" && !it.path.contains("${File.separator}db${File.separator}generated${File.separator}") }
        .map { it.relativeTo(SERVER_DIR).path.replace(File.separatorChar, '/') to it.readText() }
        .toList()

private fun codeOnly(text: String): List<String> =
    text.lines().filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

internal fun resultDtoConstructionFindings(sources: List<Pair<String, String>>): List<String> =
    sources
        .filter { (name, _) -> name != OUTCOME_FILE }
        .flatMap { (name, text) ->
            codeOnly(text)
                .filter { Regex("""\bSystemicConsensus(Option)?ResultDto\s*\(""").containsMatchIn(it) }
                .map { "$name builds a result DTO outside the outcome file: ${it.trim()}" }
        }

internal fun resistanceTableReadFindings(sources: List<Pair<String, String>>): List<String> =
    sources
        .filter { (name, _) -> name !in RESISTANCE_TABLE_ALLOWLIST }
        .flatMap { (name, text) ->
            codeOnly(text)
                .filter { Regex("""\bSystemicConsensusResistanceTable\b""").containsMatchIn(it) && !it.trimStart().startsWith("import ") }
                .map { "$name reads the ratings table: ${it.trim()}" }
        }

internal fun ballotDtoConstructionFindings(sources: List<Pair<String, String>>): List<String> =
    sources
        .filter { (name, _) -> name != OUTCOME_FILE }
        .flatMap { (name, text) ->
            codeOnly(text)
                .filter { Regex("""\bSystemicConsensusBallotDto\s*\(""").containsMatchIn(it) }
                .map { "$name builds a ballot DTO outside the outcome file: ${it.trim()}" }
        }

internal fun ballotTableReadFindings(sources: List<Pair<String, String>>): List<String> =
    sources
        .filter { (name, _) -> name !in BALLOT_TABLE_ALLOWLIST }
        .flatMap { (name, text) ->
            codeOnly(text)
                .filter { Regex("""\bSystemicConsensusBallotTable\b""").containsMatchIn(it) && !it.trimStart().startsWith("import ") }
                .map { "$name reads the ballot table: ${it.trim()}" }
        }

class ServerConsensusDisclosureTripwireTest :
    FunSpec({
        test("the server sources are found (tripwire is not vacuous)") {
            val sources = serverSources()
            sources.any { it.first == OUTCOME_FILE } shouldBe true
            RESISTANCE_TABLE_ALLOWLIST.all { allowed -> sources.any { it.first == allowed } } shouldBe true
            BALLOT_TABLE_ALLOWLIST.all { allowed -> sources.any { it.first == allowed } } shouldBe true
        }

        test("a consensus result DTO is only ever built by the outcome file, which applies the withholding") {
            resultDtoConstructionFindings(serverSources()).shouldBeEmpty()
            val outcome = serverSources().first { it.first == OUTCOME_FILE }.second
            outcome.contains("systemicConsensusFiguresWithheld") shouldBe true
            // the detector itself
            resultDtoConstructionFindings(listOf("rpc/Other.kt" to "val d = SystemicConsensusResultDto(a, b)")).size shouldBe 1
            resultDtoConstructionFindings(listOf("rpc/Other.kt" to "SystemicConsensusOptionResultDto(optionId = x)")).size shouldBe 1
            resultDtoConstructionFindings(listOf("rpc/Other.kt" to "// SystemicConsensusResultDto(a, b)")).size shouldBe 0
            resultDtoConstructionFindings(listOf(OUTCOME_FILE to "SystemicConsensusResultDto(a, b)")).size shouldBe 0
        }

        test("the ratings table is only read by the outcome, the reads and the service") {
            resistanceTableReadFindings(serverSources()).shouldBeEmpty()
            resistanceTableReadFindings(listOf("rpc/Other.kt" to "SystemicConsensusResistanceTable.selectAll()")).size shouldBe 1
            resistanceTableReadFindings(listOf("rpc/Other.kt" to "import x.SystemicConsensusResistanceTable")).size shouldBe 0
            resistanceTableReadFindings(listOf("rpc/Other.kt" to " * [SystemicConsensusResistanceTable] has no member")).size shouldBe 0
            resistanceTableReadFindings(listOf(OUTCOME_FILE to "SystemicConsensusResistanceTable.selectAll()")).size shouldBe 0
        }

        test("a ballot DTO is only ever built by the outcome file") {
            ballotDtoConstructionFindings(serverSources()).shouldBeEmpty()
            ballotDtoConstructionFindings(listOf("rpc/Other.kt" to "val d = SystemicConsensusBallotDto(id = x)")).size shouldBe 1
            ballotDtoConstructionFindings(listOf("rpc/Other.kt" to "fun f(): List<SystemicConsensusBallotDto> = emptyList()")).size shouldBe
                0
            ballotDtoConstructionFindings(listOf("rpc/Other.kt" to "// SystemicConsensusBallotDto(a)")).size shouldBe 0
            ballotDtoConstructionFindings(listOf(OUTCOME_FILE to "SystemicConsensusBallotDto(id = x)")).size shouldBe 0
        }

        test("the ballot table is only read by the allowlisted files") {
            ballotTableReadFindings(serverSources()).shouldBeEmpty()
            ballotTableReadFindings(listOf("rpc/Other.kt" to "SystemicConsensusBallotTable.selectAll()")).size shouldBe 1
            ballotTableReadFindings(listOf("rpc/Other.kt" to "import x.SystemicConsensusBallotTable")).size shouldBe 0
            ballotTableReadFindings(listOf("rpc/Other.kt" to " * [SystemicConsensusBallotTable] note")).size shouldBe 0
            ballotTableReadFindings(listOf(OUTCOME_FILE to "SystemicConsensusBallotTable.selectAll()")).size shouldBe 0
        }

        test("disclosedSystemicConsensusBallots decides before it touches the ballot or rating table") {
            val outcome = serverSources().first { it.first == OUTCOME_FILE }.second
            val start = outcome.indexOf("internal fun disclosedSystemicConsensusBallots")
            (start >= 0) shouldBe true
            val body = outcome.substring(start)
            val gate = body.indexOf("systemicConsensusSingleBallotsDisclosable")
            val ballotRead = body.indexOf("SystemicConsensusBallotTable")
            val resistanceRead = body.indexOf("SystemicConsensusResistanceTable")
            (gate >= 0) shouldBe true
            (gate < ballotRead) shouldBe true
            (gate < resistanceRead) shouldBe true
        }

        test("listResistanceBallots delegates to disclosedSystemicConsensusBallots and touches neither ballot nor rating table itself") {
            val service = serverSources().first { it.first == "rpc/SystemicConsensusService.kt" }.second
            val start = service.indexOf("override suspend fun listResistanceBallots")
            val end = service.indexOf("override suspend fun getSystemicConsensusResult")
            (start in 0 until end) shouldBe true
            val body = service.substring(start, end)
            body.contains("disclosedSystemicConsensusBallots") shouldBe true
            body.contains("SystemicConsensusBallotTable") shouldBe false
            body.contains("SystemicConsensusResistanceTable") shouldBe false
        }
    })
