package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.forMemberUpdate
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeSeatAutoEndReason
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.IncumbentPolicy
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SeatOutcome
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val ROUNDS = 6

// the tally takes its stichtag from the organisation's clock, so the racing leave must use the same day (a fixed date broke at midnight)
private val LEAVE_STICHTAG: LocalDate = OrganizationTimeZone.today()
private val LEAVE_NOW: LocalDateTime = LocalDateTime(LEAVE_STICHTAG, kotlinx.datetime.LocalTime(10, 0))

/**
 * Welle V1.9.103 -- the succession under real concurrency (H2 and PostgreSQL run the same assertions). Lock order of the tally: motion,
 * election, winner member rows, seat rows, audit last; the status hook of V1.9.102 takes member row, seat rows, audit. Each scenario counts
 * how often the transaction block ran, because Exposed repeats the whole block on a failed statement and would otherwise hide an error.
 */
abstract class ElectionSeatSuccessionConcurrencyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = CommitteeSeatFixture()
        val es = ElectionSuccessionFixture(fx)

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            es.cleanUp()
            fx.cleanUp()
            db.deactivate()
        }

        /** What `leaveMembership` does around the seat hook: lock the member row, set the status, end the seats, audit. */
        fun leave(
            member: Uuid,
            runs: AtomicInteger,
        ) = transaction {
            runs.incrementAndGet()
            MemberTable
                .selectAll()
                .where { MemberTable.id eq member }
                .forMemberUpdate()
                .single()
            MemberTable.update({ MemberTable.id eq member }) { it[status] = MemberStatus.WITHDRAWN }
            CommitteeSeatLifecycle
                .endSeatsForStatusChange(
                    memberId = member,
                    newStatus = MemberStatus.WITHDRAWN,
                    reason = MemberStatus.WITHDRAWN,
                    stichtag = LEAVE_STICHTAG,
                    now = LEAVE_NOW,
                    actorMemberId = null,
                    actorRole = null,
                ).recordAudits()
        }

        fun <T> raceTwo(
            first: () -> T,
            second: () -> T,
        ): List<Result<T>> {
            val pool = Executors.newFixedThreadPool(2)
            try {
                val barrier = CyclicBarrier(2)
                val futures =
                    listOf(first, second).map { job ->
                        pool.submit<Result<T>> {
                            barrier.await(20, TimeUnit.SECONDS)
                            runCatching { job() }
                        }
                    }
                return futures.map { it.get(90, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
        }

        fun seatsOf(
            member: Uuid,
            committee: Uuid,
        ) = transaction {
            CommitteeMembershipTable
                .selectAll()
                .where { CommitteeMembershipTable.memberId eq member }
                .filter { it[CommitteeMembershipTable.committeeId] == committee }
        }

        test("the tally races the winner's own leaveMembership: skipped, or seated and then ended by the hook -- never a half state") {
            repeat(ROUNDS) {
                val group = fx.committee()
                val winner = fx.member()
                val election =
                    es.seed(
                        committee = group,
                        role = CommitteeRole.MEMBER,
                        policy = IncumbentPolicy.KEEP,
                        candidates =
                            listOf(
                                winner to 3,
                                fx.member() to 1,
                            ),
                    )
                val leaveRuns = AtomicInteger()

                val results = raceTwo({ es.tally(election) }, { leave(winner, leaveRuns) })

                results.forEach { it.exceptionOrNull() shouldBe null }
                leaveRuns.get() shouldBe 1
                es.electionRow(election.electionId)[ElectionTable.status] shouldBe ElectionStatus.TALLIED
                val seats = seatsOf(winner, group)
                val outcome =
                    es
                        .outcomes(
                            election.electionId,
                        ).map { it[network.lapis.cloud.server.db.generated.ElectionSeatOutcomeTable.outcome] }
                when (outcome.single()) {
                    SeatOutcome.SKIPPED -> seats.size shouldBe 0
                    SeatOutcome.SEATED -> {
                        // the hook ran after the tally committed: the seat exists and ended on the stichtag with the reason
                        seats.size shouldBe 1
                        seats.single()[CommitteeMembershipTable.autoEndReason] shouldBe CommitteeSeatAutoEndReason.WITHDRAWN
                        seats.single()[CommitteeMembershipTable.until] shouldBe LEAVE_STICHTAG
                    }
                    else -> error("unexpected outcome $outcome")
                }
            }
        }

        test("the tally races the previous holder's leaveMembership: the seat ends exactly once and never carries two causes") {
            repeat(ROUNDS) {
                val group = fx.committee()
                val old = fx.member()
                val winner = fx.member()
                fx.seat(committee = group, member = old, role = CommitteeRole.CHAIR, since = SUCCESSION_OLD_SINCE)
                val election =
                    es.seed(
                        committee = group,
                        role = CommitteeRole.CHAIR,
                        policy = IncumbentPolicy.REPLACE,
                        candidates =
                            listOf(
                                winner to 3,
                                fx.member() to 1,
                            ),
                    )
                val leaveRuns = AtomicInteger()

                val results = raceTwo({ es.tally(election) }, { leave(old, leaveRuns) })

                results.forEach { it.exceptionOrNull() shouldBe null }
                leaveRuns.get() shouldBe 1
                val seat = seatsOf(old, group).single()
                (seat[CommitteeMembershipTable.until] != null) shouldBe true
                val byElection = seat[CommitteeMembershipTable.endedByElectionId] != null
                val byStatus = seat[CommitteeMembershipTable.autoEndReason] != null
                (byElection xor byStatus) shouldBe true
                es.seatsStartedBy(election.electionId).single()[CommitteeMembershipTable.memberId] shouldBe winner
            }
        }

        test("two parallel tallies of the same election: exactly one wins, the other is refused, nothing is seated twice") {
            repeat(ROUNDS) {
                val group = fx.committee()
                val old = fx.member()
                val winner = fx.member()
                fx.seat(committee = group, member = old, role = CommitteeRole.CHAIR, since = SUCCESSION_OLD_SINCE)
                val election =
                    es.seed(
                        committee = group,
                        role = CommitteeRole.CHAIR,
                        policy = IncumbentPolicy.REPLACE,
                        candidates =
                            listOf(
                                winner to 3,
                                fx.member() to 1,
                            ),
                    )
                val runs = AtomicInteger()
                val tallyOnce = {
                    transaction {
                        runs.incrementAndGet()
                        val motionRow = MotionDecisionLock.lockMotion(election.motionId)
                        val electionRow = lockElectionRow(election.electionId)
                        tallyElectionLocked(electionMotionRow = motionRow, electionRow = electionRow, actor = es.actor)
                    }
                }

                val results = raceTwo(tallyOnce, tallyOnce)

                results.count { it.isSuccess } shouldBe 1
                results.count { it.exceptionOrNull() is ConflictException } shouldBe 1
                runs.get() shouldBe 2
                es.seatsStartedBy(election.electionId).size shouldBe 1
                es.outcomes(election.electionId).size shouldBe 2 // SEATED + ENDED, written once
                seatsOf(old, group).single()[CommitteeMembershipTable.endedByElectionId] shouldBe election.electionId
            }
        }
    })

class ElectionSeatSuccessionConcurrencyTest : ElectionSeatSuccessionConcurrencyScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ElectionSeatSuccessionConcurrencyPostgresTest : ElectionSeatSuccessionConcurrencyScenarios(TestDatabase.Postgres())
