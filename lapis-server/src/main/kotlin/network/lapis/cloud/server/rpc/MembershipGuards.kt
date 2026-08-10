package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
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

/**
 * V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- the SINGLE place "may this caller
 * enter/inspect this conference room at all" is decided, shared by every conference-domain service
 * that needs it ([ConferenceService.joinRoom]/[ConferenceService.listParticipants],
 * [ConferenceRecordingService.getActiveRecording], [ConferenceStreamingService.getActiveStream] --
 * see the Wave 5 design review's D13 finding: recording/streaming state must be visible to an
 * IN-ROOM guest too, "everyone in the room has a legal right to know" applies to a federated guest
 * exactly as much as to an AKTIV member) so none of them can drift apart into a second, inconsistent
 * gate. Must run INSIDE the caller's open `transaction {}`, same convention every other query
 * helper in this package follows.
 *
 * Returns the caller's freshly-read [MemberStatus] (never a cached one) so the caller can branch on
 * GAST without a second query -- the exact reason [requireActiveOrGuestMembership] returns a status
 * rather than `Unit`.
 *
 * Ordering matters: [requireActiveOrGuestMembership] runs FIRST, so an ANTRAG/AUSGETRETEN/
 * ABGELEHNT caller is rejected identically whether or not the room happens to be guest-opted-in --
 * the per-room `allowFederationGuests` toggle can only NARROW the pre-existing status gate, never
 * widen it.
 */
fun requireRoomEntryAuthorization(
    roomRow: ResultRow,
    current: CurrentMember,
): MemberStatus {
    val status = requireActiveOrGuestMembership(current.memberId)
    if (status == MemberStatus.GAST && !roomRow[ConferenceRoomTable.allowFederationGuests]) {
        throw ForbiddenException(
            "This conference room does not admit federated guests -- its moderator has not enabled " +
                "allowFederationGuests. (Note: this message is server-side only; the client learns " +
                "the reason from getGuestJoinInfo, see ConferenceGuestJoinInfoDto KDoc.)",
        )
    }
    return status
}

/**
 * V1.0 Videokonferenzen, Wave 5 -- companion to [requireRoomEntryAuthorization]: [status] proves the
 * room ADMITS guests, but a guest must additionally be CURRENTLY IN [roomId] (an open
 * `conference_participation` row, `leftAt IS NULL`) before it can enumerate the roster or read
 * recording/streaming state -- a guest merely handed a bare room id must not be able to probe any of
 * that without ever entering the room, and a guest who has since LEFT or been EJECTED
 * ([ConferenceService.removeParticipant]) must not be able to keep probing either. Security-audit
 * fix: the original version tested only row EXISTENCE, ignoring `leftAt` -- since
 * `conference_participation` is append-only (never deleted), that let an ejected/departed guest
 * retain this gate forever (a moderator's `removeParticipant` on a disruptive guest disconnected
 * them from LiveKit but left `listParticipants`/recording/streaming probes open). No-op for a
 * non-GAST [status]. Shared by
 * [ConferenceService.listParticipants]/[ConferenceRecordingService.getActiveRecording]/
 * [ConferenceStreamingService.getActiveStream] so the three call sites cannot drift apart. Must run
 * INSIDE the caller's open `transaction {}`, same convention as [requireRoomEntryAuthorization].
 */
fun requireGuestHasJoinedRoom(
    roomId: Uuid,
    current: CurrentMember,
    status: MemberStatus,
) {
    if (status != MemberStatus.GAST) return
    val hasOpenParticipation =
        ConferenceParticipationTable
            .selectAll()
            .where {
                (ConferenceParticipationTable.roomId eq roomId) and
                    (ConferenceParticipationTable.memberId eq current.memberId) and
                    ConferenceParticipationTable.leftAt.isNull()
            }.limit(1)
            .any()
    if (!hasOpenParticipation) throw ForbiddenException("Guests may only access a room they are currently in")
}
