package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * The single status-gate primitive (V0.11.0 refactor -- see [MemberStatusSets] KDoc). Must run
 * inside the caller's open `transaction {}`, same convention every other query helper in this
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
fun requireMembershipStatusIn(
    memberId: Uuid,
    allowed: Set<MemberStatus>,
    forUpdate: Boolean = false,
): MemberStatus {
    val query = MemberTable.selectAll().where { MemberTable.id eq memberId }
    val status = (if (forUpdate) query.forUpdate() else query).singleOrNull()?.get(MemberTable.status)
    if (status == null || status !in allowed) throw ForbiddenException()
    return status
}

/**
 * Extracted from [CrowdfundingService]'s own originally-private `requireActiveMembership` (V0.6.1)
 * so [PoliticianService] and others can reuse the exact same "caller must be [MemberStatus.ACTIVE]"
 * gate instead of a second, potentially-drifting copy -- both domains apply it to the same kind of
 * decision (a member-initiated action that stakes/spends LTR or casts a rating; `APPLICATION`/
 * `GUEST`/`FRIEND`/`WITHDRAWN`/`REJECTED` members are all excluded). Byte-for-byte the historical
 * contract -- [MemberStatus.FRIEND] is deliberately NOT admitted here, same as every other
 * non-`ACTIVE` status.
 */
fun requireActiveMembership(
    memberId: Uuid,
    forUpdate: Boolean = false,
) {
    requireMembershipStatusIn(
        memberId = memberId,
        allowed = MemberStatusSets.ORGANIZATION_MEMBER,
        forUpdate = forUpdate,
    )
}

/**
 * Politician-rating basket (V0.6.4/V0.8.2) -- allows [MemberStatus.ACTIVE] AND [MemberStatus.GUEST]
 * specifically, still excludes `APPLICATION`/`WITHDRAWN`/`REJECTED`, and (V0.11.0) deliberately NOT
 * [MemberStatus.FRIEND] either: an unverified, self-registered name must not move a public trust
 * metric. Introduced for the [PoliticianService] guest-rating wave (`castRating`/`retractRating`)
 * that closes V0.6.4's own "member-only rating, no Gast basket" scope cut now that V0.8.2's OIDC
 * guest-identity federation makes GUEST a real, reachable status -- see [PoliticianService] class
 * KDoc "Guest-rating weighting". [requireActiveMembership] itself is untouched; every other call
 * site (Crowdfunding, PeerTransfer, etc.) keeps excluding GUEST exactly as before.
 *
 * **RENAMED from `requireActiveOrGuestMembership`** (V0.11.0) on purpose: the old name invited
 * exactly the mistake of widening it for conference access, which would have silently handed
 * FRIEND a vote in the public politician trust metric. See [requireConferenceEligibleMembership]
 * for the (intentionally different, intentionally wider) conference-entry gate.
 *
 * Returns the freshly-read [MemberStatus] (not `Unit`) so a caller that also needs to classify the
 * row it is about to write (e.g. `PoliticianReactionTable.raterType`) does not need a second query
 * for the same member row.
 */
fun requirePoliticianRaterMembership(memberId: Uuid): MemberStatus =
    requireMembershipStatusIn(memberId = memberId, allowed = MemberStatusSets.POLITICIAN_RATER)

/**
 * May enter a conference room at all -- [MemberStatus.ACTIVE], [MemberStatus.GUEST], AND (V0.11.0)
 * [MemberStatus.FRIEND]. THE extension point for widening FRIEND's scope later, should that ever
 * be decided -- see [MemberStatusSets.CONFERENCE_ELIGIBLE] KDoc.
 *
 * **V0.11.0 email-verification gate**: when [FriendRegistrationConfig.requireEmailVerification] is
 * `true` (default `false` -- see that property's KDoc for why), a [MemberStatus.FRIEND] caller
 * ADDITIONALLY needs `MemberTable.emailVerifiedAt != null`, checked in the SAME query as the status
 * read (no extra round trip). [MemberStatus.ACTIVE]/[MemberStatus.GUEST] are entirely unaffected --
 * this gate only ever narrows FRIEND, never any other status. [config] defaults to a fresh
 * [FriendRegistrationConfig.load] per call, same "cheap, pure env-var read, safe to repeat"
 * reasoning every other per-request config load in this codebase already relies on.
 */
fun requireConferenceEligibleMembership(
    memberId: Uuid,
    config: FriendRegistrationConfig = FriendRegistrationConfig.load(),
): MemberStatus {
    val row =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .singleOrNull()
    val status = row?.get(MemberTable.status)
    if (status == null || status !in MemberStatusSets.CONFERENCE_ELIGIBLE) throw ForbiddenException()
    if (status == MemberStatus.FRIEND && config.requireEmailVerification && row[MemberTable.emailVerifiedAt] == null) {
        throw ForbiddenException("This FRIEND account has not verified its email address yet")
    }
    return status
}

/**
 * V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- the SINGLE place "may this caller
 * enter/inspect this conference room at all" is decided, shared by every conference-domain service
 * that needs it ([ConferenceService.joinRoom]/[ConferenceService.listParticipants],
 * [ConferenceRecordingService.getActiveRecording], [ConferenceStreamingService.getActiveStream] --
 * see the Wave 5 design review's D13 finding: recording/streaming state must be visible to an
 * IN-ROOM guest too, "everyone in the room has a legal right to know" applies to a federated guest
 * (and, since V0.11.0, a FRIEND) exactly as much as to an ACTIVE member) so none of them can drift
 * apart into a second, inconsistent gate. Must run INSIDE the caller's open `transaction {}`, same
 * convention every other query helper in this package follows.
 *
 * Returns the caller's freshly-read [MemberStatus] (never a cached one) so the caller can branch on
 * non-member status without a second query -- the exact reason [requireConferenceEligibleMembership]
 * returns a status rather than `Unit`.
 *
 * Ordering matters: [requireConferenceEligibleMembership] runs FIRST, so an APPLICATION/WITHDRAWN/
 * REJECTED caller is rejected identically whether or not the room happens to be guest-opted-in --
 * the per-room `allowFederationGuests` toggle can only NARROW the pre-existing status gate, never
 * widen it. **Security-relevant (V0.11.0)**: the `allowFederationGuests` narrowing now applies to
 * every [MemberStatusSets.NON_MEMBER] status (GUEST *and* FRIEND), not just GUEST -- otherwise a
 * self-registered FRIEND would get BROADER room access than a federated GUEST, which at least
 * proved an identity at a trusted home server while FRIEND proved nothing.
 */
fun requireRoomEntryAuthorization(
    roomRow: ResultRow,
    current: CurrentMember,
): MemberStatus {
    val status = requireConferenceEligibleMembership(memberId = current.memberId)
    if (status in MemberStatusSets.NON_MEMBER && !roomRow[ConferenceRoomTable.allowFederationGuests]) {
        throw ForbiddenException(
            "This conference room does not admit non-members -- its moderator has not enabled " +
                "allowFederationGuests. (Note: this message is server-side only; the client learns " +
                "the reason from getGuestJoinInfo, see ConferenceGuestJoinInfoDto KDoc.)",
        )
    }
    return status
}

/**
 * V1.0 Videokonferenzen, Wave 5 -- companion to [requireRoomEntryAuthorization]: [status] proves the
 * room ADMITS non-members, but a non-member must additionally be CURRENTLY IN [roomId] (an open
 * `conference_participation` row, `leftAt IS NULL`) before it can enumerate the roster or read
 * recording/streaming state -- a non-member merely handed a bare room id must not be able to probe
 * any of that without ever entering the room, and one who has since LEFT or been EJECTED
 * ([ConferenceService.removeParticipant]) must not be able to keep probing either. Security-audit
 * fix: the original version tested only row EXISTENCE, ignoring `leftAt` -- since
 * `conference_participation` is append-only (never deleted), that let an ejected/departed guest
 * retain this gate forever (a moderator's `removeParticipant` on a disruptive guest disconnected
 * them from LiveKit but left `listParticipants`/recording/streaming probes open). No-op for an
 * [MemberStatusSets.ORGANIZATION_MEMBER] [status] (V0.11.0: widened from a `!= GUEST` check to
 * `!in NON_MEMBER`, so a departed/ejected FRIEND is held to the exact same continued-participation
 * discipline a departed GUEST always was). Shared by
 * [ConferenceService.listParticipants]/[ConferenceRecordingService.getActiveRecording]/
 * [ConferenceStreamingService.getActiveStream] so the three call sites cannot drift apart. Must run
 * INSIDE the caller's open `transaction {}`, same convention as [requireRoomEntryAuthorization].
 */
fun requireGuestHasJoinedRoom(
    roomId: Uuid,
    current: CurrentMember,
    status: MemberStatus,
) {
    if (status !in MemberStatusSets.NON_MEMBER) return
    val hasOpenParticipation =
        ConferenceParticipationTable
            .selectAll()
            .where {
                (ConferenceParticipationTable.roomId eq roomId) and
                    (ConferenceParticipationTable.memberId eq current.memberId) and
                    ConferenceParticipationTable.leftAt.isNull()
            }.limit(1)
            .any()
    if (!hasOpenParticipation) throw ForbiddenException("Non-members may only access a room they are currently in")
}
