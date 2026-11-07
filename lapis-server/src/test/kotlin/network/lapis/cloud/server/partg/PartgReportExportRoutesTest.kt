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
import network.lapis.cloud.server.db.generated.PartgReportValueTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.routes.registerPartgReportExportRoutes
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgImportRejectionCode
import network.lapis.cloud.shared.domain.PartgImportRejectionDto
import network.lapis.cloud.shared.domain.PartgReportExportAuditSnapshot
import network.lapis.cloud.shared.domain.PartgReportFormat
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.domain.PartgValueSource
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.256 -- the export routes through the real Ktor routing: access order (role first, then the organisation switch), input checks, rate limit,
 * concurrency permit, content type and fixed file name, and exactly one audit entry per successful export (counters only).
 */
class PartgReportExportRoutesTest :
    FunSpec({
        val db = TestDatabase.H2
        val fx = PartgFixtures()
        val json = Json { ignoreUnknownKeys = true }

        beforeSpec {
            db.activate()
            DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest { fx.setParty(true) }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        val year: Int by lazy { PartgYearRules.maxYear() - 1 }

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

        suspend fun HttpClient.export(
            member: Uuid?,
            format: String = "pdf",
            query: String = "year=$year&scope=ALL",
        ): HttpResponse = get("/api/partg-report/export/$format?$query") { member?.let { header("X-Member-Id", it.toString()) } }

        suspend fun HttpResponse.code() = json.decodeFromString(PartgImportRejectionDto.serializer(), bodyAsText()).code

        fun exportAudits(member: Uuid) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq AuditEntityType.PARTG_REPORT_EXPORT }
                    .filter { it[AuditLogEntryTable.actorMemberId] == member }
                    .toList()
                    .map { it[AuditLogEntryTable.afterSnapshot].orEmpty() to it[AuditLogEntryTable.action] }
            }

        test("member and treasurer are forbidden, also when the organisation is not a party; the role is checked first") {
            fx.setParty(false)
            withRoutes {
                for (role in listOf(AccountRole.MEMBER, AccountRole.TREASURER)) {
                    val member = fx.member(role = role)
                    for (format in listOf("pdf", "xlsx")) {
                        val response = export(member, format)
                        response.status shouldBe HttpStatusCode.Forbidden
                        response.code() shouldBe PartgImportRejectionCode.FORBIDDEN
                    }
                }
            }
        }

        test("the board of a non-party gets NOT_ENABLED") {
            fx.setParty(false)
            withRoutes {
                val response = export(fx.member(role = AccountRole.BOARD))
                response.status shouldBe HttpStatusCode.Forbidden
                response.code() shouldBe PartgImportRejectionCode.NOT_ENABLED
            }
        }

        test("without a login nothing is delivered") {
            withRoutes { (export(member = null).status == HttpStatusCode.OK) shouldBe false }
        }

        test("board and admin get the PDF and the XLSX with the right type, a fixed file name and no caching") {
            withRoutes {
                for (role in listOf(AccountRole.BOARD, AccountRole.ADMIN)) {
                    val member = fx.member(role = role)
                    val pdf = export(member, "pdf")
                    pdf.status shouldBe HttpStatusCode.OK
                    pdf.headers[HttpHeaders.ContentType] shouldBe "application/pdf"
                    pdf.headers[HttpHeaders.ContentDisposition] shouldBe "attachment; filename=\"rechenschaftsbericht-$year-entwurf.pdf\""
                    pdf.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    pdf.headers["X-Content-Type-Options"] shouldBe "nosniff"
                    String(pdf.readRawBytes().copyOf(5), Charsets.US_ASCII) shouldBe "%PDF-"
                    val xlsx = export(member, "xlsx")
                    xlsx.status shouldBe HttpStatusCode.OK
                    xlsx.headers[HttpHeaders.ContentType] shouldBe "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    xlsx.headers[HttpHeaders.ContentDisposition] shouldBe
                        "attachment; filename=\"rechenschaftsbericht-$year-entwurf.xlsx\""
                    xlsx.readRawBytes().copyOf(2).toList() shouldBe listOf('P'.code.toByte(), 'K'.code.toByte())
                }
            }
        }

        test("invalid input is a 400 and writes no audit entry") {
            val board = fx.member()
            withRoutes {
                val bad =
                    listOf(
                        "year=2000&scope=ALL",
                        "year=${PartgYearRules.maxYear() + 1}&scope=ALL",
                        "year=abc&scope=ALL",
                        "scope=ALL",
                        "year=$year",
                        "year=$year&scope=../../etc",
                        "year=$year&scope=STATE:${Uuid.random().toString().uppercase()}",
                        "year=$year&scope=SUB:" + "a".repeat(60),
                        "year=$year&scope=all",
                    )
                for (query in bad) {
                    val response = export(board, query = query)
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.code() shouldBe PartgImportRejectionCode.INVALID_INPUT
                }
            }
            exportAudits(board) shouldBe emptyList()
        }

        test("an unknown regional chapter is a 404") {
            val board = fx.member()
            withRoutes {
                val response = export(board, query = "year=$year&scope=STATE:${Uuid.random()}")
                response.status shouldBe HttpStatusCode.NotFound
                response.code() shouldBe PartgImportRejectionCode.NOT_FOUND
            }
            exportAudits(board) shouldBe emptyList()
        }

        test("the eleventh export within a minute is refused with 429") {
            val board = fx.member()
            withRoutes(limiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)) {
                repeat(10) { export(board, "xlsx").status shouldBe HttpStatusCode.OK }
                val response = export(board, "xlsx")
                response.status shouldBe HttpStatusCode.TooManyRequests
                response.code() shouldBe PartgImportRejectionCode.RATE_LIMITED
            }
        }

        test("without a free permit the export is refused with 429 and the permit is released after a normal export") {
            val board = fx.member()
            val semaphore = Semaphore(1)
            withRoutes(semaphore = semaphore) {
                export(board, "xlsx").status shouldBe HttpStatusCode.OK
                semaphore.availablePermits shouldBe 1
                semaphore.acquire()
                export(board, "xlsx").status shouldBe HttpStatusCode.TooManyRequests
                semaphore.release()
            }
        }

        test("a successful export writes exactly one audit entry with counters only: no amount, no name") {
            val board = fx.member()
            val chapter = fx.chapter(name = "Geheimname-Landesverband")
            val sheet = Uuid.random()
            transaction {
                PartgReportSheetTable.insert {
                    it[id] = sheet
                    it[fiscalYear] = year
                    it[unitLevel] = PartgUnitLevel.STATE
                    it[regionalChapterId] = chapter
                    it[unitKey] = PartgUnit.State(chapter.toString()).key()
                    it[revision] = 1L
                    it[updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[updatedBy] = board
                }
                PartgReportValueTable.insert {
                    it[id] = Uuid.random()
                    it[sheetId] = sheet
                    it[positionCode] = "E01"
                    it[amountCents] = 424_242_42L
                    it[valueSource] = PartgValueSource.MANUAL
                    it[updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[updatedBy] = board
                }
            }
            withRoutes {
                export(board, "pdf").status shouldBe HttpStatusCode.OK
                export(board, "xlsx", query = "year=$year&scope=${PartgUnit.State(chapter.toString()).key()}").status shouldBe
                    HttpStatusCode.OK
                export(board, "xlsx", query = "year=1999&scope=ALL").status shouldBe HttpStatusCode.BadRequest
            }
            val audits = exportAudits(board)
            audits.size shouldBe 2
            audits.forEach { (after, action) ->
                action shouldBe AuditAction.CREATE
                after shouldNotContain "Geheimname"
                after shouldNotContain "424242"
                after shouldNotContain "4242"
            }
            val snapshots = audits.map { json.decodeFromString(PartgReportExportAuditSnapshot.serializer(), it.first) }
            snapshots.map { it.format } shouldBe listOf(PartgReportFormat.PDF, PartgReportFormat.XLSX)
            snapshots[0].scope shouldBe "ALL"
            snapshots[0].year shouldBe year
            snapshots[1].unitCount shouldBe 1
            snapshots[1].missingCount shouldBe 34 // one of the 35 leaf positions is entered
            (snapshots[0].byteSize > 1000) shouldBe true
            audits[0].first shouldContain "\"format\":\"PDF\""
        }

        test(
            "V1.9.269: the export carries the separate statements and the notes; the audit entry counts rows and hints but holds no name or address",
        ) {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString())
            val current = fx.currentOf(memberId = board)
            val now =
                network.lapis.cloud.server.time.ServerClock
                    .now()
            PartgAnnexWriter.saveDonor(
                current = current,
                year = year,
                unit = unit,
                expectedRevision = 0,
                donor =
                    network.lapis.cloud.shared.domain.PartgLargeDonorInputDto(
                        id = null,
                        personKind = network.lapis.cloud.shared.domain.PartgPersonKind.LEGAL,
                        name = "Exportprobe Geheimfirma",
                        street = null,
                        postalCode = null,
                        city = null,
                        country = "DE",
                        contributionKind = network.lapis.cloud.shared.domain.PartgContributionKind.FEE,
                        amountCents = 3_333_333L,
                    ),
                now = now,
            ) {}
            PartgAnnexWriter.saveNotes(
                current = current,
                year = year,
                expectedRevision = 0,
                notes =
                    listOf(
                        network.lapis.cloud.shared.domain.PartgNoteDto(
                            block = network.lapis.cloud.shared.domain.PartgNoteBlock.GENERAL_ACCOUNTING,
                            included = true,
                            body = "Exportprobe Geheimtext",
                            reliefAbs1 = null,
                            reliefAbs3 = null,
                        ),
                    ),
                holdings = emptyList(),
                now = now,
            ) {}
            withRoutes {
                val pdf = export(board, "pdf", query = "year=$year&scope=${unit.key()}")
                pdf.status shouldBe HttpStatusCode.OK
                val pdfText =
                    org.apache.pdfbox.Loader
                        .loadPDF(pdf.readRawBytes())
                        .use {
                            org.apache.pdfbox.text
                                .PDFTextStripper()
                                .getText(it)
                        }
                pdfText shouldContain "Gesonderter Ausweis B"
                pdfText shouldContain "Exportprobe Geheimfirma"
                pdfText shouldContain "33.333,33"
                pdfText shouldContain "Exportprobe Geheimtext"
                val xlsx = export(board, "xlsx", query = "year=$year&scope=${unit.key()}")
                xlsx.status shouldBe HttpStatusCode.OK
                val sheets = readXlsxForRoute(xlsx.readRawBytes())
                sheets shouldContain "Ausweis B"
            }
            val audits = exportAudits(board)
            audits.size shouldBe 2
            audits.forEach { (after, _) ->
                listOf("Geheim", "3333333", "Exportprobe").forEach { after shouldNotContain it }
                val snapshot = json.decodeFromString(PartgReportExportAuditSnapshot.serializer(), after)
                snapshot.donorRowCount shouldBe 1
                (snapshot.findingCount >= 1) shouldBe true // at least P01 (no address) and P04 (legal person)
            }
        }

        test(
            "V1.9.283: the export carries statements C and F; the audit entry counts only the sponsors in the report and holds no name or amount",
        ) {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString())
            val current = fx.currentOf(memberId = board)
            val now =
                network.lapis.cloud.server.time.ServerClock
                    .now()

            fun sponsorInput(
                name: String,
                cents: Long,
            ) = network.lapis.cloud.shared.domain.PartgSponsorInputDto(
                id = null,
                personKind = network.lapis.cloud.shared.domain.PartgPersonKind.LEGAL,
                name = name,
                street = null,
                postalCode = null,
                city = null,
                country = "DE",
                entries =
                    listOf(
                        network.lapis.cloud.shared.domain.PartgSponsoringEntryInputDto(
                            id = null,
                            date = kotlinx.datetime.LocalDate(year, 5, 5),
                            amountCents = cents,
                            purpose = "Exportzweck Geheim",
                        ),
                    ),
            )
            PartgAnnexWriter.saveSponsor(
                current = current,
                year = year,
                unit = unit,
                expectedRevision = 0,
                sponsor = sponsorInput("Sponsorprobe Geheimfirma", 4_444_444L),
                now = now,
            ) {
            }
            PartgAnnexWriter.saveSponsor(
                current = current,
                year = year,
                unit = unit,
                expectedRevision = 1,
                sponsor = sponsorInput("Unsichtbar Unterhalb", 100L),
                now = now,
            ) {
            }
            PartgAnnexWriter.saveYouthOrg(
                current = current,
                year = year,
                unit = unit,
                expectedRevision = 2,
                youthOrg =
                    network.lapis.cloud.shared.domain
                        .PartgYouthOrgInputDto(
                            id = null,
                            name = "Jugendprobe Geheim",
                            relationship = null,
                            incomeCents = 5_555_555L,
                            expensesCents = 1L,
                        ),
                now = now,
            ) {}
            withRoutes {
                val pdf = export(board, "pdf", query = "year=$year&scope=${unit.key()}")
                pdf.status shouldBe HttpStatusCode.OK
                val pdfText =
                    org.apache.pdfbox.Loader
                        .loadPDF(pdf.readRawBytes())
                        .use {
                            org.apache.pdfbox.text
                                .PDFTextStripper()
                                .getText(it)
                        }
                pdfText shouldContain "Sponsorprobe Geheimfirma"
                pdfText shouldContain "44.444,44"
                pdfText shouldContain "Jugendprobe Geheim"
                pdfText shouldNotContain "Unsichtbar Unterhalb"
                val xlsx = export(board, "xlsx", query = "year=$year&scope=${unit.key()}")
                xlsx.status shouldBe HttpStatusCode.OK
                val sheets = readXlsxForRoute(xlsx.readRawBytes())
                sheets shouldContain "Ausweis C"
                sheets shouldContain "Ausweis F"
            }
            val audits = exportAudits(board)
            audits.size shouldBe 2
            audits.forEach { (after, _) ->
                listOf("Geheim", "4444444", "Sponsorprobe", "Exportzweck").forEach { after shouldNotContain it }
                val snapshot = json.decodeFromString(PartgReportExportAuditSnapshot.serializer(), after)
                snapshot.sponsorRowCount shouldBe 1
                snapshot.youthOrgRowCount shouldBe 1
            }
        }

        test("V1.9.287: the export carries statement D; the audit entry counts the enterprises and holds no name or amount") {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString())
            val current = fx.currentOf(memberId = board)
            val now =
                network.lapis.cloud.server.time.ServerClock
                    .now()
            listOf("Betriebsprobe Geheimfirma" to 4_444_444L, "Zweiter Geheimbetrieb" to 1_111L).forEachIndexed { index, (name, cents) ->
                PartgAnnexWriter.saveEnterprise(
                    current = current,
                    year = year,
                    unit = unit,
                    expectedRevision = index.toLong(),
                    enterprise =
                        network.lapis.cloud.shared.domain
                            .PartgEnterpriseInputDto(
                                id = null,
                                name = name,
                                legalForm = null,
                                seat = null,
                                shareBasisPoints = null,
                                incomeCents = cents,
                                expensesCents = 1L,
                            ),
                    now = now,
                ) {}
            }
            withRoutes {
                val pdf = export(board, "pdf", query = "year=$year&scope=${unit.key()}")
                pdf.status shouldBe HttpStatusCode.OK
                val pdfText =
                    org.apache.pdfbox.Loader
                        .loadPDF(pdf.readRawBytes())
                        .use {
                            org.apache.pdfbox.text
                                .PDFTextStripper()
                                .getText(it)
                        }
                pdfText shouldContain "Betriebsprobe Geheimfirma"
                pdfText shouldContain "44.444,44"
                val xlsx = export(board, "xlsx", query = "year=$year&scope=${unit.key()}")
                xlsx.status shouldBe HttpStatusCode.OK
                readXlsxForRoute(xlsx.readRawBytes()) shouldContain "Ausweis D"
            }
            val audits = exportAudits(board)
            audits.size shouldBe 2
            audits.forEach { (after, _) ->
                listOf("Geheim", "4444444", "Betriebsprobe").forEach { after shouldNotContain it }
                val snapshot = json.decodeFromString(PartgReportExportAuditSnapshot.serializer(), after)
                snapshot.enterpriseRowCount shouldBe 2
            }
        }
    })

private fun readXlsxForRoute(bytes: ByteArray): String {
    val names = StringBuilder()
    java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == "xl/workbook.xml") names.append(String(zip.readBytes(), Charsets.UTF_8))
        }
    }
    return names.toString()
}
