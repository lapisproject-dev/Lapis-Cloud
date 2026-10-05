package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.53 -- minimum participation for secret elections, as a tripwire on the sources (same shape as
 * [ServerElectionDisclosureTripwireTest]):
 *
 *  - the DTOs that carry election figures are built in exactly one place each: `ElectionResultDto` in
 *    `rpc/ElectionResultDisclosure.kt`, `ElectionOptionDto` and its `voteCount` only in `rpc/ElectionService.kt` from the result of
 *    `disclosedOptionVoteCounts`, `ResolutionDto` and the read of `ResolutionTable.votes*` only in `rpc/ResolutionBook.kt` (and
 *    there only through `toResolutionDtos`), `PublicApiResolutionDto` only in `routes/PublicApiRoutes.kt`;
 *  - `ElectionService` never builds an `ElectionResultDto` itself (`computeOutcome` returns the internal figures) and the
 *    per-option selection count lives only in `disclosedOptionVoteCounts`, after the withheld decision;
 *  - a snapshot of a resolution is built only where it is written (`ResolutionBook.kt`) or masked on delivery (`AuditLogService.kt`).
 */
private val SERVER_DIR =
    File("src/main/kotlin/network/lapis/cloud/server")
        .let { if (it.exists()) it else File("lapis-server/src/main/kotlin/network/lapis/cloud/server") }

private const val RESULT_DISCLOSURE_FILE = "rpc/ElectionResultDisclosure.kt"
private const val ELECTION_SERVICE_FILE = "rpc/ElectionService.kt"
private const val RESOLUTION_BOOK_FILE = "rpc/ResolutionBook.kt"
private const val AUDIT_LOG_FILE = "rpc/AuditLogService.kt"
private const val PUBLIC_API_ROUTES_FILE = "routes/PublicApiRoutes.kt"

private fun resultSources(): List<Pair<String, String>> =
    SERVER_DIR
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" && !it.path.contains("${File.separator}db${File.separator}generated${File.separator}") }
        .map { it.relativeTo(SERVER_DIR).path.replace(File.separatorChar, '/') to it.readText() }
        .toList()

private fun codeLines(text: String): List<String> =
    text.lines().filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

/** Every code line matching [pattern] outside the files in [allowed]. */
internal fun constructionOutsideFindings(
    sources: List<Pair<String, String>>,
    pattern: Regex,
    allowed: Set<String>,
): List<String> =
    sources
        .filter { (name, _) -> name !in allowed }
        .flatMap { (name, text) ->
            codeLines(text)
                .filter {
                    pattern.containsMatchIn(it) &&
                        !it.trimStart().startsWith("import ") &&
                        !Regex("""\bclass\s""").containsMatchIn(it)
                }.map { "$name: ${it.trim()}" }
        }

private val RESULT_DTO = Regex("""\bElectionResultDto\s*\(""")
private val OPTION_DTO = Regex("""\bElectionOptionDto\s*\(""")
private val RESOLUTION_DTO = Regex("""\bResolutionDto\s*\(""")
private val PUBLIC_RESOLUTION_DTO = Regex("""\bPublicApiResolutionDto\s*\(""")
private val RESOLUTION_SNAPSHOT = Regex("""\bResolutionSnapshot\s*\(""")
private val RESOLUTION_FIGURE_READ = Regex("""ResolutionTable\.votes(Yes|No|Abstain)\b""")

/** The text of the function that starts at [signature] up to the next top-level or member `fun` declaration at the same indent. */
internal fun functionBody(
    text: String,
    signature: String,
): String {
    val start = text.indexOf(signature)
    if (start < 0) return ""
    val next = Regex("""\n\s*(private |internal |override |public )*(suspend )?fun """).find(text, start + signature.length)
    return text.substring(start, next?.range?.first ?: text.length)
}

class ServerElectionResultDisclosureTripwireTest :
    FunSpec({
        test("the sources are found (tripwire is not vacuous)") {
            val names = resultSources().map { it.first }
            listOf(RESULT_DISCLOSURE_FILE, ELECTION_SERVICE_FILE, RESOLUTION_BOOK_FILE, AUDIT_LOG_FILE, PUBLIC_API_ROUTES_FILE)
                .all { it in names } shouldBe true
        }

        test("ElectionResultDto is only built by the disclosure file") {
            constructionOutsideFindings(
                sources = resultSources(),
                pattern = RESULT_DTO,
                allowed = setOf(RESULT_DISCLOSURE_FILE),
            ).shouldBeEmpty()
            constructionOutsideFindings(
                sources = listOf("rpc/Other.kt" to "val r = ElectionResultDto(electionId = x)"),
                pattern = RESULT_DTO,
                allowed = emptySet(),
            ).size shouldBe 1
            constructionOutsideFindings(
                sources = listOf("rpc/Other.kt" to "fun f(): ElectionResultDto = g()"),
                pattern = RESULT_DTO,
                allowed = emptySet(),
            ).size shouldBe
                0
        }

        test("ElectionService builds no ElectionResultDto, computeOutcome returns the internal figures") {
            val service = resultSources().first { it.first == ELECTION_SERVICE_FILE }.second
            codeLines(service).any { RESULT_DTO.containsMatchIn(it) } shouldBe false
            val computeOutcome = functionBody(text = service, signature = "private fun computeOutcome(")
            (computeOutcome.isNotEmpty()) shouldBe true
            computeOutcome.contains("ElectionOutcomeFigures(") shouldBe true
            computeOutcome.contains("ElectionResultDto") shouldBe false
        }

        test("ElectionOptionDto is only built in ElectionService, with voteCount from disclosedOptionVoteCounts") {
            constructionOutsideFindings(
                sources = resultSources(),
                pattern = OPTION_DTO,
                allowed = setOf(ELECTION_SERVICE_FILE),
            ).shouldBeEmpty()
            val service = resultSources().first { it.first == ELECTION_SERVICE_FILE }.second
            val toDto = functionBody(text = service, signature = "private fun ResultRow.toElectionDto()")
            toDto.contains("disclosedOptionVoteCounts(") shouldBe true
            Regex("""voteCount\s*=\s*voteCountByOptionId\[""").containsMatchIn(toDto) shouldBe true
            toDto.contains("eachCount") shouldBe false
            toDto.contains("ElectionBallotSelectionTable") shouldBe false
        }

        test("the per-option selection count lives only in disclosedOptionVoteCounts, after the withheld decision") {
            val sources = resultSources()
            constructionOutsideFindings(sources = sources, pattern = Regex("""\beachCount\s*\("""), allowed = emptySet())
                .filter { it.startsWith(ELECTION_SERVICE_FILE) || it.startsWith(RESULT_DISCLOSURE_FILE) }
                .size shouldBe 1
            val disclosure = sources.first { it.first == RESULT_DISCLOSURE_FILE }.second
            val body = functionBody(text = disclosure, signature = "internal fun disclosedOptionVoteCounts(")
            val gate = body.indexOf("electionFiguresWithheld(")
            val read = body.indexOf("ElectionBallotSelectionTable")
            (gate >= 0) shouldBe true
            (read > gate) shouldBe true
            disclosure
                .indexOf("ElectionBallotSelectionTable", disclosure.indexOf("internal fun disclosedOptionVoteCounts(") + body.length)
                .let { it < 0 } shouldBe true
        }

        test("ResolutionDto and the figure columns are only handled in ResolutionBook, only through toResolutionDtos") {
            constructionOutsideFindings(
                sources = resultSources(),
                pattern = RESOLUTION_DTO,
                allowed = setOf(RESOLUTION_BOOK_FILE),
            ).shouldBeEmpty()
            constructionOutsideFindings(
                sources = resultSources(),
                pattern = RESOLUTION_FIGURE_READ,
                allowed = setOf(RESOLUTION_BOOK_FILE),
            ).shouldBeEmpty()
            val book = resultSources().first { it.first == RESOLUTION_BOOK_FILE }.second
            val raw = Regex("""toRawResolutionDto\s*\(""").findAll(book).map { it.range.first }.toList()
            // one declaration + one call, and the call sits inside toResolutionDtos
            raw.size shouldBe 2
            functionBody(
                text = book,
                signature = "internal fun List<ResultRow>.toResolutionDtos()",
            ).contains("toRawResolutionDto()") shouldBe
                true
            book.contains("private fun ResultRow.toRawResolutionDto()") shouldBe true
            constructionOutsideFindings(
                sources = resultSources().filter { it.first != RESOLUTION_BOOK_FILE },
                pattern = Regex("""toRawResolutionDto"""),
                allowed = emptySet(),
            ).shouldBeEmpty()
        }

        test("a resolution snapshot is only built where it is written or masked") {
            constructionOutsideFindings(
                sources = resultSources(),
                pattern = RESOLUTION_SNAPSHOT,
                allowed = setOf(RESOLUTION_BOOK_FILE),
            ).shouldBeEmpty()
            constructionOutsideFindings(
                sources = listOf("rpc/Other.kt" to "val s = ResolutionSnapshot(x)"),
                pattern = RESOLUTION_SNAPSHOT,
                allowed = setOf(AUDIT_LOG_FILE),
            ).size shouldBe 1
        }

        test("PublicApiResolutionDto is only built by the public API routes") {
            constructionOutsideFindings(
                sources = resultSources(),
                pattern = PUBLIC_RESOLUTION_DTO,
                allowed = setOf(PUBLIC_API_ROUTES_FILE),
            ).shouldBeEmpty()
            val routes = resultSources().first { it.first == PUBLIC_API_ROUTES_FILE }.second
            val mapping = functionBody(text = routes, signature = "private fun ResolutionDto.toPublicApiDto()")
            mapping.contains("if (figuresWithheld) null else votesYes") shouldBe true
        }

        test("the audit log masks resolution figures on delivery and leaves the hash chain alone") {
            val audit = resultSources().first { it.first == AUDIT_LOG_FILE }.second
            audit.contains("withheldElectionIds(") shouldBe true
            val verify = functionBody(text = audit, signature = "override suspend fun verifyChainIntegrity(")
            verify.contains("withheldElectionIds") shouldBe false
            verify.contains("toAuditLogEntryDtos") shouldBe false
        }

        test("the withheld rule itself") {
            val disclosure = resultSources().first { it.first == RESULT_DISCLOSURE_FILE }.second
            disclosure.contains("secret && ballotCount < DisclosureRules.MIN_ANONYMOUS_RESPONSES") shouldBe true
        }
    })
