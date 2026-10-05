package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.54 -- receipt-freeness as a tripwire on the server sources (the receipt of a secret election or an anonymous consensus proves
 * inclusion only, never the choice; behavioural evidence: `ReceiptFreenessTest`, `SystemicConsensusReadsTest`):
 *
 *  - the receipt code is read in four files only (the two services, the election receipt check, the consensus reads) and never on a
 *    line with a logger or an audit call; no MCP, route, embed, webhook, DSGVO or audit file names it at all;
 *  - `SystemicConsensusReads.verifyReceipt` does not touch the resistance table or the option table;
 *  - nothing in `src/main` constructs the retired `SystemicConsensusReceiptResistanceDto`.
 */
private val SERVER_DIR =
    File("src/main/kotlin/network/lapis/cloud/server")
        .let { if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server") }

private val RECEIPT_CODE_FILES =
    setOf(
        "rpc/ElectionService.kt",
        "rpc/ElectionReceiptCheck.kt",
        "rpc/SystemicConsensusService.kt",
        "rpc/SystemicConsensusReads.kt",
    )

private fun serverSources(): List<Pair<String, String>> =
    SERVER_DIR
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" && !it.path.contains("${File.separator}db${File.separator}generated${File.separator}") }
        .map { it.relativeTo(SERVER_DIR).path.replace(File.separatorChar, '/') to it.readText() }
        .toList()

private fun codeOnly(text: String): List<String> =
    text.lines().filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

private val RECEIPT_CODE_NAME = Regex("""\b(receiptCode|receipt_code|receiptCodes)\b""")

internal fun receiptCodeFindings(sources: List<Pair<String, String>>): List<String> =
    sources.flatMap { (name, text) ->
        codeOnly(text)
            .filter { RECEIPT_CODE_NAME.containsMatchIn(it) && !it.trimStart().startsWith("import ") }
            .flatMap { line ->
                buildList {
                    if (name !in RECEIPT_CODE_FILES) add("$name names the receipt code: ${line.trim()}")
                    if (Regex("""\blogger\b|\blog\.|\baudit""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                        add("$name puts the receipt code next to a logger or audit call: ${line.trim()}")
                    }
                }
            }
    }

internal fun consensusReceiptReadFindings(reads: String): List<String> {
    val start = reads.indexOf("fun verifyReceipt(")
    if (start < 0) return listOf("verifyReceipt not found")
    val next = Regex("""\n    (private )?fun """).find(reads, start + 10)
    val body = codeOnly(reads.substring(start, next?.range?.first ?: reads.length)).joinToString("\n")
    return listOf("SystemicConsensusResistanceTable", "SystemicConsensusOptionTable", "SystemicConsensusReceiptResistanceDto")
        .filter { body.contains(it) }
        .map { "verifyReceipt reads the ratings ($it)" }
}

internal fun retiredResistanceDtoFindings(sources: List<Pair<String, String>>): List<String> =
    sources.flatMap { (name, text) ->
        codeOnly(text)
            .filter { Regex("""\bSystemicConsensusReceiptResistanceDto\s*\(""").containsMatchIn(it) }
            .map { "$name constructs the retired receipt resistance DTO: ${it.trim()}" }
    }

class ServerReceiptFreenessTripwireTest :
    FunSpec({
        test("the server sources are found (tripwire is not vacuous)") {
            val sources = serverSources()
            RECEIPT_CODE_FILES.all { allowed -> sources.any { it.first == allowed } } shouldBe true
        }

        test("the receipt code is named only by the four allowed files and never next to a logger or an audit call") {
            receiptCodeFindings(serverSources()).shouldBeEmpty()
            receiptCodeFindings(listOf("mcp/Tools.kt" to "val c = ballot.receiptCode")).size shouldBe 1
            receiptCodeFindings(listOf("routes/Export.kt" to "put(\"receipt_code\", x)")).size shouldBe 1
            receiptCodeFindings(listOf("rpc/ElectionService.kt" to "logger.info { \"code \$receiptCode\" }")).size shouldBe 1
            receiptCodeFindings(listOf("rpc/ElectionService.kt" to "auditCreate(receiptCode)")).size shouldBe 1
            receiptCodeFindings(listOf("rpc/ElectionService.kt" to "it[ElectionBallotTable.receiptCode] = code")).size shouldBe 0
            receiptCodeFindings(listOf("rpc/Other.kt" to "// receiptCode")).size shouldBe 0
            receiptCodeFindings(listOf("rpc/Other.kt" to "import x.receiptCode")).size shouldBe 0
        }

        test("the consensus receipt check never reads a rating or an option") {
            val reads = serverSources().first { it.first == "rpc/SystemicConsensusReads.kt" }.second
            consensusReceiptReadFindings(reads).shouldBeEmpty()
            consensusReceiptReadFindings(
                "    fun verifyReceipt(a: Int) {\n  SystemicConsensusResistanceTable.selectAll()\n}\n    fun other() {}",
            ).size shouldBe 1
            consensusReceiptReadFindings(
                "    fun verifyReceipt(a: Int) {\n  x()\n}\n    fun other() { SystemicConsensusOptionTable.selectAll() }",
            ).size shouldBe 0
        }

        test("nothing constructs the retired receipt resistance DTO") {
            retiredResistanceDtoFindings(serverSources()).shouldBeEmpty()
            retiredResistanceDtoFindings(listOf("rpc/X.kt" to "SystemicConsensusReceiptResistanceDto(a, b, c, d)")).size shouldBe 1
            retiredResistanceDtoFindings(listOf("rpc/X.kt" to "import x.SystemicConsensusReceiptResistanceDto")).size shouldBe 0
        }
    })
