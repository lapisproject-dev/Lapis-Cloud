package network.lapis.cloud.server.tender

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.TenderAwardTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.db.generated.VoteEligibleVoterTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * V1.9.201 -- the tender rules that no constraint can state alone (one bid per bidder, a bid never after the deadline, the award against the
 * cancellation, the registration of the award motion against the opening of its vote) are upheld by the lock order motion -> tender -> bids -> member
 * -> audit. Proven with real parallel requests on H2 (smoke) and on a real PostgreSQL (the actual proof, including "no deadlock").
 */
abstract class TenderConcurrencyScenarios(
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

        test("four parallel first bids of the same bidder leave exactly one row, and nobody sees a unique violation") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val bidder = fx.member(label = "bidder")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    val results =
                        coroutineScope {
                            (1..4)
                                .map { i ->
                                    async(
                                        Dispatchers.IO,
                                    ) { tenderAttempt(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "1$i.00")) } }
                                }.awaitAll()
                        }
                    transaction { TenderBidTable.selectAll().where { TenderBidTable.bidderMemberId eq bidder }.count() } shouldBe 1
                    if (db.isPostgres) results.forEach { it.isSuccess shouldBe true }
                    results.forEach { r -> r.exceptionOrNull()?.shouldBeInstanceOf<ConflictException>() }
                }
            }
        }

        test("at the deadline a parallel award and new bids: no bid gets in, the award succeeds") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val early = fx.member(label = "early")
                    val late = (1..3).map { fx.member(label = "late$it") }
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    val first = tender(member = early) { submitBid(tenderId = id, input = bidInput(price = "10.00")) }
                    clock.advance(1.days)
                    val results =
                        coroutineScope {
                            val bids =
                                late.map { who ->
                                    async(
                                        Dispatchers.IO,
                                    ) { tenderAttempt(member = who) { submitBid(tenderId = id, input = bidInput(price = "1.00")) } }
                                }
                            val award =
                                async(
                                    Dispatchers.IO,
                                ) { tenderAttempt(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = first.id)) } }
                            bids.awaitAll() to award.await()
                        }
                    results.first.forEach { it.exceptionOrNull().shouldBeInstanceOf<ConflictException>() }
                    transaction { TenderBidTable.selectAll().count() } shouldBe 1
                    if (db.isPostgres) results.second.isSuccess shouldBe true
                    transaction { TenderAwardTable.selectAll().count() } shouldBe (if (results.second.isSuccess) 1 else 0)
                }
            }
        }

        test("an award racing a cancellation: the end state is consistent with the winner") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val other = fx.member(label = "other", role = AccountRole.BOARD)
                    val bidder = fx.member(label = "bidder")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    val bid = tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "10.00")) }
                    clock.advance(1.days)
                    val results =
                        coroutineScope {
                            val award =
                                async(
                                    Dispatchers.IO,
                                ) { tenderAttempt(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = bid.id)) } }
                            val cancel =
                                async(Dispatchers.IO) { tenderAttempt(member = other) { cancelTender(tenderId = id, reason = "Abgesagt") } }
                            award.await() to cancel.await()
                        }
                    // a cancellation is possible from CLOSED and from AWARDED, so it always lands; an award that came second is refused
                    if (db.isPostgres) results.second.isSuccess shouldBe true
                    storedStatus(id) shouldBe (if (results.second.isSuccess) TenderStatus.CANCELLED else TenderStatus.AWARDED)
                    if (results.first.isFailure) results.first.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    transaction { TenderAwardTable.selectAll().count() } shouldBe (if (results.first.isSuccess) 1 else 0)
                }
            }
        }

        test(
            "registering the award motion racing the opening of its vote: either the vote came first (no registration) or the circle has no bidder",
        ) {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = bestOfferScenario(fx = fx, clock = clock, present = { c, b -> c + b }, register = false)
                    val results =
                        coroutineScope {
                            val register =
                                async(
                                    Dispatchers.IO,
                                ) {
                                    tenderAttempt(
                                        member = s.board,
                                    ) { registerAwardMotion(tenderId = s.tenderId, motionId = s.awardMotion.toString()) }
                                }
                            val open =
                                async(
                                    Dispatchers.IO,
                                ) {
                                    attempt(
                                        member = s.board,
                                    ) { GovernanceService(call = it).openVote(VoteOpenInput(motionId = s.awardMotion.toString())) }
                                }
                            register.await() to open.await()
                        }
                    val registered = transaction { TenderTable.selectAll().single()[TenderTable.awardMotionId] } == s.awardMotion
                    val voters =
                        transaction {
                            val voteId = VoteTable.selectAll().map { it[VoteTable.id] }.singleOrNull()
                            voteId?.let { v ->
                                VoteEligibleVoterTable
                                    .selectAll()
                                    .where {
                                        VoteEligibleVoterTable.voteId eq v
                                    }.map { it[VoteEligibleVoterTable.memberId] }
                            }
                        }
                    if (voters != null && registered) (voters.toSet() intersect s.bidders.toSet()) shouldBe emptySet<Uuid>()
                    if (voters != null && !registered) results.first.isFailure shouldBe true
                    if (db.isPostgres) results.second.isSuccess shouldBe true
                }
            }
        }

        test(
            "every list of the service comes in a deterministic order: deadline descending, then id; bids by price, then moment, then id",
        ) {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val binding = fx.approvedMotion()
                    val ids =
                        listOf(
                            java.time.LocalDate.of(2026, 11, 5),
                            java.time.LocalDate.of(2026, 11, 7),
                            java.time.LocalDate.of(2026, 11, 6),
                        ).map {
                            newTender(
                                board = board,
                                binding = binding,
                                deadline = LocalDateTime(it.year, it.monthValue, it.dayOfMonth, 11, 0),
                            )
                        }
                    tender(member = board) { listTenders() }.map { it.id } shouldBe listOf(ids[1], ids[2], ids[0])
                    val tied = (1..3).map { fx.member(label = "tied$it") }
                    tied.forEach { tender(member = it) { submitBid(tenderId = ids[0], input = bidInput(price = "7.00")) } }
                    tender(member = fx.member(label = "cheap")) { submitBid(tenderId = ids[0], input = bidInput(price = "3.00")) }
                    clock.advance(4.days)
                    val shown = tender(member = board) { getTender(ids[0]) }.bids
                    shown.map { it.price.toPlainString() } shouldBe listOf("3.00", "7.00", "7.00", "7.00")
                    // equal price: the earlier price moment, then the id -- the same answer every time
                    val again = tender(member = board) { getTender(ids[0]) }.bids
                    again.map { it.id } shouldBe shown.map { it.id }
                }
            }
        }
    })

class TenderConcurrencyTest : TenderConcurrencyScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class TenderConcurrencyPostgresTest : TenderConcurrencyScenarios(TestDatabase.Postgres())
