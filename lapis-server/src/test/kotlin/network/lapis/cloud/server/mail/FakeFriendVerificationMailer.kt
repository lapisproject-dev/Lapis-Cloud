package network.lapis.cloud.server.mail

import network.lapis.cloud.shared.domain.DeliveryStatus

/**
 * Test-only stand-in for [FriendVerificationMailer] -- returns [DeliveryStatus.SENT] without
 * doing anything, for the many `RegistrationService(...)` test call sites across this module that
 * do not care about mail content/delivery at all (unlike `FriendRegistrationTest.kt`'s own
 * `RecordingFriendVerificationMailer`, which DOES assert on what was "sent"). Replaces the
 * production `NoOpFriendVerificationMailer` this wave removed -- that class used to double as
 * BOTH the production graceful-degradation fallback AND the default test double; those two
 * concerns are now split: [network.lapis.cloud.server.mail.NoOpMailTransport] is the production
 * fallback, this class is the test-only default.
 */
class FakeFriendVerificationMailer : FriendVerificationMailer {
    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus = DeliveryStatus.SENT
}
