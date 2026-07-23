package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostalDeliveryLogTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.postal.PostalDispatchOutcome
import network.lapis.cloud.server.postal.PostalMailProvider
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostalDeliveryStatus
import network.lapis.cloud.shared.domain.PostalInvitationDispatchInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime as KotlinLocalDateTime

/**
 * Exercises [PostalMailService] end to end -- same "throwaway routes calling the service class
 * directly" idiom as [AccountingServiceTest]/[OrganizationSettingsServiceTest], with a hand-rolled
 * [FakePostalMailProvider] test double standing in for [PostalMailProvider] (no HTTP layer at all
 * -- [network.lapis.cloud.server.postal.LetterxpressPostalMailProviderTest] covers the real
 * provider's own wire behaviour separately).
 */
class PostalMailServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdJournalEntryIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        beforeTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[postalMailEnabled] = false
                    it[taxExemptionAuthority] = null
                    it[taxExemptionDate] = null
                    it[bankIban] = "DE02120300000000202051"
                    it[bankBic] = "BYLADEM1001"
                }
            }
        }

        afterSpec {
            transaction {
                PostalDeliveryLogTable.deleteWhere { PostalDeliveryLogTable.recipientMemberId inList createdMemberIds }
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
                if (createdTierIds.isNotEmpty()) {
                    MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    DocumentVersionTable.deleteWhere { DocumentVersionTable.uploadedBy inList createdMemberIds }
                    DocumentTable.deleteWhere { DocumentTable.createdBy inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[postalMailEnabled] = false
                    it[taxExemptionAuthority] = null
                    it[taxExemptionDate] = null
                }
            }
        }

        fun enablePostalMail() {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[postalMailEnabled] = true
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole,
            withAddress: Boolean,
            displayName: String = "Postal Testmitglied",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    if (withAddress) {
                        it[street] = "Musterstrasse 5"
                        it[postalCode] = "38102"
                        it[city] = "Braunschweig"
                        it[country] = "Deutschland"
                    }
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
                    it[name] = "Postal-Tarif"
                    it[description] = "Testtarif"
                    it[contributionAmount] = java.math.BigDecimal("30.00")
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                }
            }
            createdTierIds += id
            return id
        }

        // year distinguishes the (member_id, membership_tier_id, period_start, period_end) UNIQUE
        // constraint (uq_contribution_member_tier_period) when a test creates more than one
        // contribution for the same member+tier pair.
        fun createContribution(
            memberId: Uuid,
            tierId: Uuid,
            year: Int = 2026,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[ContributionTable.memberId] = memberId
                    it[ContributionTable.membershipTierId] = tierId
                    it[periodStart] = LocalDate(year, 1, 1)
                    it[periodEnd] = LocalDate(year, 12, 31)
                    it[amountDue] = java.math.BigDecimal("30.00")
                    it[status] = ContributionStatus.OPEN
                    it[paidAt] = null
                    it[paidAmount] = null
                    it[note] = null
                    it[createdAt] = KotlinLocalDateTime(year, 1, 1, 0, 0)
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
                    it[name] = "Postal-Konto $number"
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

        fun postDonationEntry(
            donorMemberId: Uuid,
            createdBy: Uuid,
            kasse: Uuid,
            spenden: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                JournalEntryTable.insert {
                    it[JournalEntryTable.id] = id
                    it[entryDate] = LocalDate(2026, 6, 15)
                    it[description] = "Spende"
                    it[voucherReference] = "BELEG-1"
                    it[JournalEntryTable.createdBy] = createdBy
                    it[JournalEntryTable.status] = JournalEntryStatus.POSTED
                    it[postedAt] = KotlinLocalDateTime(2026, 6, 15, 12, 0)
                    it[createdAt] = KotlinLocalDateTime(2026, 6, 15, 11, 0)
                    it[JournalEntryTable.donorMemberId] = donorMemberId
                }
                PostingTable.insert {
                    it[PostingTable.id] = Uuid.random()
                    it[journalEntryId] = id
                    it[ledgerAccountId] = kasse
                    it[side] = PostingSide.DEBIT
                    it[amount] = java.math.BigDecimal("100.00")
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[costCenterId] = null
                }
                PostingTable.insert {
                    it[PostingTable.id] = Uuid.random()
                    it[journalEntryId] = id
                    it[ledgerAccountId] = spenden
                    it[side] = PostingSide.CREDIT
                    it[amount] = java.math.BigDecimal("100.00")
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[costCenterId] = null
                }
            }
            createdJournalEntryIds += id
            return id
        }

        // ── postalMailEnabled gate ───────────────────────────────────────

        test("postalMailEnabled=false rejects every dispatch method with 409, provider never called, no log row") {
            testApplication {
                val callCount = AtomicInteger(0)
                application {
                    install(StatusPages) { installPostalMailExceptionHandlers() }
                    routing { registerPostalMailTestRoutes(FakePostalMailProvider(callCounter = callCount)) }
                }
                val treasurer = createMember("postal-treasurer-gate@example.org", AccountRole.TREASURER, withAddress = true)
                val logCountBefore = transaction { PostalDeliveryLogTable.selectAll().count() }

                val beitragsrechnung =
                    client.post("/test/dispatch-beitragsrechnung?contributionId=${Uuid.random()}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                beitragsrechnung.status shouldBe HttpStatusCode.Conflict

                val spendenbescheinigung =
                    client.post("/test/dispatch-spendenbescheinigung?journalEntryId=${Uuid.random()}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                spendenbescheinigung.status shouldBe HttpStatusCode.Conflict

                transaction { PostalDeliveryLogTable.selectAll().count() } shouldBe logCountBefore
                callCount.get() shouldBe 0
            }
        }

        // ── Beitragsrechnung ─────────────────────────────────────────────

        test("dispatchBeitragsrechnungByPost: happy path writes one SENT log row and archives a Document") {
            testApplication {
                application {
                    install(StatusPages) { installPostalMailExceptionHandlers() }
                    routing { registerPostalMailTestRoutes(fakeProvider()) }
                }
                enablePostalMail()

                val treasurer = createMember("postal-treasurer-1@example.org", AccountRole.TREASURER, withAddress = true)
                val member = createMember("postal-member-1@example.org", AccountRole.MEMBER, withAddress = true)
                val tier = createTier()
                val contribution = createContribution(member, tier)

                val documentCountBefore = transaction { DocumentTable.selectAll().count() }
                val logCountBefore = transaction { PostalDeliveryLogTable.selectAll().count() }

                val response =
                    client.post("/test/dispatch-beitragsrechnung?contributionId=$contribution") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split(":")[0] shouldBe PostalDeliveryStatus.SENT.name

                transaction { DocumentTable.selectAll().count() } shouldBe documentCountBefore + 1
                transaction { PostalDeliveryLogTable.selectAll().count() } shouldBe logCountBefore + 1
            }
        }

        test("dispatchBeitragsrechnungByPost: incomplete address rejects with 409, no log row written") {
            testApplication {
                application {
                    install(StatusPages) { installPostalMailExceptionHandlers() }
                    routing { registerPostalMailTestRoutes(fakeProvider()) }
                }
                enablePostalMail()

                val treasurer = createMember("postal-treasurer-2@example.org", AccountRole.TREASURER, withAddress = true)
                val memberNoAddress = createMember("postal-member-noaddress@example.org", AccountRole.MEMBER, withAddress = false)
                val tier = createTier()
                val contribution = createContribution(memberNoAddress, tier)

                val logCountBefore = transaction { PostalDeliveryLogTable.selectAll().count() }
                val response =
                    client.post("/test/dispatch-beitragsrechnung?contributionId=$contribution") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict
                transaction { PostalDeliveryLogTable.selectAll().count() } shouldBe logCountBefore
            }
        }

        // ── Spendenbescheinigung ─────────────────────────────────────────

        test("dispatchSpendenbescheinigungByPost: happy path writes one SENT log row and archives a Document") {
            testApplication {
                application {
                    install(StatusPages) { installPostalMailExceptionHandlers() }
                    routing { registerPostalMailTestRoutes(fakeProvider()) }
                }
                enablePostalMail()
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[taxExemptionAuthority] = "Finanzamt Braunschweig"
                        it[taxExemptionDate] = LocalDate(2025, 1, 15)
                    }
                }

                val treasurer = createMember("postal-treasurer-3@example.org", AccountRole.TREASURER, withAddress = true)
                val donor = createMember("postal-donor-1@example.org", AccountRole.MEMBER, withAddress = true)
                val kasse = createLedgerAccount("9701", LedgerAccountType.ASSET)
                val spenden = createLedgerAccount("9801", LedgerAccountType.INCOME)
                val entry = postDonationEntry(donor, treasurer, kasse, spenden)

                val documentCountBefore = transaction { DocumentTable.selectAll().count() }
                val response =
                    client.post("/test/dispatch-spendenbescheinigung?journalEntryId=$entry") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split(":")[0] shouldBe PostalDeliveryStatus.SENT.name
                transaction { DocumentTable.selectAll().count() } shouldBe documentCountBefore + 1
            }
        }

        // ── Einladung ────────────────────────────────────────────────────

        test("dispatchEinladungByPost: happy path with 3 recipients writes 3 log rows via 3 distinct provider calls") {
            testApplication {
                val provider = fakeProvider()
                application {
                    install(StatusPages) { installPostalMailExceptionHandlers() }
                    routing { registerPostalMailTestRoutes(provider) }
                }
                enablePostalMail()

                val board = createMember("postal-board-1@example.org", AccountRole.BOARD, withAddress = true)
                val r1 = createMember("postal-recipient-1@example.org", AccountRole.MEMBER, withAddress = true, displayName = "Empf1")
                val r2 = createMember("postal-recipient-2@example.org", AccountRole.MEMBER, withAddress = true, displayName = "Empf2")
                val r3 = createMember("postal-recipient-3@example.org", AccountRole.MEMBER, withAddress = true, displayName = "Empf3")

                val logCountBefore = transaction { PostalDeliveryLogTable.selectAll().count() }
                val response =
                    client.post(
                        "/test/dispatch-einladung?title=Einladung&eventDateTime=2026-09-12T18:30:00" +
                            "&location=Vereinsheim&bodyText=Text&recipientMemberIds=$r1,$r2,$r3",
                    ) { header("X-Member-Id", board.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val entries = response.bodyAsText().split(";")
                entries.size shouldBe 3
                entries.all { it.startsWith(PostalDeliveryStatus.SENT.name) } shouldBe true

                transaction { PostalDeliveryLogTable.selectAll().count() } shouldBe logCountBefore + 3
                provider.callCounter.get() shouldBe 3
                provider.dispatchedRecipients.toSet() shouldBe setOf("Empf1", "Empf2", "Empf3")
            }
        }

        test("dispatchEinladungByPost: one recipient missing an address rejects the whole call with 409, zero log rows written") {
            testApplication {
                application {
                    install(StatusPages) { installPostalMailExceptionHandlers() }
                    routing { registerPostalMailTestRoutes(fakeProvider()) }
                }
                enablePostalMail()

                val board = createMember("postal-board-2@example.org", AccountRole.BOARD, withAddress = true)
                val withAddr = createMember("postal-recipient-4@example.org", AccountRole.MEMBER, withAddress = true)
                val withoutAddr = createMember("postal-recipient-5@example.org", AccountRole.MEMBER, withAddress = false)

                val logCountBefore = transaction { PostalDeliveryLogTable.selectAll().count() }
                val response =
                    client.post(
                        "/test/dispatch-einladung?title=Einladung&eventDateTime=2026-09-12T18:30:00" +
                            "&location=Vereinsheim&bodyText=Text&recipientMemberIds=$withAddr,$withoutAddr",
                    ) { header("X-Member-Id", board.toString()) }
                response.status shouldBe HttpStatusCode.Conflict
                transaction { PostalDeliveryLogTable.selectAll().count() } shouldBe logCountBefore
            }
        }

        test("dispatchEinladungByPost: exceeding MAX_POSTAL_INVITATION_RECIPIENTS rejects with 400, zero dispatch attempts") {
            testApplication {
                val callCount = AtomicInteger(0)
                application {
                    install(StatusPages) { installPostalMailExceptionHandlers() }
                    routing {
                        registerPostalMailTestRoutes(
                            FakePostalMailProvider(callCounter = callCount) { PostalDispatchOutcome.Dispatched("ref") },
                        )
                    }
                }
                enablePostalMail()

                val board = createMember("postal-board-3@example.org", AccountRole.BOARD, withAddress = true)
                // 51 ids -- one over the cap. They need not resolve to real members: the cap is
                // enforced before any recipient lookup happens.
                val tooMany = (1..51).map { Uuid.random() }.joinToString(",")

                val response =
                    client.post(
                        "/test/dispatch-einladung?title=Einladung&eventDateTime=2026-09-12T18:30:00" +
                            "&location=Vereinsheim&bodyText=Text&recipientMemberIds=$tooMany",
                    ) { header("X-Member-Id", board.toString()) }
                response.status shouldBe HttpStatusCode.BadRequest
                callCount.get() shouldBe 0
            }
        }

        // ── Role checks ──────────────────────────────────────────────────

        test(
            "role checks: MEMBER forbidden everywhere; TREASURER can dispatch financial docs but not Einladung; BOARD can dispatch all three",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPostalMailExceptionHandlers() }
                    routing { registerPostalMailTestRoutes(fakeProvider()) }
                }
                enablePostalMail()
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[taxExemptionAuthority] = "Finanzamt Braunschweig"
                        it[taxExemptionDate] = LocalDate(2025, 1, 15)
                    }
                }

                val plainMember = createMember("postal-role-member@example.org", AccountRole.MEMBER, withAddress = true)
                val treasurer = createMember("postal-role-treasurer@example.org", AccountRole.TREASURER, withAddress = true)
                val board = createMember("postal-role-board@example.org", AccountRole.BOARD, withAddress = true)
                val recipient = createMember("postal-role-recipient@example.org", AccountRole.MEMBER, withAddress = true)
                val tier = createTier()
                val contributionForMember = createContribution(recipient, tier, year = 2021)
                val contributionForTreasurer = createContribution(recipient, tier, year = 2022)
                val contributionForBoard = createContribution(recipient, tier, year = 2023)

                client
                    .post("/test/dispatch-beitragsrechnung?contributionId=$contributionForMember") {
                        header("X-Member-Id", plainMember.toString())
                    }.status shouldBe HttpStatusCode.Forbidden

                client
                    .post(
                        "/test/dispatch-einladung?title=X&eventDateTime=2026-09-12T18:30:00&location=Y&bodyText=Z" +
                            "&recipientMemberIds=$recipient",
                    ) { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden

                client
                    .post("/test/dispatch-beitragsrechnung?contributionId=$contributionForTreasurer") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.OK

                client
                    .post("/test/dispatch-beitragsrechnung?contributionId=$contributionForBoard") {
                        header("X-Member-Id", board.toString())
                    }.status shouldBe HttpStatusCode.OK

                client
                    .post(
                        "/test/dispatch-einladung?title=X&eventDateTime=2026-09-12T18:30:00&location=Y&bodyText=Z" +
                            "&recipientMemberIds=$recipient",
                    ) { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        // ── Provider failure is a legitimate business outcome ─────────────

        test("a Failed provider outcome writes a FAILED log row and the RPC call returns normally (does not throw)") {
            testApplication {
                application {
                    install(StatusPages) { installPostalMailExceptionHandlers() }
                    routing {
                        registerPostalMailTestRoutes(
                            FakePostalMailProvider { PostalDispatchOutcome.Failed("Letterxpress returned HTTP 500") },
                        )
                    }
                }
                enablePostalMail()

                val treasurer = createMember("postal-treasurer-fail@example.org", AccountRole.TREASURER, withAddress = true)
                val member = createMember("postal-member-fail@example.org", AccountRole.MEMBER, withAddress = true)
                val tier = createTier()
                val contribution = createContribution(member, tier)

                val response =
                    client.post("/test/dispatch-beitragsrechnung?contributionId=$contribution") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                val parts = response.bodyAsText().split(":")
                parts[0] shouldBe PostalDeliveryStatus.FAILED.name
                parts.last() shouldBe "Letterxpress returned HTTP 500"
            }
        }

        // ── Read ───────────────────────────────────────────────────────

        test("listPostalDeliveryLog: TREASURER/BOARD/ADMIN can read, MEMBER is forbidden, newest first") {
            testApplication {
                application {
                    install(StatusPages) { installPostalMailExceptionHandlers() }
                    routing { registerPostalMailTestRoutes(fakeProvider()) }
                }
                enablePostalMail()

                val treasurer = createMember("postal-list-treasurer@example.org", AccountRole.TREASURER, withAddress = true)
                val plainMember = createMember("postal-list-member@example.org", AccountRole.MEMBER, withAddress = true)
                val recipient = createMember("postal-list-recipient@example.org", AccountRole.MEMBER, withAddress = true)
                val tier = createTier()
                val contributionA = createContribution(recipient, tier, year = 2021)
                val contributionB = createContribution(recipient, tier, year = 2022)

                client.post("/test/dispatch-beitragsrechnung?contributionId=$contributionA") {
                    header("X-Member-Id", treasurer.toString())
                }
                client.post("/test/dispatch-beitragsrechnung?contributionId=$contributionB") {
                    header("X-Member-Id", treasurer.toString())
                }

                val forbidden = client.get("/test/list-delivery-log") { header("X-Member-Id", plainMember.toString()) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val ok = client.get("/test/list-delivery-log") { header("X-Member-Id", treasurer.toString()) }
                ok.status shouldBe HttpStatusCode.OK
                val timestamps = ok.bodyAsText().split(";").filter { it.isNotBlank() }
                (timestamps.size >= 2) shouldBe true
                timestamps shouldBe timestamps.sortedDescending()
            }
        }
    })

/** No-argument convenience factory -- a [FakePostalMailProvider] that always succeeds. */
private fun fakeProvider(): FakePostalMailProvider = FakePostalMailProvider()

/**
 * Configurable [PostalMailProvider] test double -- no HTTP layer at all. [behavior] decides the
 * outcome per call (defaults to always-[PostalDispatchOutcome.Dispatched]); [callCounter] and
 * [dispatchedRecipients] let tests assert how many times, and with which distinct addresses,
 * dispatch was actually attempted.
 */
private class FakePostalMailProvider(
    val callCounter: AtomicInteger = AtomicInteger(0),
    val dispatchedRecipients: MutableList<String> = mutableListOf(),
    val behavior: (String) -> PostalDispatchOutcome = { PostalDispatchOutcome.Dispatched("fake-provider-ref") },
) : PostalMailProvider {
    override suspend fun dispatchLetter(
        pdfBytes: ByteArray,
        recipientName: String,
        recipientStreet: String,
        recipientPostalCode: String,
        recipientCity: String,
        recipientCountry: String,
    ): PostalDispatchOutcome {
        callCounter.incrementAndGet()
        dispatchedRecipients += recipientName
        return behavior(recipientName)
    }
}

private fun StatusPagesConfig.installPostalMailExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
    exception<BadRequestException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.BadRequest)
    }
}

/** Shared throwaway routes for [PostalMailServiceTest] -- string encodings kept deliberately simple/parseable. */
private fun Route.registerPostalMailTestRoutes(provider: PostalMailProvider) {
    val storageRoot = File("build/test-document-storage-postal-mail")

    post("/test/dispatch-beitragsrechnung") {
        val service = PostalMailService(call, storageRoot, provider)
        val contributionId = call.request.queryParameters["contributionId"]!!
        val dto = service.dispatchBeitragsrechnungByPost(contributionId)
        call.respondText("${dto.status}:${dto.recipientMemberId}:${dto.documentReference}:${dto.errorMessage}")
    }
    post("/test/dispatch-spendenbescheinigung") {
        val service = PostalMailService(call, storageRoot, provider)
        val journalEntryId = call.request.queryParameters["journalEntryId"]!!
        val dto = service.dispatchSpendenbescheinigungByPost(journalEntryId)
        call.respondText("${dto.status}:${dto.recipientMemberId}:${dto.documentReference}:${dto.errorMessage}")
    }
    post("/test/dispatch-einladung") {
        val service = PostalMailService(call, storageRoot, provider)
        val q = call.request.queryParameters
        val input =
            PostalInvitationDispatchInput(
                title = q["title"]!!,
                eventDateTime = kotlinx.datetime.LocalDateTime.parse(q["eventDateTime"]!!),
                location = q["location"]!!,
                bodyText = q["bodyText"]!!,
                recipientMemberIds = q["recipientMemberIds"]!!.split(","),
            )
        val dtos = service.dispatchEinladungByPost(input)
        call.respondText(dtos.joinToString(";") { "${it.status}:${it.recipientMemberId}" })
    }
    get("/test/list-delivery-log") {
        val service = PostalMailService(call, storageRoot, provider)
        val dtos = service.listPostalDeliveryLog()
        call.respondText(dtos.joinToString(";") { it.dispatchedAt.toString() })
    }
}
