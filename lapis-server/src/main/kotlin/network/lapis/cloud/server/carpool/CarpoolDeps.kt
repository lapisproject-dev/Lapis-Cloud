package network.lapis.cloud.server.carpool

/**
 * V1.9.125 -- the long-lived collaborators of the carpool service, created ONCE in `Application.kt` (a request-scoped service instance must not
 * own state that has to outlive the request: the rate-limit windows and the invitee tokens). Tests inject their own (clock, no-op mailer).
 */
internal class CarpoolDeps(
    val rateLimiter: CarpoolRateLimiter = CarpoolRateLimiter(),
    val refStore: CarpoolInviteeRefStore = CarpoolInviteeRefStore(),
    val notifier: CarpoolNotifier = CarpoolNotifier(NoOpCarpoolNoticeMailer),
)
