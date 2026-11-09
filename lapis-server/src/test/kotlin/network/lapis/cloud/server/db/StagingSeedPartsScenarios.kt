package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.activism.ActivismAccessPolicy
import network.lapis.cloud.server.activism.ActivismSignalStore
import network.lapis.cloud.server.activism.ActivismStore
import network.lapis.cloud.server.audit.AuditChainVerifier
import network.lapis.cloud.server.db.generated.ActivismActionTable
import network.lapis.cloud.server.db.generated.ActivismContributionTable
import network.lapis.cloud.server.db.generated.ActivismResponsibleTable
import network.lapis.cloud.server.db.generated.ActivismSignalTable
import network.lapis.cloud.server.db.generated.ActivismTaskTable
import network.lapis.cloud.server.db.generated.ActivismTextBlockTable
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.CrowdfundingReactionTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberFamilyLinkTable
import network.lapis.cloud.server.db.generated.MemberFamilyTable
import network.lapis.cloud.server.db.generated.MemberHonorTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PoliticianProfileTable
import network.lapis.cloud.server.db.generated.PoliticianReactionTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotTable
import network.lapis.cloud.server.db.generated.PublicRankingConsentEventTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.db.generated.TenderAwardTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.membermap.AddressGroup
import network.lapis.cloud.server.membermap.PostalCodeCentroidIndex
import network.lapis.cloud.server.membermap.aggregateMemberMap
import network.lapis.cloud.server.rpc.AuctionBidView
import network.lapis.cloud.server.rpc.CrowdfundingWeightDecay
import network.lapis.cloud.server.rpc.PublicRankingConsentStore
import network.lapis.cloud.server.rpc.SocialVisibility
import network.lapis.cloud.server.rpc.computeAuctionOutcome
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.tender.TenderOptions
import network.lapis.cloud.server.tender.TenderStore
import network.lapis.cloud.server.tender.tenderBidderEligible
import network.lapis.cloud.server.testdb.PostgresTestSupport
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ActivismContributionStatus
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ActivismViewerRole
import network.lapis.cloud.shared.domain.AnniversaryCalendar
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.CrowdfundingReactionValue
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.FamilyMemberRole
import network.lapis.cloud.shared.domain.FamilyMembershipRules
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PublicRankingConsentEventType
import network.lapis.cloud.shared.domain.PublicRankingKind
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderMotionIneligibility
import network.lapis.cloud.shared.domain.TenderStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val MY_PREFIX = "0000000a-0000-0000-0000-7"

/**
 * The V1.9.205 top-up parts on both databases. Every scenario gets a FRESH, isolated, seeded database (an isolated in-memory H2 on the normal lane,
 * a clone of the migrated template on the Postgres lane), so scenarios cannot influence each other. The read side is checked through the same
 * domain functions the services use (decay, auction outcome, social visibility, tender options and store, consent state) -- the RPC wiring itself
 * is not exercised here.
 */
abstract class StagingSeedPartsScenarios(
    private val testDb: TestDatabase,
) : FunSpec({
        val seedPassword = "ein-starkes-testpasswort"

        beforeSpec {
            System.setProperty("net.fortuna.ical4j.timezone.update.enabled", "false")
            System.setProperty("net.fortuna.ical4j.recur.maxincrementcount", "1000")
            testDb.activate()
        }
        afterSpec { testDb.deactivate() }
        installLaneGuards(db = testDb)

        fun withFresh(body: (Database) -> Unit) {
            val pg = if (testDb is TestDatabase.Postgres) PostgresTestSupport.createDatabase() else null
            try {
                val db = pg?.database ?: IsolatedH2Database.create()
                StagingSeedData.seedWith(seedPassword = seedPassword, database = db, sepaKey = null)
                body(db)
            } finally {
                pg?.close()
            }
        }

        fun full(db: Database): StagingTopUpOutcome.Applied =
            StagingSeedTopUp.topUpWith(database = db).shouldBeInstanceOf<StagingTopUpOutcome.Applied>()

        fun ltrOnly(db: Database) =
            StagingSeedTopUp.topUpWith(database = db, parts = emptyList()).shouldBeInstanceOf<StagingTopUpOutcome.Applied>()

        fun mine(id: Uuid): Boolean = id.toString().startsWith(MY_PREFIX)

        @Suppress("UNCHECKED_CAST")
        fun JdbcTransaction.ids(table: Table): List<String> {
            val idColumn = table.columns.first { it.name == "id" } as Column<Uuid>
            return table
                .select(idColumn)
                .map { it[idColumn] }
                .filter { mine(it) }
                .map { it.toString() }
                .sorted()
        }

        val partTables: List<Table> =
            listOf(
                CrowdfundingProjectTable,
                CrowdfundingReactionTable,
                AuctionTable,
                AuctionBidTable,
                SocialPostTable,
                SocialPostBoostTable,
                PoliticianProfileTable,
                PoliticianReactionTable,
                CommitteeTable,
                CommitteeMembershipTable,
                MeetingTable,
                AgendaItemTable,
                AttendanceTable,
                MotionTable,
                TenderTable,
                TenderBidTable,
                TenderAwardTable,
                EventTable,
                EventRegistrationTable,
                PriceOracleSnapshotTable,
                LtrLedgerEntryTable,
                MemberFamilyTable,
                MemberFamilyLinkTable,
                MemberHonorTable,
                ActivismActionTable,
                ActivismTaskTable,
                ActivismTextBlockTable,
                ActivismContributionTable,
            )

        fun JdbcTransaction.snapshot(): List<String> =
            partTables.map { "${it.tableName}=${ids(it)}" } +
                MemberTable
                    .selectAll()
                    .orderBy(MemberTable.id, SortOrder.ASC)
                    .map {
                        "member ${it[MemberTable.id]} ${it[MemberTable.postalCode]} ${it[MemberTable.dateOfBirth]} ${it[MemberTable.status]}"
                    } +
                StagingSeedActivism.actions.map { a ->
                    "action ${a.index} responsible=${ActivismStore.responsibleMemberIds(a.id).size} " +
                        "signals=${ActivismSignalTable.selectAll().where { ActivismSignalTable.actionId eq a.id }.count()}"
                } +
                PublicRankingConsentEventTable.selectAll().count().toString() +
                ResolutionTable.selectAll().count().toString() +
                AuditLogEntryTable.selectAll().count().toString()

        fun JdbcTransaction.balance(member: Int): BigDecimal = LedgerBackedLtrBalanceProvider().freeBalance(SeedIds.member(member))

        fun JdbcTransaction.assertNoNegativeBalance() {
            (1..40).forEach { (balance(it) >= BigDecimal.ZERO) shouldBe true }
        }

        test("all parts apply: none failed, every part wrote, the row counts are as planned") {
            withFresh { db ->
                val outcome = full(db)
                outcome.parts.map { it.part } shouldBe StagingSeedTopUp.PARTS.map { it.name }
                outcome.parts.none { it.failed } shouldBe true
                outcome.parts.all { it.inserted > 0 } shouldBe true
                transaction(db) {
                    fun count(table: Table) = ids(table).size
                    count(CrowdfundingProjectTable) shouldBe 5
                    count(CrowdfundingReactionTable) shouldBe 12
                    count(AuctionTable) shouldBe 5
                    count(AuctionBidTable) shouldBe 7
                    count(SocialPostTable) shouldBe 12
                    count(SocialPostBoostTable) shouldBe 4
                    count(PoliticianProfileTable) shouldBe 5
                    count(PoliticianReactionTable) shouldBe 10
                    count(CommitteeTable) shouldBe 2
                    count(CommitteeMembershipTable) shouldBe 9
                    count(MeetingTable) shouldBe 3
                    count(AgendaItemTable) shouldBe 6
                    count(AttendanceTable) shouldBe 9
                    count(MotionTable) shouldBe 5
                    count(TenderTable) shouldBe 4
                    count(TenderBidTable) shouldBe 8
                    count(TenderAwardTable) shouldBe 1
                    count(EventTable) shouldBe 5
                    count(EventRegistrationTable) shouldBe 40
                    count(PriceOracleSnapshotTable) shouldBe 2430
                    count(MemberFamilyTable) shouldBe 3
                    count(MemberFamilyLinkTable) shouldBe 7
                    count(MemberHonorTable) shouldBe 4
                    count(ActivismActionTable) shouldBe 5
                    count(ActivismTaskTable) shouldBe 5
                    count(ActivismTextBlockTable) shouldBe 1
                    count(ActivismContributionTable) shouldBe 12
                    ActivismResponsibleTable.selectAll().count() shouldBe 6L
                    ActivismSignalTable.selectAll().count() shouldBe 6L
                    MemberTable.selectAll().count { it[MemberTable.postalCode] != null } shouldBe 25
                    MemberTable.selectAll().count { it[MemberTable.dateOfBirth] != null } shouldBe 12
                    PublicRankingConsentEventTable.selectAll().count() shouldBe 2L
                    MotionTable.selectAll().count { mine(it[MotionTable.id]) && it[MotionTable.resolutionId] != null } shouldBe 3
                }
            }
        }

        test("idempotent: a second run inserts nothing, fails nothing and changes no table") {
            withFresh { db ->
                full(db)
                val before = transaction(db) { snapshot() }
                val second = full(db)
                second.parts.none { it.failed } shouldBe true
                second.parts.all { it.inserted == 0 } shouldBe true
                (second.result.mintsInserted + second.result.transfersInserted) shouldBe 0
                transaction(db) { snapshot() } shouldContainExactlyInAnyOrder before
            }
        }

        test("balances as the LTR views compute them equal the pinned end balances; none is negative") {
            withFresh { db ->
                full(db)
                transaction(db) {
                    assertNoNegativeBalance()
                    EXPECTED_END_BALANCES.forEach { (member, expected) -> balance(member).compareTo(BigDecimal(expected)) shouldBe 0 }
                    (1..40).filter { it !in EXPECTED_END_BALANCES.keys }.forEach { balance(it).signum() shouldBe 0 }
                }
            }
        }

        test("the audit chain is valid after all parts and grew by exactly the audited events") {
            withFresh { db ->
                val before = ltrOnly(db).let { transaction(db) { AuditLogEntryTable.selectAll().count() } }
                full(db)
                transaction(db) {
                    val rows = AuditLogEntryTable.selectAll().orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.ASC).toList()
                    AuditChainVerifier.verify(rows).valid shouldBe true
                    // 1 legal removal + 3 resolutions + 3 tender publications + 1 award
                    (rows.size - before) shouldBe 8L
                }
            }
        }

        test("crowdfunding as the views derive it: states, auto-approval, reaction counts") {
            withFresh { db ->
                full(db)
                transaction(db) {
                    val now = DbClock.nowLocalDateTime()
                    val rows =
                        CrowdfundingProjectTable
                            .selectAll()
                            .filter {
                                mine(it[CrowdfundingProjectTable.id])
                            }.sortedBy { it[CrowdfundingProjectTable.id].toString() }
                    val effective =
                        rows.map {
                            val status = it[CrowdfundingProjectTable.status]
                            if (status == CrowdfundingProjectStatus.PENDING &&
                                CrowdfundingWeightDecay.isAutoApproved(submittedAt = it[CrowdfundingProjectTable.submittedAt], now = now)
                            ) {
                                CrowdfundingProjectStatus.APPROVED
                            } else {
                                status
                            }
                        }
                    effective shouldBe
                        listOf(
                            CrowdfundingProjectStatus.APPROVED,
                            CrowdfundingProjectStatus.REJECTED,
                            CrowdfundingProjectStatus.APPROVED,
                            CrowdfundingProjectStatus.APPROVED,
                            CrowdfundingProjectStatus.PENDING,
                        )
                    val reactions = CrowdfundingReactionTable.selectAll().filter { mine(it[CrowdfundingReactionTable.id]) }
                    val project3 = SeedIds.crowdfunding(3)
                    reactions.count {
                        it[CrowdfundingReactionTable.projectId] == project3 &&
                            it[CrowdfundingReactionTable.reactionValue] == CrowdfundingReactionValue.LIKE
                    } shouldBe
                        6
                    reactions.count {
                        it[CrowdfundingReactionTable.projectId] == project3 &&
                            it[CrowdfundingReactionTable.reactionValue] == CrowdfundingReactionValue.DISLIKE
                    } shouldBe
                        1
                }
            }
        }

        test("auctions as the views derive them: open ones lead and price as planned, settled ones carry winner and price") {
            withFresh { db ->
                full(db)
                transaction(db) {
                    val clock = SeedClock.capture(TimeZone.of("Europe/Berlin"))
                    StagingSeedAuctions.auctions.forEach { planned ->
                        val row = AuctionTable.selectAll().where { AuctionTable.id eq planned.id }.single()
                        val views =
                            AuctionBidTable.selectAll().where { AuctionBidTable.auctionId eq planned.id }.map {
                                AuctionBidView(
                                    bidId = it[AuctionBidTable.id],
                                    bidderMemberId = it[AuctionBidTable.bidderMemberId],
                                    maxBidLtr = it[AuctionBidTable.maxBidLtr],
                                    createdAt = it[AuctionBidTable.createdAt],
                                )
                            }
                        val outcome =
                            computeAuctionOutcome(
                                startingBidLtr = row[AuctionTable.startingBidLtr],
                                minIncrementLtr = BigDecimal("0.01"),
                                bids = views,
                            )
                        if (row[AuctionTable.status] == AuctionStatus.OPEN) {
                            (row[AuctionTable.endsAt] > clock.now) shouldBe true
                        }
                        if (row[AuctionTable.status] == AuctionStatus.SETTLED && planned.buyNowPrice == null) {
                            row[AuctionTable.winnerMemberId] shouldBe outcome.leaderMemberId
                            row[AuctionTable.finalPriceLtr]!!.compareTo(outcome.currentPriceLtr) shouldBe 0
                        }
                    }
                    val first = AuctionBidTable.selectAll().where { AuctionBidTable.auctionId eq SeedIds.auctions(1) }.toList()
                    first.size shouldBe 3
                    AuctionTable.selectAll().where { AuctionTable.id eq SeedIds.auctions(3) }.single()[AuctionTable.status] shouldBe
                        AuctionStatus.CLOSED_NO_SALE
                }
            }
        }

        test("social network as the views derive it: removal and hiding, friend versus member, comments follow the root") {
            withFresh { db ->
                full(db)
                transaction(db) {
                    val rows = SocialPostTable.selectAll().filter { mine(it[SocialPostTable.id]) }

                    fun readable(status: MemberStatus) =
                        rows
                            .filter {
                                SocialVisibility.isReadable(
                                    visibility = it[SocialPostTable.visibility],
                                    state = it[SocialPostTable.state],
                                    status = status,
                                )
                            }.map { it[SocialPostTable.id] }
                            .toSet()

                    val member = readable(MemberStatus.ACTIVE)
                    val friend = readable(MemberStatus.FRIEND)
                    (friend - member).shouldBeEmpty()
                    rows.filter { it[SocialPostTable.state] == SocialPostState.REMOVED_LEGAL }.forEach {
                        (it[SocialPostTable.id] in member) shouldBe
                            false
                    }
                    rows.filter { it[SocialPostTable.visibility] == SocialPostVisibility.MEMBERS_ONLY }.forEach {
                        (
                            it[SocialPostTable.id] in
                                friend
                        ) shouldBe
                            false
                    }
                    (rows.size - member.size) shouldBe 1 // only the legal removal is hidden from a member at this level
                    val byId = rows.associateBy { it[SocialPostTable.id] }
                    rows.filter { it[SocialPostTable.parentId] != null }.forEach {
                        it[SocialPostTable.visibility] shouldBe byId.getValue(it[SocialPostTable.rootId])[SocialPostTable.visibility]
                    }
                    SocialPostBoostTable.selectAll().count { mine(it[SocialPostBoostTable.id]) } shouldBe 4
                }
            }
        }

        test("politicians: the listing consent exists exactly for the two listed profiles; the former profile is revoked") {
            withFresh { db ->
                full(db)
                transaction(db) {
                    fun listed(member: Int) =
                        PublicRankingConsentStore
                            .currentState(SeedIds.member(member))
                            .first { it.kind == PublicRankingKind.POLITICIAN_LISTING }
                            .effective
                    listed(2) shouldBe true
                    listed(10) shouldBe true
                    listed(5) shouldBe false
                    listed(20) shouldBe false
                    val former = PoliticianProfileTable.selectAll().where { PoliticianProfileTable.memberId eq SeedIds.member(19) }.single()
                    former[PoliticianProfileTable.status] shouldBe PoliticianProfileStatus.FORMER
                    (former[PoliticianProfileTable.revokedAt] != null) shouldBe true
                    PoliticianReactionTable.selectAll().count {
                        it[PoliticianReactionTable.politicianProfileId] ==
                            former[PoliticianProfileTable.id]
                    } shouldBe
                        0
                }
            }
        }

        test("politicians: a hand-made listing consent row is neither superseded nor duplicated") {
            withFresh { db ->
                val handMadeAt = LocalDateTime(2020, 1, 1, 10, 0)
                transaction(db) {
                    PublicRankingConsentEventTable.insert {
                        it[id] = Uuid.random()
                        it[memberId] = SeedIds.member(10)
                        it[rankingKind] = PublicRankingKind.POLITICIAN_LISTING
                        it[eventType] = PublicRankingConsentEventType.GRANTED
                        it[occurredAt] = handMadeAt
                        it[consentVersion] = "hand-made-v0"
                        it[consentSha256] = "0".repeat(64)
                    }
                }
                full(db)
                transaction(db) {
                    val rows =
                        PublicRankingConsentEventTable
                            .selectAll()
                            .where {
                                (PublicRankingConsentEventTable.memberId eq SeedIds.member(10)) and
                                    (PublicRankingConsentEventTable.rankingKind eq PublicRankingKind.POLITICIAN_LISTING)
                            }.toList()
                    rows.size shouldBe 1
                    rows.single()[PublicRankingConsentEventTable.supersededAt] shouldBe null
                    rows.single()[PublicRankingConsentEventTable.occurredAt] shouldBe handMadeAt
                    rows.single()[PublicRankingConsentEventTable.consentVersion] shouldBe "hand-made-v0"
                }
            }
        }

        test("tenders as the views derive them: motion options, visibility of drafts, bids, award, conflict of interest, guest refusal") {
            withFresh { db ->
                full(db)
                transaction(db) {
                    val options = TenderOptions.bindingMotionOptions(limit = 500).associateBy { it.motionId }
                    options.getValue(StagingSeedGovernanceTopUp.motion1.toString()).selectable shouldBe true
                    options.getValue(StagingSeedGovernanceTopUp.motion2.toString()).selectable shouldBe true
                    options.getValue(StagingSeedGovernanceTopUp.motion3.toString()).reason shouldBe TenderMotionIneligibility.NOT_RESOLVED
                    // a rejected motion is not even offered (the options list only decided-and-adopted and still open motions)
                    (StagingSeedGovernanceTopUp.motion4.toString() in options.keys) shouldBe false
                    options.getValue(StagingSeedGovernanceTopUp.motion5.toString()).reason shouldBe TenderMotionIneligibility.AMENDMENT

                    val wallNow = SeedClock.capture(TimeZone.of("Europe/Berlin")).wallNow
                    val visible =
                        TenderStore
                            .list(
                                statusFilter = null,
                                wallNow = wallNow,
                                includeInternal = false,
                            ).map { it[TenderTable.id] }
                    (SeedIds.tenders(1) in visible) shouldBe false
                    listOf(2, 3, 4).forEach { (SeedIds.tenders(it) in visible) shouldBe true }

                    val awarded = SeedIds.tenders(3)
                    TenderStore.find(awarded)!![TenderTable.status] shouldBe TenderStatus.AWARDED
                    TenderStore.award(awarded)!![TenderAwardTable.conflictedCount] shouldBe 3
                    TenderStore.bids(awarded).associate { it[TenderBidTable.bidderMemberId] to it[TenderBidTable.status] } shouldBe
                        mapOf(
                            SeedIds.member(2) to TenderBidStatus.REJECTED,
                            SeedIds.member(10) to TenderBidStatus.AWARDED,
                            SeedIds.member(5) to TenderBidStatus.REJECTED,
                        )
                    TenderStore.hasAnyBid(tenderId = awarded, memberId = SeedIds.member(2)) shouldBe true // bidder: cannot award
                    TenderStore.hasAnyBid(tenderId = awarded, memberId = SeedIds.member(1)) shouldBe false // the awarder has no bid
                    TenderStore
                        .bids(
                            SeedIds.tenders(2),
                        ).map { it[TenderBidTable.status] }
                        .count { it == TenderBidStatus.WITHDRAWN } shouldBe
                        1
                    TenderStore.find(SeedIds.tenders(3))!![TenderTable.awardCriterion] shouldBe TenderAwardCriterion.LOWEST_PRICE

                    tenderBidderEligible(status = MemberStatus.GUEST, currency = TenderCurrency.LTR) shouldBe false
                    tenderBidderEligible(status = MemberStatus.ACTIVE, currency = TenderCurrency.LTR) shouldBe true
                    tenderBidderEligible(status = MemberStatus.FRIEND, currency = TenderCurrency.LTR) shouldBe true
                }
            }
        }

        test("events as the views derive them: occupancy, waiting list, cancellations, keys, guest addresses") {
            withFresh { db ->
                full(db)
                transaction(db) {
                    fun registrations(index: Int) =
                        EventRegistrationTable.selectAll().where { EventRegistrationTable.eventId eq SeedIds.events(index) }.toList()
                    registrations(2).count { it[EventRegistrationTable.status] == EventRegistrationStatus.CONFIRMED } shouldBe 11
                    EventStore.getEventOrNull(SeedIds.events(2))!![EventTable.capacity] shouldBe 12
                    val third = registrations(3)
                    third.count { it[EventRegistrationTable.status] == EventRegistrationStatus.CONFIRMED } shouldBe 6
                    third
                        .filter {
                            it[EventRegistrationTable.status] == EventRegistrationStatus.WAITLISTED
                        }.map { it[EventRegistrationTable.waitlistPosition] }
                        .sortedBy { it } shouldBe
                        listOf(1, 2, 3)
                    third.count { it[EventRegistrationTable.status] == EventRegistrationStatus.CANCELLED } shouldBe 1
                    registrations(2).count { it[EventRegistrationTable.message] != null } shouldBe 3
                    val all = EventRegistrationTable.selectAll().filter { mine(it[EventRegistrationTable.id]) }
                    all.forEach { row ->
                        val key = row[EventRegistrationTable.activeParticipantKey]
                        if (row[EventRegistrationTable.status] == EventRegistrationStatus.CANCELLED) {
                            (key == null && row[EventRegistrationTable.cancelledAt] != null) shouldBe true
                        } else {
                            (key!!.startsWith("m:") || key.startsWith("g:")) shouldBe true
                        }
                        row[EventRegistrationTable.guestEmail]?.endsWith("@staging.invalid") ?: true shouldBe true
                        row[EventRegistrationTable.cancelTokenSha256] shouldBe null
                        row[EventRegistrationTable.feeAmount].signum() shouldBe 0
                    }
                    EventTable.selectAll().filter { mine(it[EventTable.id]) }.forEach {
                        it[EventTable.status] shouldBe EventStatus.PUBLISHED
                        it[EventTable.feeAmount].signum() shouldBe 0
                    }
                    (
                        EventTable
                            .selectAll()
                            .filter { mine(it[EventTable.id]) }
                            .map { it[EventTable.visibility] }
                            .toSet()
                    ) shouldBe
                        setOf(EventVisibility.PUBLIC, EventVisibility.MEMBERS_ONLY)
                }
            }
        }

        test("oracle: three series with the planned counts") {
            withFresh { db ->
                full(db)
                transaction(db) {
                    val rows = PriceOracleSnapshotTable.selectAll().toList()
                    rows.groupingBy { it[PriceOracleSnapshotTable.anchorAsset].name }.eachCount() shouldBe
                        mapOf("BITCOIN_BTC" to 2160, "GOLD_XAU" to 180, "FIAT" to 90)
                    rows.all { it[PriceOracleSnapshotTable.donationCurrency] == "EUR" } shouldBe true
                }
            }
        }

        test("tester spent LTR: bundles the balance no longer covers are skipped whole, nothing goes negative") {
            withFresh { db ->
                ltrOnly(db)
                transaction(db) {
                    val spend = balance(1) - BigDecimal("3.00")
                    LtrLedgerEntryTable.insert {
                        it[id] = Uuid.random()
                        it[memberId] = SeedIds.member(1)
                        it[entryType] = LtrLedgerEntryType.PEER_TRANSFER_OUT
                        it[amountLtr] = spend.negate()
                        it[note] = "von Hand ausgegeben"
                        it[createdAt] = DbClock.nowLocalDateTime()
                    }
                }
                val outcome = full(db)
                outcome.parts.none { it.failed } shouldBe true
                transaction(db) {
                    assertNoNegativeBalance()
                    (SeedIds.crowdfunding(1) in ids(CrowdfundingProjectTable).map { Uuid.parse(it) }) shouldBe false // Anna: 5.00 > 3.00
                    (SeedIds.crowdfunding(3) in ids(CrowdfundingProjectTable).map { Uuid.parse(it) }) shouldBe false
                    (SeedIds.crowdfunding(2) in ids(CrowdfundingProjectTable).map { Uuid.parse(it) }) shouldBe true // Clara is untouched
                    // no half bundle: every stake row has its project
                    val stakes =
                        LtrLedgerEntryTable.selectAll().filter {
                            it[LtrLedgerEntryTable.entryType] ==
                                LtrLedgerEntryType.PROJECT_STAKE
                        }
                    stakes.forEach { stake ->
                        CrowdfundingProjectTable
                            .selectAll()
                            .where {
                                CrowdfundingProjectTable.id eq stake[LtrLedgerEntryTable.referenceId]!!
                            }.empty() shouldBe
                            false
                    }
                    // the seller's sale income (auction 2) raises Anna's balance again, so later bundles of hers may be covered: only the rule counts
                    LtrLedgerEntryTable
                        .selectAll()
                        .filter { it[LtrLedgerEntryTable.entryType] == LtrLedgerEntryType.SOCIAL_POST_STAKE }
                        .forEach { stake ->
                            SocialPostTable
                                .selectAll()
                                .where {
                                    SocialPostTable.id eq stake[LtrLedgerEntryTable.referenceId]!!
                                }.empty() shouldBe
                                false
                        }
                }
            }
        }

        test(
            "hand-made rows stay untouched: an own auction, an own post, an own event with a colliding slug, an own oracle row, an own profile",
        ) {
            withFresh { db ->
                ltrOnly(db)
                val now = DbClock.nowLocalDateTime()
                val manualAuction = Uuid.random()
                val manualPost = Uuid.random()
                val manualEvent = Uuid.random()
                transaction(db) {
                    AuctionTable.insert {
                        it[id] = manualAuction
                        it[title] = "Von Hand"
                        it[description] = "Handgemachte Auktion"
                        it[startingBidLtr] = BigDecimal("1.00")
                        it[buyNowPriceLtr] = null
                        it[status] = AuctionStatus.OPEN
                        it[sellerMemberId] = SeedIds.member(2)
                        it[winnerMemberId] = null
                        it[finalPriceLtr] = null
                        it[listingFeeLtr] = BigDecimal("0.01")
                        it[createdAt] = now
                        it[endsAt] = now
                        it[settledAt] = null
                    }
                    SocialPostTable.insert {
                        it[id] = manualPost
                        it[parentId] = null
                        it[rootId] = manualPost
                        it[depth] = 0
                        it[authorMemberId] = SeedIds.member(2)
                        it[content] = "Von Hand"
                        it[visibility] = SocialPostVisibility.PUBLIC
                        it[initialWeightLtr] = BigDecimal("1.00")
                        it[publishedAt] = now
                        it[state] = SocialPostState.VISIBLE
                        it[aiAssisted] = false
                    }
                    EventStore.insertEvent(
                        id = manualEvent,
                        slug = "kochkurs-demodaten",
                        title = "Von Hand",
                        description = "Handgemachte Veranstaltung",
                        locationText = "Irgendwo",
                        onlineUrl = null,
                        startsAt = now,
                        endsAt = now,
                        capacity = null,
                        feeAmount = BigDecimal("0.00"),
                        feeCurrency = "EUR",
                        visibility = EventVisibility.PUBLIC,
                        registrationClosesAt = null,
                        createdAt = now,
                        createdBy = SeedIds.member(2),
                    )
                    PriceOracleSnapshotTable.insert {
                        it[id] = Uuid.random()
                        it[anchorAsset] = network.lapis.cloud.shared.domain.AnchorAsset.BITCOIN_BTC
                        it[donationCurrency] = "EUR"
                        it[medianPrice] = BigDecimal("1.000000000000000000")
                        it[priceStatus] = network.lapis.cloud.shared.domain.PriceStatus.LIVE
                        it[sourceCount] = 1
                        it[sourcesUsed] = "von-hand"
                        it[priceTimestamp] = now
                        it[capturedAt] = now
                    }
                    PoliticianProfileTable.insert {
                        it[id] = Uuid.random()
                        it[memberId] = SeedIds.member(2)
                        it[status] = PoliticianProfileStatus.ACTIVE
                        it[mandateText] = "Von Hand"
                        it[grantedAt] = now
                        it[grantedByMemberId] = SeedIds.member(1)
                        it[revokedAt] = null
                        it[revokedByMemberId] = null
                    }
                }
                val outcome = full(db)
                outcome.parts.none { it.failed } shouldBe true
                transaction(db) {
                    AuctionTable.selectAll().where { AuctionTable.id eq manualAuction }.single()[AuctionTable.title] shouldBe "Von Hand"
                    SocialPostTable.selectAll().where { SocialPostTable.id eq manualPost }.single()[SocialPostTable.content] shouldBe
                        "Von Hand"
                    EventTable.selectAll().where { EventTable.slug eq "kochkurs-demodaten" }.single()[EventTable.id] shouldBe manualEvent
                    ids(EventTable).size shouldBe 4 // the colliding one is skipped alone
                    PriceOracleSnapshotTable.selectAll().count() shouldBe 1L // an existing row: the oracle part writes nothing
                    ids(PoliticianProfileTable).size shouldBe 4 // Bernd already has a profile: his bundle is skipped alone
                    ids(AuctionTable).size shouldBe 5
                    assertNoNegativeBalance()
                }
            }
        }

        test(
            "a member who is no longer eligible gets nothing: his bundles and the dependent governance and tenders are skipped, no ledger row for him",
        ) {
            withFresh { db ->
                transaction(db) { MemberTable.update({ MemberTable.id eq SeedIds.member(4) }) { it[status] = MemberStatus.WITHDRAWN } }
                val outcome = full(db)
                outcome.parts.none { it.failed } shouldBe true
                transaction(db) {
                    LtrLedgerEntryTable
                        .selectAll()
                        .where { LtrLedgerEntryTable.memberId eq SeedIds.member(4) }
                        .toList()
                        .shouldBeEmpty()
                    ids(AuctionTable).size shouldBe 3 // 1 (bidder) and 4 (seller) are skipped
                    ids(CommitteeTable).size shouldBe 0 // Daniel has a seat: the governance part writes nothing
                    ids(TenderTable).size shouldBe 0 // no adopted motion, so no tender
                    assertNoNegativeBalance()
                }
                outcome.parts.first { it.part == "governance" }.inserted shouldBe 0
                outcome.parts.first { it.part == "tenders" }.inserted shouldBe 0
            }
        }

        test("an event with an ineligible registrant is skipped whole: no free seat next to a waiting list") {
            withFresh { db ->
                transaction(db) { MemberTable.update({ MemberTable.id eq SeedIds.member(25) }) { it[status] = MemberStatus.WITHDRAWN } }
                val outcome = full(db)
                outcome.parts.none { it.failed } shouldBe true
                transaction(db) {
                    EventTable.selectAll().where { EventTable.slug eq "werkstattabend-demodaten" }.count() shouldBe 0L
                    EventTable.selectAll().where { EventTable.slug eq "kochkurs-demodaten" }.count() shouldBe 1L
                }
            }
        }

        val newPartNames = listOf("member-details", "families", "activism", "honors")

        test("an instance seeded before this wave is topped up with the new parts only; the older parts insert nothing again") {
            withFresh { db ->
                val before = StagingSeedTopUp.topUpWith(database = db, parts = StagingSeedTopUp.PARTS.dropLast(newPartNames.size))
                before.shouldBeInstanceOf<StagingTopUpOutcome.Applied>()
                transaction(db) {
                    ids(ActivismActionTable).shouldBeEmpty()
                    MemberTable.selectAll().count { it[MemberTable.postalCode] != null } shouldBe 0
                }
                val second = full(db)
                second.parts.none { it.failed } shouldBe true
                second.parts.filter { it.part in newPartNames }.all { it.inserted > 0 } shouldBe true
                second.parts.filter { it.part !in newPartNames }.all { it.inserted == 0 } shouldBe true
            }
        }

        test("member details never overwrite: a set postal code, a set birth date stay, a blank postal code is filled") {
            withFresh { db ->
                val ownBirth = LocalDate(1970, 1, 1)
                transaction(db) {
                    MemberTable.update({ MemberTable.id eq SeedIds.member(4) }) { it[postalCode] = "99999" }
                    MemberTable.update({ MemberTable.id eq SeedIds.member(5) }) { it[dateOfBirth] = ownBirth }
                    MemberTable.update({ MemberTable.id eq SeedIds.member(6) }) { it[postalCode] = "" }
                }
                full(db).parts.none { it.failed } shouldBe true
                transaction(db) {
                    fun row(n: Int) = MemberTable.selectAll().where { MemberTable.id eq SeedIds.member(n) }.single()
                    row(4)[MemberTable.postalCode] shouldBe "99999"
                    row(5)[MemberTable.dateOfBirth] shouldBe ownBirth
                    row(5)[MemberTable.postalCode] shouldBe "20095" // the other column of the same member is still filled
                    row(6)[MemberTable.postalCode] shouldBe "80331"
                    // nothing else of a member row is touched
                    row(4)[MemberTable.status] shouldBe MemberStatus.ACTIVE
                    row(4)[MemberTable.countryCode] shouldBe "DE"
                }
            }
        }

        test("a family is written whole or not at all: a payer already in a family of his own skips that family alone") {
            withFresh { db ->
                val own = Uuid.random()
                transaction(db) {
                    MemberFamilyTable.insert {
                        it[id] = own
                        it[name] = "Von Hand"
                        it[createdBy] = SeedIds.member(1)
                        it[createdAt] = DbClock.nowLocalDateTime()
                    }
                    MemberFamilyLinkTable.insert {
                        it[id] = Uuid.random()
                        it[familyId] = own
                        it[memberId] = SeedIds.member(19)
                        it[role] = FamilyMemberRole.PAYER
                        it[payerFamilyId] = own
                        it[linkedAt] = DbClock.nowLocalDateTime()
                        it[linkedBy] = SeedIds.member(1)
                    }
                }
                val outcome = full(db)
                outcome.parts.none { it.failed } shouldBe true
                transaction(db) {
                    ids(MemberFamilyTable) shouldBe listOf(SeedIds.families(2), SeedIds.families(3)).map { it.toString() }.sorted()
                    MemberFamilyTable.selectAll().count() shouldBe 3L
                    // no half family: the dependents of the skipped family have no link
                    MemberFamilyLinkTable.selectAll().where { MemberFamilyLinkTable.memberId eq SeedIds.member(20) }.empty() shouldBe true
                    MemberFamilyLinkTable.selectAll().where { MemberFamilyLinkTable.memberId eq SeedIds.member(21) }.empty() shouldBe true
                }
            }
        }

        test(
            "an action with a signalling or responsible member who has left is skipped whole; the other actions and the honors are written",
        ) {
            withFresh { db ->
                transaction(db) { MemberTable.update({ MemberTable.id eq SeedIds.member(7) }) { it[status] = MemberStatus.WITHDRAWN } }
                val outcome = full(db)
                outcome.parts.none { it.failed } shouldBe true
                transaction(db) {
                    // member 7 signals for the info stand (1) and is responsible for the idea (4): both are skipped, nothing of them is left
                    ActivismStore.getAction(id = SeedIds.activism(1)) shouldBe null
                    ActivismStore.getAction(id = SeedIds.activism(4)) shouldBe null
                    ids(ActivismActionTable).size shouldBe 3
                    ids(ActivismTaskTable).size shouldBe 2
                    ActivismSignalTable.selectAll().count() shouldBe 0L
                    ids(MemberHonorTable).size shouldBe 4
                }
            }
        }

        test("activism as the live service derives the lists: plain member, responsible person and manager see what the policy allows") {
            withFresh { db ->
                full(db)
                transaction(db) {
                    fun viewer(
                        n: Int,
                        role: AccountRole,
                    ) = CurrentMember(memberId = SeedIds.member(n), role = role, status = MemberStatus.ACTIVE)

                    fun visibleIndexes(
                        viewer: CurrentMember,
                        statuses: List<ActivismStatus>,
                    ): List<Int> {
                        val rows = ActivismStore.listActions(statuses = statuses)
                        val mine =
                            ActivismStore.responsibleActionIds(
                                memberId = viewer.memberId,
                                actionIds = rows.map { it[ActivismActionTable.id] },
                            )
                        return rows
                            .filter {
                                ActivismAccessPolicy.resolve(
                                    viewer = viewer,
                                    status = it[ActivismActionTable.status],
                                    responsible = it[ActivismActionTable.id] in mine,
                                ) != null
                            }.map { row -> StagingSeedActivism.actions.single { it.id == row[ActivismActionTable.id] }.index }
                            .sorted()
                    }
                    val all = ActivismStatus.entries
                    val plain = viewer(20, AccountRole.MEMBER)
                    visibleIndexes(plain, listOf(ActivismStatus.PLANNED, ActivismStatus.RUNNING)) shouldBe listOf(1, 2) // the default list
                    visibleIndexes(plain, all) shouldBe listOf(1, 2, 3) // idea and withdrawn are hidden from a plain member
                    visibleIndexes(viewer(7, AccountRole.MEMBER), all) shouldBe listOf(1, 2, 3, 4) // the responsible person sees his idea
                    visibleIndexes(viewer(2, AccountRole.BOARD), all) shouldBe listOf(1, 2, 3, 4, 5)
                    ActivismAccessPolicy.resolve(
                        viewer = viewer(2, AccountRole.BOARD),
                        status = ActivismStatus.WITHDRAWN,
                        responsible = false,
                    ) shouldBe
                        ActivismViewerRole.MANAGER

                    val progress = ActivismStore.progressOf(StagingSeedActivism.actions.map { it.id })
                    progress.getValue(SeedIds.activism(2)).run { (reported to confirmed) shouldBe (140 to 115) }
                    progress.getValue(SeedIds.activism(3)).run { (reported to confirmed) shouldBe (534 to 534) }
                    progress.getValue(SeedIds.activism(1)).run { (reported to confirmed) shouldBe (0 to 0) }
                    // the signal count the info stand shows (every signalling member is an active participant)
                    ActivismSignalStore.countOf(SeedIds.activism(1)) shouldBe 6
                    ActivismStore.getAction(id = SeedIds.activism(5))!![ActivismActionTable.withdrawnReason] shouldBe
                        "Demodaten: Termin entfaellt."
                    ActivismStore.getAction(id = SeedIds.activism(3))!![ActivismActionTable.resultQuantity] shouldBe 534
                    ActivismStore
                        .listContributions(actionId = SeedIds.activism(2))
                        .count { it[ActivismContributionTable.status] == ActivismContributionStatus.REJECTED } shouldBe 1
                }
            }
        }

        test("the member map, the birthday list and the upcoming majorities show data as the services compute them") {
            withFresh { db ->
                full(db)
                transaction(db) {
                    val clock = SeedClock.capture(TimeZone.of("Europe/Berlin"))
                    val active = MemberTable.selectAll().where { MemberTable.status eq MemberStatus.ACTIVE }.toList()
                    val groups =
                        active
                            .groupingBy { (it[MemberTable.postalCode]) to (it[MemberTable.countryCode] ?: it[MemberTable.country]) }
                            .eachCount()
                            .map { (key, count) -> AddressGroup(postalCode = key.first, country = key.second, count = count) }
                    val response = aggregateMemberMap(groups = groups, centroids = PostalCodeCentroidIndex.bundled, tilesAvailable = false)
                    response.total shouldBe active.size
                    val planned =
                        StagingSeedMemberDetails.plan
                            .filter { it.postalCode != null }
                            .map { it.member }
                            .toSet()
                    val activeNumbers = (1..40).filter { n -> active.any { it[MemberTable.id] == SeedIds.member(n) } }
                    response.mappedTotal shouldBe activeNumbers.count { it in planned }
                    response.noPostalCode shouldBe activeNumbers.count { it !in planned }
                    (response.mappedTotal > 0) shouldBe true
                    response.unresolvableGermanPostalCode shouldBe 0
                    response.foreign shouldBe 0

                    // birthdays in the next 14 days (members 2, 5, 8 and 21 in the plan)
                    val soon =
                        active.filter { row ->
                            row[MemberTable.dateOfBirth]?.let {
                                AnniversaryCalendar.nextOccurrence(anniversary = it, today = clock.today) <=
                                    AnniversaryCalendar.windowEnd(today = clock.today, windowDays = 14)
                            } == true
                        }
                    soon.map { it[MemberTable.id] }.toSet() shouldBe listOf(2, 5, 8, 21).map { SeedIds.member(it) }.toSet()

                    // the one dependent minor turns 18 within 30 days
                    val minor = MemberTable.selectAll().where { MemberTable.id eq SeedIds.member(40) }.single()[MemberTable.dateOfBirth]!!
                    val majority = AnniversaryCalendar.nthAnniversary(anniversary = minor, years = FamilyMembershipRules.MAJORITY_AGE_YEARS)
                    (majority > clock.today) shouldBe true
                    (majority <= AnniversaryCalendar.windowEnd(today = clock.today, windowDays = 30)) shouldBe true
                    MemberFamilyLinkTable
                        .selectAll()
                        .where {
                            (MemberFamilyLinkTable.memberId eq SeedIds.member(40)) and
                                (MemberFamilyLinkTable.role eq FamilyMemberRole.DEPENDENT)
                        }.count() shouldBe 1L
                    // a dependent has no tier of its own (addFamilyMember clears it), so the contribution run bills only the payer
                    val dependentIds =
                        MemberFamilyLinkTable
                            .selectAll()
                            .where { MemberFamilyLinkTable.role eq FamilyMemberRole.DEPENDENT }
                            .map { it[MemberFamilyLinkTable.memberId] }
                    dependentIds.size shouldBe 4
                    MemberTable
                        .selectAll()
                        .where {
                            MemberTable.id inList dependentIds
                        }.all { it[MemberTable.membershipTierId] == null } shouldBe
                        true
                    // every payer link carries the shadow column the constraint demands
                    MemberFamilyLinkTable
                        .selectAll()
                        .where { MemberFamilyLinkTable.role eq FamilyMemberRole.PAYER }
                        .forEach { it[MemberFamilyLinkTable.payerFamilyId] shouldBe it[MemberFamilyLinkTable.familyId] }
                    MemberHonorTable.selectAll().all { it[MemberHonorTable.awardedAt] < clock.today } shouldBe true
                }
            }
        }

        test("refused on a non-staging instance: nothing of the new parts is written, no member row changes") {
            withFresh { db ->
                transaction(db) {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq StagingSeedData.organizationSettingsId }) {
                        it[OrganizationSettingsTable.name] = "Echter Verein"
                    }
                }
                val before = transaction(db) { snapshot() }
                StagingSeedTopUp.topUpWith(database = db).shouldBeInstanceOf<StagingTopUpOutcome.Skipped>()
                transaction(db) { snapshot() } shouldContainExactlyInAnyOrder before
                transaction(db) {
                    ids(ActivismActionTable).shouldBeEmpty()
                    ids(MemberFamilyTable).shouldBeEmpty()
                    ids(MemberHonorTable).shouldBeEmpty()
                    MemberTable.selectAll().count { it[MemberTable.postalCode] != null || it[MemberTable.dateOfBirth] != null } shouldBe 0
                }
            }
        }

        test("the instance check inside every part: when it fails between parts, the new parts report 0/0 and write nothing") {
            withFresh { db ->
                val renamer =
                    object : StagingSeedPart {
                        override val name: String = "renamer"

                        override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
                            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq StagingSeedData.organizationSettingsId }) {
                                it[OrganizationSettingsTable.name] = "Echter Verein"
                            }
                            return StagingSeedPartResult(inserted = 1, skipped = 0)
                        }
                    }
                val outcome =
                    StagingSeedTopUp.topUpWith(
                        database = db,
                        parts = listOf(renamer) + StagingSeedTopUp.PARTS.filter { it.name in newPartNames },
                    )
                outcome.shouldBeInstanceOf<StagingTopUpOutcome.Applied>()
                outcome.parts.filter { it.part in newPartNames }.forEach {
                    (it.inserted to it.skipped) shouldBe (0 to 0)
                    it.failed shouldBe false
                }
                transaction(db) {
                    ids(ActivismActionTable).shouldBeEmpty()
                    ids(MemberFamilyTable).shouldBeEmpty()
                    ids(MemberHonorTable).shouldBeEmpty()
                    MemberTable.selectAll().count { it[MemberTable.postalCode] != null || it[MemberTable.dateOfBirth] != null } shouldBe 0
                }
            }
        }

        test("a fresh initial seed switches the politician ranking on") {
            withFresh { db ->
                transaction(db) {
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq StagingSeedData.organizationSettingsId }
                        .single()[OrganizationSettingsTable.politicianRankingEnabled] shouldBe true
                }
            }
        }

        test("a failing part is rolled back alone: the parts around it are applied, the report names it, it leaves no row") {
            withFresh { db ->
                val failing =
                    object : StagingSeedPart {
                        override val name: String = "failing"

                        override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
                            with(StagingSeedGovernance) {
                                insertCommittee(
                                    id = SeedIds.governanceTopUp(0x0f),
                                    name = "Wird zurueckgerollt",
                                    type = network.lapis.cloud.shared.domain.CommitteeType.OTHER,
                                    description = "x",
                                    quorumPercent = 50,
                                    createdAt = DbClock.nowLocalDateTime(),
                                )
                            }
                            error("boom")
                        }
                    }
                val outcome =
                    StagingSeedTopUp.topUpWith(
                        database = db,
                        parts = listOf(StagingSeedCrowdfunding, failing, StagingSeedAuctions),
                    )
                outcome.shouldBeInstanceOf<StagingTopUpOutcome.Applied>()
                outcome.parts.map { it.part to it.failed } shouldBe listOf("crowdfunding" to false, "failing" to true, "auctions" to false)
                outcome.parts.first { it.part == "crowdfunding" }.inserted shouldBe 17
                outcome.parts.first { it.part == "auctions" }.inserted shouldBe 5
                transaction(db) {
                    CommitteeTable.selectAll().where { CommitteeTable.id eq SeedIds.governanceTopUp(0x0f) }.empty() shouldBe true
                }
            }
        }
    })
