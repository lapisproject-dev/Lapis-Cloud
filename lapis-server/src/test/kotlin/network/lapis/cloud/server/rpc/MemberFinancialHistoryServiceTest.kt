package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.datetime.LocalTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.ExternalDonorTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.4.1 "Beitragshistorie" -- exercises [MemberFinancialHistoryService] directly through
 * throwaway routes, same house style [ContributionPaymentRpcTest] establishes (own fixtures,
 * direct table inserts, `X-Member-Id` trusted-header auth).
 */
class MemberFinancialHistoryServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdJournalEntryIds = mutableListOf<Uuid>()
        val createdExternalDonorIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdJournalEntryIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.journalEntryId inList createdJournalEntryIds }
                    JournalEntryTable.deleteWhere { JournalEntryTable.id inList createdJournalEntryIds }
                }
                if (createdContributionIds.isNotEmpty()) {
                    ContributionTable.deleteWhere { ContributionTable.id inList createdContributionIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.ledgerAccountId inList createdLedgerAccountIds }
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdExternalDonorIds.isNotEmpty()) {
                    ExternalDonorTable.deleteWhere { ExternalDonorTable.id inList createdExternalDonorIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                if (createdTierIds.isNotEmpty()) {
                    MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole,
            joinedAt: LocalDate = LocalDate(2020, 1, 1),
            friendSince: LocalDate? = null,
            anonymizedAt: LocalDateTime? = null,
            displayName: String = "Fixture Mitglied",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[MemberTable.joinedAt] = joinedAt
                    it[MemberTable.friendSince] = friendSince
                    it[MemberTable.anonymizedAt] = anonymizedAt
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

        fun createTier(): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "MFH-Fixture Tarif ${id.toString().take(6)}"
                    it[description] = "Test-Tarif"
                    it[contributionAmount] = BigDecimal("50.00")
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
            }
            createdTierIds += id
            return id
        }

        fun createContribution(
            memberId: Uuid,
            tierId: Uuid,
            status: ContributionStatus,
            amountDue: BigDecimal,
            periodStart: LocalDate,
            periodEnd: LocalDate,
            dueDate: LocalDate,
            paidAt: LocalDateTime? = null,
            paidAmount: BigDecimal? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[ContributionTable.periodStart] = periodStart
                    it[ContributionTable.periodEnd] = periodEnd
                    it[ContributionTable.amountDue] = amountDue
                    it[ContributionTable.status] = status
                    it[ContributionTable.paidAt] = paidAt
                    it[ContributionTable.paidAmount] = paidAmount
                    it[ContributionTable.createdAt] = LocalDateTime(2020, 1, 1, 0, 0)
                    it[ContributionTable.memberId] = memberId
                    it[ContributionTable.membershipTierId] = tierId
                    it[ContributionTable.dueDate] = dueDate
                }
            }
            createdContributionIds += id
            return id
        }

        fun createLedgerAccount(
            number: String,
            type: LedgerAccountType,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "MFH-Fixture Konto $number"
                    it[accountClass] = 0
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun createExternalDonor(): Uuid {
            val id = Uuid.random()
            transaction {
                ExternalDonorTable.insert {
                    it[ExternalDonorTable.id] = id
                    it[displayName] = "MFH-Fixture Externer Spender"
                    it[donorCategory] = DonorCategory.GERMAN_NATURAL_PERSON
                    it[active] = true
                }
            }
            createdExternalDonorIds += id
            return id
        }

        /** One journal entry with a single posting against [ledgerAccountId], on [side]. */
        fun createDonationEntry(
            createdBy: Uuid,
            donorMemberId: Uuid?,
            externalDonorId: Uuid?,
            ledgerAccountId: Uuid,
            amount: BigDecimal,
            entryDate: LocalDate,
            side: PostingSide,
            status: JournalEntryStatus = JournalEntryStatus.POSTED,
            donorCategory: DonorCategory? = DonorCategory.GERMAN_NATURAL_PERSON,
            description: String = "MFH-Fixture Spende",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                JournalEntryTable.insert {
                    it[JournalEntryTable.id] = id
                    it[JournalEntryTable.entryDate] = entryDate
                    it[JournalEntryTable.description] = description
                    it[voucherReference] = null
                    it[JournalEntryTable.createdBy] = createdBy
                    it[JournalEntryTable.status] = status
                    it[postedAt] = if (status == JournalEntryStatus.POSTED) LocalDateTime(entryDate, LocalTime(0, 0)) else null
                    it[createdAt] = LocalDateTime(entryDate, LocalTime(0, 0))
                    it[JournalEntryTable.donorMemberId] = donorMemberId
                    it[JournalEntryTable.donorCategory] = donorCategory
                    it[JournalEntryTable.externalDonorId] = externalDonorId
                }
                PostingTable.insert {
                    it[PostingTable.id] = Uuid.random()
                    it[PostingTable.side] = side
                    it[PostingTable.amount] = amount
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[journalEntryId] = id
                    it[PostingTable.ledgerAccountId] = ledgerAccountId
                    it[costCenterId] = null
                }
            }
            createdJournalEntryIds += id
            return id
        }

        test(
            "gemischte Beitragsstatus: contributionsOutstanding summiert die volle OUTSTANDING-Menge, " +
                "Paid/Waived bleiben getrennt, DEBIT_IN_FLIGHT zaehlt zu keiner der vier Summen, " +
                "erzeugt aber (Review MEDIUM) eine eigene Zeile (T-1/T-4)",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                    }
                    routing { registerMemberFinancialHistoryTestRoutes() }
                }
                val tierId = createTier()
                val memberId = createMember(email = "mfh-mixed-${Uuid.random()}@example.org", role = AccountRole.MEMBER)

                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.OPEN,
                    amountDue = BigDecimal("10.00"),
                    periodStart = LocalDate(2025, 1, 1),
                    periodEnd = LocalDate(2025, 3, 31),
                    dueDate = LocalDate(2025, 1, 15),
                )
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.OVERDUE,
                    amountDue = BigDecimal("20.00"),
                    periodStart = LocalDate(2025, 4, 1),
                    periodEnd = LocalDate(2025, 6, 30),
                    dueDate = LocalDate(2025, 4, 15),
                )
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.RETURNED,
                    amountDue = BigDecimal("30.00"),
                    periodStart = LocalDate(2025, 7, 1),
                    periodEnd = LocalDate(2025, 9, 30),
                    dueDate = LocalDate(2025, 7, 15),
                )
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.IN_DUNNING,
                    amountDue = BigDecimal("40.00"),
                    periodStart = LocalDate(2025, 10, 1),
                    periodEnd = LocalDate(2025, 12, 31),
                    dueDate = LocalDate(2025, 10, 15),
                )
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.PAID,
                    amountDue = BigDecimal("50.00"),
                    periodStart = LocalDate(2024, 1, 1),
                    periodEnd = LocalDate(2024, 12, 31),
                    dueDate = LocalDate(2024, 1, 15),
                    paidAt = LocalDateTime(2024, 2, 1, 10, 0),
                    paidAmount = BigDecimal("50.00"),
                )
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.WAIVED,
                    amountDue = BigDecimal("60.00"),
                    periodStart = LocalDate(2023, 1, 1),
                    periodEnd = LocalDate(2023, 12, 31),
                    dueDate = LocalDate(2023, 1, 15),
                )
                // DEBIT_IN_FLIGHT -- must appear in none of the four SUMS, but (Review MEDIUM fix)
                // DOES get its own row in the year's entry list. Own period (2026, not reused from
                // the OPEN row above) -- `contribution` carries a UNIQUE constraint on
                // (member_id, membership_tier_id, period_start, period_end).
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.DEBIT_SCHEDULED,
                    amountDue = BigDecimal("70.00"),
                    periodStart = LocalDate(2026, 1, 1),
                    periodEnd = LocalDate(2026, 3, 31),
                    dueDate = LocalDate(2026, 1, 20),
                )

                val response =
                    client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                // Encoding: "paid:waived:outstanding:donations:entryCount"
                // outstanding = OPEN(10) + OVERDUE(20) + RETURNED(30) + IN_DUNNING(40) = 100.00
                // entryCount = 4 OUTSTANDING + 1 PAID + 1 WAIVED + 1 DEBIT_SCHEDULED row = 7 -- the
                // DEBIT_SCHEDULED row contributes to NONE of the four leading sums (still 100.00,
                // not 170.00) but is counted as its own row (Review MEDIUM fix).
                body shouldBe "50.00:60.00:100.00:0.00:7"
            }
        }

        test(
            "Regression Review MEDIUM: DEBIT_SCHEDULED/DEBIT_SUBMITTED erzeugen eine eigene " +
                "CONTRIBUTION_DEBIT_IN_FLIGHT-Zeile datiert auf dueDate, sonst waere ein laufender " +
                "SEPA-Einzug spurlos aus der Historie verschwunden (T-17)",
        ) {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val tierId = createTier()
                val memberId = createMember(email = "mfh-debitinflight-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.DEBIT_SCHEDULED,
                    amountDue = BigDecimal("120.00"),
                    periodStart = LocalDate(2026, 1, 1),
                    periodEnd = LocalDate(2026, 12, 31),
                    dueDate = LocalDate(2026, 2, 1),
                )

                val getResponse = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                // No contribution to any of the four sums, but exactly one row.
                getResponse.bodyAsText() shouldBe "0.00:0.00:0.00:0.00:1"

                val kindsResponse = client.get("/test/mfh/kinds?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                kindsResponse.bodyAsText() shouldBe "CONTRIBUTION_DEBIT_IN_FLIGHT"

                val datesResponse = client.get("/test/mfh/dates?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                // Dated by dueDate, same as CONTRIBUTION_OUTSTANDING's Zeitachse rule.
                datesResponse.bodyAsText() shouldBe "2026-02-01"
            }
        }

        test("PAID mit paidAmount != amountDue nimmt paidAmount (T-2)") {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val tierId = createTier()
                val memberId = createMember(email = "mfh-paidamount-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.PAID,
                    amountDue = BigDecimal("100.00"),
                    periodStart = LocalDate(2025, 1, 1),
                    periodEnd = LocalDate(2025, 12, 31),
                    dueDate = LocalDate(2025, 1, 15),
                    paidAt = LocalDateTime(2025, 2, 1, 9, 0),
                    paidAmount = BigDecimal("75.00"),
                )
                val response = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                response.bodyAsText() shouldBe "75.00:0.00:0.00:0.00:1"
            }
        }

        test("PAID ohne paidAt (Altbestand) faellt auf periodStart zurueck statt NPE (T-3)") {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val tierId = createTier()
                val memberId = createMember(email = "mfh-legacypaid-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.PAID,
                    amountDue = BigDecimal("42.00"),
                    periodStart = LocalDate(2025, 5, 1),
                    periodEnd = LocalDate(2025, 5, 31),
                    dueDate = LocalDate(2025, 5, 15),
                    paidAt = null,
                    paidAmount = null,
                )
                val response = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "42.00:0.00:0.00:0.00:1"
            }
        }

        test("Spenden: POSTED + donorCategory zaehlen, DRAFT und donorCategory=null nicht (T-5)") {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val incomeAccountId = createLedgerAccount(number = "MF${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val adminId = createMember(email = "mfh-donor-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val memberId = createMember(email = "mfh-donor-${Uuid.random()}@example.org", role = AccountRole.MEMBER)

                createDonationEntry(
                    createdBy = adminId,
                    donorMemberId = memberId,
                    externalDonorId = null,
                    ledgerAccountId = incomeAccountId,
                    amount = BigDecimal("25.00"),
                    entryDate = LocalDate(2025, 3, 1),
                    side = PostingSide.CREDIT,
                    status = JournalEntryStatus.POSTED,
                )
                // DRAFT -- must not count.
                createDonationEntry(
                    createdBy = adminId,
                    donorMemberId = memberId,
                    externalDonorId = null,
                    ledgerAccountId = incomeAccountId,
                    amount = BigDecimal("999.00"),
                    entryDate = LocalDate(2025, 3, 2),
                    side = PostingSide.CREDIT,
                    status = JournalEntryStatus.DRAFT,
                )
                // donorCategory null -- must not count.
                createDonationEntry(
                    createdBy = adminId,
                    donorMemberId = memberId,
                    externalDonorId = null,
                    ledgerAccountId = incomeAccountId,
                    amount = BigDecimal("999.00"),
                    entryDate = LocalDate(2025, 3, 3),
                    side = PostingSide.CREDIT,
                    status = JournalEntryStatus.POSTED,
                    donorCategory = null,
                )

                val response = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                response.bodyAsText() shouldBe "0.00:0.00:0.00:25.00:1"
            }
        }

        test("Negativtest: eine external_donor-Spende im selben Zeitraum veraendert donationsTotal nicht (T-6)") {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val incomeAccountId = createLedgerAccount(number = "MG${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val adminId = createMember(email = "mfh-extdonor-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val memberId = createMember(email = "mfh-extdonor-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val externalDonorId = createExternalDonor()

                createDonationEntry(
                    createdBy = adminId,
                    donorMemberId = null,
                    externalDonorId = externalDonorId,
                    ledgerAccountId = incomeAccountId,
                    amount = BigDecimal("500.00"),
                    entryDate = LocalDate(2025, 6, 1),
                    side = PostingSide.CREDIT,
                )

                val response = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                response.bodyAsText() shouldBe "0.00:0.00:0.00:0.00:0"
            }
        }

        test("Vorzeichen: eine DEBIT-Buchung auf INCOME (Storno) wird subtrahiert, Netto<=0 erzeugt keinen Eintrag (T-7)") {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val incomeAccountId = createLedgerAccount(number = "MH${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val adminId = createMember(email = "mfh-storno-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val memberId = createMember(email = "mfh-storno-${Uuid.random()}@example.org", role = AccountRole.MEMBER)

                val entryId = Uuid.random()
                transaction {
                    JournalEntryTable.insert {
                        it[JournalEntryTable.id] = entryId
                        it[entryDate] = LocalDate(2025, 4, 1)
                        it[description] = "MFH-Fixture Spende storniert"
                        it[voucherReference] = null
                        it[createdBy] = adminId
                        it[status] = JournalEntryStatus.POSTED
                        it[postedAt] = LocalDateTime(2025, 4, 1, 0, 0)
                        it[createdAt] = LocalDateTime(2025, 4, 1, 0, 0)
                        it[donorMemberId] = memberId
                        it[donorCategory] = DonorCategory.GERMAN_NATURAL_PERSON
                        it[externalDonorId] = null
                    }
                    // Original CREDIT posting + a fully offsetting DEBIT posting (storno) -- net 0.
                    PostingTable.insert {
                        it[id] = Uuid.random()
                        it[side] = PostingSide.CREDIT
                        it[amount] = BigDecimal("100.00")
                        it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                        it[journalEntryId] = entryId
                        it[ledgerAccountId] = incomeAccountId
                        it[costCenterId] = null
                    }
                    PostingTable.insert {
                        it[id] = Uuid.random()
                        it[side] = PostingSide.DEBIT
                        it[amount] = BigDecimal("100.00")
                        it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                        it[journalEntryId] = entryId
                        it[ledgerAccountId] = incomeAccountId
                        it[costCenterId] = null
                    }
                }
                createdJournalEntryIds += entryId

                val response = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                response.bodyAsText() shouldBe "0.00:0.00:0.00:0.00:0"

                // Regression Review MINOR: das Jahr des in sich vollstaendig gegengebuchten
                // journal_entry (Netto exakt 0,00, keine Anzeigezeile) darf KEINEN leeren
                // Jahresblock erzeugen -- sonst rendert der Client eine leere Tabelle statt des
                // dafuer vorgesehenen Leerzustands (MemberFinancialHistoryScreen.kt prueft
                // `dto.years.isEmpty()`).
                val yearsResponse = client.get("/test/mfh/years?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                yearsResponse.bodyAsText() shouldBe ""
            }
        }

        test(
            "Regression Review MAJOR: eine Storno-/Korrekturbuchung als EIGENER (zweiter) journal_entry " +
                "wird gegen den urspruenglichen Eintrag genettet statt donationsTotal zu ueberhoehen (T-8)",
        ) {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val incomeAccountId = createLedgerAccount(number = "MI${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val adminId = createMember(email = "mfh-crossstorno-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val memberId = createMember(email = "mfh-crossstorno-${Uuid.random()}@example.org", role = AccountRole.MEMBER)

                // Entry A: the original donation, own journal_entry, nets to +500 by itself.
                createDonationEntry(
                    createdBy = adminId,
                    donorMemberId = memberId,
                    externalDonorId = null,
                    ledgerAccountId = incomeAccountId,
                    amount = BigDecimal("500.00"),
                    entryDate = LocalDate(2026, 3, 1),
                    side = PostingSide.CREDIT,
                )
                // Entry B: the refund, booked a month later as its OWN journal_entry (this schema
                // has no REVERSED status -- a correction is always a second entry, never a status
                // change on the first) -- nets to -500 by itself, so it produces no row of its own.
                createDonationEntry(
                    createdBy = adminId,
                    donorMemberId = memberId,
                    externalDonorId = null,
                    ledgerAccountId = incomeAccountId,
                    amount = BigDecimal("500.00"),
                    entryDate = LocalDate(2026, 4, 1),
                    side = PostingSide.DEBIT,
                )

                val response = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                // donationsTotal must be 0.00 (500 - 500), not 500.00 -- and exactly ONE row (entry
                // A) shows, because entry B's own net is <= 0 and therefore has no row.
                response.bodyAsText() shouldBe "0.00:0.00:0.00:0.00:1"

                val yearsResponse = client.get("/test/mfh/years?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                // Same netted total must show up at the year-level breakdown too -- this is exactly
                // the sign-rule-parity the DTO KDoc promises between the two totals.
                yearsResponse.bodyAsText() shouldBe "2026:0.00:0.00"
            }
        }

        test(
            "Regression MINOR: eine jahresuebergreifende Storno-Buchung darf den NEGATIV genetteten " +
                "Jahresblock nicht verlieren -- die zweite `.filter`-Bedingung " +
                "`donationsTotal.signum() != 0` (MemberFinancialHistoryService.kt) ist bewusst " +
                "`!= 0` statt `> 0`, genau um diesen Fall (Storno im FOLGEJAHR) sichtbar zu halten (T-19)",
        ) {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val incomeAccountId = createLedgerAccount(number = "MK${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val adminId = createMember(email = "mfh-crossyearstorno-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val memberId = createMember(email = "mfh-crossyearstorno-${Uuid.random()}@example.org", role = AccountRole.MEMBER)

                // Entry A: the original donation in 2025, own journal_entry, nets to +500 by itself
                // -> shows one row, donationsTotal[2025] = +500.
                createDonationEntry(
                    createdBy = adminId,
                    donorMemberId = memberId,
                    externalDonorId = null,
                    ledgerAccountId = incomeAccountId,
                    amount = BigDecimal("500.00"),
                    entryDate = LocalDate(2025, 3, 1),
                    side = PostingSide.CREDIT,
                )
                // Entry B: the refund booked as its OWN journal_entry a year later, in 2026 -- nets
                // to -500 by itself, so it has NO row of its own, but MUST still surface a 2026 year
                // block (entries empty, donationsTotal[2026] = -500) instead of vanishing silently.
                createDonationEntry(
                    createdBy = adminId,
                    donorMemberId = memberId,
                    externalDonorId = null,
                    ledgerAccountId = incomeAccountId,
                    amount = BigDecimal("500.00"),
                    entryDate = LocalDate(2026, 4, 1),
                    side = PostingSide.DEBIT,
                )

                val response = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                // donationsTotal must be 0.00 (500 - 500) and exactly ONE row (entry A) overall.
                response.bodyAsText() shouldBe "0.00:0.00:0.00:0.00:1"

                val yearsResponse = client.get("/test/mfh/years?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                // 2026 MUST still appear (entries empty, donationsTotal -500.00) -- if the second
                // `.filter` disjunct were ever weakened to `entries.isNotEmpty()` alone, or to
                // `donationsTotal.signum() > 0`, this year block would silently disappear even
                // though it carries a real -500.00 net correction. Years sorted descending.
                yearsResponse.bodyAsText() shouldBe "2026:0.00:-500.00|2025:0.00:500.00"
            }
        }

        test("MEMBER auf fremde Id: ForbiddenException (T-9); MEMBER auf eigene Id: OK (T-10)") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing { registerMemberFinancialHistoryTestRoutes() }
                }
                val memberId = createMember(email = "mfh-self-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val otherMemberId = createMember(email = "mfh-other-${Uuid.random()}@example.org", role = AccountRole.MEMBER)

                val selfResponse = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                selfResponse.status shouldBe HttpStatusCode.OK

                val foreignResponse = client.get("/test/mfh/get?memberId=$otherMemberId") { header("X-Member-Id", memberId.toString()) }
                foreignResponse.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("TREASURER, BOARD und ADMIN duerfen jeweils eine fremde Id lesen (T-11)") {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val memberId = createMember(email = "mfh-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val treasurerId = createMember(email = "mfh-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val boardId = createMember(email = "mfh-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val adminId = createMember(email = "mfh-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)

                val treasurerResponse = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", treasurerId.toString()) }
                treasurerResponse.status shouldBe HttpStatusCode.OK
                val boardResponse = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", boardId.toString()) }
                boardResponse.status shouldBe HttpStatusCode.OK
                val adminResponse = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", adminId.toString()) }
                adminResponse.status shouldBe HttpStatusCode.OK
            }
        }

        test("anonymisiertes Mitglied: anonymized=true, maskierter Name, Betraege unveraendert (T-12)") {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val tierId = createTier()
                val adminId = createMember(email = "mfh-anon-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val memberId =
                    createMember(
                        email = "mfh-anon-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        anonymizedAt = LocalDateTime(2025, 1, 1, 0, 0),
                        displayName = "Sollte nie erscheinen",
                    )
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.PAID,
                    amountDue = BigDecimal("10.00"),
                    periodStart = LocalDate(2025, 1, 1),
                    periodEnd = LocalDate(2025, 12, 31),
                    dueDate = LocalDate(2025, 1, 15),
                    paidAt = LocalDateTime(2025, 2, 1, 0, 0),
                    paidAmount = BigDecimal("10.00"),
                )

                val response =
                    client.get("/test/mfh/anonymized?memberId=$memberId") { header("X-Member-Id", adminId.toString()) }
                response.bodyAsText() shouldBe "true:(DSGVO-gelöscht):10.00"
            }
        }

        test("wohlgeformte, unbekannte Mitglieds-Id: NotFoundException (T-13)") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                    }
                    routing { registerMemberFinancialHistoryTestRoutes() }
                }
                val adminId = createMember(email = "mfh-notfound-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val unknownId = Uuid.random()

                val response = client.get("/test/mfh/get?memberId=$unknownId") { header("X-Member-Id", adminId.toString()) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("nicht UUID-foermige Mitglieds-Id: ebenfalls NotFoundException statt einer ungefangenen Exception (T-20)") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                    }
                    routing { registerMemberFinancialHistoryTestRoutes() }
                }
                val adminId = createMember(email = "mfh-malformed-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)

                val response =
                    client.get("/test/mfh/get?memberId=not-a-uuid-at-all") { header("X-Member-Id", adminId.toString()) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("Jahresgruppierung: years absteigend, Jahres-Zwischensummen korrekt (T-14)") {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val tierId = createTier()
                val memberId = createMember(email = "mfh-years-${Uuid.random()}@example.org", role = AccountRole.MEMBER)

                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.PAID,
                    amountDue = BigDecimal("10.00"),
                    periodStart = LocalDate(2023, 1, 1),
                    periodEnd = LocalDate(2023, 12, 31),
                    dueDate = LocalDate(2023, 1, 15),
                    paidAt = LocalDateTime(2023, 2, 1, 0, 0),
                    paidAmount = BigDecimal("10.00"),
                )
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.PAID,
                    amountDue = BigDecimal("20.00"),
                    periodStart = LocalDate(2025, 1, 1),
                    periodEnd = LocalDate(2025, 12, 31),
                    dueDate = LocalDate(2025, 1, 15),
                    paidAt = LocalDateTime(2025, 2, 1, 0, 0),
                    paidAmount = BigDecimal("20.00"),
                )

                val response = client.get("/test/mfh/years?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                // "year1:paid1:donations1|year2:paid2:donations2|..." descending by year, no
                // donations in this fixture.
                response.bodyAsText() shouldBe "2025:20.00:0.00|2023:10.00:0.00"
            }
        }

        test(
            "Regression Review MINOR test-coverage: Beitrag UND Spende im selben Jahr landen in " +
                "derselben FinancialHistoryYearDto und werden dort getrennt summiert (T-18)",
        ) {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val tierId = createTier()
                val incomeAccountId = createLedgerAccount(number = "MJ${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val adminId = createMember(email = "mfh-mixedyear-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val memberId = createMember(email = "mfh-mixedyear-${Uuid.random()}@example.org", role = AccountRole.MEMBER)

                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.PAID,
                    amountDue = BigDecimal("20.00"),
                    periodStart = LocalDate(2025, 1, 1),
                    periodEnd = LocalDate(2025, 12, 31),
                    dueDate = LocalDate(2025, 1, 15),
                    paidAt = LocalDateTime(2025, 2, 1, 0, 0),
                    paidAmount = BigDecimal("20.00"),
                )
                createDonationEntry(
                    createdBy = adminId,
                    donorMemberId = memberId,
                    externalDonorId = null,
                    ledgerAccountId = incomeAccountId,
                    amount = BigDecimal("15.00"),
                    entryDate = LocalDate(2025, 6, 1),
                    side = PostingSide.CREDIT,
                )

                val yearsResponse = client.get("/test/mfh/years?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                yearsResponse.bodyAsText() shouldBe "2025:20.00:15.00"

                val getResponse = client.get("/test/mfh/get?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                getResponse.bodyAsText() shouldBe "20.00:0.00:0.00:15.00:2"

                val kindsResponse = client.get("/test/mfh/kinds?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                kindsResponse.bodyAsText() shouldBe "CONTRIBUTION_PAID,DONATION"
            }
        }

        test(
            "Regression Review MINOR test-coverage: bei gleichem Datum entscheidet sourceId als " +
                "deterministischer Sortier-Tiebreaker, aufsteigend (T-19)",
        ) {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val tierId = createTier()
                val memberId = createMember(email = "mfh-tiebreak-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val sameDay = LocalDateTime(2025, 5, 1, 0, 0)

                val idA =
                    createContribution(
                        memberId = memberId,
                        tierId = tierId,
                        status = ContributionStatus.PAID,
                        amountDue = BigDecimal("10.00"),
                        periodStart = LocalDate(2025, 1, 1),
                        periodEnd = LocalDate(2025, 3, 31),
                        dueDate = LocalDate(2025, 1, 15),
                        paidAt = sameDay,
                        paidAmount = BigDecimal("10.00"),
                    )
                val idB =
                    createContribution(
                        memberId = memberId,
                        tierId = tierId,
                        status = ContributionStatus.PAID,
                        amountDue = BigDecimal("20.00"),
                        periodStart = LocalDate(2025, 4, 1),
                        periodEnd = LocalDate(2025, 6, 30),
                        dueDate = LocalDate(2025, 4, 15),
                        paidAt = sameDay,
                        paidAmount = BigDecimal("20.00"),
                    )
                val expectedOrder = listOf(idA.toString(), idB.toString()).sorted()

                val response =
                    client.get("/test/mfh/source-order?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                response.bodyAsText() shouldBe expectedOrder.joinToString(",")
            }
        }

        test("Zeitachse je Art: PAID->paidAt.date, OUTSTANDING->dueDate, WAIVED->periodStart (T-15)") {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val tierId = createTier()
                val memberId = createMember(email = "mfh-timeline-${Uuid.random()}@example.org", role = AccountRole.MEMBER)

                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.PAID,
                    amountDue = BigDecimal("10.00"),
                    periodStart = LocalDate(2025, 1, 1),
                    periodEnd = LocalDate(2025, 3, 31),
                    dueDate = LocalDate(2025, 1, 15),
                    paidAt = LocalDateTime(2025, 6, 20, 0, 0),
                    paidAmount = BigDecimal("10.00"),
                )
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.OVERDUE,
                    amountDue = BigDecimal("20.00"),
                    periodStart = LocalDate(2025, 4, 1),
                    periodEnd = LocalDate(2025, 6, 30),
                    dueDate = LocalDate(2025, 7, 10),
                )
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.WAIVED,
                    amountDue = BigDecimal("30.00"),
                    periodStart = LocalDate(2025, 8, 5),
                    periodEnd = LocalDate(2025, 8, 31),
                    dueDate = LocalDate(2025, 8, 20),
                )

                val response = client.get("/test/mfh/dates?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                // sorted ascending by date for a deterministic assertion.
                response.bodyAsText() shouldBe "2025-06-20,2025-07-10,2025-08-05"
            }
        }

        test("Regression Bugfix B-1: getMemberContributionSummary.totalOpen zaehlt IN_DUNNING mit (T-16)") {
            testApplication {
                application { routing { registerMemberFinancialHistoryTestRoutes() } }
                val tierId = createTier()
                val memberId = createMember(email = "mfh-bugfix-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                createContribution(
                    memberId = memberId,
                    tierId = tierId,
                    status = ContributionStatus.IN_DUNNING,
                    amountDue = BigDecimal("33.00"),
                    periodStart = LocalDate(2025, 1, 1),
                    periodEnd = LocalDate(2025, 12, 31),
                    dueDate = LocalDate(2025, 1, 15),
                )
                val response =
                    client.get("/test/mfh/summary-total-open?memberId=$memberId") { header("X-Member-Id", memberId.toString()) }
                response.bodyAsText() shouldBe "33.00"
            }
        }
    })

/** Shared throwaway routes for [MemberFinancialHistoryServiceTest]. */
private fun Route.registerMemberFinancialHistoryTestRoutes() {
    get("/test/mfh/get") {
        val memberId = call.request.queryParameters["memberId"]!!
        val dto = MemberFinancialHistoryService(call).getMemberFinancialHistory(memberId)
        val entryCount = dto.years.sumOf { it.entries.size }
        call.respondText(
            "${dto.contributionsPaid}:${dto.contributionsWaived}:${dto.contributionsOutstanding}:${dto.donationsTotal}:$entryCount",
        )
    }
    get("/test/mfh/anonymized") {
        val memberId = call.request.queryParameters["memberId"]!!
        val dto = MemberFinancialHistoryService(call).getMemberFinancialHistory(memberId)
        call.respondText("${dto.anonymized}:${dto.memberDisplayName}:${dto.contributionsPaid}")
    }
    get("/test/mfh/years") {
        val memberId = call.request.queryParameters["memberId"]!!
        val dto = MemberFinancialHistoryService(call).getMemberFinancialHistory(memberId)
        // "year:contributionsPaid:donationsTotal" -- both per-year amounts, not just the
        // contribution side (Review MINOR test-coverage fix: donationsTotal per year was
        // previously never asserted through this route at all).
        call.respondText(dto.years.joinToString("|") { "${it.year}:${it.contributionsPaid}:${it.donationsTotal}" })
    }
    get("/test/mfh/dates") {
        val memberId = call.request.queryParameters["memberId"]!!
        val dto = MemberFinancialHistoryService(call).getMemberFinancialHistory(memberId)
        val allEntries = dto.years.flatMap { it.entries }
        val dates = allEntries.map { it.date }.sorted()
        call.respondText(dates.joinToString(","))
    }
    get("/test/mfh/kinds") {
        val memberId = call.request.queryParameters["memberId"]!!
        val dto = MemberFinancialHistoryService(call).getMemberFinancialHistory(memberId)
        val kinds =
            dto.years
                .flatMap { it.entries }
                .map { it.kind.name }
                .sorted()
        call.respondText(kinds.joinToString(","))
    }
    // Deliberately NOT `.sorted()` before responding -- unlike `/test/mfh/dates`, this route
    // exists specifically to assert the SERVICE's own ordering (Review MINOR test-coverage fix:
    // `/test/mfh/dates` re-sorts before the assertion and can therefore never catch a regression
    // in `loadHistory`'s `compareByDescending { it.date }.thenBy { it.sourceId }`).
    get("/test/mfh/source-order") {
        val memberId = call.request.queryParameters["memberId"]!!
        val dto = MemberFinancialHistoryService(call).getMemberFinancialHistory(memberId)
        val sourceIds = dto.years.flatMap { it.entries }.map { it.sourceId }
        call.respondText(sourceIds.joinToString(","))
    }
    get("/test/mfh/summary-total-open") {
        val memberId = call.request.queryParameters["memberId"]!!
        val dto = ContributionService(call).getMemberContributionSummary(memberId)
        call.respondText(dto.totalOpen.toString())
    }
}
