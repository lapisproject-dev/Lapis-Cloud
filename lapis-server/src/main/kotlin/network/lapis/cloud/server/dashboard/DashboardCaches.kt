package network.lapis.cloud.server.dashboard

import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.NavCountsDto
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The key of both caches. Role and status are part of it: a member whose role or status just changed gets a new key at once, never an
 * answer
 * computed for the old role (a demoted admin must not see the application count for another 20 seconds).
 */
internal data class DashboardCacheKey(
    val memberId: Uuid,
    val role: AccountRole,
    val status: MemberStatus,
)

/**
 * V1.9.224 -- one pair of caches per process (the service is constructed per request, like the member-finder rate limiter). Signals live 20
 * seconds, the sidebar counters 30 seconds.
 */
internal class DashboardCaches(
    now: () -> Instant = ServerClock::nowInstant,
) {
    val signals: TtlCache<DashboardCacheKey, List<DashboardSignalDto>> = TtlCache(ttl = SIGNALS_TTL, now = now)
    val navCounts: TtlCache<DashboardCacheKey, NavCountsDto> = TtlCache(ttl = NAV_COUNTS_TTL, now = now)

    companion object {
        val SIGNALS_TTL = 20.seconds
        val NAV_COUNTS_TTL = 30.seconds
    }
}
