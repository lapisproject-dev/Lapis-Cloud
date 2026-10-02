package network.lapis.cloud.server.rpc

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PaymentCheckoutSessionTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AuditMarkers
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentCheckoutSessionStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.35 -- `listOpenEventRefunds` / `markEventRefunded` and the `ownPaid` / `ownRefundMarkedAt`
 * fields on `EventDto`, over the real RPC surface (throwaway routes + `X-Member-Id`).
 */
abstract class EventRefundRpcScenarios(
    db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    val regIds =
                        EventRegistrationTable
                            .selectAll()
                            .where { EventRegistrationTable.eventId inList createdEventIds }
                            .map { it[EventRegistrationTable.id] }
                    if (regIds.isNotEmpty()) {
                        val sessionIds =
                            PaymentCheckoutSessionTable
                                .selectAll()
                                .where { PaymentCheckoutSessionTable.eventRegistrationId inList regIds }
                                .map { it[PaymentCheckoutSessionTable.id] }
                        if (sessionIds.isNotEmpty()) {
                            PaymentTransactionTable.deleteWhere { checkoutSessionId inList sessionIds }
                            PaymentCheckoutSessionTable.deleteWhere { id inList sessionIds }
                        }
                    }
                    EventRegistrationTable.deleteWhere { eventId inList createdEventIds }
                    EventTable.deleteWhere { id inList createdEventIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.deleteWhere { actorMemberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
            db.deactivate()
        }

        fun createMember(
            role: AccountRole,
            name: String = "Erstattung Testmitglied",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = name
                    it[email] = "refund-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createEvent(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "refund-test-$id"
                    it[title] = "Erstattungs-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = LocalDateTime(2030, 1, 1, 18, 0)
                    it[endsAt] = LocalDateTime(2030, 1, 1, 22, 0)
                    it[capacity] = null
                    it[feeAmount] = BigDecimal("25.00")
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = DbClock.nowLocalDateTime()
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        fun insertRegistration(
            eventId: Uuid,
            memberId: Uuid?,
            status: EventRegistrationStatus,
            guestName: String? = null,
            registeredAt: LocalDateTime = DbClock.nowLocalDateTime(),
        ): Uuid {
            val id = Uuid.random()
            val inactive = status == EventRegistrationStatus.CANCELLED || status == EventRegistrationStatus.EXPIRED
            transaction {
                EventRegistrationTable.insert {
                    it[EventRegistrationTable.id] = id
                    it[EventRegistrationTable.eventId] = eventId
                    it[EventRegistrationTable.memberId] = memberId
                    it[EventRegistrationTable.guestName] = guestName
                    it[guestEmail] = guestName?.let { "guest-${Uuid.random()}@example.org" }
                    it[activeParticipantKey] = if (inactive) null else (memberId?.let { m -> "m:$m" } ?: "g:$id")
                    it[EventRegistrationTable.status] = status
                    it[feeAmount] = BigDecimal("25.00")
                    it[holdExpiresAt] =
                        if (status == EventRegistrationStatus.PENDING_PAYMENT) LocalDateTime(2035, 1, 1, 0, 0) else null
                    it[waitlistPosition] = null
                    it[cancelTokenSha256] = null
                    it[EventRegistrationTable.registeredAt] = registeredAt
                    it[confirmedAt] = null
                    it[cancelledAt] = if (inactive) registeredAt else null
                    it[waitlistOfferedAt] = null
                }
            }
            return id
        }

        /** One checkout session (+ a payment_transaction when COMPLETED, as the webhook does). Returns the transaction id (or null). */
        fun insertSession(
            registrationId: Uuid,
            status: PaymentCheckoutSessionStatus,
            amount: String = "25.00",
            intent: PaymentIntent = PaymentIntent.EVENT_FEE,
        ): Uuid? {
            val sessionId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                PaymentCheckoutSessionTable.insert {
                    it[id] = sessionId
                    it[provider] = PaymentProvider.STRIPE
                    it[providerSessionId] = "cs_refund_$sessionId"
                    it[PaymentCheckoutSessionTable.status] = status
                    it[PaymentCheckoutSessionTable.intent] = intent
                    it[eventRegistrationId] = registrationId
                    it[PaymentCheckoutSessionTable.amount] = BigDecimal(amount)
                    it[currency] = "EUR"
                    it[createdAt] = now
                    it[expiresAt] = now
                    it[completedAt] = if (status == PaymentCheckoutSessionStatus.COMPLETED) now else null
                    it[providerIdempotencyKey] = "idem-$sessionId"
                }
            }
            if (status != PaymentCheckoutSessionStatus.COMPLETED) return null
            val txId = Uuid.random()
            transaction {
                PaymentTransactionTable.insert {
                    it[id] = txId
                    it[provider] = PaymentProvider.STRIPE
                    it[providerEventId] = "evt_refund_$txId"
                    it[providerPaymentId] = "pi_refund_$txId"
                    it[PaymentTransactionTable.status] = PaymentTransactionStatus.CAPTURED
                    it[PaymentTransactionTable.amount] = BigDecimal(amount)
                    it[currency] = "EUR"
                    it[PaymentTransactionTable.intent] = intent
                    it[receivedAt] = now
                    it[rawPayloadDigest] = "0".repeat(64)
                    it[checkoutSessionId] = sessionId
                }
            }
            return txId
        }

        fun paid(registrationId: Uuid) = insertSession(registrationId, PaymentCheckoutSessionStatus.COMPLETED)

        fun Route.refundRoutes(limiter: FederationInboxRateLimiter) {
            fun service(call: ApplicationCall) =
                EventService(
                    call = call,
                    checkoutGateways = emptyMap(),
                    baseUrl = "https://example.org",
                    mailDispatcher =
                        MailDispatcher(
                            transport = NoOpMailTransport(),
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                        ),
                    writeRateLimiter = limiter,
                    checkInRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                    seriesPreviewRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                )
            post("/test/refunds") {
                val list = service(call).listOpenEventRefunds()
                call.respondText(
                    list.joinToString("\n") {
                        "${it.registrationId}|${it.participantDisplayName}|${it.paidAmount}|${it.paymentCount}|${it.status}"
                    },
                )
            }
            post("/test/refunds/{id}/mark") {
                val dto = service(call).markEventRefunded(call.parameters["id"]!!)
                call.respondText("${dto.registrationId}|${dto.refundMarkedAt != null}")
            }
            post("/test/event/{id}") {
                val dto = service(call).getEvent(call.parameters["id"]!!)
                call.respondText("${dto.ownRegistrationStatus}|${dto.ownPaid}|${dto.ownRefundMarkedAt != null}|${dto.ownPaidAmount}")
            }
        }

        fun io.ktor.server.application.Application.setup(
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
        ) {
            install(StatusPages) {
                exception<ForbiddenException> { call, _ -> call.respondText("forbidden", status = HttpStatusCode.Forbidden) }
                exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            }
            routing { refundRoutes(limiter) }
        }

        fun refundAuditCount(txId: Uuid): Long =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityId eq txId) and
                            (AuditLogEntryTable.entityType eq AuditEntityType.PAYMENT_TRANSACTION) and
                            (AuditLogEntryTable.afterSnapshot eq AuditMarkers.EVENT_REFUND_MARKED)
                    }.count()
            }

        test("list and mark are BOARD/ADMIN only; everyone else gets Forbidden without any change") {
            testApplication {
                application { setup() }
                val organizer = createMember(AccountRole.BOARD)
                val event = createEvent(organizer)
                val reg = insertRegistration(event, createMember(AccountRole.MEMBER), EventRegistrationStatus.CANCELLED)
                val tx = paid(reg)!!
                listOf(AccountRole.ADMIN, AccountRole.BOARD).forEach { role ->
                    val actor = createMember(role)
                    client.post("/test/refunds") { header("X-Member-Id", actor.toString()) }.status shouldBe HttpStatusCode.OK
                }
                listOf(AccountRole.TREASURER, AccountRole.MEMBER).forEach { role ->
                    val actor = createMember(role)
                    client.post("/test/refunds") { header("X-Member-Id", actor.toString()) }.status shouldBe HttpStatusCode.Forbidden
                    client.post("/test/refunds/$reg/mark") { header("X-Member-Id", actor.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                }
                refundAuditCount(tx) shouldBe 0
                transaction {
                    EventRegistrationTable
                        .selectAll()
                        .where {
                            EventRegistrationTable.id eq reg
                        }.single()[EventRegistrationTable.refundMarkedAt]
                } shouldBe null
            }
        }

        test("only paid, inactive, unmarked registrations are listed; amount is the sum of the COMPLETED sessions") {
            testApplication {
                application { setup() }
                val board = createMember(AccountRole.BOARD)
                val event = createEvent(board)
                val self = createMember(AccountRole.MEMBER, name = "Selbst Abgemeldet")
                val selfReg = insertRegistration(event, self, EventRegistrationStatus.CANCELLED)
                paid(selfReg)
                val guestReg = insertRegistration(event, null, EventRegistrationStatus.CANCELLED, guestName = "Gast Gabi")
                paid(guestReg)
                val expiredReg = insertRegistration(event, createMember(AccountRole.MEMBER), EventRegistrationStatus.EXPIRED)
                paid(expiredReg)
                val doubleReg = insertRegistration(event, createMember(AccountRole.MEMBER), EventRegistrationStatus.CANCELLED)
                paid(doubleReg)
                paid(doubleReg)
                val unpaid = insertRegistration(event, createMember(AccountRole.MEMBER), EventRegistrationStatus.CANCELLED)
                val createdOnly = insertRegistration(event, createMember(AccountRole.MEMBER), EventRegistrationStatus.EXPIRED)
                insertSession(createdOnly, PaymentCheckoutSessionStatus.CREATED)
                insertSession(createdOnly, PaymentCheckoutSessionStatus.FAILED)
                val active = insertRegistration(event, createMember(AccountRole.MEMBER), EventRegistrationStatus.CONFIRMED)
                paid(active)
                val foreignIntent = insertRegistration(event, createMember(AccountRole.MEMBER), EventRegistrationStatus.CANCELLED)
                insertSession(foreignIntent, PaymentCheckoutSessionStatus.COMPLETED, intent = PaymentIntent.DONATION)

                val body = client.post("/test/refunds") { header("X-Member-Id", board.toString()) }.bodyAsText()
                val lines = body.lines()
                lines.any { it.startsWith("$selfReg|Selbst Abgemeldet|25.00|1|CANCELLED") } shouldBe true
                lines.any { it.startsWith("$guestReg|Gast Gabi|") } shouldBe true
                lines.any { it.startsWith("$expiredReg|") && it.endsWith("|EXPIRED") } shouldBe true
                lines.any { it.startsWith("$doubleReg|") && it.contains("|50.00|2|") } shouldBe true
                listOf(unpaid, createdOnly, active, foreignIntent).forEach { notListed ->
                    body shouldNotContain notListed.toString()
                }
            }
        }

        test("list is ordered oldest first") {
            testApplication {
                application { setup() }
                val board = createMember(AccountRole.BOARD)
                val event = createEvent(board)
                val newer =
                    insertRegistration(
                        event,
                        createMember(AccountRole.MEMBER),
                        EventRegistrationStatus.CANCELLED,
                        registeredAt = LocalDateTime(2029, 6, 2, 10, 0),
                    )
                val older =
                    insertRegistration(
                        event,
                        createMember(AccountRole.MEMBER),
                        EventRegistrationStatus.CANCELLED,
                        registeredAt = LocalDateTime(2029, 6, 1, 10, 0),
                    )
                paid(newer)
                paid(older)
                val ids =
                    client
                        .post(
                            "/test/refunds",
                        ) { header("X-Member-Id", board.toString()) }
                        .bodyAsText()
                        .lines()
                        .map { it.substringBefore('|') }
                ids shouldContain older.toString()
                (ids.indexOf(older.toString()) < ids.indexOf(newer.toString())) shouldBe true
            }
        }

        test("mark succeeds once: columns set, exactly one value-free audit entry, second call is a Conflict") {
            testApplication {
                application { setup() }
                val board = createMember(AccountRole.BOARD)
                val event = createEvent(board)
                val reg =
                    insertRegistration(event, createMember(AccountRole.MEMBER, name = "Geheimer Name"), EventRegistrationStatus.CANCELLED)
                val tx = paid(reg)!!
                val first = client.post("/test/refunds/$reg/mark") { header("X-Member-Id", board.toString()) }
                first.status shouldBe HttpStatusCode.OK
                first.bodyAsText() shouldBe "$reg|true"
                val row = transaction { EventRegistrationTable.selectAll().where { EventRegistrationTable.id eq reg }.single() }
                (row[EventRegistrationTable.refundMarkedAt] != null) shouldBe true
                row[EventRegistrationTable.refundMarkedBy] shouldBe board
                refundAuditCount(tx) shouldBe 1
                val audit =
                    transaction {
                        AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq tx }.single()
                    }
                audit[AuditLogEntryTable.action] shouldBe AuditAction.UPDATE
                audit[AuditLogEntryTable.beforeSnapshot] shouldBe null
                audit[AuditLogEntryTable.afterSnapshot] shouldBe AuditMarkers.EVENT_REFUND_MARKED

                client.post("/test/refunds/$reg/mark") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.Conflict
                refundAuditCount(tx) shouldBe 1
                client.post("/test/refunds") { header("X-Member-Id", board.toString()) }.bodyAsText() shouldNotContain reg.toString()
            }
        }

        test("mark on an active, an unpaid, an unknown and a malformed id are all the same Conflict") {
            testApplication {
                application { setup() }
                val board = createMember(AccountRole.BOARD)
                val event = createEvent(board)
                val active = insertRegistration(event, createMember(AccountRole.MEMBER), EventRegistrationStatus.CONFIRMED)
                paid(active)
                val unpaid = insertRegistration(event, createMember(AccountRole.MEMBER), EventRegistrationStatus.CANCELLED)
                listOf(active.toString(), unpaid.toString(), Uuid.random().toString(), "not-a-uuid").forEach { id ->
                    val r = client.post("/test/refunds/$id/mark") { header("X-Member-Id", board.toString()) }
                    r.status shouldBe HttpStatusCode.Conflict
                    r.bodyAsText() shouldBe "Diese Erstattung ist nicht offen."
                }
                transaction {
                    EventRegistrationTable
                        .selectAll()
                        .where {
                            EventRegistrationTable.id eq active
                        }.single()[EventRegistrationTable.refundMarkedAt]
                } shouldBe null
            }
        }

        test("two concurrent marks: exactly one success, one conflict, one audit entry") {
            testApplication {
                application { setup() }
                val board = createMember(AccountRole.BOARD)
                val event = createEvent(board)
                val reg = insertRegistration(event, createMember(AccountRole.MEMBER), EventRegistrationStatus.CANCELLED)
                val tx = paid(reg)!!
                val statuses =
                    coroutineScope {
                        (1..2)
                            .map {
                                async {
                                    client
                                        .post(
                                            "/test/refunds/$reg/mark",
                                        ) { header("X-Member-Id", board.toString()) }
                                        .status
                                }
                            }.awaitAll()
                    }
                statuses.count { it == HttpStatusCode.OK } shouldBe 1
                statuses.count { it == HttpStatusCode.Conflict } shouldBe 1
                refundAuditCount(tx) shouldBe 1
            }
        }

        test("the write rate limit applies to marking") {
            testApplication {
                application { setup(FederationInboxRateLimiter(maxRequests = 2, window = 60.minutes)) }
                val board = createMember(AccountRole.BOARD)
                val statuses =
                    (1..3).map {
                        client
                            .post(
                                "/test/refunds/${Uuid.random()}/mark",
                            ) { header("X-Member-Id", board.toString()) }
                            .bodyAsText()
                    }
                statuses[2] shouldBe "Zu viele Anfragen -- bitte spaeter erneut versuchen."
            }
        }

        test("EventDto.ownPaid / ownRefundMarkedAt reflect the caller's newest own registration only") {
            testApplication {
                application { setup() }
                val board = createMember(AccountRole.BOARD)
                val event = createEvent(board)
                val me = createMember(AccountRole.MEMBER)
                val other = createMember(AccountRole.MEMBER)
                // someone else's paid, cancelled registration must not leak into my view
                paid(insertRegistration(event, other, EventRegistrationStatus.CANCELLED))
                client.post("/test/event/$event") { header("X-Member-Id", me.toString()) }.bodyAsText() shouldBe "null|false|false|null"

                val reg = insertRegistration(event, me, EventRegistrationStatus.CANCELLED, registeredAt = LocalDateTime(2029, 1, 1, 10, 0))
                paid(reg)
                client.post("/test/event/$event") { header("X-Member-Id", me.toString()) }.bodyAsText() shouldBe "null|true|false|25.00"

                client.post("/test/refunds/$reg/mark") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
                client.post("/test/event/$event") { header("X-Member-Id", me.toString()) }.bodyAsText() shouldBe "null|true|true|25.00"

                // a newer, unpaid registration is the one that counts
                insertRegistration(event, me, EventRegistrationStatus.CONFIRMED, registeredAt = LocalDateTime(2029, 2, 1, 10, 0))
                client.post("/test/event/$event") { header("X-Member-Id", me.toString()) }.bodyAsText() shouldBe
                    "CONFIRMED|false|false|null"
            }
        }

        test("DB checks: marker only with both columns set and only on an inactive registration") {
            val board = createMember(AccountRole.BOARD)
            val event = createEvent(board)
            val confirmed = insertRegistration(event, createMember(AccountRole.MEMBER), EventRegistrationStatus.CONFIRMED)
            val cancelled = insertRegistration(event, createMember(AccountRole.MEMBER), EventRegistrationStatus.CANCELLED)
            val now = DbClock.nowLocalDateTime()
            val onlyAt =
                runCatching {
                    transaction { EventRegistrationTable.update({ EventRegistrationTable.id eq cancelled }) { it[refundMarkedAt] = now } }
                }
            onlyAt.isFailure shouldBe true
            val onlyBy =
                runCatching {
                    transaction { EventRegistrationTable.update({ EventRegistrationTable.id eq cancelled }) { it[refundMarkedBy] = board } }
                }
            onlyBy.isFailure shouldBe true
            val onActive =
                runCatching {
                    transaction {
                        EventRegistrationTable.update({ EventRegistrationTable.id eq confirmed }) {
                            it[refundMarkedAt] = now
                            it[refundMarkedBy] = board
                        }
                    }
                }
            onActive.isFailure shouldBe true
            val ok =
                runCatching {
                    transaction {
                        EventRegistrationTable.update({ EventRegistrationTable.id eq cancelled }) {
                            it[refundMarkedAt] = now
                            it[refundMarkedBy] = board
                        }
                    }
                }
            ok.isSuccess shouldBe true
        }
    })

/** The unchanged H2 run (normal `test` task). */
class EventRefundRpcTest : EventRefundRpcScenarios(TestDatabase.H2)

/** The same scenarios on a fresh PostgreSQL database (`postgresTest` task). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EventRefundRpcPostgresTest : EventRefundRpcScenarios(TestDatabase.Postgres())
