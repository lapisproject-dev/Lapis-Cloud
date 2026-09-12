package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** Seeded BOARD account -- same id `DevSeedData` uses everywhere else in this package's tests. */
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"

/**
 * Welle V1.4.11 "Reisekostenabrechnung" -- happy paths, rounding (HALF_UP), rate-snapshot freeze,
 * and submit-time validation for [TravelExpenseService]. Mirrors [ContributionReliefRequestTest]'s
 * house style (throwaway routes calling the service class directly).
 */
class TravelExpenseReportTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        fun setRates(
            mileage: BigDecimal?,
            perDiem: BigDecimal?,
        ) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[travelMileageRatePerKm] = mileage
                    it[travelPerDiemRate] = perDiem
                }
            }
        }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            setRates(mileage = null, perDiem = null)
            transaction {
                val reportIds =
                    if (createdMemberIds.isEmpty()) {
                        emptyList()
                    } else {
                        TravelExpenseReportTable
                            .selectAll()
                            .where { TravelExpenseReportTable.subjectMemberId inList createdMemberIds }
                            .map { it[TravelExpenseReportTable.id] }
                    }
                TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId inList reportIds }
                TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList reportIds }
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                    it[actorMemberId] = null
                }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newMember(status: MemberStatus = MemberStatus.ACTIVE): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Travel-Expense Testmitglied"
                    it[email] = "travel-expense-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        test("MILEAGE happy path: 120km * 0.30 = 36.00, rateSnapshot frozen, submit REQUESTED") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                setRates(mileage = BigDecimal("0.3000"), perDiem = BigDecimal("14.00"))
                val memberId = newMember()

                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Konferenz&from=2026-01-10&to=2026-01-10") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]

                val afterLine =
                    client
                        .post("/test/travelexpense/addline?reportId=$draftId&kind=MILEAGE&description=Anfahrt&kilometers=120") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")
                afterLine[6] shouldBe "36.00" // totalAmount

                val submitted =
                    client.post("/test/travelexpense/submit?id=$draftId") { header("X-Member-Id", memberId.toString()) }.bodyAsText().split(
                        "|",
                    )
                submitted[2] shouldBe TravelExpenseReportStatus.REQUESTED.name
                submitted[6] shouldBe "36.00"
            }
        }

        test("PER_DIEM happy path: 3 days * 14.00 = 42.00") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                setRates(mileage = BigDecimal("0.3000"), perDiem = BigDecimal("14.00"))
                val memberId = newMember()

                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Klausur&from=2026-02-01&to=2026-02-03") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]

                val afterLine =
                    client
                        .post("/test/travelexpense/addline?reportId=$draftId&kind=PER_DIEM&description=Verpflegung&days=3") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")
                afterLine[6] shouldBe "42.00"
            }
        }

        test("RECEIPTED happy path: amount passes through, submit requires a receipt") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val memberId = newMember()
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Seminar&from=2026-03-01&to=2026-03-01") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]
                client
                    .post("/test/travelexpense/addline?reportId=$draftId&kind=RECEIPTED&description=Bahnticket&amount=87.40") {
                        header("X-Member-Id", memberId.toString())
                    }.bodyAsText()
                    .split("|")[6] shouldBe "87.40"

                // No receipt attached yet -> submit must fail.
                val response =
                    client.post("/test/travelexpense/submit?id=$draftId") { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("rounding: 123.45 km * 0.3012 rounds HALF_UP to 37.18") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                setRates(mileage = BigDecimal("0.3012"), perDiem = null)
                val memberId = newMember()
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Fahrt&from=2026-04-01&to=2026-04-01") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]
                val afterLine =
                    client
                        .post("/test/travelexpense/addline?reportId=$draftId&kind=MILEAGE&description=Anfahrt&kilometers=123.45") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")
                afterLine[6] shouldBe "37.18"
            }
        }

        test("rate snapshot: submit freezes the rate, a later rate change does not retroactively change an already-submitted report") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                setRates(mileage = BigDecimal("0.3000"), perDiem = null)
                val memberId = newMember()
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Fahrt&from=2026-05-01&to=2026-05-01") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]
                client.post("/test/travelexpense/addline?reportId=$draftId&kind=MILEAGE&description=Anfahrt&kilometers=100") {
                    header("X-Member-Id", memberId.toString())
                }
                client.post("/test/travelexpense/submit?id=$draftId") { header("X-Member-Id", memberId.toString()) }

                setRates(mileage = BigDecimal("0.3800"), perDiem = null)

                val mine =
                    client.get("/test/travelexpense/mine") { header("X-Member-Id", memberId.toString()) }.bodyAsText()
                mine.split(";").single { it.contains(draftId) }.split("|")[6] shouldBe "30.00"
            }
        }

        test(
            "review MAJOR fix: rate changed to an excessive value between addLine and submit -> submit " +
                "rejects with BadRequest instead of freezing an amount beyond MAX_LINE_AMOUNT",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                setRates(mileage = BigDecimal("0.3000"), perDiem = null)
                val memberId = newMember()
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Fahrt&from=2026-07-01&to=2026-07-01") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]
                // 10,000 km (MAX_KILOMETERS) * 0.30 = 3,000.00 -- well within MAX_LINE_AMOUNT (100,000)
                // at addLine time, so validateLine's own requireLineAmountInRange lets it through.
                client.post("/test/travelexpense/addline?reportId=$draftId&kind=MILEAGE&description=Anfahrt&kilometers=10000") {
                    header("X-Member-Id", memberId.toString())
                }

                // ADMIN mistypes the kilometer rate before the member submits -- 10,000 km * 99.9999
                // = 999,999.00, ten times MAX_LINE_AMOUNT.
                setRates(mileage = BigDecimal("99.9999"), perDiem = null)

                val response =
                    client.post("/test/travelexpense/submit?id=$draftId") { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest

                val lineRow =
                    transaction {
                        TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq Uuid.parse(draftId) }.single()
                    }
                // The failed submit must not have partially frozen the line at the excessive amount.
                lineRow[TravelExpenseLineTable.amount] shouldBe BigDecimal("3000.00")
                transaction {
                    TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq Uuid.parse(draftId) }.single()[
                        TravelExpenseReportTable.status,
                    ]
                } shouldBe TravelExpenseReportStatus.DRAFT
            }
        }

        test("amount supplied for MILEAGE/PER_DIEM is rejected -- server computes it") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                setRates(mileage = BigDecimal("0.3000"), perDiem = null)
                val memberId = newMember()
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Fahrt&from=2026-06-01&to=2026-06-01") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]
                val response =
                    client.post("/test/travelexpense/addline?reportId=$draftId&kind=MILEAGE&description=Anfahrt&kilometers=10&amount=99") {
                        header("X-Member-Id", memberId.toString())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("addLine(MILEAGE) without a configured rate -> BadRequestException, not a raw SQL error") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                setRates(mileage = null, perDiem = null)
                val memberId = newMember()
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Fahrt&from=2026-06-01&to=2026-06-01") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]
                val response =
                    client.post("/test/travelexpense/addline?reportId=$draftId&kind=MILEAGE&description=Anfahrt&kilometers=10") {
                        header("X-Member-Id", memberId.toString())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("submitReport with no line -> BadRequestException") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val memberId = newMember()
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Fahrt&from=2026-06-01&to=2026-06-01") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]
                client
                    .post("/test/travelexpense/submit?id=$draftId") {
                        header("X-Member-Id", memberId.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("travelTo in the future -> BadRequestException at submit time") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                setRates(mileage = BigDecimal("0.3000"), perDiem = null)
                val memberId = newMember()
                val future = DbClock.nowLocalDateTime().date.plus(30, DateTimeUnit.DAY)
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Fahrt&from=$future&to=$future") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]
                client.post("/test/travelexpense/addline?reportId=$draftId&kind=MILEAGE&description=Anfahrt&kilometers=10") {
                    header("X-Member-Id", memberId.toString())
                }
                client
                    .post("/test/travelexpense/submit?id=$draftId") {
                        header("X-Member-Id", memberId.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("PER_DIEM days exceeding the travel span -> BadRequestException") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                setRates(mileage = null, perDiem = BigDecimal("14.00"))
                val memberId = newMember()
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Reise&from=2026-07-01&to=2026-07-03") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]
                val response =
                    client.post("/test/travelexpense/addline?reportId=$draftId&kind=PER_DIEM&description=Verpflegung&days=10") {
                        header("X-Member-Id", memberId.toString())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("11th open draft for the same member -> ConflictException") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val memberId = newMember()
                repeat(10) { i ->
                    client.post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Reise$i&from=2026-08-01&to=2026-08-01") {
                        header("X-Member-Id", memberId.toString())
                    }
                }
                val response =
                    client.post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Reise11&from=2026-08-01&to=2026-08-01") {
                        header("X-Member-Id", memberId.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("kilometers = 0 is rejected (V-3 / strictly positive posting amount)") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                setRates(mileage = BigDecimal("0.3000"), perDiem = null)
                val memberId = newMember()
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Fahrt&from=2026-09-01&to=2026-09-01") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]
                val response =
                    client.post("/test/travelexpense/addline?reportId=$draftId&kind=MILEAGE&description=Anfahrt&kilometers=0") {
                        header("X-Member-Id", memberId.toString())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("purpose over 200 characters is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val memberId = newMember()
                val longPurpose = "x".repeat(201)
                val response =
                    client.post(
                        "/test/travelexpense/create?subjectMemberId=$memberId&purpose=$longPurpose&from=2026-09-01&to=2026-09-01",
                    ) { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "review MINOR fix (test coverage): listReports caps at MAX_LIST_RESULTS per page but the " +
                "afterSubmittedAt/afterId keyset cursor reaches every row -- mirrors " +
                "ContributionReliefRequestTest's identical pagination test for listReliefRequests",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val memberId = newMember()

                // MAX_LIST_RESULTS (200) + 1 rows, all REQUESTED, inserted directly (bypassing the
                // service) so `submittedAt` can be pinned precisely -- one minute-granularity slot
                // per row, strictly increasing and collision-free for i in 0..200, all dated 2020
                // so they sort before every other REQUESTED row this shared spec's other tests may
                // have left behind.
                val rowCount = 201
                val ids = List(rowCount) { Uuid.random() }
                try {
                    transaction {
                        ids.forEachIndexed { i, id ->
                            TravelExpenseReportTable.insert {
                                it[TravelExpenseReportTable.id] = id
                                it[subjectMemberId] = memberId
                                it[status] = TravelExpenseReportStatus.REQUESTED
                                it[purpose] = "Pagination-Testfahrt"
                                it[travelFrom] = LocalDate(2020, 1, 1)
                                it[travelTo] = LocalDate(2020, 1, 1)
                                it[totalAmount] = BigDecimal("10.00")
                                it[createdAt] = LocalDateTime(2020, 1, 1, i / 60, i % 60, 0)
                                it[requestedBy] = memberId
                                it[submittedAt] = LocalDateTime(2020, 1, 1, i / 60, i % 60, 0)
                            }
                        }
                    }

                    val page1 =
                        client
                            .get("/test/travelexpense/list?status=REQUESTED") { header("X-Member-Id", BOARD_ID) }
                            .bodyAsText()
                            .split(";")
                    val page1Ids = page1.map { it.split("|")[0] }

                    page1Ids.size shouldBe 200 // MAX_LIST_RESULTS -- a page size, not a hard cutoff.
                    page1Ids shouldContain ids.first().toString() // oldest submittedAt -- first page, first row.
                    page1Ids shouldNotContain ids.last().toString() // newest of these 201 -- pushed to page 2.

                    val lastOfPage1 = page1.last().split("|")
                    val cursorId = lastOfPage1[0]
                    val cursorSubmittedAt = lastOfPage1[9] // toPipeString()'s submittedAt field.
                    val page2Ids =
                        client
                            .get(
                                "/test/travelexpense/list?status=REQUESTED&afterId=$cursorId&afterSubmittedAt=$cursorSubmittedAt",
                            ) { header("X-Member-Id", BOARD_ID) }
                            .bodyAsText()
                            .split(";")
                            .map { it.split("|")[0] }

                    // The very next row after the page-1/page-2 boundary must be the newest of the
                    // 201, not skipped and not repeated.
                    page2Ids.first() shouldBe ids.last().toString()
                    page2Ids shouldNotContain ids.first().toString() // no repeat of page 1's first row.

                    // Both-halves-or-neither cursor contract from the interface KDoc: a cursor
                    // with only ONE half set must behave as "no cursor", never throw.
                    client
                        .get("/test/travelexpense/list?status=REQUESTED&afterId=$cursorId") {
                            header("X-Member-Id", BOARD_ID)
                        }.status shouldBe HttpStatusCode.OK
                } finally {
                    transaction {
                        TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList ids }
                    }
                }
            }
        }

        test(
            "review MINOR fix: listMyReports caps at 200 (MAX_LIST_RESULTS), newest-created first, " +
                "instead of returning a member's entire unbounded report history",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val memberId = newMember()

                // 201 REJECTED rows -- MAX_OPEN_DRAFTS_PER_MEMBER never bounds these (they are all
                // terminal, not OPEN), so before this fix listMyReports returned every single one.
                // REJECTED (not EXECUTED) so this insert needs no valid posted_journal_entry_id
                // (chk_ter_posted_entry_state requires that only for EXECUTED); decision_note is
                // still required for any of APPROVED/EXECUTED/REJECTED (chk_ter_decided_needs_note).
                val rowCount = 201
                val ids = List(rowCount) { Uuid.random() }
                try {
                    transaction {
                        ids.forEachIndexed { i, id ->
                            TravelExpenseReportTable.insert {
                                it[TravelExpenseReportTable.id] = id
                                it[subjectMemberId] = memberId
                                it[status] = TravelExpenseReportStatus.REJECTED
                                it[purpose] = "Cap-Testfahrt"
                                it[travelFrom] = LocalDate(2020, 1, 1)
                                it[travelTo] = LocalDate(2020, 1, 1)
                                it[totalAmount] = BigDecimal("10.00")
                                it[createdAt] = LocalDateTime(2020, 1, 1, i / 60, i % 60, 0)
                                it[requestedBy] = memberId
                                it[submittedAt] = LocalDateTime(2020, 1, 1, i / 60, i % 60, 0)
                                it[decisionNote] = "Cap-Test Ablehnung"
                            }
                        }
                    }

                    val mineIds =
                        client
                            .get("/test/travelexpense/mine") { header("X-Member-Id", memberId.toString()) }
                            .bodyAsText()
                            .split(";")
                            .map { it.split("|")[0] }

                    mineIds.size shouldBe 200
                    mineIds shouldContain ids.last().toString() // newest-created -- must survive the cap.
                    mineIds shouldNotContain ids.first().toString() // oldest-created -- truncated away.
                } finally {
                    transaction {
                        TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList ids }
                    }
                }
            }
        }
    })
