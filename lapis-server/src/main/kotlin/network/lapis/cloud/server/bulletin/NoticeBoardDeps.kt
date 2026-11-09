package network.lapis.cloud.server.bulletin

import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.messaging.DirectMessageRateLimiter

/**
 * V1.9.297 -- the long-lived collaborators of the bulletin-board service, created ONCE in `Application.kt` (the service itself is built per
 * request): the board's limiter, the process-wide direct-message limiter (the SAME instance as the message service, so a reply shares its
 * budget with every other message) and the LTR balance provider.
 */
internal class NoticeBoardDeps(
    val limiter: NoticeBoardRateLimiter = NoticeBoardRateLimiter(),
    val directMessageRateLimiter: DirectMessageRateLimiter = DirectMessageRateLimiter(),
    val ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider(),
)
