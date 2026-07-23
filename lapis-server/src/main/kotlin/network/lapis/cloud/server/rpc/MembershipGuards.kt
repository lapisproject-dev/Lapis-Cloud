package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Extracted from [CrowdfundingService]'s own originally-private `requireActiveMembership` (V0.6.1)
 * so [PoliticianService] (V0.6.4) can reuse the exact same "caller must be
 * [MemberStatus.AKTIV]" gate instead of a second, potentially-drifting copy -- both domains apply
 * it to the same kind of decision (a member-initiated action that stakes/spends LTR or casts a
 * rating; `ANTRAG`/`GAST`/`AUSGETRETEN` members are excluded from both). Must run inside the
 * caller's already-open `transaction {}`, same convention every other query helper in this
 * package follows.
 */
fun requireActiveMembership(memberId: Uuid) {
    val isActive =
        MemberTable
            .selectAll()
            .where { (MemberTable.id eq memberId) and (MemberTable.status eq MemberStatus.AKTIV) }
            .count() > 0
    if (!isActive) throw ForbiddenException()
}
