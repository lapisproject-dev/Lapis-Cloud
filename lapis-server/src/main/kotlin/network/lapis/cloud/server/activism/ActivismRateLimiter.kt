package network.lapis.cloud.server.activism

import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.246 -- in-process throttles of the activism module (state per JVM, resets on restart; the permanent caps are counted in the database under
 * the action lock). Wraps the project's sliding-window limiter. **Must never be called inside a `transaction {}`** (Exposed repeats a block and
 * would count twice): `ActivismTripwireTest` pins it. Limits: 20 writes (contribution and signal together) per minute, 10 reports per hour,
 * 30 candidate searches per minute, 20 writes of the planning (create / edit) per minute.
 */
internal class ActivismRateLimiter(
    private val participation: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 20, window = 1.minutes),
    private val reports: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.hours),
    private val searches: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
    private val planning: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 20, window = 1.minutes),
) {
    fun allowParticipation(memberId: Uuid): Boolean = participation.checkAndRecord("p:$memberId")

    fun allowReport(memberId: Uuid): Boolean = reports.checkAndRecord("r:$memberId")

    fun allowSearch(memberId: Uuid): Boolean = searches.checkAndRecord("s:$memberId")

    fun allowPlanning(memberId: Uuid): Boolean = planning.checkAndRecord("w:$memberId")
}
