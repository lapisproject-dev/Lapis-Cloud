package network.lapis.cloud.server.dashboard

import network.lapis.cloud.server.rpc.DUNNING_READ_ROLES
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets

/**
 * V1.9.224 -- the server side of "does the menu show this entry to this person". A signal for a module whose menu entry the person does not
 * see would point to nothing, so every gate answers exactly like its client counterpart in `NavVisibility`, and ONLY through
 * [MemberStatusSets] (never an inline `== ACTIVE`). The client additionally checks the registry entry before it draws a card.
 */
internal object SignalGates {
    /** `NavVisibility.showsMemberOnlyEconomy` (auction, crowdfunding). */
    fun showsMemberOnlyEconomy(status: MemberStatus): Boolean = status in MemberStatusSets.ORGANIZATION_MEMBER

    /** `NavVisibility.showsTenders`. */
    fun showsTenders(status: MemberStatus): Boolean = status in MemberStatusSets.LTR_ELIGIBLE

    /** `NavVisibility.showsSelfGovernance` (elections, consensus, polls, meetings, motions). */
    fun showsSelfGovernance(status: MemberStatus): Boolean = status in MemberStatusSets.ORGANIZATION_MEMBER

    /** `NavVisibility.showsMembershipSection` (contributions, events, travel expenses). */
    fun showsMembershipSection(status: MemberStatus): Boolean = status in MemberStatusSets.ORGANIZATION_MEMBER

    /** `NavVisibility.showsAiDrafts`; [mcpEnabled] is the instance switch. */
    fun showsAiDrafts(
        status: MemberStatus,
        mcpEnabled: Boolean,
    ): Boolean = mcpEnabled && status in MemberStatusSets.LTR_ELIGIBLE

    /** `NavVisibility.showsEncounterSpaces` (also the rule behind the live counter of the encounter spaces). */
    fun showsEncounterSpaces(status: MemberStatus): Boolean = status in MemberStatusSets.CONFERENCE_ELIGIBLE

    /** BOARD or ADMIN: the application count and the other approval-style hints. */
    fun isBoardOrAdmin(role: AccountRole): Boolean = role == AccountRole.BOARD || role == AccountRole.ADMIN

    /** TREASURER, BOARD or ADMIN: the roles that read the dunning cases (`DUNNING_READ_ROLES`). */
    fun mayReadDunning(role: AccountRole): Boolean = role in DUNNING_READ_ROLES
}
