package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.TenderAcceptanceDecision
import network.lapis.cloud.shared.domain.TenderAcceptanceInput
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderDraftUpdateInput
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

internal fun storedStatus(tenderId: String): TenderStatus =
    transaction { TenderTable.selectAll().where { TenderTable.id eq Uuid.parse(tenderId) }.single()[TenderTable.status] }

internal fun tenderAuditSnapshots(tenderId: String): List<String> =
    transaction {
        AuditLogEntryTable
            .selectAll()
            .where { (AuditLogEntryTable.entityType eq AuditEntityType.TENDER) and (AuditLogEntryTable.entityId eq Uuid.parse(tenderId)) }
            .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.ASC)
            .map { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
    }

/** V1.9.201 -- the lifecycle of a tender: every allowed transition, every forbidden one, the lazy close and the audit entries. */
class TenderLifecycleTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        test("a draft is created, invisible to plain members, publishable by the board, and publishing writes exactly one audit entry") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val member = fx.member(label = "member")
                    val binding = fx.approvedMotion()
                    val created = tender(member = board) { createTender(createInput(binding = binding)) }
                    created.tender.status shouldBe TenderStatus.DRAFT
                    created.tender.bidVisibility shouldBe TenderBidVisibility.HIDDEN_UNTIL_DEADLINE
                    created.canManage shouldBe true
                    tenderAuditSnapshots(created.tender.id).shouldBeEmpty()

                    tenderAttempt(
                        member = member,
                    ) { getTender(created.tender.id) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    tender(member = member) { listTenders() }.shouldBeEmpty()
                    tender(member = board) { listTenders() } shouldHaveSize 1

                    val published = tender(member = board) { publishTender(created.tender.id) }
                    published.tender.status shouldBe TenderStatus.PUBLISHED
                    tender(member = member) { getTender(created.tender.id) }.tender.status shouldBe TenderStatus.PUBLISHED
                    val audit = tenderAuditSnapshots(created.tender.id)
                    audit shouldHaveSize 1
                    audit.single().contains("\"event\":\"PUBLISH\"") shouldBe true
                    audit.single().contains("Website") shouldBe false
                }
            }
        }

        test("the organisation default pre-fills the visibility, an explicit choice overrides it") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val binding = fx.approvedMotion()
                    fx.setDefaultVisibility(TenderBidVisibility.OPEN)
                    tender(member = board) { createTender(createInput(binding = binding)) }.tender.bidVisibility shouldBe
                        TenderBidVisibility.OPEN
                    tender(member = board) { createTender(createInput(binding = binding, visibility = TenderBidVisibility.OWN_ONLY)) }
                        .tender.bidVisibility shouldBe TenderBidVisibility.OWN_ONLY
                    tender(member = board) { getTenderDefaults() }.defaultVisibility shouldBe TenderBidVisibility.OPEN
                }
            }
        }

        test("a draft can be changed, a published tender cannot") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val binding = fx.approvedMotion()
                    val id = newTender(board = board, binding = binding, publish = false)
                    val update =
                        TenderDraftUpdateInput(
                            bindingMotionId = binding.toString(),
                            title = "Neuer Titel",
                            description = "Neu",
                            expectedResult = "Neu",
                            deadline = LocalDateTime(2026, 11, 4, 11, 0),
                            awardCriterion = TenderAwardCriterion.BEST_OFFER,
                            bidVisibility = TenderBidVisibility.OPEN,
                        )
                    val changed = tender(member = board) { updateTenderDraft(tenderId = id, input = update) }
                    changed.tender.title shouldBe "Neuer Titel"
                    changed.tender.bidVisibility shouldBe TenderBidVisibility.OPEN
                    tender(member = board) { publishTender(id) }
                    tenderAttempt(
                        member = board,
                    ) { updateTenderDraft(tenderId = id, input = update) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    tenderAttempt(member = board) { publishTender(id) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                }
            }
        }

        test("the fields are validated: blank texts, too short and too long deadline, EUR") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val binding = fx.approvedMotion()
                    val ok = createInput(binding = binding)
                    listOf(
                        ok.copy(title = "   "),
                        ok.copy(description = ""),
                        ok.copy(expectedResult = " "),
                        ok.copy(title = "x".repeat(201)),
                        ok.copy(deadline = LocalDateTime(2026, 11, 2, 11, 59)),
                        ok.copy(deadline = LocalDateTime(2027, 11, 5, 11, 0)),
                        ok.copy(currency = TenderCurrency.EUR),
                    ).forEach { bad ->
                        tenderAttempt(member = board) { createTender(bad) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    }
                    // exactly one hour ahead is the earliest accepted deadline
                    tender(member = board) { createTender(ok.copy(deadline = LocalDateTime(2026, 11, 2, 12, 0))) }.tender.status shouldBe
                        TenderStatus.DRAFT
                }
            }
        }

        test("lazy close: a write persists CLOSED with closed_at, a read only shows it and writes nothing") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val bidder = fx.member(label = "bidder")
                    val binding = fx.approvedMotion()
                    val id = newTender(board = board, binding = binding)
                    val firstBid = tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "10.00")) }
                    clock.advance(1.days - 1.seconds)
                    tender(member = bidder) { listTenders() }.single().status shouldBe TenderStatus.PUBLISHED

                    clock.advance(1.seconds)
                    tender(member = bidder) { listTenders() }.single().status shouldBe TenderStatus.CLOSED
                    tender(member = bidder) { getTender(id) }.tender.status shouldBe TenderStatus.CLOSED
                    tender(member = bidder) { listTenders(TenderStatus.CLOSED) } shouldHaveSize 1
                    tender(member = bidder) { listTenders(TenderStatus.PUBLISHED) }.shouldBeEmpty()
                    // reads never persisted the close
                    storedStatus(id) shouldBe TenderStatus.PUBLISHED
                    transaction { TenderTable.selectAll().single()[TenderTable.closedAt] }.shouldBeNull()

                    // a refused write rolls back with its transaction; readers keep computing the status
                    tenderAttempt(member = bidder) {
                        submitBid(tenderId = id, input = bidInput(price = "9.00"))
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    storedStatus(id) shouldBe TenderStatus.PUBLISHED

                    // a writing path that succeeds persists the close (closed_at) together with its own change
                    tender(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = firstBid.id)) }.tender.status shouldBe
                        TenderStatus.AWARDED
                    transaction { TenderTable.selectAll().single()[TenderTable.closedAt] }.shouldNotBeNull()
                    // publication and award: closing itself writes no audit entry
                    tenderAuditSnapshots(id) shouldHaveSize 2
                }
            }
        }

        test("the forbidden transitions are refused") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val bidder = fx.member(label = "bidder")
                    val binding = fx.approvedMotion()
                    val draft = newTender(board = board, binding = binding, publish = false)
                    tenderAttempt(member = bidder) {
                        submitBid(tenderId = draft, input = bidInput(price = "5.00"))
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    tenderAttempt(member = board) { awardTender(TenderAwardInput(tenderId = draft, bidId = Uuid.random().toString())) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    tenderAttempt(
                        member = board,
                    ) { recordAcceptance(TenderAcceptanceInput(tenderId = draft, decision = TenderAcceptanceDecision.ACCEPTED)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()

                    val running = newTender(board = board, binding = binding)
                    val bid = tender(member = bidder) { submitBid(tenderId = running, input = bidInput(price = "5.00")) }
                    // award while the tender still runs, acceptance before an award
                    tenderAttempt(
                        member = board,
                    ) {
                        awardTender(
                            TenderAwardInput(tenderId = running, bidId = bid.id),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    tenderAttempt(
                        member = board,
                    ) { recordAcceptance(TenderAcceptanceInput(tenderId = running, decision = TenderAcceptanceDecision.ACCEPTED)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                }
            }
        }

        test("cancel: allowed from draft, running, closed and awarded with a reason, never twice, never when completed") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val other = fx.member(label = "other-board", role = AccountRole.BOARD)
                    val bidder = fx.member(label = "bidder")
                    val binding = fx.approvedMotion()

                    val draft = newTender(board = board, binding = binding, publish = false)
                    tenderAttempt(
                        member = board,
                    ) { cancelTender(tenderId = draft, reason = "  ") }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    tender(member = board) { cancelTender(tenderId = draft, reason = "Nicht mehr gebraucht") }.tender.status shouldBe
                        TenderStatus.CANCELLED
                    tenderAttempt(member = board) {
                        cancelTender(tenderId = draft, reason = "noch einmal")
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    tenderAttempt(member = bidder) { getTender(draft) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()

                    val running = newTender(board = board, binding = binding)
                    tender(member = board) { cancelTender(tenderId = running, reason = "Abgesagt") }.tender.status shouldBe
                        TenderStatus.CANCELLED
                    tender(member = bidder) { getTender(running) }.tender.status shouldBe TenderStatus.CANCELLED

                    val awarded = newTender(board = board, binding = binding)
                    val bid = tender(member = bidder) { submitBid(tenderId = awarded, input = bidInput(price = "5.00")) }
                    clock.advance(1.days)
                    tender(member = other) { awardTender(TenderAwardInput(tenderId = awarded, bidId = bid.id)) }.tender.status shouldBe
                        TenderStatus.AWARDED
                    tender(member = board) { cancelTender(tenderId = awarded, reason = "Doch nicht") }.tender.status shouldBe
                        TenderStatus.CANCELLED
                    tenderAuditSnapshots(awarded).last().contains("\"fromStatus\":\"AWARDED\"") shouldBe true
                }
            }
        }

        test("the whole way: bid, close, award the cheapest, reject once, then accept - COMPLETED") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val a = fx.member(label = "a")
                    val b = fx.member(label = "b")
                    val binding = fx.approvedMotion()
                    val id = newTender(board = board, binding = binding)
                    val bidA = tender(member = a) { submitBid(tenderId = id, input = bidInput(price = "20.00")) }
                    val bidB = tender(member = b) { submitBid(tenderId = id, input = bidInput(price = "15.50")) }
                    clock.advance(1.days)

                    // not the cheapest -> refused, the cheapest -> awarded
                    tenderAttempt(
                        member = board,
                    ) {
                        awardTender(
                            TenderAwardInput(tenderId = id, bidId = bidA.id),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    val awarded = tender(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = bidB.id)) }
                    awarded.tender.status shouldBe TenderStatus.AWARDED
                    awarded.tender.award
                        .shouldNotBeNull()
                        .winnerDisplayName shouldBe "Tender-Test b"
                    tenderAttempt(
                        member = board,
                    ) {
                        awardTender(
                            TenderAwardInput(tenderId = id, bidId = bidB.id),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()

                    // a rejection needs a reason and leaves the tender awarded
                    tenderAttempt(
                        member = board,
                    ) { recordAcceptance(TenderAcceptanceInput(tenderId = id, decision = TenderAcceptanceDecision.REJECTED)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    val rejected =
                        tender(member = board) {
                            recordAcceptance(
                                TenderAcceptanceInput(tenderId = id, decision = TenderAcceptanceDecision.REJECTED, reason = "Nachbessern"),
                            )
                        }
                    rejected.tender.status shouldBe TenderStatus.AWARDED
                    rejected.acceptances shouldHaveSize 1
                    val done =
                        tender(
                            member = board,
                        ) { recordAcceptance(TenderAcceptanceInput(tenderId = id, decision = TenderAcceptanceDecision.ACCEPTED)) }
                    done.tender.status shouldBe TenderStatus.COMPLETED
                    done.acceptances shouldHaveSize 2
                    tenderAttempt(
                        member = board,
                    ) { cancelTender(tenderId = id, reason = "zu spaet") }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    tender(member = a) { getTender(id) }.myBid.shouldNotBeNull().status shouldBe TenderBidStatus.REJECTED
                    tender(member = b) { getTender(id) }.myBid.shouldNotBeNull().status shouldBe TenderBidStatus.AWARDED

                    // publish, award, acceptance: one audit entry each; nothing for bids or the lazy close
                    tenderAuditSnapshots(id).map { Regex("\"event\":\"([A-Z_]+)\"").find(it)!!.groupValues[1] } shouldBe
                        listOf("PUBLISH", "AWARD", "ACCEPTANCE", "ACCEPTANCE")
                }
            }
        }
    })
