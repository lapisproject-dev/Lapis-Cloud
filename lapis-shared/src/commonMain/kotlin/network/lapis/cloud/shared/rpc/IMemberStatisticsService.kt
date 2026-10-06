package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MemberCountHistoryDto
import network.lapis.cloud.shared.domain.MemberCountHistoryQuery

/**
 * Welle V1.9.59 "Mitgliederzahlen ueber Zeit" -- BOARD/ADMIN-only history of the number of members per status. The numbers come from
 * the append-only status log (`member_status_history`), so a figure for a past date is what the log says for that date, not the
 * present state projected backwards.
 *
 * Only counts per period cross this method -- never a member id, a name or a single member's status instant (see
 * [MemberCountHistoryDto]). Invalid ranges (`from` after `to`, `to` in the future, `from` before 1900, more than
 * `MemberStatisticsRules.MAX_POINTS` periods) are rejected with a `BadRequestException`, never silently coarsened.
 */
@RpcService
interface IMemberStatisticsService {
    suspend fun getMemberCountHistory(query: MemberCountHistoryQuery): MemberCountHistoryDto
}
