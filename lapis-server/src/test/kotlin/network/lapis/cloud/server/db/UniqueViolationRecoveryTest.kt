package network.lapis.cloud.server.db

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.FederationRelationshipEventTable
import network.lapis.cloud.server.db.generated.FederationRelationshipTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventParticipant
import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.server.events.EventRegistrationResult
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.EventTicketIssuer
import network.lapis.cloud.server.events.EventTicketPolicy
import network.lapis.cloud.server.events.plusDuration
import network.lapis.cloud.server.federation.FederationRelationshipStore
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.routes.sha256Hex
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.FederationRelationshipDirection
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Welle V1.9.37 (Postgres test lane) -- code that CATCHES a unique violation and then keeps using the
 * same transaction. On PostgreSQL a failed statement aborts the whole transaction (SQLSTATE `25P02`
 * for everything after it, and a silent ROLLBACK at COMMIT); H2 carries on, so these paths were only
 * ever exercised on H2. Each scenario forces the genuine collision and asserts that the surrounding
 * transaction stayed healthy.
 *
 * IMPORTANT -- Exposed re-runs a whole `transaction {}` after an `SQLException`, which MASKS this bug
 * class (the second run simply succeeds). So the assertions do not only look at the result: they count
 * how often the transaction body ran / how often a side-effecting step (ticket minting) was invoked.
 * "Exactly once" is the real assertion.
 */
abstract class UniqueViolationRecoveryScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()
        val createdActorUris = mutableListOf<String>()
        val farFutureStartsAt = LocalDateTime(2030, 1, 1, 18, 0)
        val farFutureEndsAt = LocalDateTime(2030, 1, 1, 22, 0)

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterSpec {
            transaction {
                if (createdActorUris.isNotEmpty()) {
                    val ids =
                        FederationRelationshipTable
                            .selectAll()
                            .where { FederationRelationshipTable.remoteActorUri inList createdActorUris }
                            .map { it[FederationRelationshipTable.id] }
                    if (ids.isNotEmpty()) FederationRelationshipEventTable.deleteWhere { relationshipId inList ids }
                    FederationRelationshipTable.deleteWhere { remoteActorUri inList createdActorUris }
                }
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
            EventTicketIssuer.rawCodeSupplier = EventTicketPolicy::newRawCode
            db.deactivate()
        }

        fun createOrganizer(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "UniqueViolationRecovery Organisator"
                    it[email] = "uvr-organizer-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.BOARD
                }
            }
            createdMemberIds += id
            return id
        }

        fun createFreeEvent(capacity: Int?): Uuid {
            val organizer = createOrganizer()
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "uvr-$id"
                    it[title] = "UniqueViolationRecovery-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = farFutureStartsAt
                    it[endsAt] = farFutureEndsAt
                    it[EventTable.capacity] = capacity
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[createdAt] = now
                    it[createdBy] = organizer
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        fun insertGuestRegistration(
            eventId: Uuid,
            email: String,
            status: EventRegistrationStatus,
            holdExpiresAt: LocalDateTime? = null,
            waitlistPosition: Int? = null,
            ticketCodeSha256: String? = null,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            EventStore.insertRegistration(
                id = id,
                eventId = eventId,
                memberId = null,
                guestName = "Gast",
                guestEmail = email,
                activeParticipantKey = EventPolicy.activeParticipantKey(memberId = null, normalizedGuestEmail = email),
                status = status,
                feeAmount = BigDecimal.ZERO,
                holdExpiresAt = holdExpiresAt,
                waitlistPosition = waitlistPosition,
                cancelTokenSha256 = sha256Hex(Uuid.random().toString().toByteArray()),
                registeredAt = now,
                confirmedAt = if (status == EventRegistrationStatus.CONFIRMED) now else null,
                ticketCodeSha256 = ticketCodeSha256,
                ticketIssuedAt = if (ticketCodeSha256 != null) now else null,
            )
            return id
        }

        test("EventTicketIssuer: a genuine ticket-code collision is retried on a healthy transaction (minted twice, body ran once)") {
            val eventId = createFreeEvent(capacity = null)
            val collidingRaw = "COLLIDING-${Uuid.random()}"
            val collidingHash = sha256Hex(collidingRaw.toByteArray(Charsets.US_ASCII))
            transaction {
                insertGuestRegistration(
                    eventId,
                    "holder-${Uuid.random()}@example.org",
                    EventRegistrationStatus.CONFIRMED,
                    ticketCodeSha256 = collidingHash,
                )
            }
            val target =
                transaction { insertGuestRegistration(eventId, "target-${Uuid.random()}@example.org", EventRegistrationStatus.CONFIRMED) }

            val mintCalls = AtomicInteger()
            EventTicketIssuer.rawCodeSupplier = {
                if (mintCalls.incrementAndGet() == 1) collidingRaw else EventTicketPolicy.newRawCode()
            }
            val bodyRuns = AtomicInteger()
            val issued =
                try {
                    transaction {
                        bodyRuns.incrementAndGet()
                        val ticket = EventTicketIssuer.issueIfMissing(registrationId = target, now = DbClock.nowLocalDateTime())
                        // A follow-up statement on the SAME transaction -- on a poisoned (25P02) transaction this throws.
                        EventStore.getRegistrationOrNull(target) shouldNotBe null
                        ticket
                    }
                } finally {
                    EventTicketIssuer.rawCodeSupplier = EventTicketPolicy::newRawCode
                }

            issued shouldNotBe null
            issued!!.rawCode shouldNotBe collidingRaw
            mintCalls.get() shouldBe 2 // the collision + exactly one good code -- no hidden whole-transaction retry
            bodyRuns.get() shouldBe 1
            transaction {
                EventStore.getRegistrationOrNull(target)!![EventRegistrationTable.ticketCodeSha256] shouldBe issued.sha256
            }
        }

        test("FederationRelationshipStore: concurrent first Follow from the same actor -> one row, exactly one winner, losers get null") {
            val rounds = 10
            repeat(rounds) { round ->
                val uri = "https://remote-$round-${Uuid.random()}.example/actor"
                createdActorUris += uri
                val barrier = CyclicBarrier(2)
                val bodyRuns = AtomicInteger()
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val futures =
                        (1..2).map { n ->
                            pool.submit<Result<Uuid?>> {
                                runCatching {
                                    barrier.await(20, TimeUnit.SECONDS)
                                    transaction {
                                        bodyRuns.incrementAndGet()
                                        FederationRelationshipStore.upsertByRemoteActorUri(
                                            direction = FederationRelationshipDirection.INBOUND,
                                            remoteActorUri = uri,
                                            remoteInboxUri = "$uri/inbox",
                                            remotePublicKeyPem = null,
                                            initiatedActivityId = "act-$round-$n",
                                            now = DbClock.nowLocalDateTime(),
                                        )
                                    }
                                }
                            }
                        }
                    val results = futures.map { it.get(60, TimeUnit.SECONDS) }
                    transaction {
                        FederationRelationshipTable.selectAll().where { FederationRelationshipTable.remoteActorUri eq uri }.count()
                    } shouldBe 1L
                    if (db.isPostgres) {
                        results.all { it.isSuccess } shouldBe true
                        results.count { it.getOrNull() != null } shouldBe 1
                    } else {
                        // H2: a loser may legitimately time out on the lock; the end state above is what counts.
                        results.count { it.getOrNull() != null } shouldBe 1
                    }
                } finally {
                    pool.shutdownNow()
                }
            }
        }
    })

/** The H2 run (normal `test` task). */
class UniqueViolationRecoveryTest : UniqueViolationRecoveryScenarios(TestDatabase.H2)

/** The same scenarios on a fresh PostgreSQL database (`postgresTest` task). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class UniqueViolationRecoveryPostgresTest : UniqueViolationRecoveryScenarios(TestDatabase.Postgres())

/**
 * The DETERMINISTIC variants (PostgreSQL only): a second transaction holds an uncommitted conflicting
 * row, the code under test blocks behind it inside its INSERT, and only then is the blocker committed
 * -- so the code under test hits the unique violation for certain, and `awaitLockWaiter` (no `sleep`)
 * tells us when it is waiting.
 */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class UniqueViolationWaitPostgresTest :
    FunSpec({
        val pgDb = TestDatabase.Postgres()
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { pgDb.activate() }
        installLaneGuards(db = pgDb)
        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
                FederationRelationshipEventTable.deleteWhere { FederationRelationshipEventTable.id neq Uuid.NIL }
                FederationRelationshipTable.deleteWhere { FederationRelationshipTable.id neq Uuid.NIL }
            }
            EventTicketIssuer.rawCodeSupplier = EventTicketPolicy::newRawCode
            pgDb.deactivate()
        }

        fun organizer(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "UniqueViolationWait Organisator"
                    it[email] = "uvw-organizer-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.BOARD
                }
            }
            createdMemberIds += id
            return id
        }

        fun event(capacity: Int?): Uuid {
            val createdByMember = organizer()
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "uvw-$id"
                    it[title] = "UniqueViolationWait-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = LocalDateTime(2030, 1, 1, 18, 0)
                    it[endsAt] = LocalDateTime(2030, 1, 1, 22, 0)
                    it[EventTable.capacity] = capacity
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[createdAt] = now
                    it[createdBy] = createdByMember
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        fun insertGuest(
            eventId: Uuid,
            email: String,
            status: EventRegistrationStatus,
            holdExpiresAt: LocalDateTime? = null,
            waitlistPosition: Int? = null,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            EventStore.insertRegistration(
                id = id,
                eventId = eventId,
                memberId = null,
                guestName = "Gast",
                guestEmail = email,
                activeParticipantKey = EventPolicy.activeParticipantKey(memberId = null, normalizedGuestEmail = email),
                status = status,
                feeAmount = BigDecimal.ZERO,
                holdExpiresAt = holdExpiresAt,
                waitlistPosition = waitlistPosition,
                cancelTokenSha256 = sha256Hex(Uuid.random().toString().toByteArray()),
                registeredAt = now,
                confirmedAt = if (status == EventRegistrationStatus.CONFIRMED) now else null,
            )
            return id
        }

        test(
            "event registration: a duplicate hit at INSERT time keeps the waitlist promotion of the same lock acquisition (no 25P02, no retry)",
        ) {
            val eventId = event(capacity = 1)
            val now = DbClock.nowLocalDateTime()
            val staleHolder =
                transaction {
                    insertGuest(
                        eventId,
                        "stale-${Uuid.random()}@example.org",
                        EventRegistrationStatus.PENDING_PAYMENT,
                        holdExpiresAt = now.plusDuration((-1).hours),
                    )
                }
            val waiting =
                transaction {
                    insertGuest(eventId, "waiting-${Uuid.random()}@example.org", EventRegistrationStatus.WAITLISTED, waitlistPosition = 1)
                }
            val contestedEmail = "contested-${Uuid.random()}@example.org"
            val cancelledRow =
                transaction {
                    val id = Uuid.random()
                    EventStore.insertRegistration(
                        id = id,
                        eventId = eventId,
                        memberId = null,
                        guestName = "Gast",
                        guestEmail = contestedEmail,
                        activeParticipantKey = null,
                        status = EventRegistrationStatus.CANCELLED,
                        feeAmount = BigDecimal.ZERO,
                        holdExpiresAt = null,
                        waitlistPosition = null,
                        cancelTokenSha256 = sha256Hex(Uuid.random().toString().toByteArray()),
                        registeredAt = now,
                        confirmedAt = null,
                    )
                    id
                }

            // Counts promotions: the free waitlist promotion mints one ticket. A hidden whole-transaction retry would mint twice.
            val mints = AtomicInteger()
            EventTicketIssuer.rawCodeSupplier = {
                mints.incrementAndGet()
                EventTicketPolicy.newRawCode()
            }

            val blockerHoldsRow = CountDownLatch(1)
            val releaseBlocker = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val blocker =
                    pool.submit {
                        transaction {
                            // Re-activates a cancelled row for the same participant by UPDATE (no foreign-key check, so it
                            // does NOT touch the event row and does not queue behind the submission's event lock), left
                            // uncommitted: invisible to the duplicate pre-check, yet its unique-index entry blocks the
                            // submission's INSERT. (An INSERT blocker would take a KEY SHARE lock on the event row and
                            // block the submission at its `FOR UPDATE` instead -- the reason this backstop is so rarely hit.)
                            EventRegistrationTable.update({ EventRegistrationTable.id eq cancelledRow }) {
                                it[status] = EventRegistrationStatus.CONFIRMED
                                it[activeParticipantKey] =
                                    EventPolicy.activeParticipantKey(memberId = null, normalizedGuestEmail = contestedEmail)
                                it[confirmedAt] = now
                            }
                            blockerHoldsRow.countDown()
                            releaseBlocker.await(60, TimeUnit.SECONDS)
                        }
                    }
                blockerHoldsRow.await(20, TimeUnit.SECONDS)
                val submission =
                    EventRegistrationSubmission(
                        checkoutGateways = emptyMap(),
                        baseUrl = "https://example.org",
                        mailDispatcher =
                            MailDispatcher(
                                transport = NoOpMailTransport(),
                                scope =
                                    CoroutineScope(
                                        SupervisorJob() + Dispatchers.IO,
                                    ),
                            ),
                    )
                val submit =
                    pool.submit<EventRegistrationResult> {
                        runBlocking {
                            submission.submit(
                                eventId = eventId,
                                participant = EventParticipant.Guest(name = "Umkaempft", normalizedEmail = contestedEmail),
                                now = now,
                            )
                        }
                    }
                pgDb.db.awaitLockWaiter(queryFragment = "insert into event_registration")
                releaseBlocker.countDown()
                blocker.get(30, TimeUnit.SECONDS)
                submit.get(60, TimeUnit.SECONDS) shouldBe EventRegistrationResult.AlreadyRegistered
            } finally {
                pool.shutdownNow()
                EventTicketIssuer.rawCodeSupplier = EventTicketPolicy::newRawCode
            }

            // The promotion done under the same lock acquisition is COMMITTED (it would be silently rolled back on a poisoned transaction) ...
            transaction {
                EventStore.getRegistrationOrNull(waiting)!![EventRegistrationTable.status] shouldBe EventRegistrationStatus.CONFIRMED
                EventStore.getRegistrationOrNull(staleHolder)!![EventRegistrationTable.status] shouldBe EventRegistrationStatus.EXPIRED
            }
            // ... exactly once (no hidden transaction retry re-running the promotion).
            mints.get() shouldBe 1
        }

        test("federation: the loser of a first-Follow race re-reads on a healthy transaction (body ran once)") {
            val uri = "https://wait-${Uuid.random()}.example/actor"
            val blockerHoldsRow = CountDownLatch(1)
            val releaseBlocker = CountDownLatch(1)
            val bodyRuns = AtomicInteger()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val blocker =
                    pool.submit {
                        transaction {
                            FederationRelationshipStore.insert(
                                direction = FederationRelationshipDirection.INBOUND,
                                status = network.lapis.cloud.shared.domain.FederationRelationshipStatus.PENDING,
                                remoteActorUri = uri,
                                remoteInboxUri = "$uri/inbox",
                                remotePublicKeyPem = null,
                                initiatedActivityId = "act-winner",
                                now = DbClock.nowLocalDateTime(),
                            )
                            blockerHoldsRow.countDown()
                            releaseBlocker.await(60, TimeUnit.SECONDS)
                        }
                    }
                blockerHoldsRow.await(20, TimeUnit.SECONDS)
                val loser =
                    pool.submit<Uuid?> {
                        transaction {
                            bodyRuns.incrementAndGet()
                            FederationRelationshipStore.upsertByRemoteActorUri(
                                direction = FederationRelationshipDirection.INBOUND,
                                remoteActorUri = uri,
                                remoteInboxUri = "$uri/inbox",
                                remotePublicKeyPem = null,
                                initiatedActivityId = "act-loser",
                                now = DbClock.nowLocalDateTime(),
                            )
                        }
                    }
                pgDb.db.awaitLockWaiter(queryFragment = "insert into federation_relationship")
                releaseBlocker.countDown()
                blocker.get(30, TimeUnit.SECONDS)
                loser.get(60, TimeUnit.SECONDS) shouldBe null // a non-terminal row already exists -> defer to the winner
            } finally {
                pool.shutdownNow()
            }
            bodyRuns.get() shouldBe 1
            transaction {
                FederationRelationshipTable.selectAll().where { FederationRelationshipTable.remoteActorUri eq uri }.count()
            } shouldBe
                1L
        }
    })
