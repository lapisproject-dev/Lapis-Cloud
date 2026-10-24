package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.204 -- the registrant message on H2 AND PostgreSQL: the column of migration V117 (length, NULL for old rows), the audit literal's CHECK,
 * storage through the shared submission path (a duplicate never overwrites, a new registration after a withdrawal carries its own message),
 * the stable order of the registration list, and the notice to the event's creator (direct message for an active member, mail without the
 * text, nothing for a guest's postbox, a per-event limit, no effect on the registration when the mail transport fails).
 */
abstract class EventRegistrationMessageScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = EventOnlineFixtures()
        val mails = CopyOnWriteArrayList<List<String>>()

        class RecordingTransport(
            private val fail: Boolean = false,
        ) : MailTransport {
            override suspend fun send(
                to: String,
                subject: String,
                plainTextBody: String,
                htmlBody: String,
            ): MailSendOutcome {
                if (fail) throw IllegalStateException("transport down")
                mails += listOf(to, subject, plainTextBody, htmlBody)
                return MailSendOutcome.Sent
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dispatcher = MailDispatcher(transport = RecordingTransport(), scope = scope)

        fun notifier(
            max: Int = 30,
            useDispatcher: MailDispatcher = dispatcher,
        ) = EventRegistrationMessageNotifier(
            mailDispatcher = useDispatcher,
            baseUrl = "https://example.org",
            perEventLimiter = FederationInboxRateLimiter(maxRequests = max, window = 60.minutes),
        )

        fun submission(
            n: EventRegistrationMessageNotifier,
            d: MailDispatcher = dispatcher,
        ) = EventRegistrationSubmission(
            checkoutGateways = emptyMap(),
            baseUrl = "https://example.org",
            mailDispatcher = d,
            messageNotifier = n,
        )

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db)
        afterSpec {
            transaction {
                if (fx.memberIds.isNotEmpty()) {
                    DirectMessageTable.deleteWhere { senderId inList fx.memberIds }
                    DirectMessageTable.deleteWhere { recipientId inList fx.memberIds }
                }
            }
            fx.cleanUp()
            db.deactivate()
        }

        fun eventOf(
            creator: Uuid,
            capacity: Int? = null,
        ) = fx.event(createdBy = creator, kind = EventOnlineKind.NONE, capacity = capacity, title = "Sommerfest")

        fun memberParticipant(id: Uuid) =
            EventParticipant.Member(
                memberId = id,
                displayName = "Mitglied",
                email = "event-online-$id@example.org",
            )

        suspend fun awaitMails(
            count: Int,
            timeoutMs: Long = 5_000,
        ) {
            var waited = 0L
            while (mails.size < count && waited < timeoutMs) {
                delay(25)
                waited += 25
            }
        }

        fun messageOf(registrationId: Uuid): String? =
            transaction {
                EventRegistrationTable
                    .selectAll()
                    .where { EventRegistrationTable.id eq registrationId }
                    .single()[EventRegistrationTable.message]
            }

        fun dmCount(
            from: Uuid,
            to: Uuid,
        ): Int =
            transaction {
                DirectMessageTable
                    .selectAll()
                    .where { (DirectMessageTable.senderId eq from) and (DirectMessageTable.recipientId eq to) }
                    .count()
                    .toInt()
            }

        test("the column holds 500 characters, rejects 501 and is NULL for a registration without a message") {
            val creator = fx.member(role = AccountRole.BOARD)
            val event = eventOf(creator)
            val plain = fx.registration(eventId = event, memberId = fx.member())
            messageOf(plain) shouldBe null
            val longId = fx.registration(eventId = event, memberId = fx.member())
            transaction {
                EventRegistrationTable.update({ EventRegistrationTable.id eq longId }) { it[message] = "a".repeat(500) }
            }
            messageOf(longId) shouldBe "a".repeat(500)
            val tooLong = fx.registration(eventId = event, memberId = fx.member())
            shouldThrowAny {
                transaction {
                    EventRegistrationTable.update({ EventRegistrationTable.id eq tooLong }) { it[message] = "a".repeat(501) }
                }
            }
            messageOf(tooLong) shouldBe null
        }

        test("the audit CHECK accepts EVENT_REGISTRATION_EXPORT") {
            val actor = fx.member(role = AccountRole.BOARD)
            val eventId = eventOf(actor)
            transaction {
                AuditLogRecorder.record(
                    // no actor: an audit row pins its actor member (FK) and would block the clean-up of the fixtures
                    actorMemberId = null,
                    actorRole = null,
                    entityType = AuditEntityType.EVENT_REGISTRATION_EXPORT,
                    entityId = eventId,
                    action = AuditAction.CREATE,
                    after = "{\"rowCount\":1,\"format\":\"csv\"}",
                )
            }
            val stored =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.entityId eq eventId }
                        .map { it[AuditLogEntryTable.entityType] }
                }
            stored shouldBe listOf(AuditEntityType.EVENT_REGISTRATION_EXPORT)
        }

        test("a member registration stores the message; a duplicate does not overwrite it") {
            mails.clear()
            val creator = fx.member(role = AccountRole.BOARD)
            val member = fx.member()
            val event = eventOf(creator)
            val s = submission(notifier())
            val first = s.submit(eventId = event, participant = memberParticipant(member), message = "Bitte vegetarisch")
            val confirmed = first.shouldBeInstanceOf<EventRegistrationResult.Confirmed>()
            messageOf(confirmed.registrationId) shouldBe "Bitte vegetarisch"
            val second = s.submit(eventId = event, participant = memberParticipant(member), message = "Etwas ganz anderes")
            second shouldBe EventRegistrationResult.AlreadyRegistered
            messageOf(confirmed.registrationId) shouldBe "Bitte vegetarisch"
        }

        test("after a withdrawal a new registration carries its own message, the old row keeps its one") {
            val creator = fx.member(role = AccountRole.BOARD)
            val member = fx.member()
            val event = eventOf(creator)
            val s = submission(notifier())
            val first =
                s
                    .submit(
                        eventId = event,
                        participant = memberParticipant(member),
                        message = "Erste",
                    ).shouldBeInstanceOf<EventRegistrationResult.Confirmed>()
            transaction {
                EventStore.cancelRegistration(id = first.registrationId, now = LocalDateTime(2029, 1, 1, 0, 0))
            }
            val again =
                s
                    .submit(
                        eventId = event,
                        participant = memberParticipant(member),
                        message = "Zweite",
                    ).shouldBeInstanceOf<EventRegistrationResult.Confirmed>()
            again.registrationId shouldNotBe first.registrationId
            messageOf(first.registrationId) shouldBe "Erste"
            messageOf(again.registrationId) shouldBe "Zweite"
        }

        test("status changes never touch the message (cancel, expire, confirm)") {
            val creator = fx.member(role = AccountRole.BOARD)
            val event = eventOf(creator)
            val id = fx.registration(eventId = event, memberId = fx.member(), status = EventRegistrationStatus.PENDING_PAYMENT)
            transaction { EventRegistrationTable.update({ EventRegistrationTable.id eq id }) { it[message] = "Bleibt" } }
            transaction { EventStore.confirmRegistration(id = id, now = LocalDateTime(2029, 1, 1, 0, 0)) }
            messageOf(id) shouldBe "Bleibt"
            transaction { EventStore.cancelRegistration(id = id, now = LocalDateTime(2029, 1, 2, 0, 0)) }
            messageOf(id) shouldBe "Bleibt"
        }

        test("a guest registration stores the message and notifies by mail only, never by postbox, and the mail carries no text") {
            mails.clear()
            val creator = fx.member(role = AccountRole.BOARD)
            val event = eventOf(creator)
            val s = submission(notifier())
            val result =
                s.submit(
                    eventId = event,
                    participant = EventParticipant.Guest(name = "Gast Gerd", normalizedEmail = "gerd-${Uuid.random()}@example.org"),
                    message = "Geheimer Wunsch 4711",
                )
            val confirmed = result.shouldBeInstanceOf<EventRegistrationResult.Confirmed>()
            messageOf(confirmed.registrationId) shouldBe "Geheimer Wunsch 4711"
            // the guest gets his own confirmation mail too; the creator's notice is the one with the purpose subject
            awaitMails(2)
            val notice = mails.firstOrNull { it[1].startsWith("Neue Anmeldung mit Nachricht") }
            notice shouldNotBe null
            notice!![0] shouldBe "event-online-$creator@example.org"
            notice[2] shouldContain "Sommerfest"
            notice[2] shouldContain "https://example.org/app#/events/$event"
            mails.forEach {
                it[2] shouldNotContain "Geheimer Wunsch"
                it[3] shouldNotContain "Geheimer Wunsch"
            }
            transaction { DirectMessageTable.selectAll().where { DirectMessageTable.recipientId eq creator }.count() } shouldBe 0
        }

        test("an active member registrant sends a direct message to the creator with title and text, plus the mail without the text") {
            mails.clear()
            val creator = fx.member(role = AccountRole.BOARD)
            val member = fx.member()
            val event = eventOf(creator)
            submission(notifier()).submit(eventId = event, participant = memberParticipant(member), message = "Ich komme mit Rollstuhl")
            awaitMails(2)
            dmCount(member, creator) shouldBe 1
            val body =
                transaction {
                    DirectMessageTable
                        .selectAll()
                        .where { DirectMessageTable.recipientId eq creator }
                        .single()[DirectMessageTable.body]
                }
            body shouldContain "Sommerfest"
            body shouldContain "Ich komme mit Rollstuhl"
            mails.any { it[1].startsWith("Neue Anmeldung mit Nachricht") } shouldBe true
            mails.forEach { it[2] shouldNotContain "Rollstuhl" }
        }

        test("no notice without a message, and none when the creator registers himself") {
            mails.clear()
            val creator = fx.member(role = AccountRole.BOARD)
            val member = fx.member()
            val event = eventOf(creator)
            val s = submission(notifier())
            s.submit(eventId = event, participant = memberParticipant(member), message = null)
            s.submit(eventId = event, participant = memberParticipant(creator), message = "Ich bin selbst Veranstalter")
            delay(300)
            dmCount(member, creator) shouldBe 0
            dmCount(creator, creator) shouldBe 0
            mails.none { it[1].startsWith("Neue Anmeldung mit Nachricht") } shouldBe true
        }

        test("no direct message from a FRIEND registrant and none to an inactive creator") {
            mails.clear()
            val creator = fx.member(role = AccountRole.BOARD)
            val friend = fx.member(status = MemberStatus.FRIEND)
            val event = eventOf(creator)
            val inactiveCreator = fx.member(role = AccountRole.BOARD)
            transaction { MemberTable.update({ MemberTable.id eq inactiveCreator }) { it[status] = MemberStatus.WITHDRAWN } }
            val event2 = eventOf(inactiveCreator)
            val s = submission(notifier())
            s.submit(eventId = event, participant = memberParticipant(friend), message = "Hallo")
            s.submit(eventId = event2, participant = memberParticipant(fx.member()), message = "Hallo")
            delay(300)
            dmCount(friend, creator) shouldBe 0
            transaction { DirectMessageTable.selectAll().where { DirectMessageTable.recipientId eq inactiveCreator }.count() } shouldBe 0
        }

        test("only 30 notices per event and hour: the 31st registration is stored but nobody is notified") {
            mails.clear()
            val creator = fx.member(role = AccountRole.BOARD)
            val event = eventOf(creator)
            val s = submission(notifier(max = 2))
            val ids =
                (1..3).map { i ->
                    s
                        .submit(
                            eventId = event,
                            participant =
                                EventParticipant.Guest(
                                    name = "Gast $i",
                                    normalizedEmail = "limit-$i-${Uuid.random()}@example.org",
                                ),
                            message = "Nachricht $i",
                        ).shouldBeInstanceOf<EventRegistrationResult.Confirmed>()
                        .registrationId
                }
            delay(500)
            ids.map { messageOf(it) } shouldBe listOf("Nachricht 1", "Nachricht 2", "Nachricht 3")
            mails.count { it[1].startsWith("Neue Anmeldung mit Nachricht") } shouldBe 2
        }

        test("a failing mail transport does not affect the registration") {
            val creator = fx.member(role = AccountRole.BOARD)
            val member = fx.member()
            val event = eventOf(creator)
            val broken = MailDispatcher(transport = RecordingTransport(fail = true), scope = scope)
            val result =
                submission(
                    notifier(useDispatcher = broken),
                    broken,
                ).submit(eventId = event, participant = memberParticipant(member), message = "Hallo")
            val confirmed = result.shouldBeInstanceOf<EventRegistrationResult.Confirmed>()
            messageOf(confirmed.registrationId) shouldBe "Hallo"
        }

        test("the list of a registration is ordered by time, then id, and countByStatus / countWithMessage agree") {
            val creator = fx.member(role = AccountRole.BOARD)
            val event = eventOf(creator)
            val sameInstant = LocalDateTime(2029, 5, 5, 12, 0)
            val ids = (1..4).map { Uuid.random() }
            transaction {
                ids.forEachIndexed { i, id ->
                    EventStore.insertRegistration(
                        id = id,
                        eventId = event,
                        memberId = null,
                        guestName = "G$i",
                        guestEmail = "order-$i-$id@example.org",
                        activeParticipantKey = "g:order-$i-$id@example.org",
                        status = EventRegistrationStatus.CONFIRMED,
                        feeAmount = BigDecimal.ZERO,
                        holdExpiresAt = null,
                        waitlistPosition = null,
                        cancelTokenSha256 = null,
                        registeredAt = sameInstant,
                        confirmedAt = sameInstant,
                        message = if (i % 2 == 0) "m$i" else null,
                    )
                }
            }
            transaction {
                EventStore.listByEvent(eventId = event).map { it[EventRegistrationTable.id] } shouldBe ids.sortedBy { it.toString() }
                EventStore.listByEvent(eventId = event, limit = 2) shouldHaveSize 2
                EventStore.countByStatus(event).getValue(EventRegistrationStatus.CONFIRMED) shouldBe 4
                EventStore.countWithMessage(event) shouldBe 2
            }
        }
    })
