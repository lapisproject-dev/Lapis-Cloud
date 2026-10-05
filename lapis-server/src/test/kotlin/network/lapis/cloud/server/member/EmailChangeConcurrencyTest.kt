package network.lapis.cloud.server.member

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.mail.FakeEmailChangeMailer
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.rpc.EmailChangePendingNotFoundException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Welle V1.9.56 -- the address-change lifecycle under real concurrency. Every scenario starts two threads on a barrier,
 * repeats [ROUNDS] times with fresh members, and asserts the INVARIANT, not one interleaving: at most one open change
 * per member, exactly one winner of a race, an address owned by exactly one member. Written as scenarios so the same
 * assertions run on H2 and on PostgreSQL (stricter there: SQLSTATE 25P02 after a unique violation, row-lock timeouts,
 * the deadlock guard of the lane).
 *
 * Holding times stay far below the lane's 10 s lock timeout (nothing here sleeps).
 */
abstract class EmailChangeConcurrencyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = EmailChangeFixture()

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        fun newAddress() = "ec-conc-${Uuid.random().toString().take(12)}@example.org"

        /** Runs both [tasks] at the same instant; returns their results (a thrown exception is a failure value). */
        fun <T> race(vararg tasks: () -> T): List<Result<T>> {
            val pool = Executors.newFixedThreadPool(tasks.size)
            try {
                val barrier = CyclicBarrier(tasks.size)
                val futures =
                    tasks.map { task ->
                        pool.submit<Result<T>> {
                            barrier.await(20, TimeUnit.SECONDS)
                            runCatching { task() }
                        }
                    }
                return futures.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
        }

        test("two simultaneous proposals for one member: both succeed, exactly one stays open, the other is SUPERSEDED") {
            repeat(ROUNDS) {
                val mailer = FakeEmailChangeMailer()
                val svc = fx.service(mailer = mailer)
                val target = fx.member()
                val boardA = fx.initiator()
                val boardB = fx.initiator()
                val a = newAddress()
                val b = newAddress()
                val results =
                    race(
                        { svc.propose(actor = boardA, targetIdRaw = target.toString(), newEmail = a, newEmailRepeat = a) },
                        { svc.propose(actor = boardB, targetIdRaw = target.toString(), newEmail = b, newEmailRepeat = b) },
                    )
                results.forEach { it.exceptionOrNull() shouldBe null }
                fx.openCount(target) shouldBe 1L
                fx.rowsOf(target).map { it[network.lapis.cloud.server.db.generated.MemberEmailChangeTable.status] }.sorted() shouldBe
                    listOf("PENDING", "SUPERSEDED")
            }
        }

        test("accept and reject at the same time: exactly one wins, the other is told 'no such pending change'") {
            repeat(ROUNDS) {
                val svc = fx.service()
                val target = fx.member()
                val owner = fx.actor(id = target, role = AccountRole.MEMBER)
                val fresh = newAddress()
                val pending = svc.propose(actor = fx.initiator(), targetIdRaw = target.toString(), newEmail = fresh, newEmailRepeat = fresh)
                val results =
                    race(
                        {
                            svc.acceptOwn(
                                actor = owner,
                                changeIdRaw = pending.changeId,
                                currentPassword = EC_PASSWORD,
                                ownRawSessionToken = null,
                            )
                        },
                        { svc.declineOwn(actor = owner, changeIdRaw = pending.changeId) },
                    )
                val wins = results.count { it.isSuccess }
                wins shouldBe 1
                results
                    .filter {
                        it.isFailure
                    }.forEach { it.exceptionOrNull()!!::class shouldBe EmailChangePendingNotFoundException::class }
                val status = fx.statusOf(pending.changeId)
                (status == "APPLIED" || status == "REVOKED") shouldBe true
                (fx.emailOf(target) == fresh) shouldBe (status == "APPLIED")
                fx.openCount(target) shouldBe 0L
            }
        }

        test("the same link used twice at the same time: applied once, the second click is INVALID") {
            repeat(ROUNDS) {
                val mailer = FakeEmailChangeMailer()
                val svc = fx.service(mailer = mailer)
                val target = fx.member()
                val fresh = newAddress()
                val pending = svc.propose(actor = fx.initiator(), targetIdRaw = target.toString(), newEmail = fresh, newEmailRepeat = fresh)
                val token = mailer.confirmMails.single().rawToken
                val results =
                    race(
                        { svc.confirmByLink(rawToken = token, password = EC_PASSWORD) },
                        { svc.confirmByLink(rawToken = token, password = EC_PASSWORD) },
                    )
                results.forEach { it.exceptionOrNull() shouldBe null }
                results.map { it.getOrThrow() }.sortedBy { it.name } shouldBe listOf(LinkResult.INVALID, LinkResult.OK)
                fx.statusOf(pending.changeId) shouldBe "APPLIED"
                fx.emailOf(target) shouldBe fresh
                fx.auditJsonFor(target).count { it.contains("\"event\":\"APPLIED\"") } shouldBe 1
            }
        }

        test("two members claim the same address at the same time: exactly one gets it, the other ends CONFLICT, its address stays") {
            repeat(ROUNDS) {
                val mailer = FakeEmailChangeMailer()
                val svc = fx.service(mailer = mailer)
                val a = fx.member()
                val b = fx.member()
                val oldA = fx.emailOf(a)
                val oldB = fx.emailOf(b)
                val contested = newAddress()
                val pa = svc.propose(actor = fx.initiator(), targetIdRaw = a.toString(), newEmail = contested, newEmailRepeat = contested)
                val pb = svc.propose(actor = fx.initiator(), targetIdRaw = b.toString(), newEmail = contested, newEmailRepeat = contested)
                val tokenA = mailer.confirmMails.first { it.email == contested }.rawToken
                val tokenB = mailer.confirmMails.last { it.email == contested }.rawToken
                val results =
                    race(
                        { svc.confirmByLink(rawToken = tokenA, password = EC_PASSWORD) },
                        { svc.confirmByLink(rawToken = tokenB, password = EC_PASSWORD) },
                    )
                // a failure here would be the transaction-abort bug (25P02) or an unmapped unique violation
                results.forEach { it.exceptionOrNull() shouldBe null }
                results.map { it.getOrThrow() }.sortedBy { it.name } shouldBe listOf(LinkResult.ADDRESS_UNAVAILABLE, LinkResult.OK)
                val owners = listOf(a, b).filter { fx.emailOf(it) == contested }
                owners.size shouldBe 1
                val loser = if (owners.single() == a) b else a
                fx.emailOf(loser) shouldBe (if (loser == a) oldA else oldB)
                fx.statusOf(if (loser == a) pa.changeId else pb.changeId) shouldBe "CONFLICT"
                fx.statusOf(if (owners.single() == a) pa.changeId else pb.changeId) shouldBe "APPLIED"
                fx.openCount(a) shouldBe 0L
                fx.openCount(b) shouldBe 0L
            }
        }

        test("the poller applying a due change and an ADMIN withdrawing it at the same time never do both") {
            repeat(ROUNDS) {
                val mailer = FakeEmailChangeMailer()
                val svc = fx.service(mailer = mailer)
                val target = fx.member(role = null)
                val admin = fx.initiator(AccountRole.ADMIN)
                val fresh = newAddress()
                val old = fx.emailOf(target)
                val pending = svc.propose(actor = admin, targetIdRaw = target.toString(), newEmail = fresh, newEmailRepeat = fresh)
                svc.confirmByLink(rawToken = mailer.confirmMails.single().rawToken, password = null) shouldBe LinkResult.CONFIRMED_PENDING
                fx.age(changeId = pending.changeId, by = 80.hours)
                val results =
                    race(
                        { svc.runDue(fx.now()) },
                        { svc.withdraw(actor = admin, changeIdRaw = pending.changeId) },
                    )
                val status = fx.statusOf(pending.changeId)
                when (status) {
                    "APPLIED" -> {
                        fx.emailOf(target) shouldBe fresh
                        results[1].exceptionOrNull()!!::class shouldBe EmailChangePendingNotFoundException::class
                    }
                    "WITHDRAWN" -> {
                        fx.emailOf(target) shouldBe old
                        results[1].exceptionOrNull() shouldBe null
                    }
                    else -> error("unexpected final status $status")
                }
                results[0].exceptionOrNull() shouldBe null
                fx.openCount(target) shouldBe 0L
            }
        }
    }) {
    private companion object {
        const val ROUNDS = 5
    }
}

class EmailChangeConcurrencyTest : EmailChangeConcurrencyScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EmailChangeConcurrencyPostgresTest : EmailChangeConcurrencyScenarios(TestDatabase.Postgres())
