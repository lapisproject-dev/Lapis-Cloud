package network.lapis.cloud.server.accounting.datev

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.routes.registerDatevRoutes
import network.lapis.cloud.server.rpc.AccountingService
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.5.2 "DATEV-Format-Export" -- DB-backed coverage of both
 * `AccountingService.previewDatevExport` and `network.lapis.cloud.server.routes.registerDatevRoutes`'s
 * binary route, in ONE spec because their whole point (test 16 below) is that they can never
 * diverge -- both call the exact same `buildDatevExportRequest` + `DatevBuchungsstapelWriter.plan`.
 * Pure formatting/blocker-derivation rules are covered DB-free in [DatevBuchungsstapelWriterTest].
 */
class DatevIntegrationTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdJournalEntryIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                if (createdJournalEntryIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.journalEntryId inList createdJournalEntryIds }
                    JournalEntryTable.deleteWhere { JournalEntryTable.id inList createdJournalEntryIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[datevBeraterNummer] = null
                    it[datevMandantNummer] = null
                }
            }
            createdMemberIds.clear()
            createdLedgerAccountIds.clear()
            createdJournalEntryIds.clear()
        }

        fun createMember(
            email: String,
            role: AccountRole,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Datev-Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createLedgerAccount(number: String): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Testkonto $number"
                    it[accountClass] = 0
                    it[type] = LedgerAccountType.ASSET
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        /**
         * Security finding fix (feature/multi-agent-pipeline-v1-4-5-2-bank-buchhaltungs-integration-d,
         * MINOR): every OTHER account number fixture in this file uses a `"DV"`/`"D"`/`"C"`-prefixed
         * hex suffix (e.g. `"DV${Uuid.random()...}"`) purely for cross-test collision avoidance --
         * fine for the tests that exercise `buildDatevExportRequest` directly (never call `plan()`,
         * see the multi-chunk test's own KDoc), but `DatevBuchungsstapelWriter.plan`'s NEW
         * `ACCOUNT_NUMBER_CONTAINS_INVALID_CHARACTERS` blocker (see that object's `plan` KDoc) now
         * correctly rejects any such letter-containing account number as unexportable. Tests that
         * expect a REAL exportable file (HTTP 200, `exportable == true`) need an account number
         * shaped like a real DATEV Sachkonto -- digits only -- so this generates one, with enough
         * entropy (9,000 possibilities) that two calls colliding within one test run is negligible.
         */
        fun randomNumericAccountNumber(): String = (1000..9999).random().toString()

        fun postEntry(
            date: LocalDate,
            debitAccountId: Uuid,
            creditAccountId: Uuid,
            amount: BigDecimal,
            createdBy: Uuid,
            description: String = "Testbuchung",
            voucherReference: String? = "BELEG-1",
            status: JournalEntryStatus = JournalEntryStatus.POSTED,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                JournalEntryTable.insert {
                    it[JournalEntryTable.id] = id
                    it[entryDate] = date
                    it[JournalEntryTable.description] = description
                    it[JournalEntryTable.voucherReference] = voucherReference
                    it[JournalEntryTable.createdBy] = createdBy
                    it[JournalEntryTable.status] = status
                    it[postedAt] =
                        if (status == JournalEntryStatus.POSTED) LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, 9, 0) else null
                    it[createdAt] = LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, 8, 0)
                }
                PostingTable.insert {
                    it[PostingTable.id] = Uuid.random()
                    it[journalEntryId] = id
                    it[ledgerAccountId] = debitAccountId
                    it[side] = PostingSide.DEBIT
                    it[PostingTable.amount] = amount
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[costCenterId] = null
                }
                PostingTable.insert {
                    it[PostingTable.id] = Uuid.random()
                    it[journalEntryId] = id
                    it[ledgerAccountId] = creditAccountId
                    it[side] = PostingSide.CREDIT
                    it[PostingTable.amount] = amount
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[costCenterId] = null
                }
            }
            createdJournalEntryIds += id
            return id
        }

        fun configureBeraterMandant(
            berater: Int?,
            mandant: Int?,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[datevBeraterNummer] = berater
                    it[datevMandantNummer] = mandant
                }
            }
        }

        fun Route.registerPreviewTestRoute() {
            get("/test/preview-datev") {
                val service = AccountingService(call)
                val q = call.request.queryParameters
                val dto = service.previewDatevExport(from = LocalDate.parse(q["from"]!!), to = LocalDate.parse(q["to"]!!))
                call.respondText("${dto.entryCount}:${dto.rowCount}:${dto.exportable}:${dto.blockers.size}")
            }
        }

        test("previewDatevExport: BOARD gets 200, MEMBER gets 403") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing { registerPreviewTestRoute() }
                }
                val board = createMember("datev-preview-board@example.org", AccountRole.BOARD)
                val member = createMember("datev-preview-member@example.org", AccountRole.MEMBER)

                client
                    .get("/test/preview-datev?from=2026-05-01&to=2026-05-31") {
                        header("X-Member-Id", member.toString())
                    }.status shouldBe HttpStatusCode.Forbidden

                client
                    .get("/test/preview-datev?from=2026-05-01&to=2026-05-31") {
                        header("X-Member-Id", board.toString())
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test("buchungsstapel.csv: TREASURER/ADMIN allowed, BOARD gets 403") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing { registerDatevRoutes(exportRateLimiter = FederationInboxRateLimiter()) }
                }
                val treasurer = createMember("datev-file-treasurer@example.org", AccountRole.TREASURER)
                val board = createMember("datev-file-board@example.org", AccountRole.BOARD)
                val debit = createLedgerAccount(randomNumericAccountNumber())
                val credit = createLedgerAccount(randomNumericAccountNumber())
                postEntry(LocalDate(2026, 5, 10), debit, credit, BigDecimal("24.95"), treasurer)
                configureBeraterMandant(1001, 1)

                client
                    .get("/api/accounting/datev/buchungsstapel.csv?from=2026-05-01&to=2026-05-31") {
                        header("X-Member-Id", board.toString())
                    }.status shouldBe HttpStatusCode.Forbidden

                client
                    .get("/api/accounting/datev/buchungsstapel.csv?from=2026-05-01&to=2026-05-31") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test("buchungsstapel.csv: 409 with no file when Berater/Mandant is not configured") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing { registerDatevRoutes(exportRateLimiter = FederationInboxRateLimiter()) }
                }
                val treasurer = createMember("datev-409-treasurer@example.org", AccountRole.TREASURER)
                val debit = createLedgerAccount("DV${Uuid.random().toString().take(4)}")
                val credit = createLedgerAccount("DV${Uuid.random().toString().take(4)}")
                postEntry(LocalDate(2026, 5, 10), debit, credit, BigDecimal("10.00"), treasurer)
                // Berater/Mandant left unconfigured (afterEach resets to null anyway).

                val response =
                    client.get("/api/accounting/datev/buchungsstapel.csv?from=2026-05-01&to=2026-05-31") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldContain "BERATER_MANDANT_NOT_CONFIGURED"
            }
        }

        test("malformed/missing from-to query params -> 400") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing { registerDatevRoutes(exportRateLimiter = FederationInboxRateLimiter()) }
                }
                val treasurer = createMember("datev-400-treasurer@example.org", AccountRole.TREASURER)
                client
                    .get("/api/accounting/datev/buchungsstapel.csv") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .get("/api/accounting/datev/buchungsstapel.csv?from=not-a-date&to=2026-05-31") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .get("/api/accounting/datev/buchungsstapel.csv?from=2026-05-31&to=2026-05-01") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("preview.rowCount matches the real file's data-line count exactly -- the anti-divergence guard") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing {
                        registerPreviewTestRoute()
                        registerDatevRoutes(exportRateLimiter = FederationInboxRateLimiter())
                    }
                }
                val treasurer = createMember("datev-parity-treasurer@example.org", AccountRole.TREASURER)
                val debit = createLedgerAccount(randomNumericAccountNumber())
                val credit = createLedgerAccount(randomNumericAccountNumber())
                postEntry(LocalDate(2026, 6, 5), debit, credit, BigDecimal("42.00"), treasurer)
                postEntry(LocalDate(2026, 6, 20), debit, credit, BigDecimal("13.50"), treasurer)
                configureBeraterMandant(1001, 1)

                val previewBody =
                    client
                        .get("/test/preview-datev?from=2026-06-01&to=2026-06-30") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                val (entryCount, rowCount, exportable, blockerCount) = previewBody.split(":")
                exportable shouldBe "true"
                blockerCount shouldBe "0"
                entryCount shouldBe "2"
                rowCount shouldBe "2"

                val fileBytes =
                    client
                        .get("/api/accounting/datev/buchungsstapel.csv?from=2026-06-01&to=2026-06-30") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsBytes()
                val lines = String(fileBytes, DatevCharacterSet.CP1252).split("\r\n").filter { it.isNotEmpty() }
                // lines[0] = format header, lines[1] = column-name header, everything after = data rows
                val fileDataLineCount = lines.size - 2
                fileDataLineCount.toString() shouldBe rowCount
            }
        }

        // Review-Runde finding (2026-09, MAJOR): `buildDatevExportRequest`'s single unbatched
        // `PostingTable.journalEntryId inList entryIds` query would exceed PostgreSQL's 65 535
        // bound-parameter limit for a large period -- crashing with a raw PSQLException/HTTP 500
        // BEFORE `DatevBuchungsstapelWriter.plan`'s own `TOO_MANY_ROWS` blocker ever gets a chance
        // to run. [POSTING_QUERY_CHUNK_SIZE] batches that query; this test drives the multi-chunk
        // path with `postingQueryChunkSize = 2` over 5 entries (3 chunks: 2/2/1) instead of needing
        // tens of thousands of real rows, and pins that postings never cross-contaminate between
        // chunks (each entry keeps exactly its OWN, distinctly-numbered accounts and amount).
        test("buildDatevExportRequest: postings are correct across a multi-chunk inList batch, none lost or swapped") {
            val treasurer = createMember("datev-chunk-treasurer@example.org", AccountRole.TREASURER)
            val entryCount = 5

            // Distinctly-numbered per entry (own debit AND credit account) so a posting that leaked
            // into the WRONG entry across a chunk boundary would show up as an account-number
            // mismatch rather than accidentally still looking correct.
            data class Fixture(
                val debitAccountNumber: String,
                val creditAccountNumber: String,
            )
            val fixtures =
                (0 until entryCount).map { i ->
                    // Random suffix (same collision-avoidance idiom as this file's other tests'
                    // "DV${Uuid.random()...}" account numbers) -- the leading D/C letter alone is
                    // NOT what distinguishes entries here, only the fixtures list index is. `take(4)`
                    // (not `take(3)`, Review Runde 4 MINOR): with 5 debit + 5 credit accounts alive at
                    // once, three hex chars (4096 possibilities) gave a ~0.49% chance per run of two
                    // accounts colliding on `uq_ledger_account_number` and flaking this test with a
                    // raw `ExposedSQLException` -- four hex chars (65 536 possibilities) makes that
                    // negligible, same length this file's OTHER `DV${...take(4)}` accounts already use.
                    // This test never calls `plan()`, so `VALID_ACCOUNT_LENGTH_RANGE` is irrelevant here.
                    val debitNumber = "D${Uuid.random().toString().take(4)}"
                    val creditNumber = "C${Uuid.random().toString().take(4)}"
                    val debit = createLedgerAccount(debitNumber)
                    val credit = createLedgerAccount(creditNumber)
                    postEntry(
                        date = LocalDate(2026, 8, 10),
                        debitAccountId = debit,
                        creditAccountId = credit,
                        amount = BigDecimal(i + 1).setScale(2),
                        createdBy = treasurer,
                        description = "Chunk-Test $i",
                        voucherReference = "CHUNK-$i",
                    )
                    Fixture(debitAccountNumber = debitNumber, creditAccountNumber = creditNumber)
                }

            val request =
                transaction {
                    buildDatevExportRequest(
                        from = LocalDate(2026, 8, 1),
                        to = LocalDate(2026, 8, 31),
                        exportedBy = "Test",
                        postingQueryChunkSize = 2,
                    )
                }

            request.entries.size shouldBe entryCount
            request.entries.forEach { e ->
                val i = e.voucherReference!!.removePrefix("CHUNK-").toInt()
                val fixture = fixtures[i]
                e.postings.size shouldBe 2
                e.postings.single { it.side == PostingSide.DEBIT }.accountNumber shouldBe fixture.debitAccountNumber
                e.postings.single { it.side == PostingSide.CREDIT }.accountNumber shouldBe fixture.creditAccountNumber
                e.postings.single { it.side == PostingSide.DEBIT }.amount shouldBe BigDecimal(i + 1).setScale(2)
            }
        }

        // Review-Runde finding (2026-09): the download route's rate limiter (DatevRoutes.kt) had
        // zero test coverage for its 429 branch. `maxRequests = 1` makes the SECOND request in the
        // same test observe the limit without needing 60 real requests.
        test("buchungsstapel.csv: the second request within the window gets 429 once the rate limiter's cap is exhausted") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing { registerDatevRoutes(exportRateLimiter = FederationInboxRateLimiter(maxRequests = 1)) }
                }
                val treasurer = createMember("datev-ratelimit-treasurer@example.org", AccountRole.TREASURER)
                val debit = createLedgerAccount(randomNumericAccountNumber())
                val credit = createLedgerAccount(randomNumericAccountNumber())
                postEntry(LocalDate(2026, 7, 10), debit, credit, BigDecimal("5.00"), treasurer)
                configureBeraterMandant(1001, 1)

                val first =
                    client.get("/api/accounting/datev/buchungsstapel.csv?from=2026-07-01&to=2026-07-31") {
                        header("X-Member-Id", treasurer.toString())
                    }
                first.status shouldBe HttpStatusCode.OK

                val second =
                    client.get("/api/accounting/datev/buchungsstapel.csv?from=2026-07-01&to=2026-07-31") {
                        header("X-Member-Id", treasurer.toString())
                    }
                second.status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        // Security finding fix (feature/multi-agent-pipeline-v1-4-5-2-bank-buchhaltungs-integration-d,
        // MINOR, DoS/Heap): `buildDatevExportRequest`'s entry-count cap bounds the number of
        // journal ENTRIES, never the number of POSTINGS a single Sammelbuchung entry can carry --
        // this drives that with ONE entry carrying six postings (five debit accounts, one credit
        // account) and a deliberately tiny `maxTotalPostings` so the test does not need to insert
        // hundreds of thousands of real rows to exercise the guard.
        test("buildDatevExportRequest: a single entry's total postings exceeding maxTotalPostings throws, refusing the whole export") {
            val treasurer = createMember("datev-postingcap-treasurer@example.org", AccountRole.TREASURER)
            val creditAccount = createLedgerAccount("DV${Uuid.random().toString().take(4)}")
            val debitAccounts = (1..5).map { createLedgerAccount("DV${Uuid.random().toString().take(4)}") }
            val entryId = Uuid.random()
            transaction {
                JournalEntryTable.insert {
                    it[JournalEntryTable.id] = entryId
                    it[entryDate] = LocalDate(2026, 9, 10)
                    it[JournalEntryTable.description] = "Sammelbuchung mit vielen Postings"
                    it[JournalEntryTable.voucherReference] = "CAP-1"
                    it[JournalEntryTable.createdBy] = treasurer
                    it[JournalEntryTable.status] = JournalEntryStatus.POSTED
                    it[postedAt] = LocalDateTime(2026, 9, 10, 9, 0)
                    it[createdAt] = LocalDateTime(2026, 9, 10, 8, 0)
                }
                debitAccounts.forEach { debitAccountId ->
                    PostingTable.insert {
                        it[PostingTable.id] = Uuid.random()
                        it[journalEntryId] = entryId
                        it[ledgerAccountId] = debitAccountId
                        it[side] = PostingSide.DEBIT
                        it[PostingTable.amount] = BigDecimal("1.00")
                        it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                        it[costCenterId] = null
                    }
                }
                PostingTable.insert {
                    it[PostingTable.id] = Uuid.random()
                    it[journalEntryId] = entryId
                    it[ledgerAccountId] = creditAccount
                    it[side] = PostingSide.CREDIT
                    it[PostingTable.amount] = BigDecimal("5.00")
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[costCenterId] = null
                }
            }
            createdJournalEntryIds += entryId

            var threw = false
            try {
                transaction {
                    buildDatevExportRequest(
                        from = LocalDate(2026, 9, 1),
                        to = LocalDate(2026, 9, 30),
                        exportedBy = "Test",
                        maxTotalPostings = 3,
                    )
                }
            } catch (e: ConflictException) {
                threw = true
            }
            threw shouldBe true

            // Same six postings, but a cap comfortably above the true total succeeds and loads
            // every single one -- the guard must never truncate a legitimate, merely-large entry.
            val request =
                transaction {
                    buildDatevExportRequest(
                        from = LocalDate(2026, 9, 1),
                        to = LocalDate(2026, 9, 30),
                        exportedBy = "Test",
                        maxTotalPostings = 100,
                    )
                }
            request.entries
                .single()
                .postings.size shouldBe 6
        }
    })
