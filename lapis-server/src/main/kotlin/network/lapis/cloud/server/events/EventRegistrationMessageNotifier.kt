package network.lapis.cloud.server.events

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.EVENT_REGISTRATION_MESSAGE_PURPOSE
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.htmlEscape
import network.lapis.cloud.server.rpc.insertDirectMessage
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.204 -- tells the creator of an event that a registration with a message came in.
 *
 * - **Member registrant** (ACTIVE): a direct message from the registrant to the creator (the postbox needs a member as sender), plus the mail.
 * - **Guest registrant**: the mail only -- a guest has no postbox sender. The text stays in the registration overview.
 * - The mail NEVER contains the message text (data minimisation, and guest-written text must not get a path into somebody's inbox).
 * - At most [LIMIT] notices per event per hour; beyond that the message stays stored but nobody is notified.
 *
 * Never call from inside a `transaction {}`. Never throws (except coroutine cancellation); logs ids and class names, never text, name or address.
 */
class EventRegistrationMessageNotifier(
    private val mailDispatcher: MailDispatcher?,
    private val baseUrl: String,
    private val perEventLimiter: FederationInboxRateLimiter?,
) {
    internal suspend fun notify(
        registrationId: Uuid,
        participant: EventParticipant,
    ) {
        val dispatcher = mailDispatcher ?: return
        try {
            val eventId = loadEventId(registrationId) ?: return
            if (perEventLimiter != null && !perEventLimiter.checkAndRecord("event:$eventId")) {
                logger.info { "event registration message notice skipped (rate limit) for event $eventId" }
                return
            }
            val context = loadContext(registrationId = registrationId, eventId = eventId, participant = participant) ?: return
            if (context.directMessage !=
                null
            ) {
                sendDirectMessage(context = context, participant = participant, registrationId = registrationId)
            }
            val to = context.creatorEmail
            if (to != null) {
                val link = "${baseUrl.trimEnd('/')}/app#/events/$eventId"
                val subject = "Neue Anmeldung mit Nachricht: ${context.title}".take(SUBJECT_MAX)
                val plain =
                    "Zu der Veranstaltung \"${context.title}\" ist eine Anmeldung mit einer Nachricht eingegangen.\n\n" +
                        "Die Nachricht sehen Sie in der Anmeldungsübersicht: $link\n\n" +
                        "Aus Datenschutzgründen enthält diese E-Mail den Text der Nachricht nicht.\n"
                val html =
                    "<p>Zu der Veranstaltung &quot;${htmlEscape(
                        context.title,
                    )}&quot; ist eine Anmeldung mit einer Nachricht eingegangen.</p>" +
                        "<p><a href=\"${htmlEscape(link)}\">Anmeldungsübersicht öffnen</a></p>" +
                        "<p>Aus Datenschutzgründen enthält diese E-Mail den Text der Nachricht nicht.</p>"
                dispatcher.enqueue(
                    to = to,
                    subject = subject,
                    plainTextBody = plain,
                    htmlBody = html,
                    purpose = EVENT_REGISTRATION_MESSAGE_PURPOSE,
                    logRecipient = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "event registration message notice failed for registration $registrationId (${e::class.simpleName})" }
        }
    }

    private fun sendDirectMessage(
        context: Context,
        participant: EventParticipant,
        registrationId: Uuid,
    ) {
        val sender = (participant as? EventParticipant.Member)?.memberId ?: return
        try {
            transaction {
                insertDirectMessage(
                    senderId = sender,
                    recipientId = context.creatorId,
                    body =
                        context.directMessage ?: return@transaction,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "event registration direct message failed for registration $registrationId (${e::class.simpleName})" }
        }
    }

    private data class Context(
        val title: String,
        val creatorId: Uuid,
        val creatorEmail: String?,
        val directMessage: String?,
    )

    private fun loadEventId(registrationId: Uuid): Uuid? =
        transaction {
            EventRegistrationTable
                .selectAll()
                .where { EventRegistrationTable.id eq registrationId }
                .singleOrNull()
                ?.get(EventRegistrationTable.eventId)
        }

    private fun loadContext(
        registrationId: Uuid,
        eventId: Uuid,
        participant: EventParticipant,
    ): Context? =
        transaction {
            val registration = EventRegistrationTable.selectAll().where { EventRegistrationTable.id eq registrationId }.singleOrNull()
            val message = registration?.get(EventRegistrationTable.message) ?: return@transaction null
            val event = EventTable.selectAll().where { EventTable.id eq eventId }.singleOrNull() ?: return@transaction null
            val creatorId = event[EventTable.createdBy]
            val creator = MemberTable.selectAll().where { MemberTable.id eq creatorId }.singleOrNull() ?: return@transaction null
            if (creator[MemberTable.anonymizedAt] != null || creator[MemberTable.status] != MemberStatus.ACTIVE) return@transaction null
            val registrantId = (participant as? EventParticipant.Member)?.memberId
            if (registrantId == creatorId) return@transaction null
            val address = creator[MemberTable.email]
            val email = if (address.endsWith(".invalid", ignoreCase = true)) null else address
            val title = event[EventTable.title]
            val registrantActive =
                registrantId != null &&
                    MemberTable
                        .selectAll()
                        .where { MemberTable.id eq registrantId }
                        .singleOrNull()
                        ?.let { it[MemberTable.status] == MemberStatus.ACTIVE && it[MemberTable.anonymizedAt] == null } == true
            val dm =
                if (registrantActive) {
                    val whenText = formatDate(event[EventTable.startsAt])
                    "Nachricht zu meiner Anmeldung für „$title“ ($whenText):\n\n$message"
                } else {
                    null
                }
            Context(title = title, creatorId = creatorId, creatorEmail = email, directMessage = dm)
        }

    private fun formatDate(value: kotlinx.datetime.LocalDateTime): String {
        val parts = value.date.toString().split('-')
        return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else value.date.toString()
    }

    companion object {
        const val LIMIT = 30
        private const val SUBJECT_MAX = 150

        /** No-op notifier for tests and callers that never carry a message. */
        val DISABLED: EventRegistrationMessageNotifier =
            EventRegistrationMessageNotifier(mailDispatcher = null, baseUrl = "", perEventLimiter = null)
    }
}
