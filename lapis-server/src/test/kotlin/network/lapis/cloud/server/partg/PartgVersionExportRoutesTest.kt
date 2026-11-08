package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.sync.Semaphore
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.PartgReportSheetTable
import network.lapis.cloud.server.db.generated.PartgReportVersionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partg.report.readXlsx
import network.lapis.cloud.server.routes.registerPartgReportExportRoutes
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgImportRejectionCode
import network.lapis.cloud.shared.domain.PartgImportRejectionDto
import network.lapis.cloud.shared.domain.PartgReportExportAuditSnapshot
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitLevel
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.291 (R6) -- the download of a KEPT version: rendered from the stored document (a later change of the figures does not change the file), the
 * footer on every page / sheet, the integrity check (409, nothing rendered), 404 for an unknown id, the gate order, one audit entry per download with
 * the version id and number but no content, and the unchanged working-state export.
 */
class PartgVersionExportRoutesTest :
    FunSpec({
        val db = TestDatabase.H2
        val fx = PartgFixtures()
        val json = Json { ignoreUnknownKeys = true }
        val year = 2002

        beforeSpec {
            db.activate()
            DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest { fx.setParty(true) }
        afterTest { fx.cleanUp() }
        afterSpec {
            db.deactivate()
        }

        fun withRoutes(
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 60.minutes),
            semaphore: Semaphore = Semaphore(2),
            block: suspend HttpClient.() -> Unit,
        ) = testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { registerPartgReportExportRoutes(rateLimiter = limiter, concurrency = semaphore) }
            }
            client.block()
        }

        fun keep(member: Uuid): String {
            val id =
                PartgVersionWriter.freeze(
                    current = fx.currentOf(memberId = member, role = AccountRole.BOARD),
                    year = year,
                    scope = PartgReportScope.All,
                    comment = null,
                    now = ServerClock.now(),
                )
            return id.toString()
        }

        suspend fun HttpClient.version(
            member: Uuid?,
            id: String,
            format: String = "pdf",
        ): HttpResponse = get("/api/partg-report/version/$format?id=$id") { member?.let { header("X-Member-Id", it.toString()) } }

        suspend fun HttpResponse.code() = json.decodeFromString(PartgImportRejectionDto.serializer(), bodyAsText()).code

        fun pdfText(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().apply { sortByPosition = true }.getText(it) }

        fun pdfPages(bytes: ByteArray): List<String> =
            Loader.loadPDF(bytes).use { pdf ->
                (1..pdf.numberOfPages).map { n ->
                    PDFTextStripper()
                        .apply {
                            startPage = n
                            endPage = n
                            sortByPosition = true
                        }.getText(pdf)
                }
            }

        fun hashOf(id: String): String =
            transaction {
                PartgReportVersionTable
                    .selectAll()
                    .where {
                        PartgReportVersionTable.id eq
                            Uuid.parse(
                                id,
                            )
                    }.single()[PartgReportVersionTable.contentHash]
            }

        fun versionAudits(member: Uuid) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq AuditEntityType.PARTG_REPORT_EXPORT }
                    .filter { it[AuditLogEntryTable.actorMemberId] == member }
                    .map { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
            }

        test("the PDF and the XLSX of a kept version: type, fixed file name, no caching, the version footer on EVERY page and sheet") {
            val board = fx.member()
            val id = keep(board)
            val hash12 = hashOf(id).take(12)
            withRoutes {
                val pdf = version(board, id, "pdf")
                pdf.status shouldBe HttpStatusCode.OK
                pdf.headers[HttpHeaders.ContentType] shouldBe "application/pdf"
                pdf.headers[HttpHeaders.ContentDisposition] shouldBe "attachment; filename=\"rechenschaftsbericht-$year-version-1.pdf\""
                pdf.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                pdf.headers["X-Content-Type-Options"] shouldBe "nosniff"
                val pages = pdfPages(pdf.readRawBytes())
                (pages.size > 1) shouldBe true
                pages.forEach { page ->
                    page shouldContain "Version 1 · festgehalten"
                    page shouldContain "Prüfsumme $hash12"
                    page shouldContain "Entwurf als Arbeitshilfe, nicht eingereicht"
                }
                val xlsx = version(board, id, "xlsx")
                xlsx.status shouldBe HttpStatusCode.OK
                xlsx.headers[HttpHeaders.ContentDisposition] shouldBe "attachment; filename=\"rechenschaftsbericht-$year-version-1.xlsx\""
                val contents = readXlsx(xlsx.readRawBytes())
                contents.sheets.values.forEach { cells ->
                    cells.getValue("A2").value shouldContain "Version 1 · festgehalten"
                    cells.getValue("A2").value shouldContain "Prüfsumme $hash12"
                }
                val sheetXml = String(contents.parts.getValue("xl/worksheets/sheet1.xml"), Charsets.UTF_8)
                sheetXml shouldContain "<headerFooter><oddFooter>&amp;L"
            }
        }

        test("a final version says so, and never that it was filed") {
            val board = fx.member()
            val id = keep(board)
            val hash = hashOf(id)
            PartgVersionWriter.declare(
                current = fx.currentOf(memberId = board),
                versionId = Uuid.parse(id),
                expectedHash = hash,
                reviewed = true,
                now = ServerClock.now(),
            )
            PartgVersionWriter.finalize(
                current = fx.currentOf(memberId = board),
                versionId = Uuid.parse(id),
                typedNumber = 1,
                expectedHash = hash,
                now = ServerClock.now(),
            )
            withRoutes {
                val text = pdfText(version(board, id).readRawBytes())
                text shouldContain "Intern final, nicht eingereicht"
                text shouldNotContain "Entwurf als Arbeitshilfe, nicht eingereicht"
            }
        }

        test("the file does not change when the working state changes afterwards: it is rendered from the stored document") {
            val board = fx.member()
            val id = keep(board)
            withRoutes {
                val before = pdfText(version(board, id).readRawBytes())
                // a new sheet value changes the working state (not the kept version)
                transaction {
                    PartgReportSheetTable.insert {
                        it[PartgReportSheetTable.id] = Uuid.random()
                        it[fiscalYear] = year
                        it[unitLevel] = PartgUnitLevel.FEDERAL
                        it[regionalChapterId] = null
                        it[unitKey] = PartgUnit.FEDERAL_KEY
                        it[revision] = 1L
                        it[updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                        it[updatedBy] = board
                    }
                }
                pdfText(version(board, id).readRawBytes()) shouldBe before
            }
        }

        test("integrity: damaged content is a 409 INTEGRITY and nothing is rendered or audited") {
            val board = fx.member()
            val id = keep(board)
            transaction {
                PartgReportVersionTable.update({ PartgReportVersionTable.id eq Uuid.parse(id) }) { it[contentJson] = "{\"x\":1}" }
            }
            withRoutes {
                for (format in listOf("pdf", "xlsx")) {
                    val response = version(board, id, format)
                    response.status shouldBe HttpStatusCode.Conflict
                    response.code() shouldBe PartgImportRejectionCode.INTEGRITY
                }
            }
            versionAudits(board) shouldBe emptyList()
        }

        test("unknown, malformed and missing ids") {
            val board = fx.member()
            withRoutes {
                val unknown = version(board, Uuid.random().toString())
                unknown.status shouldBe HttpStatusCode.NotFound
                unknown.code() shouldBe PartgImportRejectionCode.NOT_FOUND
                for (bad in listOf("abc", "'; DROP TABLE x;--", Uuid.random().toString().replace("-", ""), "")) {
                    val response = version(board, bad)
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.code() shouldBe PartgImportRejectionCode.INVALID_INPUT
                }
                get("/api/partg-report/version/pdf") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("the gate: member, treasurer and chapter treasurer are forbidden; a non-party board gets NOT_ENABLED; no login nothing") {
            val board = fx.member()
            val id = keep(board)
            val chapterTreasurer = fx.member(role = AccountRole.MEMBER)
            fx.grantTreasurer(member = chapterTreasurer, chapter = fx.chapter())
            withRoutes {
                for (role in listOf(AccountRole.MEMBER, AccountRole.TREASURER)) {
                    val who = fx.member(role = role)
                    version(who, id).status shouldBe HttpStatusCode.Forbidden
                    version(who, id).code() shouldBe PartgImportRejectionCode.FORBIDDEN
                }
                version(chapterTreasurer, id, "xlsx").code() shouldBe PartgImportRejectionCode.FORBIDDEN
                (version(member = null, id = id).status == HttpStatusCode.OK) shouldBe false
                fx.setParty(false)
                version(board, id).code() shouldBe PartgImportRejectionCode.NOT_ENABLED
            }
        }

        test("rate limit and concurrency permit") {
            val board = fx.member()
            val id = keep(board)
            withRoutes(limiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)) {
                version(board, id, "xlsx").status shouldBe HttpStatusCode.OK
                version(board, id, "xlsx").status shouldBe HttpStatusCode.OK
                version(board, id, "xlsx").status shouldBe HttpStatusCode.TooManyRequests
            }
            val semaphore = Semaphore(1)
            withRoutes(semaphore = semaphore) {
                version(board, id, "xlsx").status shouldBe HttpStatusCode.OK
                semaphore.availablePermits shouldBe 1
                semaphore.acquire()
                version(board, id, "xlsx").status shouldBe HttpStatusCode.TooManyRequests
                semaphore.release()
            }
        }

        test(
            "one audit entry per download with the version id and number, no content; the working-state export is unchanged and has its own footer",
        ) {
            val board = fx.member()
            val id = keep(board)
            withRoutes {
                version(board, id, "pdf").status shouldBe HttpStatusCode.OK
                val working = get("/api/partg-report/export/pdf?year=$year&scope=ALL") { header("X-Member-Id", board.toString()) }
                working.status shouldBe HttpStatusCode.OK
                pdfText(working.readRawBytes()) shouldContain "Arbeitsstand, keine festgehaltene Version"
            }
            val audits = versionAudits(board)
            audits.size shouldBe 2
            val kept = json.decodeFromString(PartgReportExportAuditSnapshot.serializer(), audits[0])
            kept.versionId shouldBe id
            kept.versionNumber shouldBe 1
            kept.year shouldBe year
            // the working-state entry has exactly the bytes it had before R6: the new fields are not written
            audits[1] shouldNotContain "versionId"
            audits[1] shouldNotContain "versionNumber"
        }
    })
