package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V0.5.2 §20 GwG Transparenzregister beneficial-owner tracking -- see
 * `network.lapis.cloud.server.rpc.BoardMembershipService` KDoc for the full lifecycle and the
 * legal-review flags this domain deliberately carries (Meldefiktion exception NOT modelled, no
 * automated filing to transparenzregister.de).
 *
 * [JOINED] is emitted when a [BoardMembershipDto] starts: an [network.lapis.cloud.shared.rpc
 * .IElectionService] tally seating a winner into an `EXECUTIVE_BOARD` Committee, an
 * [network.lapis.cloud.shared.rpc.IGovernanceService.addCommitteeMember] co-option into that same
 * Committee type, or an explicit [network.lapis.cloud.shared.rpc.IBoardMembershipService
 * .appointBoardMember] call. [LEFT] is emitted whenever a [BoardMembershipDto] ends: an explicit
 * [network.lapis.cloud.shared.rpc.IBoardMembershipService.endBoardMembership] call (resignation,
 * recall/Abwahl, term expiry), an [network.lapis.cloud.shared.rpc.IGovernanceService
 * .endCommitteeMembership] removal from that same Committee type, or a *displaced incumbent* --
 * a contested election (or governance co-option) seating a different member into a single-holder
 * seat (CHAIR/DEPUTY_CHAIR/SECRETARY) automatically closes the previous holder's row and emits
 * this. A re-election of the SAME incumbent into a new term does NOT emit a [LEFT] for the closed
 * prior row, only a fresh [JOINED] for the new one (see `BoardMembershipEvents`/
 * `BoardMembershipService` KDoc for the full rationale).
 */
@Serializable
enum class BoardChangeType { JOINED, LEFT }

/**
 * One beneficial owner's (a sitting or former Vorstand member's) Transparenzregister-facing board
 * term -- [endedAt] is `null` while [committeeRole] is still current. Deliberately committee-
 * agnostic (no committeeId): the Transparenzregister only cares about who, in which role, since
 * when -- not which specific Committee seated them. Kept PARALLEL to (not a replacement of)
 * `network.lapis.cloud.shared.domain.CommitteeMembershipDto`, which remains the actual Vorstand
 * seating record -- see `BoardMembershipService` KDoc for why.
 */
@Serializable
data class BoardMembershipDto(
    val id: String,
    val memberId: String,
    val memberDisplayName: String,
    val committeeRole: CommitteeRole,
    val startedAt: LocalDate,
    val endedAt: LocalDate?,
)

/** Input for [network.lapis.cloud.shared.rpc.IBoardMembershipService.appointBoardMember]. */
@Serializable
data class BoardMembershipInput(
    val memberId: String,
    val committeeRole: CommitteeRole,
    val startedAt: LocalDate,
)

/**
 * One persisted "Vorstandsaenderung" reminder -- created automatically whenever a
 * [BoardMembershipDto] starts or ends (see [BoardChangeType] KDoc for exactly when). [resolved] is
 * flipped by [network.lapis.cloud.shared.rpc.IBoardMembershipService.resolveTransparenzregisterReminder],
 * a manual acknowledgement by a BOARD/ADMIN caller that they have updated the real
 * Transparenzregister entry themselves -- **this system has no way to verify the filing actually
 * happened**, `resolved = true` only ever means "a human clicked acknowledge", never "verified
 * filed". See `BoardMembershipService` KDoc for the full limitation.
 */
@Serializable
data class TransparenzregisterReminderDto(
    val id: String,
    val triggeredAt: LocalDateTime,
    val memberId: String,
    val memberDisplayName: String,
    val committeeRole: CommitteeRole,
    val changeType: BoardChangeType,
    val resolved: Boolean,
    val resolvedAt: LocalDateTime?,
    val resolvedById: String?,
    val resolvedByDisplayName: String?,
)

/**
 * One current board member who is missing [MemberDto.dateOfBirth] and/or [MemberDto.nationality]
 * -- both required Transparenzregister beneficial-owner content (see [MemberDto] KDoc) that a
 * pre-V0.5.2 member row may never have had a reason to populate.
 */
@Serializable
data class BeneficialOwnerDataGapDto(
    val memberId: String,
    val memberDisplayName: String,
    val committeeRole: CommitteeRole,
    val missingDateOfBirth: Boolean,
    val missingNationality: Boolean,
    /** Welle V1.9.121 -- the nationality is recorded as "unclear" (`ZZ`): not missing, but not settled either. */
    val nationalityUnclear: Boolean = false,
    /** V1.9.141 -- neither `member.country_code` nor the legacy free-text `member.country` is set. */
    val missingCountry: Boolean = false,
)

/**
 * §20 GwG Transparenzregister follow-up-duty report -- read-only, mirrors the V0.5.1
 * [DonationDutyReportDto] shape/spirit: lists what a human (BOARD/ADMIN) needs to act on manually,
 * rather than this system attempting any automated external filing. Unlike [DonationDutyReportDto],
 * this report is never gated on `OrganizationSettingsDto.isPoliticalParty` -- §20 GwG beneficial-
 * owner reporting applies to every Verein/Partei, not just political parties.
 *
 * [openReminders] are the currently unresolved [TransparenzregisterReminderDto]s.
 * [currentBoard] is the live [BoardMembershipDto] roster (`endedAt == null`). [beneficialOwnerDataGaps]
 * flags every current board member still missing [MemberDto.dateOfBirth] and/or
 * [MemberDto.nationality] -- a cheap completeness aid directly serving the beneficial-owner-data
 * gap this wave closes.
 */
@Serializable
data class TransparenzregisterReportDto(
    val openReminders: List<TransparenzregisterReminderDto>,
    val currentBoard: List<BoardMembershipDto>,
    val beneficialOwnerDataGaps: List<BeneficialOwnerDataGapDto>,
)
