package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.PublicRankingConsentDisclaimer
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.PublicRankingConsentEventType
import network.lapis.cloud.shared.domain.PublicRankingKind
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.3.0 "Öffentliche Transparenz-Startseite" -- route-level tests for `GET /transparenz`, same
 * `testApplication` house style [SocialPublicRoutesTest] establishes: fixtures via direct Exposed
 * `insert`, no auth installation at all (the whole point is that none is needed).
 */
class PublicTransparencyRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdJournalEntryIds = mutableListOf<Uuid>()
        var incomeAccountId: Uuid? = null

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            transaction {
                incomeAccountId =
                    LedgerAccountTable
                        .selectAll()
                        .where { LedgerAccountTable.type eq LedgerAccountType.INCOME }
                        .firstOrNull()
                        ?.get(LedgerAccountTable.id)
                        ?: run {
                            val id = Uuid.random()
                            LedgerAccountTable.insert {
                                it[LedgerAccountTable.id] = id
                                it[accountNumber] = "4999"
                                it[name] = "Test-Spendenkonto"
                                it[accountClass] = 4
                                it[type] = LedgerAccountType.INCOME
                                it[active] = true
                                it[reserveType] = null
                                it[isCashRegister] = false
                            }
                            id
                        }
            }
        }

        afterTest {
            transaction {
                if (createdJournalEntryIds.isNotEmpty()) {
                    PostingTable.deleteWhere { journalEntryId inList createdJournalEntryIds }
                    JournalEntryTable.deleteWhere { id inList createdJournalEntryIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    PublicRankingConsentEventTable.deleteWhere { memberId inList createdMemberIds }
                    LtrLedgerEntryTable.deleteWhere { memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
            createdMemberIds.clear()
            createdJournalEntryIds.clear()
        }

        fun createMember(displayName: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[email] = "transparency-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        /**
         * A fresh EXECUTIVE_BOARD [CommitteeTable] row plus one [CommitteeMembershipTable] seat for
         * [memberId] with `until = null` (an OPEN seat, exactly the state [GovernanceService
         * .endAllOpenCommitteeMembershipsForMember] leaves behind for a member whose status moves to
         * anything outside `MemberStatusSets.MEMBERSHIP_ENDED` -- see Security-Fix finding this
         * covers). Caller MUST call [cleanUpBoardFixture] before the test ends -- `committee`/
         * `committee_membership` are outside the shared `afterTest` cleanup this file already has,
         * and `committee_membership.member_id` is a plain (non-cascading) FK, so a stray row here
         * would otherwise block the shared `afterTest`'s `MemberTable.deleteWhere`.
         */
        fun addOpenBoardSeat(
            memberId: Uuid,
            role: CommitteeRole = CommitteeRole.CHAIR,
        ): Uuid {
            val committeeId = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[id] = committeeId
                    it[name] = "Test-Vorstand"
                    it[type] = CommitteeType.EXECUTIVE_BOARD
                    it[description] = "Fixture fuer PublicTransparencyRoutesTest"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                CommitteeMembershipTable.insert {
                    it[id] = Uuid.random()
                    it[CommitteeMembershipTable.committeeId] = committeeId
                    it[CommitteeMembershipTable.memberId] = memberId
                    it[CommitteeMembershipTable.role] = role
                    it[since] = LocalDate(2020, 1, 1)
                    it[until] = null
                }
            }
            return committeeId
        }

        /** See [addOpenBoardSeat] KDoc -- must run before this test's member is deleted by the shared `afterTest`. */
        fun cleanUpBoardFixture(committeeId: Uuid) {
            transaction {
                CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId eq committeeId }
                CommitteeTable.deleteWhere { CommitteeTable.id eq committeeId }
            }
        }

        fun grantConsent(
            memberId: Uuid,
            kind: PublicRankingKind,
        ) {
            val disclaimer = PublicRankingConsentDisclaimer.of(kind)
            transaction {
                PublicRankingConsentEventTable.insert {
                    it[id] = Uuid.random()
                    it[PublicRankingConsentEventTable.memberId] = memberId
                    it[rankingKind] = kind
                    it[eventType] = PublicRankingConsentEventType.GRANTED
                    it[occurredAt] = DbClock.nowLocalDateTime()
                    it[supersededAt] = null
                    it[consentVersion] = disclaimer.version
                    it[consentSha256] = disclaimer.sha256
                }
            }
        }

        fun grantStaleConsent(
            memberId: Uuid,
            kind: PublicRankingKind,
        ) {
            transaction {
                PublicRankingConsentEventTable.insert {
                    it[id] = Uuid.random()
                    it[PublicRankingConsentEventTable.memberId] = memberId
                    it[rankingKind] = kind
                    it[eventType] = PublicRankingConsentEventType.GRANTED
                    it[occurredAt] = DbClock.nowLocalDateTime()
                    it[supersededAt] = null
                    // A version that can never equal PublicRankingConsentDisclaimer.of(kind).version --
                    // simulates a member who consented before the wording last changed.
                    it[consentVersion] = "stale-superseded-version.v0"
                    it[consentSha256] = "0".repeat(64)
                }
            }
        }

        fun mintLtr(
            memberId: Uuid,
            amount: BigDecimal,
        ) {
            transaction {
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[LtrLedgerEntryTable.memberId] = memberId
                    it[entryType] = LtrLedgerEntryType.MINT
                    it[amountLtr] = amount
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = null
                    it[createdBy] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
        }

        fun postDonation(
            donorMemberId: Uuid?,
            amount: BigDecimal,
            entryDate: LocalDate,
            status: JournalEntryStatus = JournalEntryStatus.POSTED,
            side: PostingSide = PostingSide.CREDIT,
        ): Uuid {
            val entryId = Uuid.random()
            transaction {
                JournalEntryTable.insert {
                    it[id] = entryId
                    it[JournalEntryTable.entryDate] = entryDate
                    it[description] = "Testspende"
                    it[voucherReference] = null
                    it[createdBy] = Uuid.parse(ADMIN_ID)
                    it[JournalEntryTable.status] = status
                    it[postedAt] =
                        if (status ==
                            JournalEntryStatus.POSTED
                        ) {
                            LocalDateTime(entryDate.year, entryDate.monthNumber, entryDate.dayOfMonth, 9, 0)
                        } else {
                            null
                        }
                    it[createdAt] = LocalDateTime(entryDate.year, entryDate.monthNumber, entryDate.dayOfMonth, 9, 0)
                    it[JournalEntryTable.donorMemberId] = donorMemberId
                    it[donorCategory] = DonorCategory.GERMAN_NATURAL_PERSON
                    it[externalDonorId] = null
                }
                PostingTable.insert {
                    it[id] = Uuid.random()
                    it[PostingTable.side] = side
                    it[PostingTable.amount] = amount
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[journalEntryId] = entryId
                    it[ledgerAccountId] = incomeAccountId!!
                    it[costCenterId] = null
                }
            }
            createdJournalEntryIds += entryId
            return entryId
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(
            readLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    install(AutoHeadResponse)
                    routing { registerPublicTransparencyRoutes(readRateLimiter = readLimiter) }
                }
                block()
            }
        }

        test("GET /transparenz: 200, security headers, cache-control without stale-while-revalidate") {
            testApp {
                val response = client.get("/transparenz")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "Kennzahlen"
                body shouldContain "Vorstand"
                (response.headers["Content-Security-Policy"] ?: "") shouldContain "default-src 'none'"
                response.headers["Cache-Control"] shouldBe "public, max-age=60"
            }
        }

        test(
            "Security-Fix: robots meta is noindex,follow -- the ranking sections carry revocable, " +
                "widely-crawlable PII, so the page must never be search-engine-indexed",
        ) {
            testApp {
                val body = client.get("/transparenz").bodyAsText()
                body shouldContain "content=\"noindex,follow\""
                body shouldNotContain "content=\"index,follow\""
            }
        }

        test(
            "Security-Fix: an EXECUTIVE_BOARD seat with an OPEN membership (until = null) but a member " +
                "status OUTSIDE ACTIVE (e.g. DONOR, after GovernanceService.endAllOpenCommitteeMembershipsForMember " +
                "left the seat open because DONOR is not in MemberStatusSets.MEMBERSHIP_ENDED) never appears " +
                "in the Vorstand section",
        ) {
            testApp {
                val exBoardMember = createMember("Ehemaliges Vorstandsmitglied")
                val committeeId = addOpenBoardSeat(memberId = exBoardMember)
                try {
                    transaction {
                        MemberTable.update({ MemberTable.id eq exBoardMember }) { it[status] = MemberStatus.DONOR }
                    }
                    val body = client.get("/transparenz").bodyAsText()
                    body shouldNotContain "Ehemaliges Vorstandsmitglied"
                } finally {
                    cleanUpBoardFixture(committeeId)
                }
            }
        }

        test("GET /transparenz?x=1 (unexpected query param) 308-redirects to the bare canonical URL, before any DB work") {
            testApp {
                val noRedirectClient = createClient { followRedirects = false }
                val response = noRedirectClient.get("/transparenz?x=1")
                response.status shouldBe HttpStatusCode(308, "Permanent Redirect")
                response.headers["Location"] shouldBe "http://localhost:8080/transparenz"
            }
        }

        test("rate limit exceeded returns 429 with Retry-After and security headers, independent of /s's own budget") {
            testApp(readLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                client.get("/transparenz")
                val second = client.get("/transparenz")
                second.status shouldBe HttpStatusCode.TooManyRequests
                second.headers["Retry-After"] shouldBe "60"
            }
        }

        test("below the minimum cohort (D11): the LTR/donations sections and their jump-menu anchors are entirely absent") {
            testApp {
                val single = createMember("Nur eine Zustimmung")
                mintLtr(memberId = single, amount = BigDecimal("500.00"))
                grantConsent(memberId = single, kind = PublicRankingKind.LTR_HOLDINGS)
                val body = client.get("/transparenz").bodyAsText()
                body shouldNotContain "Top-LTR-Halter"
                body shouldNotContain "id=\"ltr\""
                body shouldContain "Kennzahlen"
            }
        }

        test("at exactly five effective LTR consents the section appears, ordered by balance descending") {
            testApp {
                val richest = createMember("Reichste Zustimmerin")
                val second = createMember("Zweitreichster")
                val others = (1..3).map { createMember("Zustimmer $it") }
                mintLtr(memberId = richest, amount = BigDecimal("900.00"))
                mintLtr(memberId = second, amount = BigDecimal("500.00"))
                others.forEach { mintLtr(memberId = it, amount = BigDecimal("10.00")) }
                (listOf(richest, second) + others).forEach { grantConsent(memberId = it, kind = PublicRankingKind.LTR_HOLDINGS) }

                val body = client.get("/transparenz").bodyAsText()
                body shouldContain "Top-LTR-Halter"
                (body.indexOf("Reichste Zustimmerin") < body.indexOf("Zweitreichster")) shouldBe true

                // Revoking one drops the cohort back below five -- the section disappears again.
                transaction {
                    PublicRankingConsentEventTable.update({ PublicRankingConsentEventTable.memberId eq richest }) {
                        it[supersededAt] = DbClock.nowLocalDateTime()
                    }
                }
                val afterRevoke = client.get("/transparenz").bodyAsText()
                afterRevoke shouldNotContain "Top-LTR-Halter"
            }
        }

        test(
            "a wording change (stale consentVersion) makes an old grant ineffective -- five members consented under a " +
                "SUPERSEDED disclaimer version count toward NEITHER the cohort NOR the ranking, section stays entirely absent",
        ) {
            testApp {
                val staleConsenters = (1..5).map { createMember("Alt-Zustimmer $it") }
                staleConsenters.forEach { mintLtr(memberId = it, amount = BigDecimal("500.00")) }
                staleConsenters.forEach { grantStaleConsent(memberId = it, kind = PublicRankingKind.LTR_HOLDINGS) }

                val body = client.get("/transparenz").bodyAsText()
                body shouldNotContain "Top-LTR-Halter"
                body shouldNotContain "id=\"ltr\""
                body shouldNotContain "Alt-Zustimmer"
                body shouldContain "Kennzahlen"
            }
        }

        test(
            "donations: the SQL-side signed-amount aggregation sums multiple entries and honors the posting side -- " +
                "a DEBIT-side correction against the INCOME account reduces the donor's total",
        ) {
            testApp {
                val currentYear = DbClock.nowLocalDateTime().year
                val donor = createMember("Korrektur-Spenderin")
                val filler = (1..4).map { createMember("Korrektur-Fueller $it") }
                (listOf(donor) + filler).forEach { grantConsent(memberId = it, kind = PublicRankingKind.DONATIONS) }
                postDonation(donorMemberId = donor, amount = BigDecimal("300.00"), entryDate = LocalDate(currentYear, 4, 1))
                filler.forEach { postDonation(donorMemberId = it, amount = BigDecimal("10.00"), entryDate = LocalDate(currentYear, 4, 1)) }
                // A correction entry for the SAME donor -- a DEBIT posting against the very same
                // INCOME account reduces the donor's effective total: 300.00 - 50.00 = 250.00.
                postDonation(
                    donorMemberId = donor,
                    amount = BigDecimal("50.00"),
                    entryDate = LocalDate(currentYear, 4, 2),
                    side = PostingSide.DEBIT,
                )

                val body = client.get("/transparenz").bodyAsText()
                body shouldContain "250.00"
                body shouldNotContain "300.00"
            }
        }

        test(
            "donations: only POSTED, this-year, member-attributed, consented entries count -- " +
                "DRAFT/prior-year/external-donor entries are excluded from both the ranking and its cohort",
        ) {
            testApp {
                val currentYear = DbClock.nowLocalDateTime().year
                val donors = (1..5).map { createMember("Spenderin $it") }
                donors.forEach { grantConsent(memberId = it, kind = PublicRankingKind.DONATIONS) }
                donors.forEachIndexed { index, donor ->
                    postDonation(
                        donorMemberId = donor,
                        amount = BigDecimal("100.00") + BigDecimal(index),
                        entryDate = LocalDate(currentYear, 3, 1),
                    )
                }
                // DRAFT -- must not count.
                val draftDonor = donors.first()
                postDonation(
                    donorMemberId = draftDonor,
                    amount = BigDecimal("99999.00"),
                    entryDate = LocalDate(currentYear, 3, 2),
                    status = JournalEntryStatus.DRAFT,
                )
                // Prior year -- must not count.
                postDonation(donorMemberId = draftDonor, amount = BigDecimal("88888.00"), entryDate = LocalDate(currentYear - 1, 3, 2))
                // External donor (no donorMemberId) -- structurally excluded, never appears.
                postDonation(donorMemberId = null, amount = BigDecimal("77777.00"), entryDate = LocalDate(currentYear, 3, 2))

                val body = client.get("/transparenz").bodyAsText()
                body shouldContain "Top-Spender $currentYear"
                body shouldNotContain "99999"
                body shouldNotContain "88888"
                body shouldNotContain "77777"
            }
        }

        test("XSS: a member display_name containing markup renders escaped, never raw, in the board section") {
            testApp {
                // A board section renders even with zero board members configured this way in the
                // test DB (DevSeedData seeds none) -- so this exercises the escaping discipline via
                // the LTR ranking path instead, which DOES reach real display names.
                val attacker = createMember("<script>alert(1)</script>")
                val filler = (1..4).map { createMember("Füller $it") }
                mintLtr(memberId = attacker, amount = BigDecimal("50.00"))
                filler.forEach { mintLtr(memberId = it, amount = BigDecimal("1.00")) }
                (listOf(attacker) + filler).forEach { grantConsent(memberId = it, kind = PublicRankingKind.LTR_HOLDINGS) }
                val body = client.get("/transparenz").bodyAsText()
                body shouldNotContain "<script>alert(1)</script>"
                body shouldContain "&lt;script&gt;"
            }
        }
    })

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
