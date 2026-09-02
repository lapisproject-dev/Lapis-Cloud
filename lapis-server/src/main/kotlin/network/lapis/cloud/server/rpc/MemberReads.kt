package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberSummaryDto
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * V1.3.1 "API-Fundament, lesend" -- extracted verbatim from [MemberService.listMembers] (which now
 * delegates here, see that method's own updated body) so `network.lapis.cloud.server.routes
 * .PublicApiRoutes`' `/api/v1/members` handler can reuse the EXACT SAME, already-correct query:
 * `id`+`displayName` only (no email/role/address ever selected), [MemberStatus.ACTIVE] only. See
 * [IMemberService.listMembers] KDoc for the full DSGVO-Datenminimierung rationale this narrows to
 * -- unchanged by this extraction, and `docs/api/public-api-v1.adoc`'s own `/members` section makes
 * the SAME guarantee to REST callers.
 *
 * **Must run inside an already-open `transaction {}`**, same contract as [GovernanceReads].
 */
internal object MemberReads {
    fun listActiveMembers(
        limit: Int? = null,
        offset: Int = 0,
    ): List<MemberSummaryDto> {
        val query =
            MemberTable
                .select(MemberTable.id, MemberTable.displayName)
                .where { MemberTable.status eq MemberStatus.ACTIVE }
        val paged = if (limit != null) query.limit(limit).offset(offset.toLong()) else query
        return paged.map {
            MemberSummaryDto(
                id = it[MemberTable.id].toString(),
                displayName = it[MemberTable.displayName],
            )
        }
    }

    fun countActiveMembers(): Long = MemberTable.selectAll().where { MemberTable.status eq MemberStatus.ACTIVE }.count()
}
