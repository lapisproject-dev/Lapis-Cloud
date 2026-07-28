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

/**
 * Guest-inclusive counterpart to [requireActiveMembership] -- allows [MemberStatus.AKTIV] AND
 * [MemberStatus.GAST] specifically, still excludes ANTRAG/AUSGETRETEN/ABGELEHNT. Introduced for
 * the [PoliticianService] guest-rating wave (`castRating`/`retractRating`) that closes V0.6.4's
 * own "member-only rating, no Gast basket" scope cut now that V0.8.2's OIDC guest-identity
 * federation makes GAST a real, reachable status -- see [PoliticianService] class KDoc "Guest-
 * rating weighting". [requireActiveMembership] itself is untouched; every other call site
 * (Crowdfunding, PeerTransfer, etc.) keeps excluding GAST exactly as before.
 *
 * Returns the freshly-read [MemberStatus] (not `Unit`) so a caller that also needs to classify the
 * row it is about to write (e.g. `PoliticianReactionTable.raterType`) does not need a second query
 * for the same member row.
 */
fun requireActiveOrGuestMembership(memberId: Uuid): MemberStatus {
    val status =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .singleOrNull()
            ?.get(MemberTable.status)
    if (status != MemberStatus.AKTIV && status != MemberStatus.GAST) throw ForbiddenException()
    return status
}
