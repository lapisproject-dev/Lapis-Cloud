package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.BoardMembershipDto
import network.lapis.cloud.shared.domain.BoardMembershipInput
import network.lapis.cloud.shared.domain.TransparenzregisterReminderDto
import network.lapis.cloud.shared.domain.TransparenzregisterReportDto

/**
 * V0.5.2 §20 GwG Transparenzregister beneficial-owner reminder support -- see
 * `network.lapis.cloud.server.rpc.BoardMembershipService` for the implementation and
 * `lapis-server/src/main/kuml/13-transparenzregister.kuml.kts` for the schema-shape rationale.
 *
 * Every method requires BOARD/ADMIN (same role pair `PostalMailService`'s own
 * `GOVERNANCE_DISPATCH_ROLES` already requires for governance-adjacent actions such as Einladung
 * dispatch) -- board-membership/beneficial-owner data is treated as sensitive governance data, not
 * member-public.
 *
 * **Legal-review flags, read before relying on this for an actual filing**: this domain is
 * reminder/acknowledgement-only. There is NO automated submission to transparenzregister.de (no
 * suitable public API exists, and this wave's own title -- "automatische Erinnerung", not
 * automated filing -- is explicit about that scope). The Meldefiktion exception (entities already
 * fully captured in another public register, e.g. the Vereinsregister, may be exempt from an
 * active Transparenzregister entry) is NOT modelled here at all -- every organization using this
 * system is treated as if it must actively maintain a Transparenzregister entry, which a lawyer
 * must verify against the concrete Vereinsregister situation. §20 GwG applies to every Verein/
 * Partei -- unlike V0.5.1's §25 PartG donation-duty check, no method here is gated on
 * `network.lapis.cloud.shared.domain.OrganizationSettingsDto.isPoliticalParty`.
 */
@RpcService
interface IBoardMembershipService {
    /** Role: BOARD/ADMIN. The live roster -- every [BoardMembershipDto] with `endedAt == null`. */
    suspend fun listCurrentBoard(): List<BoardMembershipDto>

    /**
     * Role: BOARD/ADMIN. Administrative board seating outside an election (e.g. co-option) --
     * closes any currently-open [BoardMembershipDto] for [BoardMembershipInput.memberId] (same
     * single-active-membership invariant `ElectionService.tally`'s own seating enforces) and
     * inserts a fresh row, then records a `JOINED` [TransparenzregisterReminderDto].
     */
    suspend fun appointBoardMember(input: BoardMembershipInput): BoardMembershipDto

    /**
     * Role: BOARD/ADMIN. Ends the [BoardMembershipDto] identified by [boardMembershipId] as of
     * [endedAt] (resignation, recall/Abwahl, term expiry -- this system cannot distinguish which,
     * the caller's own governance-process records are the source of truth for that) and records a
     * `LEFT` [TransparenzregisterReminderDto]. Throws [ConflictException] if that membership is
     * already ended.
     */
    suspend fun endBoardMembership(
        boardMembershipId: String,
        endedAt: LocalDate,
    ): BoardMembershipDto

    /**
     * Role: BOARD/ADMIN. Read-only follow-up-duty report -- see
     * [network.lapis.cloud.shared.domain.TransparenzregisterReportDto] KDoc. Mirrors
     * `IAccountingService.getDonationDutyReport`'s shape/spirit, never gated on `isPoliticalParty`.
     * Only lists UNRESOLVED reminders ([TransparenzregisterReminderDto.resolved] `== false`) -- use
     * [listTransparenzregisterReminders] for the full acknowledgement history including resolved
     * rows.
     */
    suspend fun getTransparenzregisterReport(): TransparenzregisterReportDto

    /**
     * Role: BOARD/ADMIN. The full auditable [TransparenzregisterReminderDto] history -- unlike
     * [getTransparenzregisterReport], which only surfaces open (unresolved) reminders,
     * [includeResolved] (default `false`, matching every other `activeOnly`/`includeResolved`-style
     * flag in this codebase, e.g. `IGovernanceService.listCommittees`) lets a BOARD/ADMIN reviewer
     * also see every past acknowledgement (who filed what, when, acknowledged by whom) -- resolved
     * rows are never deleted, only flipped, precisely so this history stays inspectable.
     */
    suspend fun listTransparenzregisterReminders(includeResolved: Boolean = false): List<TransparenzregisterReminderDto>

    /**
     * Role: BOARD/ADMIN. Manual acknowledgement that the caller has updated the real
     * Transparenzregister entry themselves -- **NOT** a verification that the filing actually
     * happened, see [network.lapis.cloud.shared.domain.TransparenzregisterReminderDto] KDoc. Throws
     * [ConflictException] if [reminderId] is already resolved (idempotency guard against a double
     * acknowledgement being misread as two separate filings).
     */
    suspend fun resolveTransparenzregisterReminder(reminderId: String): TransparenzregisterReminderDto
}
