package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.RegionalChapterRefDto

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

    /**
     * "Wahlen" (V1.9.22) -- derselbe Personenkreis wie die übrige Selbstverwaltung: jede Wahl gehört zu einem Antrag eines Gremiums,
     * und `IElectionService` gilt serverseitig nur für Mitglieder. Als eigene Funktion, damit der Eintrag einzeln prüfbar ist.
     */
    fun showsElections(status: MemberStatus): Boolean = showsSelfGovernance(status)

    /**
     * "Konsensieren" (V1.9.28, systemic consensus) -- same audience as [showsElections]: every consensus hangs off a motion of a
     * committee and `ISystemicConsensusService` rates for members only. A guest who arrives through a direct link still gets the page
     * rendered read-only with a note; only the navigation entry is member-only.
     */
    fun showsConsensus(status: MemberStatus): Boolean = showsSelfGovernance(status)

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

    /** Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- ORGANIZATION_MEMBER-exklusiv, gleiche Grammatik wie [showsSelfGovernance]/[showsMemberOnlyEconomy] (`ArticleService`/`IArticleService` gatet jedes Autoren-RPC mit `requireActiveMembership`). */
    fun showsArticles(status: MemberStatus): Boolean = status in MemberStatusSets.ORGANIZATION_MEMBER

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

    /**
     * V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- the "Gliederungsverwaltung"
     * sidebar entry (ADMINISTRATION group) and its route (`Routes.REGIONAL_CHAPTERS`), BOARD or ADMIN
     * (`listChapters` and the public crest/description are BOARD/ADMIN; `createChapter`/`renameChapter`/
     * `deleteChapter`/`listOfficers`/`grantOfficer`/`revokeOfficer` stay ADMIN-only, see [showsRegionalChapterStructure]) (the single
     * exception, `assignMemberToChapter`, is BOARD/ADMIN but lives on `MemberAdministrationScreen.kt`
     * instead -- this predicate only gates the standalone chapter-management screen itself, which
     * needs the FULL surface, not just assignment).
     */
    fun showsRegionalChapterAdmin(role: AccountRole?): Boolean = role == AccountRole.BOARD || role == AccountRole.ADMIN

    /**
     * V1.9.20 follow-up -- the structural actions of the chapter screen (create, rename, delete, officers) are ADMIN-only on
     * the server; BOARD reaches the screen only for the crest and the public description (`updateChapterDescription`,
     * crest upload/removal are BOARD/ADMIN) and sees those sections alone.
     */
    fun showsRegionalChapterStructure(role: AccountRole?): Boolean = role == AccountRole.ADMIN

    /**
     * V1.9.14 -- the "Mein Landesverband" sidebar entry (MEMBERSHIP group) and its route
     * (`Routes.MY_CHAPTER`). Driven ONLY by the server-computed [RegionalChapterRefDto]
     * (`SessionInfoDto.chapterScope`), NEVER a client-derived boolean -- the server alone decides
     * whether a session belongs to an active "Landesvorstand" (regional-chapter officer) grant (see
     * `AuthService.getSessionInfo` KDoc). A stale/null [chapterScope] simply hides the entry.
     * Review fix (NIT, KDoc correction): `RegionalChaptersScreen.refreshSessionFromServer` only
     * runs after `createChapter`/`deleteChapter` in that file, NOT after `grantOfficer`/
     * `revokeOfficer`/`renameChapter` -- functionally harmless (the ADMIN driving that screen always
     * has `MemberVisibility.All` and therefore never has a [chapterScope] of their own to go stale),
     * but this entry is NOT actually refreshed after every grant/revoke as the previous wording
     * claimed. `ChapterRosterScreen.kt`'s own session re-check (once per mount, see its class KDoc)
     * is what keeps an ACTUAL officer's own view current across a revoke elsewhere.
     */
    fun showsChapterRoster(chapterScope: RegionalChapterRefDto?): Boolean = chapterScope != null
}
