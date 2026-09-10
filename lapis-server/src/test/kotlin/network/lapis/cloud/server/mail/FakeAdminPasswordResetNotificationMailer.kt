package network.lapis.cloud.server.mail

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Test-only stand-in for [AdminPasswordResetNotificationMailer] -- returns [DeliveryStatus.SENT]
 * without doing anything. Mirrors [FakePasswordResetMailer]/[FakeFriendVerificationMailer].
 */
class FakeAdminPasswordResetNotificationMailer : AdminPasswordResetNotificationMailer {
    override fun send(
        email: String,
        occurredAt: LocalDateTime,
    ): DeliveryStatus = DeliveryStatus.SENT
}
