package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberStatus

/**
 * V0.11.0 FRIEND self-registration -- pure, DOM-free predicate extracted from `App.kt`'s
 * `refreshNavbar` navbar-construction block so it is unit-testable in isolation, same posture as
 * [GovernanceAuthzUi]. A self-registered [MemberStatus.FRIEND] has no Beitragspflicht, no
 * governance/accounting/LTR rights, and (since this wave's `canAccessDocumentAtLevel` fix) no
 * `PUBLIC_MEMBERS` document access either -- showing the "Mitgliedschaft"/"Selbstverwaltung"/
 * "Wirtschaft" nav dropdowns to a FRIEND would only ever lead to a guaranteed-to-fail RPC call, so
 * `refreshNavbar` hides all three for exactly that one status. Driven from
 * [network.lapis.cloud.shared.domain.SessionInfoDto.status], never a separate client-side boolean.
 */
object NavVisibility {
    fun showsOrganizationMemberDropdowns(status: MemberStatus): Boolean = status != MemberStatus.FRIEND
}
