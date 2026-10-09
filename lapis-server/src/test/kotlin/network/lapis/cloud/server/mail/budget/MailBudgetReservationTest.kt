package network.lapis.cloud.server.mail.budget

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MailBudgetLockTable
import network.lapis.cloud.server.db.generated.MailSendSlotTable
import network.lapis.cloud.server.mail.MailBudgetConfig
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Welle V1.9.81 -- the database half of the hourly budget ([MailBudgetStore]) on H2 AND on PostgreSQL: sequential limits per lane, the
 * exact window boundary, the guarantee under 8 concurrent threads (exactly `max` slots, never more), a held row lock turning into a
 * WaitUntil instead of a hang, the bulk pause that only ever extends, and the slot sweep.
 */
abstract class MailBudgetReservationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val now = LocalDateTime(2031, 6, 1, 12, 0, 0)

        beforeSpec { db.activate() }
        installLaneGuards(db = db)

        fun clean() {
            transaction {
                MailSendSlotTable.deleteWhere { MailSendSlotTable.id neq Uuid.random() }
                MailBudgetLockTable.update({ MailBudgetLockTable.id eq 1.toShort() }) { it[bulkPausedUntil] = null }
            }
        }
        beforeTest { clean() }
        afterSpec {
            clean()
            db.deactivate()
        }

        fun store(
            max: Int,
            reserve: Int,
        ) = MailBudgetStore(MailBudgetConfig.Enabled(maxPerHour = max, reservePerHour = reserve))

        fun slotCount(lane: String? = null): Int =
            transaction {
                MailSendSlotTable
                    .selectAll()
                    .let { q ->
                        if (lane ==
                            null
                        ) {
                            q
                        } else {
                            q.where { MailSendSlotTable.lane eq lane }
                        }
                    }.count()
                    .toInt()
            }

        test("without a configured budget there is no slot, no lock, always Allowed") {
            val unlimited = MailBudgetStore(null)
            repeat(50) { unlimited.reserve(lane = MailLane.BULK, now = now) shouldBe BudgetDecision.Allowed }
            slotCount() shouldBe 0
            unlimited.usedInWindow(now) shouldBe 0
        }

        test("sequentially: SYSTEM fills the whole maximum, BULK stops at max - reserve; the wait ends one window after the oldest slot") {
            val s = store(max = 10, reserve = 2)
            repeat(8) { i -> s.reserve(lane = MailLane.BULK, now = now.plusDuration(i.seconds)) shouldBe BudgetDecision.Allowed }
            // 8 used = bulk limit: BULK is blocked until the oldest slot is one hour old, SYSTEM still has its reserve
            s.reserve(lane = MailLane.BULK, now = now.plusDuration(10.seconds)) shouldBe BudgetDecision.WaitUntil(now.plusDuration(1.hours))
            s.reserve(lane = MailLane.SYSTEM, now = now.plusDuration(10.seconds)) shouldBe BudgetDecision.Allowed
            s.reserve(lane = MailLane.SYSTEM, now = now.plusDuration(11.seconds)) shouldBe BudgetDecision.Allowed
            // 10 used = max: now SYSTEM waits too
            s.reserve(lane = MailLane.SYSTEM, now = now.plusDuration(12.seconds)) shouldBe
                BudgetDecision.WaitUntil(now.plusDuration(1.hours))
            slotCount() shouldBe 10
            slotCount("BULK") shouldBe 8
        }

        test("the window is half-open: a slot exactly 3600 s old is outside, one second younger is inside") {
            val s = store(max = 10, reserve = 2)
            transaction {
                listOf(now.plusDuration((-3600).seconds), now.plusDuration((-3599).seconds)).forEach { at ->
                    MailSendSlotTable.insert {
                        it[id] = Uuid.random()
                        it[reservedAt] = at
                        it[lane] = "SYSTEM"
                    }
                }
            }
            s.usedInWindow(now) shouldBe 1
        }

        test("8 threads x 50 attempts against max = 100 yield EXACTLY 100 slots") {
            val s = store(max = 100, reserve = 20)
            val pool = Executors.newFixedThreadPool(8)
            val allowed = AtomicInteger()
            try {
                val futures =
                    (1..8).map {
                        pool.submit {
                            repeat(50) {
                                if (s.reserve(lane = MailLane.SYSTEM, now = now) == BudgetDecision.Allowed) allowed.incrementAndGet()
                            }
                        }
                    }
                futures.forEach { it.get(120, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            allowed.get() shouldBe 100
            slotCount() shouldBe 100
        }

        test("concurrent mixed lanes never exceed the maximum overall nor the bulk limit") {
            val s = store(max = 100, reserve = 20)
            val pool = Executors.newFixedThreadPool(8)
            try {
                (1..8)
                    .map { index ->
                        pool.submit {
                            val lane = if (index % 2 == 0) MailLane.BULK else MailLane.SYSTEM
                            repeat(60) { s.reserve(lane = lane, now = now) }
                        }
                    }.forEach { it.get(120, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            (slotCount() <= 100) shouldBe true
            (slotCount("BULK") <= 80) shouldBe true
        }

        test("a held row lock turns into WaitUntil(now + recheck) within seconds -- never a hang") {
            val s = store(max = 10, reserve = 2)
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holderPool = Executors.newSingleThreadExecutor()
            val holder =
                holderPool.submit {
                    transaction {
                        MailBudgetLockTable
                            .selectAll()
                            .where { MailBudgetLockTable.id eq 1.toShort() }
                            .forUpdate()
                            .single()
                        holding.countDown()
                        release.await(60, TimeUnit.SECONDS)
                    }
                }
            try {
                holding.await(20, TimeUnit.SECONDS) shouldBe true
                val started = System.nanoTime()
                val decision = s.reserve(lane = MailLane.SYSTEM, now = now)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                decision shouldBe BudgetDecision.WaitUntil(now.plusDuration(MailBudget.RECHECK))
                elapsedMs shouldBeLessThan 30_000L
                slotCount() shouldBe 0
            } finally {
                release.countDown()
                holder.get(30, TimeUnit.SECONDS)
                holderPool.shutdownNow()
            }
            // the pool is usable afterwards
            s.reserve(lane = MailLane.SYSTEM, now = now) shouldBe BudgetDecision.Allowed
        }

        test("the bulk pause only ever extends, never shortens") {
            val s = store(max = 10, reserve = 2)
            s.bulkPausedUntil() shouldBe null
            s.pauseBulk(now.plusDuration(10.minutes))
            s.bulkPausedUntil() shouldBe now.plusDuration(10.minutes)
            s.pauseBulk(now.plusDuration(5.minutes))
            s.bulkPausedUntil() shouldBe now.plusDuration(10.minutes)
            s.pauseBulk(now.plusDuration(20.minutes))
            s.bulkPausedUntil() shouldBe now.plusDuration(20.minutes)
            // also without a configured budget
            MailBudgetStore(null).bulkPausedUntil() shouldBe now.plusDuration(20.minutes)
        }

        test("the sweep deletes slots older than the cutoff and keeps younger ones") {
            val s = store(max = 10, reserve = 2)
            transaction {
                listOf(now.plusDuration((-3).hours), now.plusDuration((-90).minutes)).forEach { at ->
                    MailSendSlotTable.insert {
                        it[id] = Uuid.random()
                        it[reservedAt] = at
                        it[lane] = "BULK"
                    }
                }
            }
            s.purgeSlotsOlderThan(now.plusDuration((-2).hours)) shouldBe 1
            slotCount() shouldBe 1
        }

        test("release gives exactly the named slot back") {
            val s = store(max = 10, reserve = 2)
            val first = s.reserveSlot(lane = MailLane.SYSTEM, now = now)
            val second = s.reserveSlot(lane = MailLane.SYSTEM, now = now)
            slotCount() shouldBe 2
            s.release(first.slotId)
            slotCount() shouldBe 1
            transaction { MailSendSlotTable.selectAll().single()[MailSendSlotTable.id] } shouldBe second.slotId
        }
    })

class MailBudgetReservationTest : MailBudgetReservationScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MailBudgetReservationPostgresTest : MailBudgetReservationScenarios(TestDatabase.Postgres())
