package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
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
 *
 * [forUpdate] takes a `SELECT ... FOR UPDATE` row lock on the member row before it is inspected --
 * same "check-then-act must not race a concurrent status change" discipline as
 * [RegistrationService.requireApplicationRow]/[PoliticianService.requireProfileRowByMember]/
 * [CrowdfundingService.requireProjectRow] etc. Required whenever the same transaction goes on to
 * *create a new standing/authority record* for [memberId] based on this check (a Committee seat,
 * an Election-board appointment) -- without the lock, a concurrent `leaveMembership()` (or any
 * other status-changing transaction) can commit its `UPDATE MemberTable ... SET status = ...`
 * in the gap between this plain `SELECT` and the later `INSERT`, since a non-locking read neither
 * blocks on nor is blocked by that concurrent writer under READ COMMITTED -- the seat then gets
 * created from a status read that was already stale by the time it was acted on. Left `false`
 * (the historical default) for the many pre-existing callers that only gate an in-place action
 * (casting a ballot, placing a bid, staking LTR) rather than minting a new persistent row; those
 * don't have a second statement later in the same transaction whose correctness depends on the
 * status not having changed since this check. New callers that mint a new row from this check
 * (`GovernanceService.addCommitteeMember`, `ElectionService.appointElectionBoard`/`tally`'s
 * winner-seating loop) MUST pass `forUpdate = true`. Cannot use a plain `count()` here the way the
 * un-parameterized version historically did -- Postgres rejects `FOR UPDATE` combined with an
 * aggregate function.
 */
fun requireActiveMembership(
    memberId: Uuid,
    forUpdate: Boolean = false,
) {
    val query = MemberTable.selectAll().where { MemberTable.id eq memberId }
    val status = (if (forUpdate) query.forUpdate() else query).singleOrNull()?.get(MemberTable.status)
    if (status != MemberStatus.AKTIV) throw ForbiddenException()
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
