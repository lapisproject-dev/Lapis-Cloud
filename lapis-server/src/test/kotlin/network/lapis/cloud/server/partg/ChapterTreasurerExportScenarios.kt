package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Semaphore
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partg.report.PartgReportXlsxWriter
import network.lapis.cloud.server.partg.report.PartgUnitPart
import network.lapis.cloud.server.routes.registerPartgReportExportRoutes
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgImportRejectionCode
import network.lapis.cloud.shared.domain.PartgImportRejectionDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgReportExportAuditSnapshot
import network.lapis.cloud.shared.domain.PartgReportFormat
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgValueSource
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.292 (R4b) -- the unit part, the journal CSV and the name guess on H2 AND PostgreSQL. The negative tests of the plan: a chapter treasurer cannot
 * read, export or enumerate another unit, never sees the whole party, the consolidation, the statements A-F or the hints P17-P21, and gets ONE
 * uniform 404 for everything that is not theirs. A revoked grant is effective at once.
 */
abstract class ChapterTreasurerExportScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PartgFixtures()
        val json = Json { ignoreUnknownKeys = true }

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest { fx.setParty(true) }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        val year: Int by lazy { PartgYearRules.maxYear() - 1 }
        val day by lazy { LocalDate(year, 3, 1) }
        val bom = 0xFEFF.toChar().toString()

        class World {
            val x = fx.chapter("Land Xaver ${Uuid.random()}")
            val y = fx.chapter("Land Ypsilon ${Uuid.random()}")
            val unitX = PartgUnit.State(x.toString())
            val unitY = PartgUnit.State(y.toString())
            val board = fx.member(label = "board", role = AccountRole.BOARD)
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val kwX = fx.member(label = "kwx", role = AccountRole.MEMBER)
            val kwY = fx.member(label = "kwy", role = AccountRole.MEMBER)
            val plain = fx.member(label = "plain", role = AccountRole.MEMBER)
            val bank = fx.ledgerAccount(type = LedgerAccountType.ASSET, name = "Bank")
            val income = fx.ledgerAccount(type = LedgerAccountType.INCOME, name = "Beiträge")
            val income2 = fx.ledgerAccount(type = LedgerAccountType.INCOME, name = "Sonstige Einnahmen")
            val grantX = fx.grantTreasurer(member = kwX, chapter = x)

            init {
                fx.grantTreasurer(member = kwY, chapter = y)
            }
        }

        fun posting(
            account: Uuid,
            side: PostingSide,
            amount: String,
        ) = PostingInput(
            ledgerAccountId = account.toString(),
            side = side,
            amount = BigDecimal(amount),
            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
        )

        fun simple(
            w: World,
            chapter: Uuid?,
            description: String,
            voucher: String? = null,
            amount: String = "12.34",
            date: LocalDate = day,
        ) = JournalEntryInput(
            entryDate = date,
            description = description,
            voucherReference = voucher,
            postings = listOf(posting(w.bank, PostingSide.DEBIT, amount), posting(w.income, PostingSide.CREDIT, amount)),
            regionalChapterId = chapter?.toString(),
        )

        suspend fun PartgApp.fetch(
            path: String,
            member: Uuid?,
        ): HttpResponse = client.get(path) { member?.let { header("X-Member-Id", it.toString()) } }

        suspend fun HttpResponse.rejection() = json.decodeFromString(PartgImportRejectionDto.serializer(), bodyAsText()).code

        fun routesWith(
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 60.minutes),
            semaphore: Semaphore = Semaphore(2),
            maxPostings: Int = 200_000,
        ): io.ktor.server.routing.Route.() -> Unit =
            { registerPartgReportExportRoutes(rateLimiter = limiter, concurrency = semaphore, maxJournalPostings = maxPostings) }

        fun pdfText(bytes: ByteArray): List<String> =
            Loader.loadPDF(bytes).use { doc ->
                (1..doc.numberOfPages).map { page ->
                    PDFTextStripper()
                        .also {
                            it.startPage = page
                            it.endPage = page
                        }.getText(doc)
                }
            }

        fun zipText(bytes: ByteArray): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    result[entry.name] = String(zip.readBytes(), Charsets.UTF_8)
                }
            }
            return result
        }

        fun exportAudits(member: Uuid) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq AuditEntityType.PARTG_REPORT_EXPORT }
                    .filter { it[AuditLogEntryTable.actorMemberId] == member }
                    .map { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
            }

        /** Books the fixture: values and statements of X and Y, entries of X, Y and the federal level, a draft of X, an entry of the year before. */
        suspend fun PartgApp.seed(w: World) {
            val now = ServerClock.now()
            val admin = fx.currentOf(memberId = w.admin)
            listOf(w.unitX to 100_000L, w.unitY to 98_765_432L).forEach { (unit, cents) ->
                PartgSheetWriter.write(
                    current = admin,
                    year = year,
                    unit = unit,
                    expectedRevision = 0,
                    expectedPriorRevision = 0,
                    currentChanges = mapOf("E01" to cents),
                    priorChanges = emptyMap(),
                    source = PartgValueSource.MANUAL,
                    now = now,
                )
            }
            listOf(w.unitX to "Xaver-Spender Ausgeschlossen", w.unitY to "Ypsilon-Spender Unauffindbar").forEach { (unit, name) ->
                PartgAnnexWriter.saveDonor(
                    current = admin,
                    year = year,
                    unit = unit,
                    expectedRevision = 0,
                    donor =
                        PartgLargeDonorInputDto(
                            id = null,
                            personKind = PartgPersonKind.LEGAL,
                            name = name,
                            street = null,
                            postalCode = null,
                            city = null,
                            country = "DE",
                            contributionKind = PartgContributionKind.FEE,
                            amountCents = 7_777_777L,
                        ),
                    now = now,
                ) {}
            }
            call(member = w.admin) { fx.accounting(it).postJournalEntry(simple(w, w.x, "Beitrag Xaver eins", "BX-1", "1234.56")) }
            call(member = w.admin) {
                fx.accounting(it).postJournalEntry(simple(w, w.x, "Beitrag Xaver zwei", "BX-2", "5.00", LocalDate(year, 4, 2)))
            }
            call(member = w.admin) { fx.accounting(it).postJournalEntry(simple(w, w.y, "Beitrag Ypsilon geheim", "BY-1", "77.00")) }
            call(member = w.admin) { fx.accounting(it).postJournalEntry(simple(w, null, "Beitrag Bund", "BB-1", "9.00")) }
            call(member = w.admin) { fx.accounting(it).saveDraftEntry(simple(w, w.x, "Entwurf Xaver nie sichtbar", "BX-D", "3.00")) }
            call(member = w.admin) {
                fx.accounting(it).postJournalEntry(simple(w, w.x, "Xaver Vorjahr", "BX-V", "2.00", LocalDate(year - 1, 3, 1)))
            }
        }

        test(
            "the unit part: PDF, XLSX and CSV of the own unit carry the part title and the footer on every page, and nothing of the rest",
        ) {
            val w = World()
            partgTestApplication(fixtures = fx, routes = routesWith()) {
                seed(w)
                val key = w.unitX.key()
                val pdf = fetch("/api/partg-report/export/pdf?year=$year&scope=$key", w.kwX)
                pdf.status shouldBe HttpStatusCode.OK
                pdf.headers[HttpHeaders.ContentType] shouldBe "application/pdf"
                pdf.headers[HttpHeaders.ContentDisposition] shouldBe
                    "attachment; filename=\"teilbericht-$year-${key.replace(':', '-')}-entwurf.pdf\""
                pdf.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                val pages = pdfText(pdf.readRawBytes())
                pages.forEach { it shouldContain "kein amtliches Formular" }
                pages.first() shouldContain "Teilbericht Landesverband Land Xaver"
                val all = pages.joinToString("\n")
                listOf("Konsolidierung", "Gesonderter Ausweis", "Erläuterungen", "Ypsilon", "Xaver-Spender", "987.654,32").forEach {
                    all shouldNotContain it
                }
                all shouldContain "1.000,00"
                val xlsx = fetch("/api/partg-report/export/xlsx?year=$year&scope=$key", w.kwX)
                xlsx.status shouldBe HttpStatusCode.OK
                val parts = zipText(xlsx.readRawBytes())
                val workbook = parts.getValue("xl/workbook.xml")
                PartgReportXlsxWriter.UNIT_PART_SHEET_NAMES.forEach { workbook shouldContain it }
                workbook shouldNotContain "Ausweis"
                parts.keys.count { it.startsWith("xl/worksheets/sheet") } shouldBe 6
                val first = parts.getValue("xl/worksheets/sheet1.xml")
                first shouldContain "Teilbericht Landesverband Land Xaver"
                first shouldContain "kein amtliches Formular"
                parts.values.joinToString("\n").let { text ->
                    listOf("Ypsilon", "Xaver-Spender", "9876543").forEach { text shouldNotContain it }
                }
                val csv = fetch("/api/partg-report/export/journal.csv?year=$year&unit=$key", w.kwX)
                csv.status shouldBe HttpStatusCode.OK
                csv.headers[HttpHeaders.ContentType] shouldBe "text/csv; charset=utf-8"
                csv.headers[HttpHeaders.ContentDisposition] shouldBe
                    "attachment; filename=\"buchungsjournal-$year-${key.replace(':', '-')}.csv\""
            }
        }

        test(
            "everything that is not the own unit is ONE 404: foreign, subordinate of another, federal, whole party, rubbish, unknown, missing",
        ) {
            val w = World()
            partgTestApplication(fixtures = fx, routes = routesWith()) {
                val foreign =
                    listOf(
                        w.unitY.key(),
                        PartgUnit.Subordinate(w.y.toString()).key(),
                        "FEDERAL",
                        "ALL",
                        "CONSOLIDATED",
                        "../../etc",
                        PartgUnit.State(Uuid.random().toString()).key(),
                        PartgUnit.Subordinate(Uuid.random().toString()).key(),
                        w.unitX.key().uppercase(),
                    )
                foreign.forEach { scope ->
                    listOf("pdf", "xlsx").forEach { format ->
                        val response = fetch("/api/partg-report/export/$format?year=$year&scope=$scope", w.kwX)
                        response.status shouldBe HttpStatusCode.NotFound
                        response.rejection() shouldBe PartgImportRejectionCode.NOT_FOUND
                    }
                    val csv = fetch("/api/partg-report/export/journal.csv?year=$year&unit=$scope", w.kwX)
                    csv.status shouldBe HttpStatusCode.NotFound
                    csv.rejection() shouldBe PartgImportRejectionCode.NOT_FOUND
                    attempt(member = w.kwX) { fx.service(it).unitStatementPreview(year = year, unitKey = scope) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<NotFoundException>()
                }
                // a missing unit is the same 404, and only a bad year is a 400 (it reveals no unit)
                fetch("/api/partg-report/export/pdf?year=$year", w.kwX).status shouldBe HttpStatusCode.NotFound
                fetch("/api/partg-report/export/xlsx?year=$year", w.kwX).status shouldBe HttpStatusCode.NotFound
                fetch("/api/partg-report/export/journal.csv?year=$year", w.kwX).status shouldBe HttpStatusCode.NotFound
                fetch("/api/partg-report/export/pdf?year=2000&scope=${w.unitY.key()}", w.kwX).status shouldBe HttpStatusCode.BadRequest
                fetch("/api/partg-report/export/journal.csv?year=abc&unit=${w.unitY.key()}", w.kwX).status shouldBe
                    HttpStatusCode.BadRequest
                // the own subordinate summary is theirs for the PDF; for the CSV it is a 400 (no bookings there, Q20)
                val sub = PartgUnit.Subordinate(w.x.toString()).key()
                fetch("/api/partg-report/export/pdf?year=$year&scope=$sub", w.kwX).status shouldBe HttpStatusCode.OK
                val subCsv = fetch("/api/partg-report/export/journal.csv?year=$year&unit=$sub", w.kwX)
                subCsv.status shouldBe HttpStatusCode.BadRequest
                subCsv.rejection() shouldBe PartgImportRejectionCode.INVALID_INPUT
                // no audit entry for any refusal
                exportAudits(w.kwX).size shouldBe 1
            }
        }

        test(
            "a revoked grant is effective at once; the kept versions answer a chapter treasurer with 403, not 404; a plain member gets 403",
        ) {
            val w = World()
            partgTestApplication(fixtures = fx, routes = routesWith()) {
                val key = w.unitX.key()
                fetch("/api/partg-report/export/pdf?year=$year&scope=$key", w.kwX).status shouldBe HttpStatusCode.OK
                listOf("pdf", "xlsx").forEach { format ->
                    val version = fetch("/api/partg-report/version/$format?id=${Uuid.random()}", w.kwX)
                    version.status shouldBe HttpStatusCode.Forbidden
                    version.rejection() shouldBe PartgImportRejectionCode.FORBIDDEN
                }
                fx.revokeTreasurer(w.grantX)
                listOf("pdf", "xlsx").forEach { format ->
                    val response = fetch("/api/partg-report/export/$format?year=$year&scope=$key", w.kwX)
                    response.status shouldBe HttpStatusCode.Forbidden
                    response.rejection() shouldBe PartgImportRejectionCode.FORBIDDEN
                }
                fetch("/api/partg-report/export/journal.csv?year=$year&unit=$key", w.kwX).status shouldBe HttpStatusCode.Forbidden
                attempt(member = w.kwX) {
                    fx.service(it).unitStatementPreview(year = year, unitKey = key)
                }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                fetch("/api/partg-report/export/pdf?year=$year&scope=$key", w.plain).rejection() shouldBe PartgImportRejectionCode.FORBIDDEN
                // the role is checked first, the organisation switch second
                fx.setParty(false)
                fetch("/api/partg-report/export/pdf?year=$year&scope=$key", w.plain).rejection() shouldBe PartgImportRejectionCode.FORBIDDEN
                val kwY = w.kwY
                fetch("/api/partg-report/export/pdf?year=$year&scope=${w.unitY.key()}", kwY).rejection() shouldBe
                    PartgImportRejectionCode.NOT_ENABLED
            }
        }

        test("the journal CSV of a chapter treasurer holds the posted entries of the year of that chapter only, in the documented format") {
            val w = World()
            partgTestApplication(fixtures = fx, routes = routesWith()) {
                seed(w)
                // one split entry (three postings): the counter-account stays empty
                call(member = w.admin) {
                    fx.accounting(it).postJournalEntry(
                        JournalEntryInput(
                            entryDate = day,
                            description = "Split Xaver",
                            voucherReference = "BX-S",
                            postings =
                                listOf(
                                    posting(w.bank, PostingSide.DEBIT, "100.00"),
                                    posting(w.income, PostingSide.CREDIT, "60.00"),
                                    posting(w.income2, PostingSide.CREDIT, "40.00"),
                                ),
                            regionalChapterId = w.x.toString(),
                        ),
                    )
                }
                val response = fetch("/api/partg-report/export/journal.csv?year=$year&unit=${w.unitX.key()}", w.kwX)
                response.status shouldBe HttpStatusCode.OK
                val text = String(response.readRawBytes(), Charsets.UTF_8)
                text.startsWith(bom) shouldBe true
                val lines = text.removePrefix(bom).split("\r\n").filter { it.isNotEmpty() }
                lines.first() shouldBe "Datum;Belegnummer;Buchungstext;Konto;Kontoname;Gegenkonto;Soll;Haben;Gliederung"
                // two simple entries (2 rows each) and the split (3 rows)
                lines.size shouldBe 1 + 4 + 3
                val body = lines.drop(1)
                body.forEach { it.split(";").size shouldBe 9 }
                body.forEach { it shouldContain "Land Xaver" }
                listOf("Ypsilon", "Beitrag Bund", "Entwurf", "Vorjahr", "Bundesebene").forEach { text shouldNotContain it }
                // the amount has a decimal comma and no thousands separator; the date is DD.MM.YYYY
                body.any { it.startsWith("01.03.$year;BX-1;Beitrag Xaver eins;") && it.contains(";1234,56;") } shouldBe true
                body.any { it.startsWith("02.04.$year;BX-2;") && it.contains(";5,00;") } shouldBe true
                // a simple entry shows the counter-account, the split entry does not
                val simpleRow = body.first { it.contains("BX-1") }.split(";")
                simpleRow[5].isNotEmpty() shouldBe true
                body.filter { it.contains("BX-S") }.forEach { it.split(";")[5] shouldBe "" }
                // debit rows have an amount in Soll and none in Haben, credit rows the other way round
                body.filter { it.contains("BX-S") }.map { it.split(";")[6] to it.split(";")[7] }.toSet() shouldBe
                    setOf("100,00" to "", "" to "60,00", "" to "40,00")
            }
        }

        test(
            "a federal role reads everything or one unit; the federal level shows as Bundesebene; rubbish is a 400, an unknown chapter a 404",
        ) {
            val w = World()
            partgTestApplication(fixtures = fx, routes = routesWith()) {
                seed(w)
                val all = String(fetch("/api/partg-report/export/journal.csv?year=$year", w.board).readRawBytes(), Charsets.UTF_8)
                listOf("Land Xaver", "Land Ypsilon", "Bundesebene").forEach { all shouldContain it }
                all shouldNotContain "Entwurf Xaver"
                val federal =
                    String(fetch("/api/partg-report/export/journal.csv?year=$year&unit=FEDERAL", w.admin).readRawBytes(), Charsets.UTF_8)
                federal shouldContain "Beitrag Bund"
                federal shouldNotContain "Land Xaver"
                val onlyY =
                    String(
                        fetch("/api/partg-report/export/journal.csv?year=$year&unit=${w.unitY.key()}", w.board).readRawBytes(),
                        Charsets.UTF_8,
                    )
                onlyY shouldContain "Beitrag Ypsilon geheim"
                onlyY shouldNotContain "Land Xaver"
                fetch("/api/partg-report/export/journal.csv?year=$year&unit=SUB:${w.y}", w.board).status shouldBe HttpStatusCode.BadRequest
                fetch("/api/partg-report/export/journal.csv?year=$year&unit=nonsense", w.board).status shouldBe HttpStatusCode.BadRequest
                fetch("/api/partg-report/export/journal.csv?year=$year&unit=STATE:${Uuid.random()}", w.board).status shouldBe
                    HttpStatusCode.NotFound
                // the federal exports still return the full report (the unit part is for the chapter treasurer only)
                val full =
                    pdfText(
                        fetch("/api/partg-report/export/pdf?year=$year&scope=${w.unitX.key()}", w.board).readRawBytes(),
                    ).joinToString("\n")
                full shouldContain "Gesonderter Ausweis"
                full shouldNotContain "Teilbericht"
                // a federal PDF of the whole party is allowed for the federal role and its audit entry has no chapter fields
                fetch("/api/partg-report/export/pdf?year=$year&scope=ALL", w.board).status shouldBe HttpStatusCode.OK
                exportAudits(w.board).filter { it.contains("\"PDF\"") }.forEach {
                    it shouldNotContain "chapterScoped"
                    it shouldNotContain "rowCount"
                }
            }
        }

        test("CSV injection: a formula start is guarded and quoted, a separator or quote is quoted") {
            val w = World()
            partgTestApplication(fixtures = fx, routes = routesWith()) {
                val descriptions = listOf("=HYPERLINK(\"http://x\")", "+1", "-2", "@x", "a;b \"c\"")
                descriptions.forEachIndexed { i, text ->
                    call(member = w.admin) { fx.accounting(it).postJournalEntry(simple(w, w.x, text, "INJ-$i", "1.00")) }
                }
                val text =
                    String(
                        fetch("/api/partg-report/export/journal.csv?year=$year&unit=${w.unitX.key()}", w.kwX).readRawBytes(),
                        Charsets.UTF_8,
                    )
                text shouldContain "\"'=HYPERLINK(\"\"http://x\"\")\""
                text shouldContain "\"'+1\""
                text shouldContain "\"'-2\""
                text shouldContain "\"'@x\""
                text shouldContain "\"a;b \"\"c\"\"\""
                // amounts are never negative, so the minus guard does not touch them
                text
                    .lines()
                    .drop(1)
                    .filter { it.isNotEmpty() }
                    .forEach { line -> line shouldNotContain ";'-" }
            }
        }

        test(
            "one audit entry per export with counters only; the unit part is marked chapter-scoped; no name and no amount in the payload",
        ) {
            val w = World()
            partgTestApplication(fixtures = fx, routes = routesWith()) {
                seed(w)
                val key = w.unitX.key()
                fetch("/api/partg-report/export/pdf?year=$year&scope=$key", w.kwX).status shouldBe HttpStatusCode.OK
                fetch("/api/partg-report/export/xlsx?year=$year&scope=$key", w.kwX).status shouldBe HttpStatusCode.OK
                fetch("/api/partg-report/export/journal.csv?year=$year&unit=$key", w.kwX).status shouldBe HttpStatusCode.OK
                val audits = exportAudits(w.kwX)
                audits.size shouldBe 3
                audits.forEach { after ->
                    listOf("Xaver", "Ypsilon", "98765432", "7777777", "BX-").forEach { after shouldNotContain it }
                    val snapshot = json.decodeFromString(PartgReportExportAuditSnapshot.serializer(), after)
                    snapshot.chapterScoped shouldBe true
                    snapshot.scope shouldBe key
                }
                val snapshots = audits.map { json.decodeFromString(PartgReportExportAuditSnapshot.serializer(), it) }
                snapshots.map { it.format }.toSet() shouldBe setOf(PartgReportFormat.PDF, PartgReportFormat.XLSX, PartgReportFormat.CSV)
                snapshots.single { it.format == PartgReportFormat.CSV }.rowCount shouldBe 4
                snapshots.single { it.format == PartgReportFormat.PDF }.donorRowCount shouldBe 0
            }
        }

        test("the preview and the export come from one source; the hints are only P05, P14, P15 and P16 and nothing of the consolidation") {
            val w = World()
            partgTestApplication(fixtures = fx, routes = routesWith()) {
                seed(w)
                val preview = call(member = w.kwX) { fx.service(it).unitStatementPreview(year = year, unitKey = w.unitX.key()) }
                preview.unit.unitKey shouldBe w.unitX.key()
                preview.current.getValue("E01") shouldBe PartgCell.Value(100_000L)
                preview.missingCurrent.size shouldBe 34
                preview.postedEntryCount shouldBe 2
                preview.findings.forEach { (it.code in PartgUnitPart.ALLOWED_FINDINGS) shouldBe true }
                // the XLSX of the same snapshot carries the same value and the same count of missing positions
                val xlsx = zipText(fetch("/api/partg-report/export/xlsx?year=$year&scope=${w.unitX.key()}", w.kwX).readRawBytes())
                xlsx.getValue("xl/worksheets/sheet3.xml") shouldContain "<v>1000.00</v>"
                xlsx.getValue("xl/worksheets/sheet1.xml") shouldContain ">34<"
                val xlsxText = xlsx.values.joinToString("\n")
                PartgFindingCode.entries.filter { it !in PartgUnitPart.ALLOWED_FINDINGS }.forEach { code ->
                    xlsxText shouldNotContain "${code.name} ·"
                }
                // a federal role may ask for any unit; a malformed key is then an input error, not a 404
                call(member = w.board) { fx.service(it).unitStatementPreview(year = year, unitKey = w.unitY.key()) }.unit.unitKey shouldBe
                    w.unitY.key()
                attempt(member = w.board) { fx.service(it).unitStatementPreview(year = year, unitKey = "nonsense") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<network.lapis.cloud.shared.rpc.PartyReportInvalidInputException>()
            }
        }

        test("the allowed hints of the unit part are exactly the four bookings and variance hints") {
            PartgUnitPart.ALLOWED_FINDINGS shouldBe
                setOf(PartgFindingCode.P05, PartgFindingCode.P14, PartgFindingCode.P15, PartgFindingCode.P16)
        }

        test("the rate limit and the permit also apply to the CSV, and a journal beyond the cap is a 413") {
            val w = World()
            partgTestApplication(
                fixtures = fx,
                routes = routesWith(limiter = FederationInboxRateLimiter(maxRequests = 3, window = 1.minutes), maxPostings = 2),
            ) {
                call(member = w.admin) { fx.accounting(it).postJournalEntry(simple(w, w.x, "Eins", "L-1")) }
                call(member = w.admin) { fx.accounting(it).postJournalEntry(simple(w, w.x, "Zwei", "L-2")) }
                val key = w.unitX.key()
                // four postings, cap 2
                val tooLarge = fetch("/api/partg-report/export/journal.csv?year=$year&unit=$key", w.kwX)
                tooLarge.status shouldBe HttpStatusCode.PayloadTooLarge
                tooLarge.rejection() shouldBe PartgImportRejectionCode.TOO_LARGE
                fetch("/api/partg-report/export/journal.csv?year=$year&unit=$key", w.kwX)
                fetch("/api/partg-report/export/journal.csv?year=$year&unit=$key", w.kwX)
                fetch("/api/partg-report/export/journal.csv?year=$year&unit=$key", w.kwX).status shouldBe HttpStatusCode.TooManyRequests
            }
            val w2 = World()
            val semaphore = Semaphore(1)
            partgTestApplication(fixtures = fx, routes = routesWith(semaphore = semaphore)) {
                semaphore.acquire()
                fetch("/api/partg-report/export/journal.csv?year=$year&unit=${w2.unitX.key()}", w2.kwX).status shouldBe
                    HttpStatusCode.TooManyRequests
                semaphore.release()
                fetch("/api/partg-report/export/journal.csv?year=$year&unit=${w2.unitX.key()}", w2.kwX).status shouldBe HttpStatusCode.OK
                semaphore.availablePermits shouldBe 1
            }
        }

        test("the name guess is for the federal role only and only for an unmapped account") {
            val w = World()
            val personal = fx.ledgerAccount(type = LedgerAccountType.EXPENSE, name = "Personal")
            val donations = fx.ledgerAccount(type = LedgerAccountType.INCOME, name = "Spenden")
            partgTestApplication(fixtures = fx) {
                val federal = call(member = w.board) { fx.service(it).getAccountMappings(year = year, unitKey = "FEDERAL") }
                federal.rows.single { it.accountId == personal.toString() }.let {
                    it.nameSuggestion shouldBe "A01"
                    it.nameSuggestionKeyword shouldBe "personal"
                }
                federal.rows.single { it.accountId == donations.toString() }.nameSuggestion shouldBe null
                // a chapter treasurer reads the same view without any guess
                val chapter = call(member = w.kwX) { fx.service(it).getAccountMappings(year = year, unitKey = w.unitX.key()) }
                chapter.editable shouldBe false
                chapter.rows.all { it.nameSuggestion == null && it.nameSuggestionKeyword == null } shouldBe true
                // once mapped, the account is no longer guessed
                call(member = w.admin) {
                    fx.service(it).saveAccountMappings(
                        year = year,
                        unitKey = "FEDERAL",
                        changes =
                            listOf(
                                network.lapis.cloud.shared.domain.PartgMappingChangeDto(
                                    accountId = personal.toString(),
                                    expectedMapped = false,
                                    newPositionCode = "A01",
                                ),
                            ),
                    )
                }
                val after = call(member = w.board) { fx.service(it).getAccountMappings(year = year, unitKey = "FEDERAL") }
                after.rows.single { it.accountId == personal.toString() }.nameSuggestion shouldBe null
            }
        }
    })
