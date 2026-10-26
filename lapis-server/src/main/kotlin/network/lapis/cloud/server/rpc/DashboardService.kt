package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.dashboard.DashboardCacheKey
import network.lapis.cloud.server.dashboard.DashboardCaches
import network.lapis.cloud.server.dashboard.DashboardSignalCollector
import network.lapis.cloud.server.dashboard.DashboardSignalSource
import network.lapis.cloud.server.dashboard.DashboardSources
import network.lapis.cloud.server.dashboard.NavCountsQuery
import network.lapis.cloud.server.dashboard.SignalContext
import network.lapis.cloud.server.dashboard.SignalModuleFlags
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.NavCountsDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IDashboardService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes

/** Uncached calls per member and minute, per method (a cache hit does not count). */
internal const val DASHBOARD_RATE_MAX = 12

/**
 * V1.9.224 -- see [IDashboardService] and `docs/features/dashboard.adoc`.
 *
 * Both methods: the session is resolved as the FIRST statement (an anonymous caller is
 * [network.lapis.cloud.shared.rpc.UnauthenticatedException]);
 * the status is read fresh from the database (a member who left a moment ago gets nothing); a cache hit costs no database access and no
 * rate-limit budget; a miss is limited to 12 per minute and member, and a member over the limit gets the last answer (up to ten
 * minutes old)
 * or a [ConflictException]. Nothing is logged for a successful call and nothing is audited (pure reads). One cache and one limiter per
 * process (the service is constructed per request).
 */
class DashboardService internal constructor(
    private val call: ApplicationCall,
    private val caches: DashboardCaches,
    private val signalsRateLimiter: FederationInboxRateLimiter,
    private val navCountsRateLimiter: FederationInboxRateLimiter,
    private val conferenceEnabled: Boolean,
    private val mcpEnabled: Boolean,
    private val sources: List<DashboardSignalSource> = DashboardSources.all,
) : IDashboardService {
    override suspend fun getDashboardSignals(): List<DashboardSignalDto> {
        val current = resolveCurrentMember(call)
        val status = freshMember(current)?.status ?: return emptyList()
        val key = DashboardCacheKey(memberId = current.memberId, role = current.role, status = status)
        caches.signals.get(key)?.let { return it }
        if (!signalsRateLimiter.checkAndRecord("dashboard:signals:${current.memberId}")) {
            return caches.signals.getStale(key = key, maxAge = STALE_MAX_AGE) ?: throw ConflictException(TOO_MANY_REQUESTS)
        }
        val wallZone = OrganizationTimeZone.current()
        val ctx =
            SignalContext(
                memberId = current.memberId,
                status = status,
                role = current.role,
                nowSystem = ServerClock.now(),
                nowWall = ServerClock.nowIn(wallZone),
                today = ServerClock.todayIn(wallZone),
                zone = wallZone,
                flags = readFlags(),
            )
        val signals = DashboardSignalCollector.collect(ctx = ctx, sources = sources)
        caches.signals.put(key = key, value = signals)
        return signals
    }

    override suspend fun getNavCounts(): NavCountsDto {
        val current = resolveCurrentMember(call)
        val fresh =
            freshMember(current) ?: return NavCountsDto(conferenceActive = 0, conferenceTotal = 0, encounterActive = 0, encounterTotal = 0)
        val status = fresh.status
        val key = DashboardCacheKey(memberId = current.memberId, role = current.role, status = status)
        caches.navCounts.get(key)?.let { return it }
        if (!navCountsRateLimiter.checkAndRecord("dashboard:nav:${current.memberId}")) {
            return caches.navCounts.getStale(key = key, maxAge = STALE_MAX_AGE) ?: throw ConflictException(TOO_MANY_REQUESTS)
        }
        // A friend whose e-mail address is unverified (while the instance requires it) is refused by the room lists; the counters do not tell
        // that person how many rooms exist either.
        val spacesAllowed =
            !(
                status == MemberStatus.FRIEND &&
                    !fresh.emailVerified &&
                    FriendRegistrationConfig.load().requireEmailVerification
            )
        val counts =
            transaction {
                NavCountsQuery.count(status = status, conferenceEnabled = conferenceEnabled, spacesAllowed = spacesAllowed)
            }
        caches.navCounts.put(key = key, value = counts)
        return counts
    }

    private class FreshMember(
        val status: MemberStatus,
        val emailVerified: Boolean,
    )

    /** One primary-key lookup; `null` for a member row that no longer exists. */
    private fun freshMember(current: CurrentMember): FreshMember? =
        transaction {
            MemberTable
                .selectAll()
                .where { MemberTable.id eq current.memberId }
                .singleOrNull()
                ?.let { FreshMember(status = it[MemberTable.status], emailVerified = it[MemberTable.emailVerifiedAt] != null) }
        }

    private fun readFlags(): SignalModuleFlags =
        transaction {
            val row =
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .singleOrNull()
            SignalModuleFlags(
                auctionEnabled = row?.get(OrganizationSettingsTable.auctionEnabled) ?: false,
                conferenceEnabled = conferenceEnabled,
                mcpEnabled = mcpEnabled,
                dunningEnabled = row?.get(OrganizationSettingsTable.dunningEnabled) ?: false,
            )
        }

    private companion object {
        val STALE_MAX_AGE = 10.minutes
        const val TOO_MANY_REQUESTS = "Too many requests"
    }
}
