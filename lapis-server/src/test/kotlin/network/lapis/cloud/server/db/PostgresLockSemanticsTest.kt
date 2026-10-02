package network.lapis.cloud.server.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.events.EventRefunds
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.payment.psp.PspCheckoutSessions
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.server.rpc.MotionDecisionLock
import network.lapis.cloud.server.rpc.PollTestData
import network.lapis.cloud.server.rpc.lockPollRow
import network.lapis.cloud.server.rpc.pollInput
import network.lapis.cloud.server.rpc.pollTestApplication
import network.lapis.cloud.server.rpc.requireActiveMembership
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * Welle V1.9.37 (Postgres test lane) -- the FOR-UPDATE and isolation assumptions the whole locking
 * design rests on, made explicit instead of assumed. H2 (1-second lock timeout, different MVCC) can
 * never prove any of this.
 *
 * Pattern per production lock helper: transaction A calls the REAL helper and keeps its transaction
 * open; transaction B sets `lock_timeout` and calls the same helper -- SQLSTATE `55P03` proves that B
 * waits for A. A second variant lets B wait (detected via `pg_stat_activity`, no sleeps), changes the
 * row in A, commits, and checks that B then sees A's COMMITTED value (READ COMMITTED + FOR UPDATE
 * re-evaluation).
 *
 * NOT covered here: the election row lock (`ElectionService.requireElectionRow` is private); it is
 * exercised through the real service in `ElectionIntegrityPostgresTest` (tally vs. abort, ballot vs.
 * close, openVoting snapshot, approvals).
 */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class PostgresLockSemanticsTest :
    FunSpec({
        val pg = TestDatabase.Postgres()
        val data = PollTestData()
        val createdEventIds = mutableListOf<Uuid>()
        val createdMeetingIds = mutableListOf<Uuid>()
        val createdMotionIds = mutableListOf<Uuid>()
        val createdSessionIds = mutableListOf<Uuid>()

        beforeSpec { pg.activate() }
        installLaneGuards(db = pg)
        afterSpec {
            transaction {
                if (createdSessionIds.isNotEmpty()) PaymentCheckoutSessionTable.deleteWhere { id inList createdSessionIds }
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMotionIds.isNotEmpty()) MotionTable.deleteWhere { id inList createdMotionIds }
                if (createdMeetingIds.isNotEmpty()) MeetingTable.deleteWhere { id inList createdMeetingIds }
            }
            data.deleteAllPolls()
            data.cleanUp()
            pg.deactivate()
        }

        /**
         * A holds the lock taken by [hold] (inside its still-open transaction); B, with `lock_timeout = 300ms`,
         * runs [contend] and must fail with `55P03 lock_not_available` -- i.e. it really waited behind A.
         */
        fun assertSecondTransactionWaits(
            hold: () -> Unit,
            contend: () -> Unit,
        ) {
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
                val failure =
                    shouldThrow<ExposedSQLException> {
                        transaction {
                            exec("SET LOCAL lock_timeout = '300ms'")
                            contend()
                        }
                    }
                failure.sqlState shouldBe "55P03"
            } finally {
                release.countDown()
                a.get(30, TimeUnit.SECONDS)
                pool.shutdownNow()
            }
        }

        fun createMotion(): Uuid {
            val chair = data.chair("lock-semantics")
            val committeeId = data.committee()
            val meetingId = Uuid.random()
            val at = LocalDateTime(2026, 3, 1, 18, 0)
            val motionId = Uuid.random()
            transaction {
                MeetingTable.insert {
                    it[id] = meetingId
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "Lock-Semantics Meeting"
                    it[scheduledAt] = at
                    it[location] = "Vereinsheim"
                    it[format] = MeetingFormat.IN_PERSON
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = at
                }
                MotionTable.insert {
                    it[id] = motionId
                    it[targetCommitteeId] = committeeId
                    it[title] = "Lock-Semantics Motion"
                    it[rationale] = "Rationale"
                    it[text] = "Motionstext"
                    it[submitterMemberId] = chair
                    it[status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = chair
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
            }
            createdMeetingIds += meetingId
            createdMotionIds += motionId
            return motionId
        }

        test("isolation: an Exposed transaction on Hikari + pgjdbc runs at READ COMMITTED (the level every locking comment assumes)") {
            var shown: String? = null
            var jdbcLevel = -1
            transaction {
                exec("SHOW transaction_isolation") { rs -> if (rs.next()) shown = rs.getString(1) }
                jdbcLevel = (TransactionManager.current().connection.transactionIsolation)
            }
            // A different result here is a FINDING about the design's assumptions, not a test to adjust.
            shown shouldBe "read committed"
            jdbcLevel shouldBe Connection.TRANSACTION_READ_COMMITTED
        }

        test("MotionDecisionLock.lockMotion: the second locker waits (55P03) ...") {
            val motionId = createMotion()
            assertSecondTransactionWaits(
                hold = { MotionDecisionLock.lockMotion(motionId) },
                contend = { MotionDecisionLock.lockMotion(motionId) },
            )
        }

        test("MotionDecisionLock.lockMotion: ... and, once the first commits, sees that transaction's COMMITTED change") {
            val motionId = createMotion()
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val a =
                    pool.submit {
                        transaction {
                            MotionDecisionLock.lockMotion(motionId)
                            holding.countDown()
                            release.await(60, TimeUnit.SECONDS)
                            MotionTable.update({ MotionTable.id eq motionId }) { it[status] = MotionStatus.RESOLVED }
                        }
                    }
                holding.await(20, TimeUnit.SECONDS) shouldBe true
                val b = pool.submit<MotionStatus> { transaction { MotionDecisionLock.lockMotion(motionId)[MotionTable.status] } }
                pg.db.awaitLockWaiter(queryFragment = "for update")
                release.countDown()
                a.get(30, TimeUnit.SECONDS)
                // B was blocked on the pre-update row; READ COMMITTED + FOR UPDATE hands it the new version.
                b.get(30, TimeUnit.SECONDS) shouldBe MotionStatus.RESOLVED
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }

        test("LedgerBackedLtrBalanceProvider.lockForDebit: the second debit waits for the member row (55P03)") {
            val member = data.member(label = "lock-for-debit")
            val provider = LedgerBackedLtrBalanceProvider()
            assertSecondTransactionWaits(
                hold = { provider.lockForDebit(member) },
                contend = { provider.lockForDebit(member) },
            )
        }

        test("requireActiveMembership(forUpdate = true) takes the same member row lock as lockForDebit") {
            val member = data.member(label = "membership-guard")
            assertSecondTransactionWaits(
                hold = { requireActiveMembership(memberId = member, forUpdate = true) },
                contend = { LedgerBackedLtrBalanceProvider().lockForDebit(member) },
            )
        }

        test("AuditLogRecorder.record: the global chain-state row lock serializes writers (55P03 for the second)") {
            val record = {
                AuditLogRecorder.record(
                    actorMemberId = null,
                    actorRole = null,
                    entityType = AuditEntityType.JOURNAL_ENTRY,
                    entityId = Uuid.random(),
                    action = AuditAction.CREATE,
                    before = null,
                    after = "{}",
                )
            }
            assertSecondTransactionWaits(hold = record, contend = record)
        }

        test("poll row lock (lockPollRow) and the organization-settings mutex of createPoll both block a second transaction") {
            val chair = data.chair("lock-semantics-poll")
            var pollId: Uuid? = null
            pollTestApplication {
                pollId = Uuid.parse(call(member = chair) { createPoll(pollInput()) }.id)
            }
            val id = requireNotNull(pollId)
            assertSecondTransactionWaits(hold = { lockPollRow(id) }, contend = { lockPollRow(id) })
            // The global open-poll-cap mutex is `SELECT ... FOR UPDATE` on the single organization_settings row.
            val mutex = { OrganizationSettingsTable.selectAll().forUpdate().single() }
            assertSecondTransactionWaits(hold = { mutex() }, contend = { mutex() })
        }

        test("PspCheckoutSessions.findByProviderSessionForUpdate: the webhook path's session row lock blocks a second transaction") {
            val member = data.member(label = "psp-session-lock")
            val sessionId = Uuid.random()
            val providerSessionId = "cs_lock_${sessionId.toString().take(12)}"
            val now = LocalDateTime(2026, 4, 1, 10, 0)
            transaction {
                PaymentCheckoutSessionTable.insert {
                    it[id] = sessionId
                    it[provider] = PaymentProvider.STRIPE
                    it[PaymentCheckoutSessionTable.providerSessionId] = providerSessionId
                    it[status] = PaymentCheckoutSessionStatus.CREATED
                    it[intent] = PaymentIntent.DONATION
                    it[contributionId] = null
                    it[memberId] = member
                    it[amount] = BigDecimal("25.00")
                    it[currency] = "EUR"
                    it[donorCategory] = null
                    it[purpose] = null
                    it[createdAt] = now
                    it[expiresAt] = now
                    it[completedAt] = null
                    it[providerIdempotencyKey] = "idem-${sessionId.toString().take(12)}"
                    it[redirectUrl] = null
                }
            }
            createdSessionIds += sessionId
            val lock = {
                PspCheckoutSessions.findByProviderSessionForUpdate(
                    provider = PaymentProvider.STRIPE,
                    providerSessionId = providerSessionId,
                )
            }
            assertSecondTransactionWaits(hold = { lock() }, contend = { lock() })
        }

        test("EventRefunds.markRefunded: the registration row lock (the double-mark guard) blocks a second marker") {
            val organizer = data.chair("refund-lock")
            val eventId = Uuid.random()
            val registrationId = Uuid.random()
            val now = LocalDateTime(2026, 4, 1, 10, 0)
            transaction {
                EventTable.insert {
                    it[id] = eventId
                    it[slug] = "lock-refund-$eventId"
                    it[title] = "Lock-Refund-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = LocalDateTime(2030, 1, 1, 18, 0)
                    it[endsAt] = LocalDateTime(2030, 1, 1, 22, 0)
                    it[capacity] = null
                    it[feeAmount] = BigDecimal("10.00")
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[createdAt] = now
                    it[createdBy] = organizer
                    it[cancelledAt] = null
                }
                EventStore.insertRegistration(
                    id = registrationId,
                    eventId = eventId,
                    memberId = null,
                    guestName = "Gast",
                    guestEmail = "refund-lock@example.org",
                    activeParticipantKey = null,
                    status = EventRegistrationStatus.CANCELLED,
                    feeAmount = BigDecimal("10.00"),
                    holdExpiresAt = null,
                    waitlistPosition = null,
                    cancelTokenSha256 = sha256Hex(registrationId.toString().toByteArray()),
                    registeredAt = now,
                    confirmedAt = null,
                )
            }
            createdEventIds += eventId
            val mark = { EventRefunds.markRefunded(registrationId = registrationId, actor = organizer, now = now) }
            assertSecondTransactionWaits(hold = { mark() }, contend = { mark() })
        }

        test("lock order: a protocol-conforming pair (motion first, then member) never deadlocks; a real inversion IS counted") {
            val motionId = createMotion()
            val member = data.member(label = "lock-order")
            val provider = LedgerBackedLtrBalanceProvider()
            val baseline = pg.deadlockCount()

            // Conforming: both transactions lock motion -> member, in that order, from different threads.
            val barrier = CountDownLatch(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                (1..2)
                    .map {
                        pool.submit {
                            transaction {
                                MotionDecisionLock.lockMotion(motionId)
                                barrier.countDown()
                                barrier.await(10, TimeUnit.SECONDS)
                                provider.lockForDebit(member)
                            }
                        }
                    }.forEach { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            pg.deadlockDelta(baseline = baseline) shouldBe 0L

            // Plumbing proof for the deadlock counter itself: two raw connections lock two rows in OPPOSITE order.
            val rowA = data.member(label = "deadlock-a")
            val rowB = data.member(label = "deadlock-b")
            val pool2 = Executors.newFixedThreadPool(2)
            val firstLocks = CountDownLatch(2)

            fun crossLock(
                first: Uuid,
                second: Uuid,
            ) = pool2.submit<Boolean> {
                pg.db.rawConnection().use { c ->
                    c.autoCommit = false
                    try {
                        c.prepareStatement("SELECT 1 FROM member WHERE id = ? FOR UPDATE").use {
                            it.setObject(1, java.util.UUID.fromString(first.toString()))
                            it.executeQuery()
                        }
                        firstLocks.countDown()
                        firstLocks.await(10, TimeUnit.SECONDS)
                        c.prepareStatement("SELECT 1 FROM member WHERE id = ? FOR UPDATE").use {
                            it.setObject(1, java.util.UUID.fromString(second.toString()))
                            it.executeQuery()
                        }
                        c.commit()
                        true
                    } catch (e: java.sql.SQLException) {
                        c.rollback()
                        e.sqlState == "40P01"
                    }
                }
            }
            val afterConforming = pg.deadlockCount()
            try {
                val results = listOf(crossLock(rowA, rowB), crossLock(rowB, rowA)).map { it.get(60, TimeUnit.SECONDS) }
                results.size shouldBe 2
            } finally {
                pool2.shutdownNow()
            }
            pg.deadlockDelta(baseline = afterConforming, maxWaitMillis = 5_000).toInt() shouldBeGreaterThan 0
            // This spec does not enable the zero-new-deadlocks guard: this is the ONE place a deadlock is provoked on purpose.
        }
    })
