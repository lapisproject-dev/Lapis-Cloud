package network.lapis.cloud.server.carpool

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.CARPOOL_NOTICE_PURPOSE
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.MailTemplates
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** What happened on a ride. The mail carries no name and no meeting note, only the kind, the date and the two places. */
enum class CarpoolNoticeKind { REQUESTED, INVITED, CONFIRMED, DECLINED, WITHDRAWN, POSTING_CHANGED, POSTING_DELETED }

/** One pending notification, collected INSIDE the transaction and sent only AFTER it committed (Exposed repeats a block on `SQLException`). */
data class CarpoolNotice(
    val recipientId: Uuid,
    val kind: CarpoolNoticeKind,
    val date: LocalDate,
    val fromPlace: String,
    val toPlace: String,
)

/** The transport of a [CarpoolNotice]; `NoOp` in tests. Fire-and-forget, never throws. */
interface CarpoolNoticeMailer {
    /** `false` = no mail can be sent (SMTP not configured): the notifier then does no work at all, not even a database read. */
    val enabled: Boolean

    fun send(
        to: String,
        notice: CarpoolNotice,
    )
}

object NoOpCarpoolNoticeMailer : CarpoolNoticeMailer {
    override val enabled: Boolean = false

    override fun send(
        to: String,
        notice: CarpoolNotice,
    ) = Unit
}

/** Thin adapter over [MailDispatcher] (same shape as `SmtpEncounterEntryNoticeMailer`): purpose `carpool-notice`, recipient never logged. */
class SmtpCarpoolNoticeMailer(
    private val dispatcher: MailDispatcher,
    private val branding: MailBranding,
    private val smtpConfigured: Boolean,
) : CarpoolNoticeMailer {
    override val enabled: Boolean get() = smtpConfigured

    override fun send(
        to: String,
        notice: CarpoolNotice,
    ) {
        if (!smtpConfigured) return
        val mail =
            MailTemplates.carpoolNotice(
                kind = notice.kind,
                date = notice.date,
                fromPlace = notice.fromPlace,
                toPlace = notice.toPlace,
                branding = branding,
            )
        dispatcher.enqueue(
            to = to,
            subject = mail.subject,
            plainTextBody = mail.plainText,
            htmlBody = mail.html,
            purpose = CARPOOL_NOTICE_PURPOSE,
            logRecipient = false,
        )
    }
}

/**
 * V1.9.125 -- the in-app news of the carpool centre is "Meine Mitfahrten" (a direct message needs an ACTIVE recipient, so it cannot reach a
 * guest or a friend); this adds the optional e-mail. **Who gets a mail**: an ACTIVE member, or a FRIEND whose address was verified. **Never**
 * a GUEST (its address is a synthetic `guest+...@federation.invalid` one that cannot receive anything), an anonymised account, or an address
 * ending in `.invalid`. Never throws and never logs a name, an address or a place: only a count.
 */
class CarpoolNotifier(
    private val mailer: CarpoolNoticeMailer,
) {
    /** Call AFTER the transaction has committed. */
    fun dispatch(notices: List<CarpoolNotice>) {
        if (notices.isEmpty() || !mailer.enabled) return
        runCatching {
            val addresses = loadAddresses(notices.map { it.recipientId }.toSet())
            var sent = 0
            notices.forEach { notice ->
                val to = addresses[notice.recipientId] ?: return@forEach
                runCatching { mailer.send(to = to, notice = notice) }
                    .onSuccess { sent++ }
            }
            logger.info { "carpool notices: ${notices.size} pending, $sent handed to the mail dispatcher" }
        }.onFailure { e -> logger.warn { "carpool notices could not be dispatched (${e::class.simpleName})" } }
    }

    private fun loadAddresses(recipientIds: Set<Uuid>): Map<Uuid, String> =
        transaction {
            recipientIds
                .mapNotNull { id ->
                    val row = MemberTable.selectAll().where { MemberTable.id eq id }.singleOrNull() ?: return@mapNotNull null
                    if (row[MemberTable.anonymizedAt] != null) return@mapNotNull null
                    val address = row[MemberTable.email]
                    if (address.endsWith(".invalid", ignoreCase = true)) return@mapNotNull null
                    val eligible =
                        when (row[MemberTable.status]) {
                            MemberStatus.ACTIVE -> true
                            MemberStatus.FRIEND -> row[MemberTable.emailVerifiedAt] != null
                            else -> false
                        }
                    if (eligible) id to address else null
                }.toMap()
        }
}
