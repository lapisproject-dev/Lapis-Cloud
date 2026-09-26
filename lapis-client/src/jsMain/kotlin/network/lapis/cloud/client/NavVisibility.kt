package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets

/**
 * V0.11.0 FRIEND self-registration -- pure, DOM-free predicates extracted from `App.kt`'s
 * `refreshNavbar` navbar-construction block so they are unit-testable in isolation, same posture
 * as [GovernanceAuthzUi]. Driven from [network.lapis.cloud.shared.domain.SessionInfoDto.status],
 * never a separate client-side boolean.
 *
 * **Welle V1.1.4**: widened from a single `showsOrganizationMemberDropdowns` predicate (a FRIEND
 * saw NEITHER "Mitgliedschaft" NOR "Selbstverwaltung" NOR "Wirtschaft") into seven fine-grained
 * predicates, because a FRIEND is now [MemberStatusSets.LTR_ELIGIBLE] and should see the PARTS of
 * "Mitgliedschaft"/"Wirtschaft" that actually work for them ("Meine Daten", "LTR-Konto", "Soziales
 * Netzwerk"), while everything else that would still be a guaranteed-to-fail RPC call
 * (Governance/Crowdfunding/Auktion/Politiker/Beiträge/Dokumente/Kommunikation) stays hidden.
 */
object NavVisibility {
    /** "Selbstverwaltung" (Gremien/Sitzungen/Anträge) -- unverändert ORGANIZATION_MEMBER-exklusiv. */
    fun showsSelfGovernance(status: MemberStatus): Boolean = status in MemberStatusSets.ORGANIZATION_MEMBER

    /** "Mitgliedschaft"-Dropdown als Ganzes (Beiträge/Dokumente/Kommunikation/Meine Daten). */
    fun showsMembershipSection(status: MemberStatus): Boolean = status in MemberStatusSets.ORGANIZATION_MEMBER

    /**
     * "Meine Daten" (DSGVO-Betroffenenrechte) -- für JEDEN authentifizierten Status sichtbar.
     * `DsgvoService.exportMyData`/`requestErasure` gattern serverseitig ohnehin nur auf
     * `resolveCurrentMember`, und seit V1.1.4 erzeugt ein FRIEND eigene, potenziell öffentlich
     * indexierte Inhalte -- ein nicht erreichbarer Betroffenenrechte-Einstieg wäre mit Art. 12
     * Abs. 2 DSGVO schlecht vereinbar.
     */
    fun showsDsgvoRights(status: MemberStatus): Boolean = true

    /** "Wirtschaft"-Dropdown überhaupt anzeigen -- seit V1.1.4 auch für FRIEND (LTR_ELIGIBLE). */
    fun showsEconomySection(status: MemberStatus): Boolean = status in MemberStatusSets.LTR_ELIGIBLE

    /** LTR-Konto -- seit V1.1.4 auch für FRIEND (LTR_ELIGIBLE). */
    fun showsLtrLedger(status: MemberStatus): Boolean = status in MemberStatusSets.LTR_ELIGIBLE

    /** Soziales Netzwerk -- seit V1.1.4 auch für FRIEND (LTR_ELIGIBLE). */
    fun showsSocialNetwork(status: MemberStatus): Boolean = status in MemberStatusSets.LTR_ELIGIBLE

    /** Crowdfunding/Auktion/Politiker -- bleiben ORGANIZATION_MEMBER-exklusiv. */
    fun showsMemberOnlyEconomy(status: MemberStatus): Boolean = status in MemberStatusSets.ORGANIZATION_MEMBER

    /**
     * "Fragen zur Satzung" (V1.6.1, optional AI assistance) -- only where the server reports the AI
     * layer as operational ([network.lapis.cloud.shared.domain.SessionInfoDto.aiAssistantEnabled])
     * AND for full organization members (the server refuses everyone else).
     */
    fun showsStatuteQa(
        status: MemberStatus,
        aiAssistantEnabled: Boolean,
    ): Boolean = aiAssistantEnabled && status in MemberStatusSets.ORGANIZATION_MEMBER

    /**
     * "KI-Entwürfe" (Welle V1.8.2b) -- bound to [network.lapis.cloud.shared.domain.SessionInfoDto
     * .mcpEnabled], deliberately **NOT** `.mcpWriteEnabled`: existing drafts an agent already
     * created must stay reachable (editable/releasable/discardable) even after the operator
     * switches WRITE access off -- only `mcpEnabled` off (or a status outside [MemberStatusSets
     * .LTR_ELIGIBLE], the same eligibility [showsSocialNetwork]/[showsLtrLedger] already require,
     * since releasing a draft debits LTR) hides this entry entirely.
     */
    fun showsAiDrafts(
        status: MemberStatus,
        mcpEnabled: Boolean,
    ): Boolean = mcpEnabled && status in MemberStatusSets.LTR_ELIGIBLE
}
