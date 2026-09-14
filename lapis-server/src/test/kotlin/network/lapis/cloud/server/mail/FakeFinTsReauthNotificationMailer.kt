package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Test-only stand-in for [FinTsReauthNotificationMailer] -- records every call (so tests can assert
 * "exactly one mail on transition, none on a repeated tick") and returns [DeliveryStatus.SENT].
 * Mirrors [FakeAdminPasswordResetNotificationMailer].
 */
class FakeFinTsReauthNotificationMailer : FinTsReauthNotificationMailer {
    data class Call(
        val recipients: List<String>,
        val accountLabel: String,
        val ibanMasked: String,
        val occurredAt: LocalDateTime,
    )

    val calls = mutableListOf<Call>()

    override fun send(
        recipients: List<String>,
        accountLabel: String,
        ibanMasked: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus {
        calls += Call(recipients = recipients, accountLabel = accountLabel, ibanMasked = ibanMasked, occurredAt = occurredAt)
        return DeliveryStatus.SENT
    }
}
