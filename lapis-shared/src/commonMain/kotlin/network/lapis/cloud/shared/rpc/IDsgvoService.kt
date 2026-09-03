package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.DsgvoAuditLogEntryDto
import network.lapis.cloud.shared.domain.DsgvoSubjectKind
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ErasureRequestDto
import network.lapis.cloud.shared.domain.ErasureStatus
import network.lapis.cloud.shared.domain.ExportManifestDto
import network.lapis.cloud.shared.domain.PublicRankingConsentDisclaimerDto
import network.lapis.cloud.shared.domain.PublicRankingConsentStateDto
import network.lapis.cloud.shared.domain.PublicRankingKind

/**
 * DSGVO-Basis (Art. 15/17/20 DSGVO): Auskunft, Loeschung als reviewbarer Workflow, Audit-Trail.
 *
 * The actual Auskunftsbuendel (full export payload) travels over the dedicated HTTP route
 * `GET /api/dsgvo/members/{id}/export` (see `network.lapis.cloud.server.routes.registerDsgvoRoutes`
 * KDoc) — same reasoning as [IDocumentService] not carrying file bytes: it can grow large and
 * Kilua RPC is tuned for small typed payloads. This RPC surface only exposes the lightweight
 * [ExportManifestDto] (row counts per section) plus the erasure workflow and audit-log reads.
 *
 * Every entity that carries member-referencing personal data participates in export/erasure
 * automatically, as long as it is registered as a
 * `network.lapis.cloud.server.dsgvo.PersonalDataContributor` in
 * `network.lapis.cloud.server.dsgvo.PersonalDataRegistry` — see that object's KDoc for the
 * `information_schema`-based test that forces every future FK-to-`member` table to either be
 * covered or explicitly allowlisted, so this list cannot silently rot.
 */
@RpcService
interface IDsgvoService {
    /** Self-service for the caller's own data, otherwise ADMIN only. Art. 15/20 DSGVO. */
    suspend fun exportManifest(memberId: String): ExportManifestDto

    /**
     * Self-service to request erasure of the caller's own data, otherwise ADMIN only. Creates a
     * [ErasureStatus.REQUESTED] request — never deletes anything by itself. Art. 17 DSGVO.
     */
    suspend fun requestErasure(
        subjectMemberId: String,
        reason: String,
        mode: ErasureMode = ErasureMode.ANONYMIZE,
    ): ErasureRequestDto

    /** Role: ADMIN. */
    suspend fun listErasureRequests(status: ErasureStatus? = null): List<ErasureRequestDto>

    /**
     * Role: ADMIN. Moves a [ErasureStatus.REQUESTED] request to [ErasureStatus.APPROVED] (if
     * [approve]) or the terminal [ErasureStatus.REJECTED] (if not). Does not itself touch any
     * personal data — that only happens in [executeErasure].
     */
    suspend fun decideErasure(
        requestId: String,
        approve: Boolean,
        note: String? = null,
    ): ErasureRequestDto

    /**
     * Role: ADMIN. The irreversible step — only callable on a [ErasureStatus.APPROVED] request.
     * Iterates every registered `PersonalDataContributor` for the subject and moves the request
     * to [ErasureStatus.COMPLETED].
     */
    suspend fun executeErasure(requestId: String): ErasureRequestDto

    /**
     * Role: ADMIN. Metadata/counts only — see [DsgvoAuditLogEntryDto] KDoc. [subjectKind]
     * (Welle V1.4.2) additionally filters by whether the row describes a member or a `crm_contact`
     * -- `CrmContactsScreen`'s own audit view passes `CRM_CONTACT`, the DSGVO-compliance screen's
     * member view passes `MEMBER` (or leaves it `null` to see both).
     */
    suspend fun listAuditLog(
        subjectMemberId: String? = null,
        subjectKind: DsgvoSubjectKind? = null,
    ): List<DsgvoAuditLogEntryDto>

    // ============================================================================================
    // V1.3.0 "Öffentliche Transparenz-Startseite" -- opt-in consent for the two public leaderboards
    // `GET /transparenz` can show (`network.lapis.cloud.server.rpc.PublicRankingConsentStore`). Art.
    // 6(1)(a)/7 DSGVO consent, not the Art. 15/17/20 export/erasure workflow above -- placed on this
    // interface anyway because the member-facing UI for it lives on the SAME self-service screen
    // ("Meine Daten") as the rest of [IDsgvoService].
    // ============================================================================================

    /**
     * The CALLER'S OWN current consent state for both [PublicRankingKind]s -- never accepts a
     * `memberId` parameter, there is no on-behalf-of read path here, not even for ADMIN (this is a
     * personal consent choice, not an administrative record).
     */
    suspend fun getPublicRankingConsents(): List<PublicRankingConsentStateDto>

    /** The current, versioned two-layer disclosure text for [kind] -- shown before [grantPublicRankingConsent]. */
    suspend fun getPublicRankingConsentDisclaimer(kind: PublicRankingKind): PublicRankingConsentDisclaimerDto

    /**
     * Records the caller's opt-in into [kind]'s public leaderboard. [version]/[sha256] must match
     * [getPublicRankingConsentDisclaimer]'s CURRENT values for [kind] exactly (echoed back
     * unchanged, never recomputed client-side) -- a mismatch is rejected with a typed exception,
     * nothing is written. Restricted to `MemberStatusSets.ORGANIZATION_MEMBER` -- a GUEST/FRIEND
     * has no LTR account here (GUEST) or an unverified display name (FRIEND), neither belongs in a
     * public leaderboard of this organization.
     */
    suspend fun grantPublicRankingConsent(
        kind: PublicRankingKind,
        version: String,
        sha256: String,
    ): PublicRankingConsentStateDto

    /**
     * Revokes the caller's opt-in into [kind]'s public leaderboard. A silent, idempotent no-op if
     * the caller was not effectively opted in.
     */
    suspend fun revokePublicRankingConsent(kind: PublicRankingKind): PublicRankingConsentStateDto
}
