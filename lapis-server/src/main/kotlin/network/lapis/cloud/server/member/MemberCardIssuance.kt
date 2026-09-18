package network.lapis.cloud.server.member

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberCardCodeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- the single issuance entry point shared by the PDF
 * download route ([network.lapis.cloud.server.routes.registerMemberCardRoutes]) and the RPC
 * reissue endpoint (`MemberService.reissueMemberCard`). Must run inside an already-open
 * `transaction {}` (same discipline as [MemberCardStore], which it delegates to).
 *
 * **Why every issuance ROTATES rather than returning the existing code.**
 * [MemberCardStore] persists only the SHA-256 digest of a card code, never the code itself (the
 * posture the previous wave established, mirroring `event_registration.ticket_code_sha256`). The
 * raw value therefore exists exactly once, in the transaction that minted it -- a second PDF for
 * an already-issued card is arithmetically impossible, not merely unimplemented. Given that, there
 * are only two coherent behaviors for "member wants their card again": refuse (and leave a member
 * who lost the file with no card at all), or mint a fresh one and invalidate the old. This wave
 * takes the second -- the same posture every "download your recovery codes again" flow takes -- and
 * makes the consequence explicit at both call sites (a POST, not a GET, plus a confirmation step in
 * the UI: see that route's KDoc for why the verb matters).
 *
 * [revoked] reports whether a previous code was invalidated, so the caller can tell the member
 * "this replaced your earlier card" rather than silently changing a document they may have printed.
 */
internal object MemberCardIssuance {
    data class Issued(
        val rawCode: String,
        val revoked: Boolean,
        val card: CardData,
    )

    /** Everything printed on the card, read under the same lock that minted the code. */
    data class CardData(
        val memberId: Uuid,
        val displayName: String,
        val memberNumber: String,
        val joinedAt: LocalDate,
        val status: MemberStatus,
        val membershipTierName: String?,
    )

    /**
     * Rotates [memberId]'s card code and returns the raw value together with the data to print.
     *
     * Throws [NotFoundException] for an unknown member and [ConflictException] for a member who is
     * not [MemberCardEligibility.isEligible] -- a card is a membership assertion, see that object's
     * KDoc. The eligibility check happens under [MemberCardStore]'s own `forUpdate()` lock on the
     * member row, so it cannot race a concurrent status change: either the status change commits
     * first (and this call refuses) or this call commits first (and the status change proceeds,
     * leaving a card whose holder the verification page will then correctly report as no longer a
     * member).
     */
    fun rotate(
        memberId: Uuid,
        now: LocalDateTime,
    ): Issued {
        // Eligibility BEFORE the write, under this call's own `forUpdate()` lock on the member row
        // (the same row [MemberCardStore.revokeAndReissue] locks a moment later -- the lock is
        // simply already held by then). Checking first keeps the refusal path free of a
        // revoke-then-roll-back round trip, and keeps the check and the write inside one lock.
        requireEligible(memberId)
        val hadActiveCode =
            MemberCardCodeTable
                .selectAll()
                .where { (MemberCardCodeTable.memberId eq memberId) and (MemberCardCodeTable.revokedAt.isNull()) }
                .empty()
                .not()
        val rawCode = MemberCardStore.revokeAndReissue(memberId = memberId, now = now)
        return Issued(rawCode = rawCode, revoked = hadActiveCode, card = loadCardData(memberId))
    }

    /** Locks the member row and refuses a non-member up front -- see [rotate]'s own comment. */
    private fun requireEligible(memberId: Uuid) {
        val row =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq memberId }
                .forUpdate()
                .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
        // Same guard every other member-mutating RPC in this codebase enforces (see
        // MemberService.kt) -- DSGVO Art. 17 erasure (FoundationPersonalData.eraseMember) does
        // NOT touch `status`, so an anonymized row can still read ACTIVE here. Without this check
        // a card could be issued for "Geloeschtes Mitglied" and, worse, MemberCardStore.issue
        // would allocate a FRESH unique member_number onto the erased row -- exactly the
        // re-identification key erasure nulled out. Must run before the eligibility check so an
        // anonymized-but-still-ACTIVE row is refused regardless of status.
        if (row[MemberTable.anonymizedAt] != null) {
            throw ConflictException("Member has been anonymized and can no longer be edited")
        }
        val status = row[MemberTable.status]
        if (!MemberCardEligibility.isEligible(status)) {
            throw ConflictException(
                "Für den Status \"${MemberCardEligibility.statusLabel(status)}\" kann kein Mitgliedsausweis ausgestellt werden.",
            )
        }
    }

    /**
     * Re-read AFTER [MemberCardStore.revokeAndReissue] on purpose: that call is what allocates a
     * missing `member_number` (see [MemberNumberAllocator]), so reading the member row before it
     * would see a `null` number for a member receiving their first card.
     */
    private fun loadCardData(memberId: Uuid): CardData {
        val row =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq memberId }
                .singleOrNull() ?: throw NotFoundException("Member $memberId not found")
        val status = row[MemberTable.status]
        val tierName =
            row[MemberTable.membershipTierId]?.let { tierId ->
                MembershipTierTable
                    .selectAll()
                    .where { MembershipTierTable.id eq tierId }
                    .singleOrNull()
                    ?.get(MembershipTierTable.name)
            }
        return CardData(
            memberId = memberId,
            displayName = row[MemberTable.displayName],
            memberNumber = row[MemberTable.memberNumber] ?: MemberCardStore.MEMBER_NUMBER_PENDING_PLACEHOLDER,
            joinedAt = row[MemberTable.joinedAt],
            status = status,
            membershipTierName = tierName,
        )
    }
}
