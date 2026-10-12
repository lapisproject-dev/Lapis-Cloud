package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReceiptTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseRateSet
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

private const val CLOCK = "2026-06-15T10:00:00Z"

/**
 * Welle V1.9.122 -- the PdV-form day grid, the new line kinds and the submit rules of
 * [TravelExpenseService]. The clock is pinned (`2026-06-15`) so "travelTo not in the future" never
 * depends on the day the suite runs; all trips lie in January 2026.
 */
class TravelExpenseDaysTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        fun setMileage(rate: BigDecimal?) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[travelMileageRatePerKm] = rate
                }
            }
        }

        fun resetTiers() {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[travelMileageRatePerKm] = null
                    it[travelTier1Hours] = 8
                    it[travelTier2Hours] = 14
                    it[travelTier3Hours] = 24
                    it[travelTier1Cents] = 600
                    it[travelTier2Cents] = 1200
                    it[travelTier3Cents] = 2800
                    it[travelBreakfastPct] = 20
                    it[travelLunchPct] = 40
                    it[travelDinnerPct] = 40
                }
            }
        }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            resetTiers()
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
                val lineIds =
                    TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId inList reportIds }.map {
                        it[TravelExpenseLineTable.id]
                    }
                TravelExpenseReceiptTable.deleteWhere { TravelExpenseReceiptTable.lineId inList lineIds }
                TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId inList reportIds }
                TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList reportIds }
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                    it[actorMemberId] = null
                }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newMember(memberNumber: String? = null): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Days Testmitglied"
                    it[email] = "travel-days-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                    it[MemberTable.memberNumber] = memberNumber
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

        fun lines(reportId: String) =
            transaction {
                TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId eq Uuid.parse(reportId) }.toList()
            }

        fun dayLines(reportId: String) =
            lines(reportId)
                .filter { it[TravelExpenseLineTable.kind] == TravelExpenseLineKind.DAY_ALLOWANCE }
                .sortedBy { it[TravelExpenseLineTable.dayDate] }

        fun report(reportId: String) =
            transaction { TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq Uuid.parse(reportId) }.single() }

        suspend fun HttpClient.create(
            member: Uuid,
            from: String,
            to: String,
            extra: String = "",
        ): HttpResponse =
            post("/test/travelexpense/create?subjectMemberId=$member&purpose=Reise&from=$from&to=$to$extra") {
                header("X-Member-Id", member.toString())
            }

        suspend fun HttpClient.createId(
            member: Uuid,
            from: String,
            to: String,
            extra: String = "",
        ): String = create(member, from, to, extra).bodyAsText().split("|")[0]

        suspend fun HttpClient.call(
            member: Uuid,
            path: String,
        ): HttpResponse = post(path) { header("X-Member-Id", member.toString()) }

        /** Inserts a receipt row directly (the upload route has its own test suite). */
        fun attachReceipt(
            lineId: Uuid,
            uploader: Uuid,
        ) {
            transaction {
                TravelExpenseReceiptTable.insert {
                    it[id] = Uuid.random()
                    it[TravelExpenseReceiptTable.lineId] = lineId
                    it[storageKey] = "test/${Uuid.random()}.bin"
                    it[originalFilename] = "beleg.pdf"
                    it[mimeType] = "application/pdf"
                    it[sizeBytes] = 1024L
                    it[sha256] = "a".repeat(64)
                    it[uploadedBy] = uploader
                    it[uploadedAt] = LocalDateTime(2026, 1, 10, 12, 0, 0)
                }
            }
        }

        fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) =
            TimeTestSupport.withServerClock(instant = CLOCK) {
                testApplication {
                    application {
                        install(StatusPages) { installTravelExpenseExceptionHandlers() }
                        routing { registerTravelExpenseTestRoutes() }
                    }
                    block()
                }
            }

        // ── Tagessynchronisierung ────────────────────────────────────────────────────────────

        test("createDraft creates one DAY_ALLOWANCE line per travel day (empty grid: only the full middle day carries money)") {
            withApp {
                val m = newMember()
                val body = client.create(m, "2026-01-10", "2026-01-12", "&fillDays=false").bodyAsText().split("|")
                val days = dayLines(body[0])
                days.map { it[TravelExpenseLineTable.dayDate] } shouldBe
                    listOf(LocalDate(2026, 1, 10), LocalDate(2026, 1, 11), LocalDate(2026, 1, 12))
                // First and last day are not filled in yet (0); a middle day is complete without input: 24 h = 28.00.
                days.map { it[TravelExpenseLineTable.amount] } shouldBe listOf(BigDecimal("0.00"), BigDecimal("28.00"), BigDecimal("0.00"))
                body[6] shouldBe "28.00"
            }
        }

        test("updateDraft: shrinking the period removes EMPTY outside days, a FILLED outside day is a 409 and nothing changes") {
            withApp {
                val m = newMember()
                val id = client.createId(m, "2026-01-10", "2026-01-12", "&fillDays=false")
                client
                    .call(m, "/test/travelexpense/update?id=$id&from=2026-01-10&to=2026-01-11")
                    .status shouldBe HttpStatusCode.OK
                dayLines(id).size shouldBe 2

                client
                    .call(m, "/test/travelexpense/update?id=$id&from=2026-01-10&to=2026-01-12")
                    .status shouldBe HttpStatusCode.OK
                client.call(m, "/test/travelexpense/savedays?id=$id&days=2026-01-12@-09:00@b@Berlin").status shouldBe HttpStatusCode.OK
                client
                    .call(m, "/test/travelexpense/update?id=$id&from=2026-01-10&to=2026-01-11")
                    .status shouldBe HttpStatusCode.Conflict
                dayLines(id).size shouldBe 3
            }
        }

        test("a trip of 31 days is accepted, 32 days is a BadRequest; a position change normalises the stored times") {
            withApp {
                val m = newMember()
                client.create(m, "2026-01-01", "2026-01-31", "&fillDays=false").status shouldBe HttpStatusCode.OK
                client.create(m, "2026-01-01", "2026-02-01", "&fillDays=false").status shouldBe HttpStatusCode.BadRequest

                val id = client.createId(m, "2026-02-01", "2026-02-01", "&fillDays=false")
                client.call(m, "/test/travelexpense/savedays?id=$id&days=2026-02-01@08:00-17:00").status shouldBe HttpStatusCode.OK
                // The single day becomes the FIRST day: its arrival time must disappear, the departure stays.
                client.call(m, "/test/travelexpense/update?id=$id&from=2026-02-01&to=2026-02-02").status shouldBe HttpStatusCode.OK
                val first = dayLines(id).first()
                first[TravelExpenseLineTable.timeFrom]?.toString() shouldBe "08:00"
                first[TravelExpenseLineTable.timeTo] shouldBe null
            }
        }

        // ── saveDays ─────────────────────────────────────────────────────────────────────────

        test(
            "saveDays: hand-calculated 3-day trip (first day from 10:00 = 12.00, middle day with lunch+dinner = 5.60, last day to 07:30 = 0)",
        ) {
            withApp {
                val m = newMember()
                val id = client.createId(m, "2026-01-10", "2026-01-12", "&fillDays=false")
                val body =
                    client
                        .call(
                            m,
                            "/test/travelexpense/savedays?id=$id&days=" +
                                "2026-01-10@10:00-@-@Berlin,2026-01-11@-@ld@Berlin,2026-01-12@-07:30@-@Hamburg",
                        ).bodyAsText()
                        .split("|")
                body[6] shouldBe "17.60"
                body[18] shouldBe "2240" // deductionCents
                dayLines(id).map { it[TravelExpenseLineTable.amount] } shouldBe
                    listOf(BigDecimal("12.00"), BigDecimal("5.60"), BigDecimal("0.00"))
                dayLines(id)[1][TravelExpenseLineTable.computedMinutes] shouldBe 1440
                dayLines(id)[0][TravelExpenseLineTable.destination] shouldBe "Berlin"
            }
        }

        test("saveDays enforces the position rules: single day needs both times, first day no arrival, middle day no times, to >= from") {
            withApp {
                val m = newMember()
                val single = client.createId(m, "2026-01-10", "2026-01-10", "&fillDays=false")
                client.call(m, "/test/travelexpense/savedays?id=$single&days=2026-01-10@08:00-").status shouldBe HttpStatusCode.BadRequest
                client.call(m, "/test/travelexpense/savedays?id=$single&days=2026-01-10@09:00-08:00").status shouldBe
                    HttpStatusCode.BadRequest
                client.call(m, "/test/travelexpense/savedays?id=$single&days=2026-01-10@08:00-09:00").status shouldBe HttpStatusCode.OK

                val multi = client.createId(m, "2026-01-10", "2026-01-12", "&fillDays=false")
                client.call(m, "/test/travelexpense/savedays?id=$multi&days=2026-01-10@08:00-17:00").status shouldBe
                    HttpStatusCode.BadRequest
                client.call(m, "/test/travelexpense/savedays?id=$multi&days=2026-01-11@08:00-").status shouldBe HttpStatusCode.BadRequest
                client.call(m, "/test/travelexpense/savedays?id=$multi&days=2026-01-12@08:00-17:00").status shouldBe
                    HttpStatusCode.BadRequest
                client.call(m, "/test/travelexpense/savedays?id=$multi&days=2026-01-12@-17:00").status shouldBe HttpStatusCode.OK
            }
        }

        test("saveDays rejects a duplicate day and a day outside the travel period") {
            withApp {
                val m = newMember()
                val id = client.createId(m, "2026-01-10", "2026-01-12", "&fillDays=false")
                client
                    .call(m, "/test/travelexpense/savedays?id=$id&days=2026-01-11@-@b,2026-01-11@-@l")
                    .status shouldBe HttpStatusCode.BadRequest
                client.call(m, "/test/travelexpense/savedays?id=$id&days=2026-01-20@-@b").status shouldBe HttpStatusCode.BadRequest
            }
        }

        // ── Frühstückssperre ─────────────────────────────────────────────────────────────────

        test(
            "an overnight stay WITH breakfast deducts the breakfast on the following mornings SERVER-side, even if the client sends false",
        ) {
            withApp {
                val m = newMember()
                val id = client.createId(m, "2026-01-10", "2026-01-12", "&fillDays=false")
                client.call(m, "/test/travelexpense/savedays?id=$id&days=2026-01-10@10:00-,2026-01-11@-,2026-01-12@-23:00")
                // 12.00 + 28.00 + 12.00 without any deduction
                val noStay = report(id)[TravelExpenseReportTable.totalAmount]
                noStay shouldBe BigDecimal("52.00")

                val withStay =
                    client
                        .call(
                            m,
                            "/test/travelexpense/addline?reportId=$id&kind=ACCOMMODATION&description=Hotel&dayDate=2026-01-10&nights=2" +
                                "&breakfastIncluded=true&amountCents=18000",
                        ).bodyAsText()
                        .split("|")
                // 12.00 + (28.00 - 5.60) + (12.00 - min(5.60, 12.00)) + 180.00 hotel
                dayLines(id).map { it[TravelExpenseLineTable.amount] } shouldBe
                    listOf(BigDecimal("12.00"), BigDecimal("22.40"), BigDecimal("6.40"))
                withStay[6] shouldBe "220.80"

                val hotelLine = lines(id).single { it[TravelExpenseLineTable.kind] == TravelExpenseLineKind.ACCOMMODATION }
                client.call(m, "/test/travelexpense/removeline?lineId=${hotelLine[TravelExpenseLineTable.id]}").status shouldBe
                    HttpStatusCode.OK
                report(id)[TravelExpenseReportTable.totalAmount] shouldBe BigDecimal("52.00")
            }
        }

        // ── Zeilenarten ──────────────────────────────────────────────────────────────────────

        test("ACCOMMODATION must lie inside the travel period and stay within 1..30 nights") {
            withApp {
                val m = newMember()
                val id = client.createId(m, "2026-01-10", "2026-01-12")
                val base = "/test/travelexpense/addline?reportId=$id&kind=ACCOMMODATION&description=Hotel&amountCents=5000"
                client.call(m, "$base&dayDate=2026-01-10&nights=3").status shouldBe HttpStatusCode.BadRequest // 4th morning is outside
                client.call(m, "$base&dayDate=2026-01-09&nights=1").status shouldBe HttpStatusCode.BadRequest
                client.call(m, "$base&dayDate=2026-01-10&nights=0").status shouldBe HttpStatusCode.BadRequest
                client.call(m, "$base&dayDate=2026-01-10").status shouldBe HttpStatusCode.BadRequest // nights missing
                client.call(m, "$base&dayDate=2026-01-10&nights=2").status shouldBe HttpStatusCode.OK
            }
        }

        test("amount and amountCents must agree; each alone works; day lines can neither be added nor removed") {
            withApp {
                val m = newMember()
                val id = client.createId(m, "2026-01-10", "2026-01-10")
                val base = "/test/travelexpense/addline?reportId=$id&kind=PUBLIC_TRANSPORT&description=Bahn"
                client.call(m, "$base&amount=12.50&amountCents=1251").status shouldBe HttpStatusCode.BadRequest
                client.call(m, "$base&amount=12.50&amountCents=1250").status shouldBe HttpStatusCode.OK
                client.call(m, "$base&amountCents=500").status shouldBe HttpStatusCode.OK
                client.call(m, "$base&amount=1.00").status shouldBe HttpStatusCode.OK
                client.call(m, "$base&amountCents=0").status shouldBe HttpStatusCode.BadRequest
                client.call(m, "$base&amountCents=1000001").status shouldBe HttpStatusCode.BadRequest
                report(id)[TravelExpenseReportTable.totalAmount] shouldBe BigDecimal("18.50")

                val day = dayLines(id).single()
                client
                    .call(m, "/test/travelexpense/removeline?lineId=${day[TravelExpenseLineTable.id]}")
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("updateLine recomputes mileage, refuses a kind change and refuses day lines") {
            withApp {
                setMileage(BigDecimal("0.3000"))
                val m = newMember()
                val id = client.createId(m, "2026-01-10", "2026-01-10")
                client.call(m, "/test/travelexpense/addline?reportId=$id&kind=MILEAGE&description=Fahrt&kilometers=100")
                val mileage = lines(id).single { it[TravelExpenseLineTable.kind] == TravelExpenseLineKind.MILEAGE }
                val lineId = mileage[TravelExpenseLineTable.id]

                val updated =
                    client
                        .call(m, "/test/travelexpense/updateline?lineId=$lineId&kind=MILEAGE&description=Fahrt&kilometers=200&passengers=1")
                        .bodyAsText()
                        .split("|")
                updated[6] shouldBe "64.00" // 200 * 0.30 + 200 * 1 * 0.02
                client
                    .call(m, "/test/travelexpense/updateline?lineId=$lineId&kind=OTHER&description=x&amountCents=100")
                    .status shouldBe HttpStatusCode.BadRequest
                val day = dayLines(id).single()
                client
                    .call(m, "/test/travelexpense/updateline?lineId=${day[TravelExpenseLineTable.id]}&kind=DAY_ALLOWANCE&description=x")
                    .status shouldBe HttpStatusCode.BadRequest
                setMileage(null)
            }
        }

        // ── Einreichen ───────────────────────────────────────────────────────────────────────

        test("submit needs a payout choice, complete day times and a total above zero") {
            withApp {
                setMileage(BigDecimal("0.3000"))
                val m = newMember()
                val noChoice = client.createId(m, "2026-01-10", "2026-01-10", "&payout=none")
                client.call(m, "/test/travelexpense/addline?reportId=$noChoice&kind=MILEAGE&description=Fahrt&kilometers=10")
                client.call(m, "/test/travelexpense/submit?id=$noChoice").bodyAsText() shouldBe "payout_choice_required"

                val incompleteDays = client.createId(m, "2026-01-10", "2026-01-10", "&fillDays=false")
                client.call(m, "/test/travelexpense/addline?reportId=$incompleteDays&kind=MILEAGE&description=Fahrt&kilometers=10")
                client.call(m, "/test/travelexpense/submit?id=$incompleteDays").status shouldBe HttpStatusCode.BadRequest
                report(incompleteDays)[TravelExpenseReportTable.status] shouldBe TravelExpenseReportStatus.DRAFT

                val zero = client.createId(m, "2026-01-10", "2026-01-10")
                client.call(m, "/test/travelexpense/submit?id=$zero").status shouldBe HttpStatusCode.BadRequest
                setMileage(null)
            }
        }

        test("an advance above the total is a BadRequest at submit; equal to the total is accepted") {
            withApp {
                setMileage(BigDecimal("0.3000"))
                val m = newMember()
                val over = client.createId(m, "2026-01-10", "2026-01-10", "&advance=3001")
                client.call(m, "/test/travelexpense/addline?reportId=$over&kind=MILEAGE&description=Fahrt&kilometers=100")
                client.call(m, "/test/travelexpense/submit?id=$over").bodyAsText() shouldBe "advance_exceeds_total"

                val equal = client.createId(m, "2026-01-10", "2026-01-10", "&advance=3000")
                client.call(m, "/test/travelexpense/addline?reportId=$equal&kind=MILEAGE&description=Fahrt&kilometers=100")
                val submitted = client.call(m, "/test/travelexpense/submit?id=$equal").bodyAsText().split("|")
                submitted[2] shouldBe TravelExpenseReportStatus.REQUESTED.name
                submitted[17] shouldBe "0" // payoutCents
                // advance range is validated at create time
                client.create(m, "2026-01-10", "2026-01-10", "&advance=-1").status shouldBe HttpStatusCode.BadRequest
                client.create(m, "2026-01-10", "2026-01-10", "&advance=100000001").status shouldBe HttpStatusCode.BadRequest
                setMileage(null)
            }
        }

        test("a receipt is mandatory for PUBLIC_TRANSPORT, ACCOMMODATION, HOSPITALITY and OTHER -- but optional for MILEAGE") {
            withApp {
                setMileage(BigDecimal("0.3000"))
                val m = newMember()
                listOf(
                    TravelExpenseLineKind.PUBLIC_TRANSPORT to "",
                    TravelExpenseLineKind.ACCOMMODATION to "&dayDate=2026-01-10&nights=1",
                    TravelExpenseLineKind.HOSPITALITY to "",
                    TravelExpenseLineKind.OTHER to "",
                ).forEach { (kind, extra) ->
                    val id = client.createId(m, "2026-01-10", "2026-01-11")
                    client
                        .call(m, "/test/travelexpense/addline?reportId=$id&kind=$kind&description=Beleg&amountCents=1000$extra")
                        .status shouldBe HttpStatusCode.OK
                    client.call(m, "/test/travelexpense/submit?id=$id").status shouldBe HttpStatusCode.BadRequest
                    val lineId = lines(id).single { it[TravelExpenseLineTable.kind] == kind }[TravelExpenseLineTable.id]
                    attachReceipt(lineId, m)
                    client.call(m, "/test/travelexpense/submit?id=$id").status shouldBe HttpStatusCode.OK
                }
                val mileageOnly = client.createId(m, "2026-01-10", "2026-01-10")
                client.call(m, "/test/travelexpense/addline?reportId=$mileageOnly&kind=MILEAGE&description=Fahrt&kilometers=10")
                client.call(m, "/test/travelexpense/submit?id=$mileageOnly").status shouldBe HttpStatusCode.OK
                setMileage(null)
            }
        }

        test("submit freezes the rate set and the membership number; a later settings change leaves the submitted report untouched") {
            withApp {
                setMileage(BigDecimal("0.3000"))
                val m = newMember(memberNumber = "M-4711")
                val id = client.createId(m, "2026-01-10", "2026-01-12", "&fillDays=false")
                client.call(m, "/test/travelexpense/savedays?id=$id&days=2026-01-10@10:00-,2026-01-12@-07:30")
                client.call(m, "/test/travelexpense/addline?reportId=$id&kind=MILEAGE&description=Fahrt&kilometers=100")
                val submitted = client.call(m, "/test/travelexpense/submit?id=$id").bodyAsText().split("|")
                submitted[2] shouldBe TravelExpenseReportStatus.REQUESTED.name
                submitted[6] shouldBe "70.00" // 12.00 + 28.00 + 0 + 30.00

                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[travelTier3Cents] = 9999
                        it[travelTier2Cents] = 9000
                        it[travelMileageRatePerKm] = BigDecimal("0.9900")
                    }
                    MemberTable.update({ MemberTable.id eq m }) { it[memberNumber] = "M-CHANGED" }
                }
                val row = report(id)
                row[TravelExpenseReportTable.totalAmount] shouldBe BigDecimal("70.00")
                row[TravelExpenseReportTable.membershipNumberSnapshot] shouldBe "M-4711"
                val snapshot =
                    travelExpenseSnapshotJson.decodeFromString(
                        TravelExpenseRateSet.serializer(),
                        row[TravelExpenseReportTable.ratesSnapshot]!!,
                    )
                snapshot.tierCents shouldBe listOf(600L, 1200L, 2800L)
                snapshot.mileageCentsPerKm shouldBe 30L
                resetTiers()
            }
        }

        test("a member without a membership number submits fine (snapshot stays null)") {
            withApp {
                setMileage(BigDecimal("0.3000"))
                val m = newMember(memberNumber = null)
                val id = client.createId(m, "2026-01-10", "2026-01-10")
                client.call(m, "/test/travelexpense/addline?reportId=$id&kind=MILEAGE&description=Fahrt&kilometers=10")
                client.call(m, "/test/travelexpense/submit?id=$id").status shouldBe HttpStatusCode.OK
                report(id)[TravelExpenseReportTable.membershipNumberSnapshot] shouldBe null
                setMileage(null)
            }
        }

        test("a legacy MILEAGE line with fractional kilometres is refused at submit (mileage_fractional_km_reenter)") {
            withApp {
                setMileage(BigDecimal("0.3000"))
                val m = newMember()
                val id = client.createId(m, "2026-01-10", "2026-01-10")
                transaction {
                    TravelExpenseLineTable.insert {
                        it[TravelExpenseLineTable.id] = Uuid.random()
                        it[reportId] = Uuid.parse(id)
                        it[kind] = TravelExpenseLineKind.MILEAGE
                        it[description] = "Altfahrt"
                        it[kilometers] = BigDecimal("12.50")
                        it[rateSnapshot] = BigDecimal("0.3000")
                        it[amount] = BigDecimal("3.75")
                        it[createdAt] = LocalDateTime(2026, 1, 10, 12, 0, 0)
                        it[breakfastProvided] = false
                        it[lunchProvided] = false
                        it[dinnerProvided] = false
                        it[deductionAmount] = BigDecimal("0.00")
                    }
                }
                client.call(m, "/test/travelexpense/submit?id=$id").bodyAsText() shouldBe "mileage_fractional_km_reenter"
                setMileage(null)
            }
        }

        test("changed tiers in the settings apply to NEW calculations of a draft (live rates until submit)") {
            withApp {
                val m = newMember()
                val id = client.createId(m, "2026-01-10", "2026-01-10", "&fillDays=false")
                val before =
                    client.call(m, "/test/travelexpense/savedays?id=$id&days=2026-01-10@08:00-16:00").bodyAsText().split("|")
                before[6] shouldBe "6.00"
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[travelTier1Cents] = 700
                    }
                }
                val after = client.call(m, "/test/travelexpense/savedays?id=$id&days=2026-01-10@08:00-16:00").bodyAsText().split("|")
                after[6] shouldBe "7.00"
                resetTiers()
            }
        }

        test("wire compatibility: an old client payload without any V1.9.122 field still deserialises, unknown keys are ignored") {
            val json =
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val oldInput =
                """{"purpose":"Reise","travelFrom":"2026-01-10","travelTo":"2026-01-11"}"""
            val parsed =
                json.decodeFromString(
                    network.lapis.cloud.shared.domain.TravelExpenseReportInput
                        .serializer(),
                    oldInput,
                )
            parsed.advanceCents shouldBe null
            parsed.payoutChoice shouldBe null
            val futureLine = """{"kind":"OTHER","description":"x","amountCents":100,"somethingNew":1}"""
            json
                .decodeFromString(
                    network.lapis.cloud.shared.domain.TravelExpenseLineInput
                        .serializer(),
                    futureLine,
                ).amountCents shouldBe
                100L
        }
    })
