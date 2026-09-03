package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Default is [ANONYMIZE] (member row survives as an FK anchor for retentionspflichtige
 * Datensaetze such as [ContributionDto], see `ContributionPersonalData` KDoc).
 * [HARD_DELETE_WHERE_UNCONSTRAINED] additionally redacts the subject's own sent
 * [DirectMessageDto] bodies — see `CommunicationPersonalData` KDoc for why received messages are
 * never touched by either mode.
 */
@Serializable
enum class ErasureMode { ANONYMIZE, HARD_DELETE_WHERE_UNCONSTRAINED }

@Serializable
enum class ErasureStatus { REQUESTED, APPROVED, REJECTED, COMPLETED }

@Serializable
enum class DsgvoAuditAction {
    EXPORT,
    ERASURE_REQUESTED,
    ERASURE_APPROVED,
    ERASURE_REJECTED,
    ERASURE_EXECUTED,
}

/**
 * Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- which kind of subject a
 * [DsgvoAuditLogEntryDto.subjectMemberId] actually names, now that a `dsgvo_audit_log` row can
 * describe either a member (Art. 15/17/20 DSGVO self-service/ADMIN workflow,
 * `network.lapis.cloud.server.rpc.DsgvoService`) or a CRM contact (admin-only export/erasure,
 * `network.lapis.cloud.server.dsgvo.CrmPersonalData`). See
 * `network.lapis.cloud.server.dsgvo.DataSubject` (server-only, not mirrored here -- see that
 * sealed interface's own KDoc for why) for the server-side type this enum is the wire-visible
 * discriminator for.
 */
@Serializable
enum class DsgvoSubjectKind { MEMBER, CRM_CONTACT }

/**
 * Per-table erasure outcome — counts and a retention rationale, never payload. Persisted as a
 * JSON array in `erasure_request.outcome_summary` / `dsgvo_audit_log.outcome_summary`.
 */
@Serializable
data class TableErasureOutcomeDto(
    val table: String,
    val rowsAnonymized: Int = 0,
    val rowsDeleted: Int = 0,
    val rowsRetained: Int = 0,
    val retentionReason: String? = null,
)

@Serializable
data class ErasureRequestDto(
    val id: String,
    val subjectMemberId: String,
    val subjectDisplayName: String,
    val requestedAt: LocalDateTime,
    val requestedBy: String,
    val reason: String,
    val mode: ErasureMode,
    val status: ErasureStatus,
    val decidedBy: String?,
    val decidedAt: LocalDateTime?,
    val decisionNote: String?,
    val executedAt: LocalDateTime?,
    val legalHold: Boolean,
    val outcome: List<TableErasureOutcomeDto>,
)

/**
 * Lightweight per-section row counts returned by the RPC surface (`IDsgvoService.exportManifest`).
 * The actual data bundle travels over a dedicated HTTP route — see `IDsgvoService` KDoc.
 */
@Serializable
data class ExportManifestDto(
    val subjectMemberId: String,
    val generatedAt: LocalDateTime,
    val sectionCounts: Map<String, Int>,
)

@Serializable
data class DsgvoAuditLogEntryDto(
    val id: String,
    val occurredAt: LocalDateTime,
    val actorMemberId: String?,
    val actorRole: AccountRole?,
    val action: DsgvoAuditAction,
    val subjectMemberId: String,
    val requestId: String?,
    val outcome: List<TableErasureOutcomeDto>,
    val legalBasis: String?,
    val subjectKind: DsgvoSubjectKind,
)
