package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.PrivilegedActionKind
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test-only [PeerNotificationMailer] that records every mail -- the raw objection token included, so a test can "click" the
 * link. Welle V1.9.57. [targetStatus] is the result of [sendRequestForTarget] (anything but SENT simulates a saturated queue).
 */
class FakePeerNotificationMailer(
    private val targetStatus: DeliveryStatus = DeliveryStatus.SENT,
    private val failing: Boolean = false,
) : PeerNotificationMailer {
    data class RequestMail(
        val email: String,
        val rawVetoToken: String,
        val actorName: String,
        val notBefore: LocalDateTime?,
    )

    data class ApprovalMail(
        val email: String,
        val actorName: String,
        val targetName: String,
        val action: PrivilegedActionKind,
    )

    data class ExecutedMail(
        val email: String,
        val event: PeerExecutedEvent,
        val actorName: String,
    )

    data class NoticeMail(
        val email: String,
        val actorName: String,
        val subjectName: String? = null,
    )

    val requestMails = CopyOnWriteArrayList<RequestMail>()
    val approvalMails = CopyOnWriteArrayList<ApprovalMail>()
    val executedMails = CopyOnWriteArrayList<ExecutedMail>()
    val resetMailNotices = CopyOnWriteArrayList<NoticeMail>()
    val protectedDataNotices = CopyOnWriteArrayList<NoticeMail>()
    val newAdminNotices = CopyOnWriteArrayList<NoticeMail>()

    val totalMails: Int
        get() =
            requestMails.size + approvalMails.size + executedMails.size + resetMailNotices.size + protectedDataNotices.size +
                newAdminNotices.size

    private fun maybeFail() {
        if (failing) throw IllegalStateException("simulated mailer failure")
    }

    override fun sendRequestForTarget(
        email: String,
        rawVetoToken: String,
        actorName: String,
        notBefore: LocalDateTime?,
    ): DeliveryStatus {
        maybeFail()
        requestMails += RequestMail(email = email, rawVetoToken = rawVetoToken, actorName = actorName, notBefore = notBefore)
        return targetStatus
    }

    override fun sendApprovalNeeded(
        email: String,
        actorName: String,
        targetName: String,
        action: PrivilegedActionKind,
        expiresAt: LocalDateTime,
    ): DeliveryStatus {
        maybeFail()
        approvalMails += ApprovalMail(email = email, actorName = actorName, targetName = targetName, action = action)
        return DeliveryStatus.SENT
    }

    override fun sendExecutedForTarget(
        email: String,
        event: PeerExecutedEvent,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus {
        maybeFail()
        executedMails += ExecutedMail(email = email, event = event, actorName = actorName)
        return DeliveryStatus.SENT
    }

    override fun sendResetMailTriggered(
        email: String,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus {
        maybeFail()
        resetMailNotices += NoticeMail(email = email, actorName = actorName)
        return DeliveryStatus.SENT
    }

    override fun sendProtectedDataChanged(
        email: String,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus {
        maybeFail()
        protectedDataNotices += NoticeMail(email = email, actorName = actorName)
        return DeliveryStatus.SENT
    }

    override fun sendNewAdministrator(
        email: String,
        newAdminName: String,
        actorName: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus {
        maybeFail()
        newAdminNotices += NoticeMail(email = email, actorName = actorName, subjectName = newAdminName)
        return DeliveryStatus.SENT
    }
}
