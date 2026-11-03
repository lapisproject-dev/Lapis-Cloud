package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.rpc.IPartyReportService
import java.io.File

/**
 * V1.9.225 -- the party report is gated in ONE place. A new RPC method or HTTP handler fails the build until it starts with
 * `requirePartyReportAccess(call)` (role first, then the organisation switch).
 */
class PartyReportAccessTripwireTest :
    FunSpec({
        fun source(name: String): String =
            SourceScan.blank(
                SourceScan
                    .mainFiles()
                    .single { it.name == name }
                    .readText(),
            )

        /** The text of the body after each `{` that follows [header] matches, up to the first statement end (line break after a non-empty line). */
        fun firstStatements(
            code: String,
            header: Regex,
        ): List<String> =
            header
                .findAll(code)
                .map { m ->
                    val open = code.indexOf('{', m.range.last)
                    code
                        .substring(open + 1)
                        .trimStart()
                        .lineSequence()
                        .first()
                }.toList()

        test("the RPC surface is exactly the fourteen known methods") {
            IPartyReportService::class.java.declaredMethods
                .filter { !it.isSynthetic && '$' !in it.name }
                .map { it.name }
                .toSet() shouldBe
                setOf(
                    "listPositions",
                    "updatePositionHint",
                    "listUnits",
                    "overview",
                    "getSheet",
                    "saveSheet",
                    "reportCompleteness",
                    // V1.9.269 (R3)
                    "getAnnex",
                    "saveAnnexFigures",
                    "saveLargeDonor",
                    "deleteLargeDonor",
                    "getNotes",
                    "saveNotes",
                    "reportFindings",
                )
        }

        test("every RPC method of the service starts with the access gate") {
            val code = source("PartyReportService.kt")
            val starts = firstStatements(code, Regex("""override suspend fun \w+\([^)]*\)\s*:\s*[\w<>?, ]+\s*"""))
            (starts.size) shouldBe 14
            starts.filterNot { it.contains("requirePartyReportAccess(call)") }.shouldBeEmpty()
        }

        test("every HTTP handler of the routes starts with the access gate") {
            // the raw text: the route paths are string literals, which the blanked source erases
            fun handlersOf(
                file: String,
                marker: String,
            ): List<String> {
                val code =
                    SourceScan
                        .mainFiles()
                        .single { it.name == file }
                        .readText()
                        .substringAfter(marker)
                return Regex("""\b(get|post)\("/api/partg-report/[^"]*"\)\s*\{""")
                    .findAll(code)
                    .map { m ->
                        code.substring(m.range.last + 1).trimStart()
                    }.toList()
            }

            val import = handlersOf("PartgReportRoutes.kt", "fun Route.registerPartgReportRoutes")
            val export = handlersOf("PartgReportExportRoutes.kt", "fun Route.registerPartgReportExportRoutes")
            import.size shouldBe 3
            export.size shouldBe 2
            (import + export).forEach { body ->
                // the gate may sit inside `val current = try { requirePartyReportAccess(call) } ...` -- it must be the first call of the handler
                val firstCall = Regex("""\w+\(""").find(body)!!.value
                (firstCall == "try(" || body.startsWith("val current =")) shouldBe true
                body.substringBefore("return@").contains("requirePartyReportAccess(call)") shouldBe true
                val gate = body.indexOf("requirePartyReportAccess(call)")
                val later =
                    listOf(
                        "receiveMultipart",
                        "receive<",
                        "rateLimiter.checkAndRecord",
                        "queryParameters",
                        "tryAcquire",
                    ).map { body.indexOf(it) }.filter { it >= 0 }
                later.all { gate < it } shouldBe true
            }
        }

        test("the gate checks the role BEFORE the organisation switch") {
            val code = source("PartgReportAccess.kt")
            val role = code.indexOf("requireRole(AccountRole.BOARD, AccountRole.ADMIN)")
            val switch = code.indexOf("isPoliticalParty")
            (role in 0 until switch) shouldBe true
            File("src/main/kotlin").let { it.exists() } // anchor for the working directory
        }

        test("no RPC method of the service logs an amount, a hint or a name") {
            source("PartyReportService.kt").lines().filter { "logger." in it }.shouldBeEmpty()
            source("PartgReportRoutes.kt").lines().filter { "logger." in it }.shouldBeEmpty()
            source("PartgReportExportRoutes.kt").lines().filter { "logger." in it && "logger.info" !in it }.shouldBeEmpty()
        }
    })
