package network.lapis.cloud.server.tender

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.TenderPayoutTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.routes.PublicTransparencyReader
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.testdb.legacyAccountRole
import network.lapis.cloud.server.time.MutableTestClock
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TenderAcceptanceDecision
import network.lapis.cloud.shared.domain.TenderAcceptanceInput
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderDetailDto
import network.lapis.cloud.shared.domain.TenderPayoutBlockedReason
import network.lapis.cloud.shared.domain.TenderPayoutFailureReason
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.TenderSummaryDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * V1.9.308 -- the LTR payout of an accepted tender (wave 3). One credit row TENDER_PAYOUT on the winner (an issuance, no debit), exactly the award
 * price, a separate four-eyes step, idempotent in three layers. Runs on H2 and, as the actual proof of the concurrency claims, on a real PostgreSQL.
 */
abstract class TenderPayoutScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = TenderFixtures()

        beforeSpec {
            db.activate()
            TimeTestSupport.setOrganizationZone("Europe/Berlin")
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterTest { fx.reset() }
        afterSpec {
            TimeTestSupport.resetOrganizationZone()
            db.deactivate()
        }

        fun payoutRows(tenderId: String) =
            transaction {
                LtrLedgerEntryTable
                    .selectAll()
                    .where { LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.TENDER_PAYOUT }
                    .toList()
                    .filter { it[LtrLedgerEntryTable.referenceId] == Uuid.parse(tenderId) }
            }

        fun allPayoutRows() =
            transaction {
                LtrLedgerEntryTable.selectAll().where { LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.TENDER_PAYOUT }.count()
            }

        fun balance(member: Uuid): BigDecimal {
            val total = LtrLedgerEntryTable.amountLtr.sum()
            return transaction {
                LtrLedgerEntryTable.select(total).where { LtrLedgerEntryTable.memberId eq member }.single()[total] ?: BigDecimal.ZERO
            }
        }

        fun setStatus(
            member: Uuid,
            status: MemberStatus,
        ) {
            transaction { MemberTable.update({ MemberTable.id eq member }) { it[MemberTable.status] = status } }
        }

        fun payoutAudit(tenderId: String): List<String> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.TENDER) and
                            (AuditLogEntryTable.entityId eq Uuid.parse(tenderId))
                    }.orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.ASC)
                    .mapNotNull { it[AuditLogEntryTable.afterSnapshot] }
                    .filter { "\"PAYOUT" in it }
            }

        /** Board A awards and records the acceptance; the winner is ACTIVE; a treasurer exists. The clock ends past the deadline. */
        suspend fun TenderApp.payable(
            clock: MutableTestClock,
            price: String = "12.50",
        ): Payable {
            val board = fx.member(label = "board", role = AccountRole.BOARD)
            val treasurer = fx.member(label = "treasurer", role = AccountRole.TREASURER)
            val winner = fx.member(label = "winner")
            // several tenders per test: the deadline is relative to the (already advanced) clock, never the fixed one of TENDER_DEADLINE
            val deadline =
                OrganizationTimeZone
                    .wallNow()
                    .toInstant(TimeZone.UTC)
                    .plus(1.days)
                    .toLocalDateTime(TimeZone.UTC)
            val id = newTender(board = board, binding = fx.approvedMotion(), deadline = deadline)
            val bid = tender(member = winner) { submitBid(tenderId = id, input = bidInput(price = price)) }
            clock.advance(1.days + 1.hours)
            tender(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = bid.id)) }
            tender(member = board) { recordAcceptance(TenderAcceptanceInput(tenderId = id, decision = TenderAcceptanceDecision.ACCEPTED)) }
            return Payable(board = board, treasurer = treasurer, winner = winner, tenderId = id, bidId = bid.id)
        }

        test(
            "happy path: exactly one TENDER_PAYOUT credit row of the award price, the status is PAID, one audit event without amount or name",
        ) {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock, price = "12.50")
                    val auditBefore = payoutAudit(p.tenderId).size
                    val before = balance(p.winner)

                    val dto = tender(member = p.treasurer) { payOutTender(p.tenderId) }

                    dto.tender.status shouldBe TenderStatus.PAID
                    val rows = payoutRows(p.tenderId)
                    rows.size shouldBe 1
                    val row = rows.single()
                    row[LtrLedgerEntryTable.memberId] shouldBe p.winner
                    row[LtrLedgerEntryTable.amountLtr].compareTo(BigDecimal("12.50")) shouldBe 0
                    row[LtrLedgerEntryTable.referenceType] shouldBe LtrLedgerReferenceType.TENDER
                    row[LtrLedgerEntryTable.note] shouldBe null
                    row[LtrLedgerEntryTable.createdBy] shouldBe p.treasurer
                    (balance(p.winner) - before).compareTo(BigDecimal("12.50")) shouldBe 0
                    // no debit anywhere: the organisation is not a member
                    transaction {
                        LtrLedgerEntryTable.selectAll().where { LtrLedgerEntryTable.amountLtr less BigDecimal.ZERO }.count()
                    } shouldBe
                        0

                    val payout = transaction { TenderPayoutTable.selectAll().single() }
                    payout[TenderPayoutTable.ledgerEntryId] shouldBe row[LtrLedgerEntryTable.id]
                    payout[TenderPayoutTable.executedBy] shouldBe p.treasurer
                    payout[TenderPayoutTable.bidId] shouldBe Uuid.parse(p.bidId)
                    transaction {
                        TenderTable.selectAll().where { TenderTable.id eq Uuid.parse(p.tenderId) }.single()[TenderTable.payoutLedgerEntryId]
                    } shouldBe row[LtrLedgerEntryTable.id]

                    val audit = payoutAudit(p.tenderId)
                    audit.size shouldBe auditBefore + 1
                    audit.last() shouldNotContain "12.5"
                    audit.last() shouldNotContain "Tender-Test"
                    dto.payout?.amount?.compareTo(BigDecimal("12.50")) shouldBe 0
                    dto.payout?.ledgerEntryId shouldBe row[LtrLedgerEntryTable.id].toString()
                    dto.payout?.paidByDisplayName shouldBe "Tender-Test treasurer"
                }
            }
        }

        test("a second call is idempotent: same entry, still one ledger row, no second audit entry") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock)
                    val second = fx.member(label = "board2", role = AccountRole.BOARD)
                    val first = tender(member = p.treasurer) { payOutTender(p.tenderId) }
                    val auditAfterFirst = payoutAudit(p.tenderId).size
                    val again = tender(member = p.treasurer) { payOutTender(p.tenderId) }
                    val byOther = tender(member = second) { payOutTender(p.tenderId) }
                    again.tender.status shouldBe TenderStatus.PAID
                    again.payout?.ledgerEntryId shouldBe first.payout?.ledgerEntryId
                    byOther.payout?.ledgerEntryId shouldBe first.payout?.ledgerEntryId
                    payoutRows(p.tenderId).size shouldBe 1
                    payoutAudit(p.tenderId).size shouldBe auditAfterFirst
                }
            }
        }

        test("two parallel payouts by two callers book exactly once (no hidden retry, no partial state)") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock)
                    val other = fx.member(label = "board2", role = AccountRole.BOARD)
                    val results =
                        coroutineScope {
                            listOf(p.treasurer, other)
                                .map { who -> async(Dispatchers.IO) { tenderAttempt(member = who) { payOutTender(p.tenderId) } } }
                                .awaitAll()
                        }
                    payoutRows(p.tenderId).size shouldBe 1
                    transaction { TenderPayoutTable.selectAll().count() } shouldBe 1
                    val ok = results.filter { it.isSuccess }.map { it.getOrThrow() }
                    if (db.isPostgres) {
                        ok.size shouldBe 2
                    } else {
                        // H2 may refuse the loser of a lock race; it never books twice
                        results.filter { it.isFailure }.forEach { it.exceptionOrNull().shouldBeInstanceOf<ConflictException>() }
                        (ok.size >= 1) shouldBe true
                    }
                    ok.map { it.payout?.ledgerEntryId }.toSet().size shouldBe 1
                    ok.forEach { it.tender.status shouldBe TenderStatus.PAID }
                }
            }
        }

        test("layer c on its own: a second tender_payout row for the same tender or the same ledger row is a unique violation") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock)
                    val q = payable(clock = clock)
                    tender(member = p.treasurer) { payOutTender(p.tenderId) }
                    val existing =
                        transaction {
                            TenderPayoutTable
                                .selectAll()
                                .where {
                                    TenderPayoutTable.tenderId eq
                                        Uuid.parse(
                                            p.tenderId,
                                        )
                                }.single()
                        }
                    val spare = Uuid.random()
                    transaction {
                        LtrLedgerEntryTable.insert {
                            it[id] = spare
                            it[memberId] = p.winner
                            it[entryType] = LtrLedgerEntryType.TENDER_PAYOUT
                            it[amountLtr] = BigDecimal("1.00")
                            it[referenceType] = LtrLedgerReferenceType.TENDER
                            it[referenceId] = Uuid.parse(p.tenderId)
                            it[createdBy] = p.treasurer
                            it[createdAt] = LocalDateTime(2026, 11, 3, 12, 0)
                        }
                    }
                    // same tender again (primary key)
                    val samePk =
                        shouldThrow<Exception> {
                            transaction {
                                TenderStore.insertPayout(
                                    tenderId = Uuid.parse(p.tenderId),
                                    ledgerEntryId = spare,
                                    bidId = Uuid.parse(p.bidId),
                                    amount = BigDecimal("1.00"),
                                    executedBy = p.treasurer,
                                    executedAt = LocalDateTime(2026, 11, 3, 12, 0),
                                )
                            }
                        }
                    samePk.isUniqueViolation() shouldBe true
                    // another tender, but the ledger row of the first payout (unique ledger_entry_id)
                    val sameLedger =
                        shouldThrow<Exception> {
                            transaction {
                                TenderStore.insertPayout(
                                    tenderId = Uuid.parse(q.tenderId),
                                    ledgerEntryId = existing[TenderPayoutTable.ledgerEntryId],
                                    bidId = Uuid.parse(q.bidId),
                                    amount = BigDecimal("1.00"),
                                    executedBy = q.treasurer,
                                    executedAt = LocalDateTime(2026, 11, 3, 12, 0),
                                )
                            }
                        }
                    sameLedger.isUniqueViolation() shouldBe true
                    // ... and the same bid on another tender (unique bid_id)
                    val sameBid =
                        shouldThrow<Exception> {
                            transaction {
                                TenderStore.insertPayout(
                                    tenderId = Uuid.parse(q.tenderId),
                                    ledgerEntryId = spare,
                                    bidId = existing[TenderPayoutTable.bidId],
                                    amount = BigDecimal("1.00"),
                                    executedBy = q.treasurer,
                                    executedAt = LocalDateTime(2026, 11, 3, 12, 0),
                                )
                            }
                        }
                    sameBid.isUniqueViolation() shouldBe true
                }
            }
        }

        // The ledger knows member accounts only, so a payout is an issuance: nothing is debited, hence "insufficient balance" cannot occur.
        test("insufficient balance is not applicable (issuance): every refusal books nothing and leaves the balance unchanged") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock)
                    val member = fx.member(label = "plain")
                    val before = balance(p.winner)
                    // the winner, an acceptor, a plain member and a bidder-treasurer are all refused
                    tenderAttempt(member = p.winner) { payOutTender(p.tenderId) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    tenderAttempt(member = p.board) { payOutTender(p.tenderId) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    tenderAttempt(member = member) { payOutTender(p.tenderId) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    allPayoutRows() shouldBe 0
                    balance(p.winner).compareTo(before) shouldBe 0
                    transaction { TenderPayoutTable.selectAll().count() } shouldBe 0
                    tender(member = p.treasurer) { getTender(p.tenderId) }.tender.status shouldBe TenderStatus.COMPLETED
                }
            }
        }

        test("a guest winner (legacy): PAYOUT_FAILED is saved without a ledger row, nothing is thrown, and a retry after the fix pays") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock)
                    setStatus(p.winner, MemberStatus.GUEST)

                    val failed = tender(member = p.treasurer) { payOutTender(p.tenderId) }
                    failed.tender.status shouldBe TenderStatus.PAYOUT_FAILED
                    failed.payoutFailure?.reason shouldBe TenderPayoutFailureReason.WINNER_GUEST
                    failed.canPayOut shouldBe true
                    payoutRows(p.tenderId).size shouldBe 0
                    val stored = transaction { TenderTable.selectAll().where { TenderTable.id eq Uuid.parse(p.tenderId) }.single() }
                    stored[TenderTable.status] shouldBe TenderStatus.PAYOUT_FAILED
                    stored[TenderTable.payoutFailureReason] shouldBe TenderPayoutFailureReason.WINNER_GUEST
                    stored[TenderTable.payoutFailedBy] shouldBe p.treasurer
                    (stored[TenderTable.payoutFailedAt] != null) shouldBe true
                    payoutAudit(p.tenderId).last().contains("\"PAYOUT_FAILED\"") shouldBe true

                    // the winner reads COMPLETED and sees nothing of the failure
                    val seenByWinner = tender(member = p.winner) { getTender(p.tenderId) }
                    seenByWinner.tender.status shouldBe TenderStatus.COMPLETED
                    seenByWinner.payoutFailure shouldBe null
                    seenByWinner.payoutPending shouldBe true

                    setStatus(p.winner, MemberStatus.ACTIVE)
                    val paid = tender(member = p.treasurer) { payOutTender(p.tenderId) }
                    paid.tender.status shouldBe TenderStatus.PAID
                    payoutRows(p.tenderId).size shouldBe 1
                    // a second failure after the payment is impossible; the failure columns stay as history only
                    paid.payoutFailure shouldBe null
                }
            }
        }

        test("a winner who left or died cannot be paid (WINNER_NOT_LTR_ELIGIBLE); a friend and a donor can") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    listOf(MemberStatus.WITHDRAWN, MemberStatus.DECEASED, MemberStatus.REJECTED).forEach { status ->
                        val p = payable(clock = clock)
                        setStatus(p.winner, status)
                        val dto = tender(member = p.treasurer) { payOutTender(p.tenderId) }
                        dto.payoutFailure?.reason shouldBe TenderPayoutFailureReason.WINNER_NOT_LTR_ELIGIBLE
                        payoutRows(p.tenderId).size shouldBe 0
                        fx.reset()
                    }
                    listOf(MemberStatus.FRIEND, MemberStatus.DONOR).forEach { status ->
                        val p = payable(clock = clock)
                        setStatus(p.winner, status)
                        tender(member = p.treasurer) { payOutTender(p.tenderId) }.tender.status shouldBe TenderStatus.PAID
                        payoutRows(p.tenderId).size shouldBe 1
                        fx.reset()
                    }
                }
            }
        }

        test("a tender that is not COMPLETED cannot be paid out, and a paid or failed one cannot be cancelled") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val treasurer = fx.member(label = "treasurer", role = AccountRole.TREASURER)
                    val binding = fx.approvedMotion()
                    val draft = newTender(board = board, binding = binding, publish = false)
                    val published = newTender(board = board, binding = binding)
                    val cancelled = newTender(board = board, binding = binding)
                    tender(member = board) { cancelTender(tenderId = cancelled, reason = "Nicht mehr noetig") }
                    listOf(draft, published, cancelled).forEach { id ->
                        tenderAttempt(member = treasurer) { payOutTender(id) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    }
                    val bidder = fx.member(label = "bidder")
                    tender(member = bidder) { submitBid(tenderId = published, input = bidInput(price = "4.00")) }
                    clock.advance(1.days)
                    // CLOSED (lazily) and then AWARDED
                    tenderAttempt(member = treasurer) { payOutTender(published) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    val bid = tender(member = board) { getTender(published) }.bids.single()
                    tender(member = board) { awardTender(TenderAwardInput(tenderId = published, bidId = bid.id)) }
                    tenderAttempt(member = treasurer) { payOutTender(published) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    allPayoutRows() shouldBe 0

                    val p = payable(clock = clock)
                    tenderAttempt(member = p.board) { cancelTender(tenderId = p.tenderId, reason = "zu spaet") }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    setStatus(p.winner, MemberStatus.GUEST)
                    tender(member = p.treasurer) { payOutTender(p.tenderId) }.tender.status shouldBe TenderStatus.PAYOUT_FAILED
                    tenderAttempt(member = p.board) { cancelTender(tenderId = p.tenderId, reason = "zu spaet") }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    setStatus(p.winner, MemberStatus.ACTIVE)
                    tender(member = p.treasurer) { payOutTender(p.tenderId) }
                    tenderAttempt(member = p.board) { cancelTender(tenderId = p.tenderId, reason = "zu spaet") }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                }
            }
        }

        test(
            "rights: members, friends, guests, a bidder, the winner and the acceptance recorder are refused; treasurer, other board and admin pay",
        ) {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock)
                    listOf(MemberStatus.ACTIVE, MemberStatus.FRIEND, MemberStatus.GUEST).forEach { status ->
                        val who = fx.member(label = "plain-$status", status = status)
                        tenderAttempt(member = who) { payOutTender(p.tenderId) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    }
                    // a treasurer who is no active member any more
                    val gone = fx.member(label = "gone", status = MemberStatus.WITHDRAWN, role = AccountRole.TREASURER)
                    tenderAttempt(member = gone) { payOutTender(p.tenderId) }.isFailure shouldBe true
                    // a treasurer who has a (withdrawn) bid on this tender
                    val biddingTreasurer = fx.member(label = "bidding-treasurer", role = AccountRole.TREASURER)
                    fx.legacyBid(tenderId = p.tenderId, bidder = biddingTreasurer, price = "99.00", bidStatus = TenderBidStatus.WITHDRAWN)
                    tenderAttempt(
                        member = biddingTreasurer,
                    ) { payOutTender(p.tenderId) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    // the winner, the acceptance recorder
                    tenderAttempt(member = p.winner) { payOutTender(p.tenderId) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    tenderAttempt(member = p.board) { payOutTender(p.tenderId) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    allPayoutRows() shouldBe 0

                    // an unknown or malformed id reads as missing
                    tenderAttempt(member = p.treasurer) { payOutTender(Uuid.random().toString()) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<NotFoundException>()
                    tenderAttempt(
                        member = p.treasurer,
                    ) { payOutTender("not-a-uuid") }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()

                    // the three paying roles, one tender each
                    val otherBoard = fx.member(label = "board2", role = AccountRole.BOARD)
                    tender(member = otherBoard) { payOutTender(p.tenderId) }.tender.status shouldBe TenderStatus.PAID
                    val q = payable(clock = clock)
                    tender(member = q.treasurer) { payOutTender(q.tenderId) }.tender.status shouldBe TenderStatus.PAID
                    val r = payable(clock = clock)
                    val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
                    tender(member = admin) { payOutTender(r.tenderId) }.tender.status shouldBe TenderStatus.PAID
                    // an administrator by flag on a base role MEMBER
                    val s = payable(clock = clock)
                    val flagged = fx.member(label = "flagged")
                    transaction {
                        AccountTable.update({ AccountTable.memberId eq flagged }) { it[isAdmin] = true }
                    }
                    tender(member = flagged) { payOutTender(s.tenderId) }.tender.status shouldBe TenderStatus.PAID
                }
            }
        }

        test(
            "the detail tells the board who may pay: canPayOut, the four-eyes reason with the name, and nothing for the winner or the others",
        ) {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock, price = "7.00")
                    val byTreasurer = tender(member = p.treasurer) { getTender(p.tenderId) }
                    byTreasurer.canPayOut shouldBe true
                    byTreasurer.payoutBlockedReason shouldBe null
                    byTreasurer.payoutAmount?.compareTo(BigDecimal("7.00")) shouldBe 0
                    byTreasurer.payoutWinnerDisplayName shouldBe "Tender-Test winner"
                    byTreasurer.acceptedByDisplayName shouldBe "Tender-Test board"
                    val byAcceptor = tender(member = p.board) { getTender(p.tenderId) }
                    byAcceptor.canPayOut shouldBe false
                    byAcceptor.payoutBlockedReason shouldBe TenderPayoutBlockedReason.FOUR_EYES
                    byAcceptor.acceptedByDisplayName shouldBe "Tender-Test board"
                    val byWinner = tender(member = p.winner) { getTender(p.tenderId) }
                    byWinner.canPayOut shouldBe false
                    byWinner.payoutBlockedReason shouldBe null
                    byWinner.payoutPending shouldBe true
                    byWinner.payoutAmount shouldBe null
                    byWinner.acceptedByDisplayName shouldBe null
                    val byOther = tender(member = fx.member(label = "other")) { getTender(p.tenderId) }
                    byOther.canPayOut shouldBe false
                    byOther.payoutPending shouldBe false
                    byOther.payoutWinnerDisplayName shouldBe null
                }
            }
        }

        test("the largest possible price is paid out exactly and the balance sum does not overflow") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock, price = "9999999999999999.99")
                    tender(member = p.treasurer) { payOutTender(p.tenderId) }.tender.status shouldBe TenderStatus.PAID
                    balance(p.winner).compareTo(BigDecimal("9999999999999999.99")) shouldBe 0
                    payoutRows(p.tenderId).single()[LtrLedgerEntryTable.amountLtr].compareTo(BigDecimal("9999999999999999.99")) shouldBe 0
                }
            }
        }

        test("the public figure of issued LTR counts a tender payout like a mint, and nothing else") {
            val member = fx.member(label = "ledger")

            fun book(
                type: LtrLedgerEntryType,
                amount: String,
            ) = transaction {
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = member
                    it[entryType] = type
                    it[amountLtr] = BigDecimal(amount)
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = null
                    it[createdBy] = null
                    it[createdAt] = LocalDateTime(2026, 11, 3, 12, 0)
                }
            }
            val before = BigDecimal(transaction { PublicTransparencyReader.loadStats() }.mintedLtrTotal)
            book(LtrLedgerEntryType.MINT, "10.00")
            book(LtrLedgerEntryType.TENDER_PAYOUT, "5.00")
            book(LtrLedgerEntryType.PEER_TRANSFER_IN, "100.00")
            book(LtrLedgerEntryType.NOTICE_BOOST, "-3.00")
            (
                BigDecimal(
                    transaction { PublicTransparencyReader.loadStats() }.mintedLtrTotal,
                ) - before
            ).compareTo(BigDecimal("15.00")) shouldBe
                0
        }

        test("nothing of a failed payout leaves the server for anybody outside the board view (JSON scan of list and detail)") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock)
                    setStatus(p.winner, MemberStatus.GUEST)
                    tender(member = p.treasurer) { payOutTender(p.tenderId) }
                    val bidTreasurer = fx.member(label = "bid-treasurer", role = AccountRole.TREASURER)
                    fx.legacyBid(tenderId = p.tenderId, bidder = bidTreasurer, price = "50.00", bidStatus = TenderBidStatus.REJECTED)
                    val friend = fx.member(label = "friend", status = MemberStatus.FRIEND)
                    val plain = fx.member(label = "plain")
                    val json = Json { encodeDefaults = true }
                    listOf(p.winner, friend, plain, bidTreasurer).forEach { who ->
                        val detail = json.encodeToString(TenderDetailDto.serializer(), tender(member = who) { getTender(p.tenderId) })
                        val list =
                            json.encodeToString(
                                ListSerializer(TenderSummaryDto.serializer()),
                                tender(member = who) { listTenders(null) },
                            )
                        listOf(detail, list).forEach { text ->
                            text shouldNotContain "PAYOUT_FAILED"
                            text shouldNotContain "WINNER_GUEST"
                            text shouldNotContain "WINNER_NOT_LTR_ELIGIBLE"
                        }
                    }
                    // the board's view does carry it
                    json
                        .encodeToString(TenderDetailDto.serializer(), tender(member = p.treasurer) { getTender(p.tenderId) })
                        .contains("WINNER_GUEST") shouldBe true
                }
            }
        }

        test("the winner stays visible after the payout, in the list and in the detail, for every participant") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock, price = "3.00")
                    tender(member = p.treasurer) { payOutTender(p.tenderId) }
                    val friend = fx.member(label = "friend", status = MemberStatus.FRIEND)
                    val listed = tender(member = friend) { listTenders(null) }.single()
                    listed.status shouldBe TenderStatus.PAID
                    listed.award?.winnerDisplayName shouldBe "Tender-Test winner"
                    val detail: TenderDetailDto = tender(member = friend) { getTender(p.tenderId) }
                    detail.payout?.paidByDisplayName shouldBe null
                    detail.payout?.ledgerEntryId shouldBe null
                    (detail.payout?.executedAt != null) shouldBe true
                    tender(member = friend) { listTenders(TenderStatus.PAID) }.size shouldBe 1
                    tender(member = friend) { listTenders(TenderStatus.COMPLETED) }.size shouldBe 0
                }
            }
        }

        test("the list filter decides on the status SHOWN: PAYOUT_FAILED reads as COMPLETED outside the board view") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock)
                    setStatus(p.winner, MemberStatus.GUEST)
                    tender(member = p.treasurer) { payOutTender(p.tenderId) }
                    val friend = fx.member(label = "friend", status = MemberStatus.FRIEND)
                    tender(member = friend) { listTenders(TenderStatus.PAYOUT_FAILED) }.size shouldBe 0
                    tender(member = friend) { listTenders(TenderStatus.COMPLETED) }.map { it.status } shouldContainExactly
                        listOf(TenderStatus.COMPLETED)
                    tender(member = p.treasurer) { listTenders(TenderStatus.PAYOUT_FAILED) }.map { it.status } shouldContainExactly
                        listOf(TenderStatus.PAYOUT_FAILED)
                    tender(member = p.treasurer) { listTenders(TenderStatus.COMPLETED) }.size shouldBe 0
                    // a treasurer who has a bid of their own is outside the manager view and reads COMPLETED, for the filter as well
                    val bidTreasurer = fx.member(label = "bid-treasurer", role = AccountRole.TREASURER)
                    fx.legacyBid(tenderId = p.tenderId, bidder = bidTreasurer, price = "50.00", bidStatus = TenderBidStatus.REJECTED)
                    tender(member = bidTreasurer) { listTenders(TenderStatus.PAYOUT_FAILED) }.size shouldBe 0
                    tender(member = bidTreasurer) { listTenders(TenderStatus.COMPLETED) }.size shouldBe 1
                    tender(member = bidTreasurer) { getTender(p.tenderId) }.tender.status shouldBe TenderStatus.COMPLETED
                }
            }
        }

        // ---- V1.9.330: receipt mail to the winner, name hidden from the winner --------------------------------------------------------

        test("V1.9.330 happy path: exactly one receipt to the winner (amount, tender, time); the winner sees the amount but not the name") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock, price = "12.50")
                    val mailer = RecordingTenderPayoutMailer()
                    val dto = tender(member = p.treasurer, mailer = mailer) { payOutTender(p.tenderId) }
                    mailer.sent.size shouldBe 1
                    val mail = mailer.sent.single()
                    mail.tenderId shouldBe Uuid.parse(p.tenderId)
                    mail.amount.compareTo(BigDecimal("12.50")) shouldBe 0
                    mail.winnerEmail shouldBe "tender-test-${p.winner}@example.org"
                    val stored = transaction { TenderPayoutTable.selectAll().single() }
                    mail.executedAt shouldBe stored[TenderPayoutTable.executedAt]
                    dto.payout?.viewerIsWinner shouldBe false

                    val seenByWinner = tender(member = p.winner) { getTender(p.tenderId) }.payout
                    seenByWinner?.viewerIsWinner shouldBe true
                    seenByWinner?.paidByDisplayName shouldBe null
                    (seenByWinner?.ledgerEntryId != null) shouldBe true
                    seenByWinner?.amount?.compareTo(BigDecimal("12.50")) shouldBe 0

                    val seenByBoard = tender(member = p.board) { getTender(p.tenderId) }.payout
                    seenByBoard?.paidByDisplayName shouldBe "Tender-Test treasurer"
                    seenByBoard?.viewerIsWinner shouldBe false

                    val stranger = fx.member(label = "stranger")
                    val seenByStranger = tender(member = stranger) { getTender(p.tenderId) }.payout
                    seenByStranger?.paidByDisplayName shouldBe null
                    seenByStranger?.ledgerEntryId shouldBe null
                    seenByStranger?.viewerIsWinner shouldBe false
                }
            }
        }

        test("V1.9.330: FRIEND and DONOR winners are paid and receive one receipt each") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    listOf(MemberStatus.FRIEND, MemberStatus.DONOR).forEach { status ->
                        val p = payable(clock = clock)
                        setStatus(p.winner, status)
                        val mailer = RecordingTenderPayoutMailer()
                        tender(member = p.treasurer, mailer = mailer) { payOutTender(p.tenderId) }.tender.status shouldBe TenderStatus.PAID
                        mailer.sent.size shouldBe 1
                        payoutRows(p.tenderId).size shouldBe 1
                    }
                }
            }
        }

        test("V1.9.330: a guest winner (PAYOUT_FAILED), a second call and refused callers send no mail") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val failedCase = payable(clock = clock)
                    setStatus(failedCase.winner, MemberStatus.GUEST)
                    val mailer = RecordingTenderPayoutMailer()
                    tender(member = failedCase.treasurer, mailer = mailer) { payOutTender(failedCase.tenderId) }
                        .tender.status shouldBe TenderStatus.PAYOUT_FAILED
                    mailer.sent.size shouldBe 0

                    val p = payable(clock = clock)
                    tender(member = p.treasurer, mailer = mailer) { payOutTender(p.tenderId) }
                    mailer.sent.size shouldBe 1
                    tender(member = p.treasurer, mailer = mailer) { payOutTender(p.tenderId) }
                    tender(member = fx.member(label = "board3", role = AccountRole.BOARD), mailer = mailer) { payOutTender(p.tenderId) }
                    mailer.sent.size shouldBe 1
                    payoutRows(p.tenderId).size shouldBe 1

                    val q = payable(clock = clock)
                    listOf(p.winner, q.winner, q.board, fx.member(label = "plain")).forEach { who ->
                        tenderAttempt(member = who, mailer = mailer) { payOutTender(q.tenderId) }
                            .exceptionOrNull()
                            .shouldBeInstanceOf<ForbiddenException>()
                    }
                    mailer.sent.size shouldBe 1
                    payoutRows(q.tenderId).size shouldBe 0
                }
            }
        }

        test("V1.9.330: a mailer that throws does not fail or repeat the payout; a winner without a usable address gets no mail") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock)
                    val broken = RecordingTenderPayoutMailer(failWith = IllegalStateException("smtp down"))
                    tender(member = p.treasurer, mailer = broken) { payOutTender(p.tenderId) }.tender.status shouldBe TenderStatus.PAID
                    broken.sent.size shouldBe 1
                    payoutRows(p.tenderId).size shouldBe 1
                    tender(member = p.treasurer, mailer = broken) { payOutTender(p.tenderId) }
                    broken.sent.size shouldBe 1

                    val q = payable(clock = clock)
                    transaction { MemberTable.update({ MemberTable.id eq q.winner }) { it[MemberTable.email] = "" } }
                    val quiet = RecordingTenderPayoutMailer()
                    tender(member = q.treasurer, mailer = quiet) { payOutTender(q.tenderId) }.tender.status shouldBe TenderStatus.PAID
                    quiet.sent.size shouldBe 0
                }
            }
        }

        test("V1.9.330: a board member who is also the winner does not see the paying member's name") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val p = payable(clock = clock)
                    transaction {
                        AccountTable.update({ AccountTable.memberId eq p.winner }) { it.legacyAccountRole(role = AccountRole.BOARD) }
                    }
                    tender(member = p.treasurer) { payOutTender(p.tenderId) }
                    val seen = tender(member = p.winner) { getTender(p.tenderId) }.payout
                    seen?.paidByDisplayName shouldBe null
                    seen?.viewerIsWinner shouldBe true
                }
            }
        }
    })

private class Payable(
    val board: Uuid,
    val treasurer: Uuid,
    val winner: Uuid,
    val tenderId: String,
    val bidId: String,
)

class TenderPayoutTest : TenderPayoutScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class TenderPayoutPostgresTest : TenderPayoutScenarios(TestDatabase.Postgres())
