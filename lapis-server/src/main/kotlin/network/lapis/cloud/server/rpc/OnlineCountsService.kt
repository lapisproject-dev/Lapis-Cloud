package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.security.OnlineSessionCounts
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.OnlineCountsDto
import network.lapis.cloud.shared.rpc.IOnlineCountsService

/**
 * V1.9.215 -- see [IOnlineCountsService] and [OnlineSessionCounts]. ADMIN only (BOARD does not imply it), checked as the FIRST statement.
 * Nothing is logged: not a count, not a person.
 */
class OnlineCountsService(
    private val call: ApplicationCall,
) : IOnlineCountsService {
    override suspend fun onlineCounts(): OnlineCountsDto {
        resolveCurrentMember(call).requireRole(AccountRole.ADMIN)
        val now = DbClock.nowLocalDateTime(TimeZone.UTC)
        val (active, signedIn) = OnlineSessionCounts.count(now)
        return OnlineCountsDto(
            active = active,
            signedIn = signedIn,
            activeWindowMinutes = OnlineSessionCounts.ACTIVE_WINDOW.inWholeMinutes.toInt(),
            sessionTtlHours = SessionStore.SESSION_TTL.inWholeHours.toInt(),
            computedAt = now,
        )
    }
}
