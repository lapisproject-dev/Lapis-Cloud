package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private class Captured(
    val to: String,
    val subject: String,
    val plain: String,
    val html: String,
)

private class DynCapturingTransport : MailTransport {
    val sent = mutableListOf<Captured>()

    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        sent += Captured(to = to, subject = subject, plain = plainTextBody, html = htmlBody)
        return MailSendOutcome.Sent
    }
}

/**
 * V1.9.111 -- the delivery worker for DYNAMIC lists: recipients are resolved at SEND time (once, audited with the count only), every
 * recipient is re-checked just before the mail, mandatory lists carry the notice, and nothing is tracked.
 */
class MailingDeliveryWorkerDynamicTest :
    FunSpec({
        val fx = DynamicFixture()
        val extraMembers = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction {
                MemberTable.deleteWhere { MemberTable.id inList extraMembers }
            }
            fx.cleanup()
        }

        // The scope is cancelled before the worker's coroutine ever starts: only the calls a test makes do anything.
        fun worker(transport: MailTransport = DynCapturingTransport()) =
            MailingDeliveryWorker(
                transport = transport,
                branding = MailBranding.notConfigured(),
                mode = MailingDeliveryMode.SMTP,
                trackingToken = testTrackingToken(),
                baseUrl = TEST_TRACKING_BASE_URL,
                scope = CoroutineScope(Job().apply { cancel() }),
                sendDelay = 0.milliseconds,
                maxWait = 20.milliseconds,
            )

        fun auditsOf(messageId: Uuid) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityId eq messageId) and
                            (AuditLogEntryTable.entityType eq AuditEntityType.MAILING_MESSAGE)
                    }.toList()
            }

        fun statusOf(messageId: Uuid) = fx.messageRow(messageId)[network.lapis.cloud.server.db.generated.MailingMessageTable.status]

        test("recipients are resolved when the worker runs, not before: nothing exists after queuing, the right people after processing") {
            val c = fx.committee()
            val sender = fx.member()
            val a = fx.member(email = "a-${Uuid.random()}@example.org")
            val b = fx.member(email = "b-${Uuid.random()}@example.org")
            val withdrawn = fx.member(status = MemberStatus.WITHDRAWN)
            val anon = fx.member(anonymised = true)
            val noMail = fx.member(email = "keine-adresse")
            val outsider = fx.member()
            listOf(a, b, withdrawn, anon, noMail).forEach { fx.seat(committeeId = c, memberId = it) }
            val list = fx.dynamicList(committeeId = c, createdBy = sender)
            val message = fx.message(listId = list, sentBy = sender)

            fx.deliveries(message) shouldBe emptyMap()
            val transport = DynCapturingTransport()
            runBlocking { worker(transport).processMessage(message) }

            fx.deliveries(message).keys shouldBe setOf(a, b)
            fx.deliveries(message).values.toSet() shouldBe setOf(DeliveryStatus.SENT)
            transport.sent.map { it.to }.size shouldBe 2
            statusOf(message) shouldBe MailingMessageStatus.SENT
            fx.messageRow(message)[network.lapis.cloud.server.db.generated.MailingMessageTable.recipientsResolvedAt] shouldNotBe null
            fx.deliveries(message).keys.contains(outsider) shouldBe false
        }

        test("a member who left between the send click and the worker run gets nothing; one who left after resolution is skipped") {
            val c = fx.committee()
            val sender = fx.member()
            val stays = fx.member(email = "stays-${Uuid.random()}@example.org")
            val leavesBefore = fx.member(email = "before-${Uuid.random()}@example.org")
            val leavesAfter = fx.member(email = "after-${Uuid.random()}@example.org")
            val optsOutAfter = fx.member(email = "optout-${Uuid.random()}@example.org")
            listOf(stays, leavesBefore, leavesAfter, optsOutAfter).forEach { fx.seat(committeeId = c, memberId = it) }
            val list = fx.dynamicList(committeeId = c, createdBy = sender)
            val message = fx.message(listId = list, sentBy = sender)

            // leaves after "Senden" but before the worker resolves
            transaction { MemberTable.update({ MemberTable.id eq leavesBefore }) { it[status] = MemberStatus.WITHDRAWN } }
            val w = worker()
            w.resolveRecipientsIfNeeded(message) shouldBe true
            fx.deliveries(message).keys shouldBe setOf(stays, leavesAfter, optsOutAfter)

            // leaves / opts out AFTER the resolution but before their mail
            transaction { MemberTable.update({ MemberTable.id eq leavesAfter }) { it[status] = MemberStatus.WITHDRAWN } }
            fx.optOut(listId = list, memberId = optsOutAfter)
            val transport = DynCapturingTransport()
            runBlocking { worker(transport).processMessage(message) }

            val deliveries = fx.deliveries(message)
            deliveries[stays] shouldBe DeliveryStatus.SENT
            deliveries[leavesAfter] shouldBe DeliveryStatus.SKIPPED_UNSUBSCRIBED
            deliveries[optsOutAfter] shouldBe DeliveryStatus.SKIPPED_UNSUBSCRIBED
            transport.sent.size shouldBe 1
            transport.sent.single().to shouldContain "stays-"
        }

        test(
            "a mandatory list ignores opt-outs but never a withdrawn or anonymised member; the mail carries subject prefix, banner and no unsubscribe link",
        ) {
            val c = fx.committee()
            val sender = fx.member()
            val optedOut = fx.member(email = "pflicht-${Uuid.random()}@example.org")
            val anon = fx.member(anonymised = true)
            listOf(optedOut, anon).forEach { fx.seat(committeeId = c, memberId = it) }
            val list = fx.dynamicList(committeeId = c, createdBy = sender, mandatory = true)
            fx.optOut(listId = list, memberId = optedOut)
            val message = fx.message(listId = list, sentBy = sender, subject = "Einladung MV")
            val transport = DynCapturingTransport()

            runBlocking { worker(transport).processMessage(message) }

            fx.deliveries(message).keys shouldBe setOf(optedOut)
            val mail = transport.sent.single()
            mail.subject shouldBe "[Pflichtmitteilung] Einladung MV"
            mail.html shouldContain "role=\"note\""
            mail.html shouldContain "kann nicht abbestellt werden"
            mail.plain shouldContain "Pflichtmitteilung des Vereins"
            mail.plain.lines().first() shouldContain "Pflichtmitteilung"
            mail.html shouldNotContain "Abmelden:"
            mail.plain shouldNotContain "Abmelden:"
            mail.html shouldNotContain "Abbestellen:"
        }

        test("a normal dynamic list carries the unsubscribe hint, no pixel, no rewritten link and no tracking hash") {
            val c = fx.committee()
            val sender = fx.member()
            val a = fx.member(email = "plain-${Uuid.random()}@example.org")
            fx.seat(committeeId = c, memberId = a)
            val list = fx.dynamicList(committeeId = c, createdBy = sender)
            val message = fx.message(listId = list, sentBy = sender, subject = "Rundbrief")
            val transport = DynCapturingTransport()

            runBlocking { worker(transport).processMessage(message) }

            val mail = transport.sent.single()
            mail.subject shouldBe "Rundbrief"
            mail.html shouldContain "Abmelden:"
            mail.html shouldNotContain "<img"
            mail.html shouldNotContain "/m/c/"
            mail.html shouldNotContain "/m/o/"
            mail.html shouldNotContain "Pflichtmitteilung"
            transaction {
                MailingDeliveryLogTable
                    .selectAll()
                    .where { MailingDeliveryLogTable.mailingMessageId eq message }
                    .single()
            }.let {
                it[MailingDeliveryLogTable.trackingTokenHash] shouldBe null
                it[MailingDeliveryLogTable.openTracked] shouldBe false
                it[MailingDeliveryLogTable.clickTracked] shouldBe false
            }
        }

        test(
            "resolution is idempotent: a second call neither doubles the rows nor writes a second audit entry; the audit holds the count only",
        ) {
            val c = fx.committee()
            val sender = fx.member(displayName = "Eindeutiger-Absender-Name")
            val a = fx.member(email = "idem-a-${Uuid.random()}@example.org")
            val b = fx.member(email = "idem-b-${Uuid.random()}@example.org")
            listOf(a, b).forEach { fx.seat(committeeId = c, memberId = it) }
            val list = fx.dynamicList(committeeId = c, createdBy = sender)
            val message = fx.message(listId = list, sentBy = sender)
            val w = worker()

            w.resolveRecipientsIfNeeded(message) shouldBe true
            w.resolveRecipientsIfNeeded(message) shouldBe true

            fx.deliveries(message).size shouldBe 2
            val audits = auditsOf(message)
            audits.size shouldBe 1
            val after = audits.single()[AuditLogEntryTable.afterSnapshot]!!
            after shouldContain "\"recipientCount\":2"
            after shouldContain list.toString()
            listOf(a, b, sender).forEach { after shouldNotContain it.toString() }
            after shouldNotContain "@example.org"
            after shouldNotContain "Eindeutiger-Absender-Name"
            audits.single()[AuditLogEntryTable.actorMemberId] shouldBe sender
        }

        test("zero recipients: the message ends FAILED and the audit entry says 0") {
            val c = fx.committee()
            val sender = fx.member()
            val list = fx.dynamicList(committeeId = c, createdBy = sender)
            val message = fx.message(listId = list, sentBy = sender)

            runBlocking { worker().processMessage(message) }

            statusOf(message) shouldBe MailingMessageStatus.FAILED
            fx.deliveries(message) shouldBe emptyMap()
            auditsOf(message).single()[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"recipientCount\":0"
        }

        test("more than MAX_RECIPIENTS: the message ends FAILED, nothing is queued, no audit entry") {
            val c = fx.committee()
            val sender = fx.member()
            val count = MailingHtmlPolicy.MAX_RECIPIENTS + 1
            val ids = List(count) { Uuid.random() }
            transaction {
                MemberTable.batchInsert(ids) { id ->
                    this[MemberTable.id] = id
                    this[MemberTable.displayName] = "Masse"
                    this[MemberTable.email] = "masse-$id@example.org"
                    this[MemberTable.status] = MemberStatus.ACTIVE
                    this[MemberTable.joinedAt] = LocalDate(2020, 1, 1)
                }
                CommitteeMembershipTable.batchInsert(ids) { id ->
                    this[CommitteeMembershipTable.id] = Uuid.random()
                    this[CommitteeMembershipTable.committeeId] = c
                    this[CommitteeMembershipTable.memberId] = id
                    this[CommitteeMembershipTable.role] = CommitteeRole.MEMBER
                    this[CommitteeMembershipTable.since] = LocalDate(2020, 1, 1)
                }
            }
            extraMembers += ids
            val list = fx.dynamicList(committeeId = c, createdBy = sender)
            val message = fx.message(listId = list, sentBy = sender)

            runBlocking { worker().processMessage(message) }

            statusOf(message) shouldBe MailingMessageStatus.FAILED
            fx.deliveries(message) shouldBe emptyMap()
            auditsOf(message) shouldBe emptyList()
            // the seats of the mass members must go before the members (afterSpec order)
            transaction { CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId eq c } }
        }

        test("recoverInterrupted does not close an unresolved dynamic message; it is then sent normally") {
            val c = fx.committee()
            val sender = fx.member()
            val a = fx.member(email = "recover-${Uuid.random()}@example.org")
            fx.seat(committeeId = c, memberId = a)
            val list = fx.dynamicList(committeeId = c, createdBy = sender)
            val message = fx.message(listId = list, sentBy = sender)
            val w = worker()

            w.recoverInterrupted()
            statusOf(message) shouldBe MailingMessageStatus.QUEUED

            // contrast: an empty QUEUED message of a MANUAL list IS closed (FAILED) -- the rule only protects dynamic messages
            val manualList = fx.list(createdBy = sender)
            val manualMessage = fx.message(listId = manualList, sentBy = sender)
            w.recoverInterrupted()
            statusOf(manualMessage) shouldBe MailingMessageStatus.FAILED
            statusOf(message) shouldBe MailingMessageStatus.QUEUED

            val transport = DynCapturingTransport()
            runBlocking { worker(transport).processMessage(message) }
            statusOf(message) shouldBe MailingMessageStatus.SENT
            transport.sent.size shouldBe 1
        }
    })
