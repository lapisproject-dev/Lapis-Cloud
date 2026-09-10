package network.lapis.cloud.server.mail

import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Test-only stand-in for [PasswordResetMailer] -- returns [DeliveryStatus.SENT] without doing
 * anything, for the many `MemberService(...)` test call sites that do not care about Weg 2's mail
 * content/delivery at all. Mirrors [FakeFriendVerificationMailer] exactly.
 */
class FakePasswordResetMailer : PasswordResetMailer {
    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus = DeliveryStatus.SENT
}
