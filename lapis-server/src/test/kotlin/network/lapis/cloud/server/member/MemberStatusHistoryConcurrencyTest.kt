package network.lapis.cloud.server.member

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.forMemberUpdate
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * Welle V1.9.59 -- the status history under real concurrency, as scenarios so the same assertions run on H2 and on PostgreSQL.
 * Every status change takes the member row lock first (`forMemberUpdate()`), writes `member.status`, then calls
 * [MemberStatusHistory.recordLocked]; the member lock serialises the writers of ONE member, so the chain stays a chain: strictly
 * increasing `effective_from`, every `previous_status` the status of the row before, the last row equal to `member.status`.
 * Each scenario counts how often the transaction block ran, because Exposed repeats the WHOLE block on a failed statement and
 * would otherwise mask a constraint error behind a retry.
 */
abstract class MemberStatusHistoryConcurrencyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val created = mutableListOf<Uuid>()

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            transaction {
                MemberStatusHistoryTable.deleteWhere { memberId inList created }
                MemberTable.deleteWhere { MemberTable.id inList created }
            }
            db.deactivate()
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Verlauf Nebenlaeufigkeit"
                    it[email] = "history-conc-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                }
            }
            created += id
            return id
        }

        fun chain(id: Uuid) =
            transaction {
                MemberStatusHistoryTable
                    .selectAll()
                    .where { MemberStatusHistoryTable.memberId eq id }
                    .orderBy(MemberStatusHistoryTable.effectiveFrom to SortOrder.ASC)
                    .map {
                        Triple(
                            it[MemberStatusHistoryTable.status],
                            it[MemberStatusHistoryTable.previousStatus],
                            it[MemberStatusHistoryTable.effectiveFrom],
                        )
                    }
            }

        /** What the service paths do: lock the member row, write the status, record the change. */
        fun change(
            id: Uuid,
            to: MemberStatus,
            now: LocalDateTime,
            blockRuns: AtomicInteger,
        ) = transaction {
            blockRuns.incrementAndGet()
            MemberTable
                .selectAll()
                .where { MemberTable.id eq id }
                .forMemberUpdate()
                .single()
            MemberTable.update({ MemberTable.id eq id }) { it[status] = to }
            MemberStatusHistory.recordLocked(memberId = id, newStatus = to, now = now, source = MemberStatusHistorySource.LIVE)
        }

        fun assertWellFormed(id: Uuid) {
            val rows = chain(id)
            rows.zipWithNext().forEach { (a, b) ->
                (b.third > a.third) shouldBe true
                b.second shouldBe a.first
            }
            rows.first().second shouldBe null
            transaction {
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq id }
                    .single()[MemberTable.status]
                    .name
            } shouldBe
                rows.last().first
        }

        test("a now older than the latest row is clamped to latest + 1 microsecond, the chain stays strictly increasing") {
            val id = newMember()
            val runs = AtomicInteger()
            change(id, MemberStatus.ACTIVE, LocalDateTime.parse("2026-05-01T10:00:05"), runs)
            change(id, MemberStatus.DONOR, LocalDateTime.parse("2026-05-01T10:00:00"), runs)
            change(id, MemberStatus.WITHDRAWN, LocalDateTime.parse("2026-05-01T10:00:00"), runs)
            val rows = chain(id)
            rows.map { it.first } shouldBe listOf("ACTIVE", "DONOR", "WITHDRAWN")
            rows[1].third shouldBe LocalDateTime.parse("2026-05-01T10:00:05.000001")
            rows[2].third shouldBe LocalDateTime.parse("2026-05-01T10:00:05.000002")
            assertWellFormed(id)
            runs.get() shouldBe 3
        }

        test("two threads change the same member status in turns: no constraint error, no retry, a consistent chain") {
            repeat(ROUNDS) {
                val id = newMember()
                val runs = AtomicInteger()
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val barrier = CyclicBarrier(2)
                    val statuses = listOf(MemberStatus.DONOR, MemberStatus.ACTIVE, MemberStatus.FRIEND, MemberStatus.WITHDRAWN)
                    val futures =
                        (0 until 2).map { t ->
                            pool.submit<Result<Unit>> {
                                barrier.await(20, TimeUnit.SECONDS)
                                runCatching {
                                    statuses.forEachIndexed { i, s ->
                                        // deliberately different, partly OLD "now" values per thread
                                        change(id, s, LocalDateTime.parse("2026-05-01T10:00:0${(i + t) % 4}"), runs)
                                    }
                                }
                            }
                        }
                    futures.map { it.get(60, TimeUnit.SECONDS) }.forEach { it.exceptionOrNull() shouldBe null }
                } finally {
                    pool.shutdownNow()
                }
                runs.get() shouldBe 8
                assertWellFormed(id)
            }
        }

        test("two threads race to change a brand-new member to the same status: exactly one row for that status") {
            repeat(ROUNDS) {
                val id = newMember()
                val runs = AtomicInteger()
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val barrier = CyclicBarrier(2)
                    val futures =
                        (0 until 2).map {
                            pool.submit<Result<Boolean>> {
                                barrier.await(20, TimeUnit.SECONDS)
                                runCatching { change(id, MemberStatus.DONOR, LocalDateTime.parse("2026-05-01T10:00:00"), runs) }
                            }
                        }
                    val results = futures.map { it.get(60, TimeUnit.SECONDS) }
                    results.forEach { it.exceptionOrNull() shouldBe null }
                    results.count { it.getOrNull() == true } shouldBe 1
                } finally {
                    pool.shutdownNow()
                }
                runs.get() shouldBe 2
                chain(id).map { it.first } shouldBe listOf("DONOR")
                assertWellFormed(id)
            }
        }
    })

private const val ROUNDS = 8

class MemberStatusHistoryConcurrencyTest : MemberStatusHistoryConcurrencyScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MemberStatusHistoryConcurrencyPostgresTest : MemberStatusHistoryConcurrencyScenarios(TestDatabase.Postgres())
