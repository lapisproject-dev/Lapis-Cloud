package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.EmailChangeKind
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test-only [EmailChangeMailer] that records every mail it was asked to send -- the raw tokens included, so a test can
 * "click" the link. Welle V1.9.56. [failing] makes every send throw, to prove a misbehaving mailer never changes a result.
 */
class FakeEmailChangeMailer(
    private val failing: Boolean = false,
    /** Result of [sendWarningToOldAddress]; anything but SENT simulates a saturated queue that dropped the warning. */
    private val warningStatus: DeliveryStatus = DeliveryStatus.SENT,
) : EmailChangeMailer {
    data class ConfirmMail(
        val email: String,
        val rawToken: String,
        val kind: EmailChangeKind,
        val effectiveAt: LocalDateTime?,
    )

    data class WarningMail(
        val email: String,
        val rawRevokeToken: String,
        val kind: EmailChangeKind,
        val maskedNewEmail: String,
        val effectiveAt: LocalDateTime?,
    )

    data class InfoMail(
        val email: String,
        val maskedNewEmail: String,
    )

    val confirmMails = CopyOnWriteArrayList<ConfirmMail>()
    val warningMails = CopyOnWriteArrayList<WarningMail>()
    val selfInfoMails = CopyOnWriteArrayList<InfoMail>()
    val appliedInfoMails = CopyOnWriteArrayList<InfoMail>()

    val totalMails: Int get() = confirmMails.size + warningMails.size + selfInfoMails.size + appliedInfoMails.size

    private fun maybeFail() {
        if (failing) throw IllegalStateException("simulated mailer failure")
    }

    override fun sendConfirmToNewAddress(
        email: String,
        rawToken: String,
        kind: EmailChangeKind,
        effectiveAt: LocalDateTime?,
    ): DeliveryStatus {
        maybeFail()
        confirmMails += ConfirmMail(email = email, rawToken = rawToken, kind = kind, effectiveAt = effectiveAt)
        return DeliveryStatus.SENT
    }

    override fun sendWarningToOldAddress(
        email: String,
        rawRevokeToken: String,
        kind: EmailChangeKind,
        maskedNewEmail: String,
        effectiveAt: LocalDateTime?,
    ): DeliveryStatus {
        maybeFail()
        warningMails +=
            WarningMail(
                email = email,
                rawRevokeToken = rawRevokeToken,
                kind = kind,
                maskedNewEmail = maskedNewEmail,
                effectiveAt = effectiveAt,
            )
        return warningStatus
    }

    override fun sendSelfChangeInfo(
        email: String,
        maskedNewEmail: String,
    ): DeliveryStatus {
        maybeFail()
        selfInfoMails += InfoMail(email = email, maskedNewEmail = maskedNewEmail)
        return DeliveryStatus.SENT
    }

    override fun sendAppliedInfo(
        email: String,
        maskedNewEmail: String,
    ): DeliveryStatus {
        maybeFail()
        appliedInfoMails += InfoMail(email = email, maskedNewEmail = maskedNewEmail)
        return DeliveryStatus.SENT
    }
}
