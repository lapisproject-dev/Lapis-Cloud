package network.lapis.cloud.server.member

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.AuditLogChainStateTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.memberbio.MemberPublicBioStore
import network.lapis.cloud.server.memberphoto.MemberPhotoStore
import network.lapis.cloud.server.rpc.MembershipTierAssignment
import network.lapis.cloud.server.rpc.PollTestData
import network.lapis.cloud.server.rpc.requireLtrEligibleMembership
import network.lapis.cloud.server.rpc.requireMembershipStatusIn
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.social.PostDraftStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.uuid.Uuid

/**
 * Welle V1.9.60 -- the member row locks of the PRODUCTION code paths against a real PostgreSQL (and, as a compatibility smoke
 * test, against H2).
 *
 * The trap (see `docs/architecture/row-locks.adoc`): a plain `FOR UPDATE` on a `member` row conflicts with the `FOR KEY SHARE`
 * that the foreign key of every child row -- above all `audit_log_entry.actor_member_id` -- takes on that row, so a transaction
 * holding `member(a) FOR UPDATE` that then waits for another lock deadlocks against one that holds that lock and inserts a child
 * row for `a`. `FOR NO KEY UPDATE` keeps every write exclusion and drops only that conflict.
 *
 * Per site that can be called directly (a lock helper or a store function):
 * - (a) the lock the site takes does not block a concurrent child insert of the same member;
 * - (b) a held `FOR KEY SHARE` (what the foreign-key check takes) does not block the site;
 * - (c) the site still excludes another writer (checked once, with the strictest contender).
 * Sites that need a request context (RPC services, routes) are covered by the source tripwire
 * (`MemberRowLockTripwireTest`) and their unchanged H2 suites; they are listed in `row-locks.adoc`.
 */
abstract class MemberRowLockScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = PollTestData()
        val pg = db as? TestDatabase.Postgres
        val now = LocalDateTime(2026, 5, 1, 12, 0)

        class Site(
            val name: String,
            /** `true` when the lock stays held until the surrounding transaction ends (so the scenario can keep it open). */
            val holdable: Boolean,
            val run: (Uuid, CurrentMember) -> Unit,
        )

        val sites =
            listOf(
                Site("LedgerBackedLtrBalanceProvider.lockForDebit", true) { m, _ -> LedgerBackedLtrBalanceProvider().lockForDebit(m) },
                Site("requireMembershipStatusIn(forUpdate)", true) { m, _ ->
                    requireMembershipStatusIn(memberId = m, allowed = setOf(MemberStatus.ACTIVE), forUpdate = true)
                },
                Site(
                    "requireLtrEligibleMembership(forUpdate)",
                    true,
                ) { m, _ -> requireLtrEligibleMembership(memberId = m, forUpdate = true) },
                Site("MemberPublicBioStore.lockMember", true) { m, _ -> MemberPublicBioStore.lockMember(m) },
                Site("MemberPhotoStore.lockMember", true) { m, _ -> MemberPhotoStore.lockMember(m) },
                Site("MembershipTierAssignment.apply (no-op path)", true) { m, actor ->
                    MembershipTierAssignment.apply(
                        targetMemberId = m,
                        newTierId = null,
                        actor = actor,
                        reason = null,
                        familyId = null,
                        now = now,
                    )
                },
                Site("PostDraftStore.restoreOwned", false) { m, _ -> PostDraftStore.restoreOwned(memberId = m, draftId = Uuid.random()) },
            )

        fun newMember(label: String): Uuid = data.member(label = label, status = MemberStatus.ACTIVE)

        fun actorOf(memberId: Uuid) = CurrentMember(memberId = memberId, role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)

        fun rawLock(
            memberId: Uuid,
            mode: String,
        ) {
            TransactionManager.current().exec("SELECT 1 FROM member WHERE id = '$memberId' $mode") { rs -> rs.next() }
        }

        fun auditInsertFor(memberId: Uuid) {
            AuditLogRecorder.record(
                actorMemberId = memberId,
                actorRole = null,
                entityType = AuditEntityType.JOURNAL_ENTRY,
                entityId = Uuid.random(),
                action = AuditAction.CREATE,
                before = null,
                after = "{}",
            )
        }

        /** Runs [body] in a transaction that keeps its locks until [release] fires, after counting [holding] down. */
        fun <T> withHolder(
            hold: () -> Unit,
            body: () -> T,
        ): T {
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            val a =
                pool.submit {
                    transaction {
                        hold()
                        holding.countDown()
                        release.await(60, TimeUnit.SECONDS)
                    }
                }
            try {
                holding.await(20, TimeUnit.SECONDS) shouldBe true
                return body()
            } finally {
                release.countDown()
                a.get(30, TimeUnit.SECONDS)
                pool.shutdownNow()
            }
        }

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            // The Postgres spec database is dropped as a whole (and audit rows reference the members, so they cannot be deleted
            // one by one); the shared in-memory H2 database must be left clean.
            if (!db.isPostgres) data.cleanUp()
            db.deactivate()
        }

        // ---- compatibility smoke test (both databases): every site's SQL is valid on the active dialect ----
        test("every directly callable site runs its lock query on the active database") {
            sites.forEach { site ->
                val m = newMember("smoke-${site.name.take(12)}")
                transaction { site.run(m, actorOf(m)) }
            }
        }

        if (pg != null) {
            sites.forEach { site ->
                test("${site.name}: a held FOR KEY SHARE (foreign-key check of a child row) does not block the site") {
                    val m = newMember("b-${site.name.take(10)}")
                    withHolder(hold = { rawLock(m, "FOR KEY SHARE") }) {
                        if (site.holdable) {
                            transaction {
                                exec("SET LOCAL lock_timeout = '300ms'")
                                site.run(m, actorOf(m))
                            }
                        } else {
                            // Opens its own transaction (pool default lock_timeout), so wait for it on another thread.
                            val pool = Executors.newSingleThreadExecutor()
                            try {
                                val f = pool.submit { site.run(m, actorOf(m)) }
                                try {
                                    f.get(4, TimeUnit.SECONDS)
                                } catch (e: TimeoutException) {
                                    throw AssertionError("${site.name} is blocked behind a foreign-key lock", e)
                                }
                            } finally {
                                pool.shutdownNow()
                            }
                        }
                    }
                }

                if (site.holdable) {
                    test("${site.name}: the lock it takes does not block a concurrent child insert of the same member") {
                        val m = newMember("a-${site.name.take(10)}")
                        withHolder(hold = { site.run(m, actorOf(m)) }) {
                            transaction {
                                exec("SET LOCAL lock_timeout = '300ms'")
                                auditInsertFor(m)
                                rollback()
                            }
                        }
                    }
                }
            }

            test("the sites still exclude another writer of the same member row (FOR NO KEY UPDATE / FOR UPDATE wait)") {
                val m = newMember("exclusion")
                sites.filter { it.holdable }.forEach { site ->
                    val failure =
                        shouldThrow<ExposedSQLException> {
                            withHolder(hold = { site.run(m, actorOf(m)) }) {
                                transaction {
                                    exec("SET LOCAL lock_timeout = '300ms'")
                                    LedgerBackedLtrBalanceProvider().lockForDebit(m)
                                }
                            }
                        }
                    failure.sqlState shouldBe "55P03"
                }
            }

            test(
                "key-column exception: MemberNumberAllocator.ensureFor keeps a plain FOR UPDATE (it may write member_number) and so waits for a foreign-key lock",
            ) {
                val m = newMember("key-change-allocator")
                val failure =
                    shouldThrow<ExposedSQLException> {
                        withHolder(hold = { rawLock(m, "FOR KEY SHARE") }) {
                            transaction {
                                exec("SET LOCAL lock_timeout = '300ms'")
                                MemberNumberAllocator.ensureFor(m)
                            }
                        }
                    }
                failure.sqlState shouldBe "55P03"
            }

            test(
                "the deadlock itself: lockForDebit(a) then an audit entry vs. an audit entry with actor a that already holds the chain row",
            ) {
                val a = newMember("deadlock-actor")
                val baseline = pg.deadlockCount()
                val chainLocked = CountDownLatch(1)
                val go = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    // T2 takes the global audit chain-state row first (what the first half of AuditLogRecorder.record does) ...
                    val t2 =
                        pool.submit {
                            transaction {
                                AuditLogChainStateTable
                                    .selectAll()
                                    .where { AuditLogChainStateTable.id eq AuditLogRecorder.AUDIT_LOG_CHAIN_STATE_ID }
                                    .forUpdate()
                                    .single()
                                chainLocked.countDown()
                                go.await(30, TimeUnit.SECONDS)
                                auditInsertFor(a)
                            }
                        }
                    chainLocked.await(20, TimeUnit.SECONDS) shouldBe true
                    // ... T1 locks the member row (the site under test) and only then wants the chain row: it waits for T2 ...
                    val t1 =
                        pool.submit {
                            transaction {
                                LedgerBackedLtrBalanceProvider().lockForDebit(a)
                                auditInsertFor(a)
                            }
                        }
                    pg.db.awaitLockWaiter(queryFragment = "audit_log_chain_state")
                    // ... and T2 now inserts its audit entry for actor a: needs FOR KEY SHARE on member(a). With FOR UPDATE held by T1
                    // this is a cycle (deadlock, 40P01); with FOR NO KEY UPDATE both go through.
                    go.countDown()
                    t2.get(60, TimeUnit.SECONDS)
                    t1.get(60, TimeUnit.SECONDS)
                } finally {
                    go.countDown()
                    pool.shutdownNow()
                }
                pg.deadlockDelta(baseline = baseline, maxWaitMillis = 3_000) shouldBe 0L
            }
        }
    })
