package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.audit.AuditChainVerifier
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
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
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
import network.lapis.cloud.server.rpc.AuctionBidView
import network.lapis.cloud.server.rpc.CrowdfundingWeightDecay
import network.lapis.cloud.server.rpc.PublicRankingConsentStore
import network.lapis.cloud.server.rpc.SocialVisibility
import network.lapis.cloud.server.rpc.computeAuctionOutcome
import network.lapis.cloud.server.tender.TenderOptions
import network.lapis.cloud.server.tender.TenderStore
import network.lapis.cloud.server.tender.tenderBidderEligible
import network.lapis.cloud.server.testdb.PostgresTestSupport
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.CrowdfundingReactionValue
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
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
            )

        fun JdbcTransaction.snapshot(): List<String> =
            partTables.map { "${it.tableName}=${ids(it)}" } +
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
